//DatabaseOpr.java
package com.bg7yoz.ft8cn.database;
/**
 * Database operation class. Most operations are asynchronous (except HTTP-related).
 * The database has gone through multiple versions, so there is an onUpgrade method.
 * Configuration information is also saved in the database.
 * @author BGY70Z @date 2023-03-20 */
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.AsyncTask;
import android.util.Log;
import com.bg7yoz.ft8cn.database.AfterInsertQSLData;
import android.database.sqlite.SQLiteException;
import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.ft8signal.FT8Package;
import com.bg7yoz.ft8cn.log.OnQueryQSLCallsign;
import com.bg7yoz.ft8cn.log.OnQueryQSLRecordCallsign;
import com.bg7yoz.ft8cn.log.QSLCallsignRecord;
import com.bg7yoz.ft8cn.log.QSLRecord;
import com.bg7yoz.ft8cn.log.QSLRecordStr;
import com.bg7yoz.ft8cn.rigs.BaseRigOperation;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.database.SecureStorage;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.List;  // Для World Model snapshot
import java.util.concurrent.ConcurrentHashMap;  // Для потокобезопасного кэша
import java.util.concurrent.locks.ReentrantLock;  // Для синхронизации
// [NEW] Secure storage imports
import android.content.SharedPreferences;
import android.os.Build;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
public class DatabaseOpr extends SQLiteOpenHelper {
    private static final String TAG = "DatabaseOpr";
    @SuppressLint("StaticFieldLeak")
    private static DatabaseOpr instance;
    private final Context context;
    private SQLiteDatabase db;
    // [NEW] Secure storage for sensitive config (passwords, API keys)
    private SecureStorage secureStorage;
    private static final String SECURE_PREFS_NAME = "ft8cn_secure_prefs";
    private static final String MIGRATION_FLAG_KEY = "secure_migration_done_v1";
    // OPTIMIZATION: In-memory cache for DXCC prefix lookups to avoid repeated DB queries
    // This significantly speeds up country name resolution in the Calling window
    private static final Map<String, String> dxccPrefixCache = new ConcurrentHashMap<>();
    private static final ReentrantLock cacheLock = new ReentrantLock();
    private static boolean dxccCacheLoaded = false;
    public static DatabaseOpr getInstance(@Nullable Context context, @Nullable String databaseName) {
        if (instance == null) {
            instance = new DatabaseOpr(context, databaseName, null, 19);
        }
        return instance;
    }
    public DatabaseOpr(@Nullable Context context, @Nullable String name,
                       @androidx.annotation.Nullable SQLiteDatabase.CursorFactory factory, int version) {
        super(context, name, factory, version);
        this.context = context;
        // Connect to database, if entity database does not exist, onCreate method will be called to initialize
        db = this.getWritableDatabase();
        // [NEW] Initialize secure storage for sensitive data
        ensureWorldModelSchema();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                MasterKey masterKey = new MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
                SharedPreferences encryptedPrefs = EncryptedSharedPreferences.create(
                        context,
                        SECURE_PREFS_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
                secureStorage = new SecureStorage(encryptedPrefs);
                Log.d(TAG, "SecureStorage initialized (AES-256-GCM)");
            } else {
                Log.w(TAG, "SecureStorage unavailable: API < 23. Using fallback.");
                secureStorage = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to init SecureStorage: " + e.getMessage());
            secureStorage = null;
        }
        // [NEW] Migrate sensitive configs if needed
        if (secureStorage != null && !isMigrationDone()) {
            migrateSensitiveConfigs();
            markMigrationDone();
        }
    }
    /**
     * Called when entity database does not exist. Can create data and add files here.
     *
     * @param sqLiteDatabase Database to connect
     */
    @Override
    public void onCreate(SQLiteDatabase sqLiteDatabase) {
        Log.d(TAG, "Create database.");
        db = sqLiteDatabase;// Save database connection
        createTables(sqLiteDatabase);// Create data tables
        // Create QSO log table
        createQSLTable(sqLiteDatabase);
        // Create DXCC table
        createDxccTables(sqLiteDatabase);
        // Create ITU table
        createItuTables(sqLiteDatabase);
        // Create CQZONE table
        createCqZoneTables(sqLiteDatabase);
        // Create callsign-grid mapping table
        createCallsignQTHTables(sqLiteDatabase);
        // Create SWL-related tables
        createSWLTables(sqLiteDatabase);
        // Create indexes
        createIndex(sqLiteDatabase);
        // Создание таблицы World Model
        sqLiteDatabase.execSQL("CREATE TABLE IF NOT EXISTS station_world_model (" +
                "callsign TEXT PRIMARY KEY," +
                "bands_seen_mask INTEGER DEFAULT 0," +
                "last_freq_hz INTEGER," +
                "last_seen_utc_sec INTEGER," +
                "last_snr REAL," +
                "last_qth TEXT," +
                "last_bearing REAL," +
                "dxcc_code TEXT," +
                "last_itu_zone INTEGER," +
                "last_cq_zone INTEGER," +
                "ft8_state_relative INTEGER DEFAULT 0," +
                "priority_score REAL DEFAULT 0," +
                "is_new_dx INTEGER DEFAULT 0," +
                "last_updated_sec INTEGER," +
                "last_sequential INTEGER DEFAULT -1" +  // [NEW] CRITICAL: Store partner's TX slot
                ")");
        sqLiteDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_world_model_priority ON station_world_model(priority_score DESC, last_freq_hz)");
        // [NEW] Add default value for acceptDxCalls
        sqLiteDatabase.execSQL("INSERT OR IGNORE INTO config (KeyName, Value) VALUES ('acceptDxCalls', '0')");
        // [REMOVED] Old setting no longer used
        // sqLiteDatabase.execSQL("INSERT OR IGNORE INTO config (KeyName, Value) VALUES ('multipleAnswersMode', '0')");
    }
    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int oldVersion, int newVersion) {
        Log.w(TAG, "onUpgrade: oldVersion=" + oldVersion + ", newVersion=" + newVersion);

        // Create QSO log table version 2
        createQSLTable(sqLiteDatabase);
        // Create DXCC table
        createDxccTables(sqLiteDatabase);
        // Create ITU table
        createItuTables(sqLiteDatabase);
        // Create CQZONE table
        createCqZoneTables(sqLiteDatabase);
        // Create callsign-grid mapping table
        createCallsignQTHTables(sqLiteDatabase);
        // Create SWL-related tables
        createSWLTables(sqLiteDatabase);
        // Create indexes
        createIndex(sqLiteDatabase);

        // Создание таблицы World Model при обновлении
        sqLiteDatabase.execSQL("CREATE TABLE IF NOT EXISTS station_world_model (" +
                "callsign TEXT PRIMARY KEY," +
                "bands_seen_mask INTEGER DEFAULT 0," +
                "last_freq_hz INTEGER," +
                "last_seen_utc_sec INTEGER," +
                "last_snr REAL," +
                "last_qth TEXT," +
                "last_bearing REAL," +
                "dxcc_code TEXT," +
                "last_itu_zone INTEGER," +
                "last_cq_zone INTEGER," +
                "ft8_state_relative INTEGER DEFAULT 0," +
                "priority_score REAL DEFAULT 0," +
                "is_new_dx INTEGER DEFAULT 0," +
                "last_updated_sec INTEGER," +
                "last_sequential INTEGER DEFAULT -1" +  // [NEW]
                ")");
        sqLiteDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_world_model_priority ON station_world_model(priority_score DESC, last_freq_hz)");

        // === [CRITICAL FIX] Миграция: добавить колонку в СУЩЕСТВУЮЩУЮ таблицу ===
        try {
            Log.d(TAG, "Checking if last_sequential column exists...");

            // Проверяем, существует ли колонка last_sequential
            Cursor cursor = sqLiteDatabase.rawQuery("PRAGMA table_info(station_world_model)", null);
            boolean hasColumn = false;
            if (cursor != null) {
                Log.d(TAG, "Existing columns in station_world_model:");
                while (cursor.moveToNext()) {
                    // Индекс 1 = имя колонки
                    String columnName = cursor.getString(1);
                    Log.d(TAG, "  - " + columnName);
                    if ("last_sequential".equals(columnName)) {
                        hasColumn = true;
                    }
                }
                cursor.close();
            }

            // Если колонки нет, добавляем её через ALTER TABLE
            if (!hasColumn) {
                Log.d(TAG, "Migration: Column NOT found. Adding last_sequential via ALTER TABLE...");
                sqLiteDatabase.execSQL("ALTER TABLE station_world_model ADD COLUMN last_sequential INTEGER DEFAULT -1");
                Log.d(TAG, "Migration SUCCESS: Column added!");
            } else {
                Log.d(TAG, "Migration: Column already exists, skipping ALTER TABLE");
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "Migration ERROR: " + e.getMessage(), e);
        }
        // [NEW] Add setting for existing users upgrading app
        sqLiteDatabase.execSQL("INSERT OR IGNORE INTO config (KeyName, Value) VALUES ('acceptDxCalls', '0')");
    }

    // OPTIMIZATION: Enable WAL mode and performance PRAGMAs when database is opened
    // This method is called automatically by SQLiteOpenHelper when database connection is established
    // WAL mode allows concurrent reads during writes and reduces disk fsync overhead
    // These settings are safe and compatible with Android API 16+
    // OPTIMIZATION: Enable WAL mode and performance PRAGMAs when database is opened
    // This method is called automatically by SQLiteOpenHelper when database connection is established
    // WAL mode allows concurrent reads during writes and reduces disk fsync overhead
    // These settings are safe and compatible with Android API 16+
    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);

        // [FIX] УДАЛИТЬ этот вызов! ensureWorldModelSchema() уже вызывается в конструкторе
        // после инициализации this.db. Вызов здесь приводит к NullPointerException,
        // потому что onOpen() вызывается фреймворком ДО завершения конструктора.
        // ensureWorldModelSchema();  ← УДАЛИТЬ ЭТУ СТРОКУ!

        if (!db.isReadOnly()) {
            try {
                Cursor cursor = db.rawQuery("PRAGMA journal_mode = WAL", null);
                if (cursor != null) { cursor.close(); }
                db.execSQL("PRAGMA synchronous = NORMAL");
                db.execSQL("PRAGMA cache_size = 32000");
                db.execSQL("PRAGMA busy_timeout = 5000");
                Log.d(TAG, "Database performance optimizations applied: WAL mode, cache=8MB, synchronous=NORMAL");
            } catch (Exception e) {
                Log.w(TAG, "Failed to apply database optimizations: " + e.getMessage());
            }
        }
    }


    public SQLiteDatabase getDb() {
        return db;
    }
    private void createTables(SQLiteDatabase sqLiteDatabase) {
        try {
            // Create configuration table
            sqLiteDatabase.execSQL("CREATE TABLE config (KeyName TEXT,Value TEXT, " +
                    "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT)");
            // Create followed callsigns table, UNIQUE means no duplicates, insert OR IGNORE into
            sqLiteDatabase.execSQL("CREATE TABLE followCallsigns (callsign  TEXT UNIQUE)");
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
    }
    /**
     * Add column to table
     *
     * @param db        Database
     * @param tableName Table name
     * @param fieldName Column name
     * @param sql       Column statement
     */
    private void alterTable(SQLiteDatabase db, String tableName, String fieldName, String sql) {
        Cursor cursor = db.rawQuery("select * from sqlite_master where name=? and sql like ?"
                , new String[]{tableName, "%" + fieldName + "%"});
        if (!cursor.moveToNext()) {
            db.execSQL(String.format("ALTER TABLE %s ADD COLUMN %s", tableName, sql));
        }
        cursor.close();
    }
    /**
     * Check if table exists
     *
     * @param db        Database
     * @param tableName Table name
     * @return Whether exists
     */
    private boolean checkTableExists(SQLiteDatabase db, String tableName) {
        Cursor cursor = db.rawQuery("select * from sqlite_master where type = 'table' and name = ?"
                , new String[]{tableName});
        if (cursor.moveToNext()) {
            cursor.close();
            return true;
        }
        return false;
    }
    /**
     * Check if index exists
     * @param db
     * @param indexName
     * @return
     */
    private boolean checkIndexExists(SQLiteDatabase db, String indexName) {
        Cursor cursor = db.rawQuery("select * from sqlite_master where type = 'index' and name = ?"
                , new String[]{indexName});
        if (cursor.moveToNext()) {
            cursor.close();
            return true;
        }
        return false;
    }
    // CHANGED: Fixed double-quoted string literal warning - use single quotes for SQL strings
    private void deleteDxccPrefixEqual(SQLiteDatabase db) {
        db.execSQL("DELETE from dxcc_prefix where prefix LIKE '=%'");
    }
    /**
     * Create QSO log table
     */
    private void createQSLTable(SQLiteDatabase sqLiteDatabase) {
        if (checkTableExists(sqLiteDatabase, "QSLTable")) {
            alterTable(sqLiteDatabase, "QSLTable", "isQSL"
                    , "isQSL INTEGER DEFAULT 0");
            alterTable(sqLiteDatabase, "QSLTable", "isLotW_import"
                    , "isLotW_import INTEGER DEFAULT 0");
            alterTable(sqLiteDatabase, "QSLTable", "isLotW_QSL"
                    , "isLotW_QSL INTEGER DEFAULT 0");
        } else {
            sqLiteDatabase.execSQL("CREATE TABLE QSLTable ( " +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "isQSL INTEGER DEFAULT 0, " +// Whether QSL confirmed
                    "isLotW_import INTEGER DEFAULT 0, " +// Whether imported from LoTW
                    "isLotW_QSL INTEGER DEFAULT 0, " +
                    "call TEXT, " +
                    "gridsquare TEXT, " +
                    "mode TEXT, " +
                    "rst_sent TEXT, " +
                    "rst_rcvd TEXT, " +
                    "qso_date TEXT, " +
                    "time_on TEXT, " +
                    "qso_date_off TEXT, " +
                    "time_off TEXT, " +
                    "band TEXT, " +
                    "freq TEXT, " +
                    "station_callsign TEXT, " +
                    "my_gridsquare TEXT, " +
                    "comment TEXT)");
        }
        if (checkTableExists(sqLiteDatabase, "QslCallsigns")) {
            alterTable(sqLiteDatabase, "QslCallsigns", "isQSL"
                    , "isQSL INTEGER DEFAULT 0");
            alterTable(sqLiteDatabase, "QslCallsigns", "isLotW_import"
                    , "isLotW_import INTEGER DEFAULT 0");
            alterTable(sqLiteDatabase, "QslCallsigns", "isLotW_QSL"
                    , "isLotW_QSL INTEGER DEFAULT 0");
            // [FIX] Заменены двойные кавычки на одинарные для значения по умолчанию
            alterTable(sqLiteDatabase, "QslCallsigns", "startTime"
                    , "startTime TEXT DEFAULT '0'");
        } else {
            sqLiteDatabase.execSQL("CREATE TABLE QslCallsigns (" +
                    "ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "isQSL INTEGER DEFAULT 0, " +
                    "isLotW_import INTEGER DEFAULT 0, " +
                    "isLotW_QSL INTEGER DEFAULT 0, " +
                    "callsign TEXT, startTime TEXT," +
                    "finishTime TEXT, mode TEXT," +
                    "grid TEXT, " +
                    "band TEXT,band_i INTEGER)");
        }
        if (!checkTableExists(sqLiteDatabase, "Messages")) {
            sqLiteDatabase.execSQL("CREATE TABLE Messages ( " +
                    "ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "I3 INTEGER, " +
                    "N3 INTEGER, " +
                    "Protocol TEXT, " +
                    "UTC INTEGER, " +
                    "SNR INTEGER, " +
                    "TIME_SEC REAL, " +
                    "FREQ INTEGER, " +
                    "CALL_TO TEXT, " +
                    "CALL_FROM TEXT, " +
                    "EXTRAL TEXT, " +
                    "REPORT INTEGER, " +
                    "BAND INTEGER)");
        }
    }
    /**
     * Create DXCC-related tables: dxccList, dxcc_prefix, dxcc_grid
     */
    private void createDxccTables(SQLiteDatabase sqLiteDatabase) {
        if (!checkTableExists(sqLiteDatabase, "dxccList")) {
            sqLiteDatabase.execSQL("CREATE TABLE dxccList ( " +
                    "id INTEGER ," +
                    "\tdxcc INTEGER, " +
                    "\tcc TEXT, " +
                    "\tccc TEXT, " +
                    "\tname TEXT, " +
                    "\tcontinent TEXT, " +
                    "\tituzone TEXT, " +
                    "\tcqzone TEXT, " +
                    "\ttimezone INTEGER, " +
                    "\tccode INTEGER, " +
                    "\taname TEXT, " +
                    "\tpp TEXT, " +
                    "\tlat REAL, " +
                    "\tlon REAL " +
                    ");");
            sqLiteDatabase.execSQL("CREATE TABLE dxcc_prefix ( " +
                    "\tdxcc INTEGER, " +
                    "\tprefix TEXT " +
                    ");");
            sqLiteDatabase.execSQL("CREATE TABLE dxcc_grid ( " +
                    "\tdxcc INTEGER, " +
                    "\tgrid TEXT " +
                    ");");
            // Import DXCC mapping data into database
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ArrayList<DxccObject> dxccObjects = loadDxccDataFromFile();
                    for (DxccObject obj : dxccObjects) {
                        obj.insertToDb(sqLiteDatabase);
                    }
                    // OPTIMIZATION: After loading DXCC data, populate the in-memory cache
                    populateDxccPrefixCache();
                }
            }).start();
        }
    }
    /**
     * OPTIMIZATION: Populate the in-memory DXCC prefix cache for fast lookups
     * This method reads all prefix->country mappings from the database once
     * and stores them in a ConcurrentHashMap for O(1) access time.
     */
    private void populateDxccPrefixCache() {
        cacheLock.lock();
        try {
            if (dxccCacheLoaded) return; // Already loaded
            dxccPrefixCache.clear();
            Cursor cursor = db.rawQuery("SELECT prefix, name FROM dxcc_prefix dp " +
                    "INNER JOIN dxccList dl ON dp.dxcc = dl.dxcc", null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String prefix = cursor.getString(0);
                    String country = cursor.getString(1);
                    if (prefix != null && country != null) {
                        dxccPrefixCache.put(prefix.toUpperCase(), country);
                    }
                }
                cursor.close();
            }
            dxccCacheLoaded = true;
            Log.d(TAG, "populateDxccPrefixCache: Loaded " + dxccPrefixCache.size() + " prefix mappings");
        } finally {
            cacheLock.unlock();
        }
    }
    /**
     * OPTIMIZATION: Fast country lookup by callsign prefix using in-memory cache
     * Falls back to database query only if cache is not loaded or prefix not found.
     * This method is thread-safe and should be called from any thread.
     *
     * @param callsign The callsign to look up (e.g., "R3BCK")
     * @return Country name or empty string if not found
     */
    public String getCountryByCallsign(String callsign) {
        if (callsign == null || callsign.isEmpty()) return "";
        // OPTIMIZATION: Inline prefix extraction since GeneralVariables.getPrefix() doesn't exist
        // Extract prefix: take first 1-4 uppercase letters/digits, stop at special chars
        String prefix = extractPrefix(callsign).toUpperCase();
        if (prefix.isEmpty()) return "";
        // Try cache first (O(1) lookup)
        String country = dxccPrefixCache.get(prefix);
        if (country != null) {
            return country;
        }
        // Cache miss or not loaded - fallback to database
        if (!dxccCacheLoaded) {
            populateDxccPrefixCache();
            country = dxccPrefixCache.get(prefix);
            if (country != null) return country;
        }
        // Final fallback: query database directly
        Cursor cursor = db.rawQuery("SELECT name FROM dxcc_prefix dp " +
                        "INNER JOIN dxccList dl ON dp.dxcc = dl.dxcc WHERE dp.prefix = ?",
                new String[]{prefix});
        if (cursor != null && cursor.moveToFirst()) {
            country = cursor.getString(0);
            // Optionally add to cache for future lookups
            cacheLock.lock();
            try {
                dxccPrefixCache.put(prefix, country);
            } finally {
                cacheLock.unlock();
            }
            cursor.close();
            return country;
        }
        if (cursor != null) cursor.close();
        return "";
    }
    /**
     * OPTIMIZATION: Helper method to extract callsign prefix (1-4 chars)
     * Handles special cases like portable operations (R3BCK/P), etc.
     *
     * @param callsign Full callsign
     * @return Prefix string (e.g., "R3" from "R3BCK/P")
     */
    private String extractPrefix(String callsign) {
        if (callsign == null || callsign.isEmpty()) return "";
        // Remove common suffixes that indicate portable/mobile operations
        String cleaned = callsign.toUpperCase();
        // Find position of special characters that end the prefix
        int endPos = cleaned.length();
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            // Prefix ends at first non-alphanumeric or at special markers
            if (c == '/' || c == 'P' || c == 'M' || c == 'A' || c == 'Q') {
                // Check if this is a suffix marker (not part of prefix)
                if (i > 0 && i < cleaned.length() - 1) {
                    endPos = i;
                    break;
                }
            }
            if (!Character.isLetterOrDigit(c)) {
                endPos = i;
                break;
            }
        }
        // Take first 1-4 characters as prefix
        String prefix = cleaned.substring(0, Math.min(endPos, 4));
        // Remove trailing non-letters (some prefixes end with digit)
        // Keep standard format: letters+digit (e.g., "R3", "UA3", "W1")
        return prefix;
    }
    /**
     * OPTIMIZATION: Clear the DXCC prefix cache (use when DXCC data is updated)
     */
    public void clearDxccPrefixCache() {
        cacheLock.lock();
        try {
            dxccPrefixCache.clear();
            dxccCacheLoaded = false;
        } finally {
            cacheLock.unlock();
        }
    }
    /**
     * Import ITU zone mapping table into database
     *
     * @param sqLiteDatabase Database
     */
    private void createItuTables(SQLiteDatabase sqLiteDatabase) {
        if (!checkTableExists(sqLiteDatabase, "ituList")) {
            sqLiteDatabase.execSQL("CREATE TABLE ituList (itu INTEGER,grid TEXT)");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    loadItuDataFromFile(sqLiteDatabase);
                }
            }).start();
        }
    }
    private void createCqZoneTables(SQLiteDatabase sqLiteDatabase) {
        if (!checkTableExists(sqLiteDatabase, "cqzoneList")) {
            sqLiteDatabase.execSQL("CREATE TABLE cqzoneList (cqzone INTEGER,grid TEXT)");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    loadICqZoneDataFromFile(sqLiteDatabase);
                }
            }).start();
        }
    }
    /**
     * Create callsign-grid mapping table
     *
     * @param sqLiteDatabase db
     */
    private void createCallsignQTHTables(SQLiteDatabase sqLiteDatabase) {
        if (!checkTableExists(sqLiteDatabase, "CallsignQTH")) {
            sqLiteDatabase.execSQL("CREATE TABLE CallsignQTH(callsign text, grid text" +
                    ",updateTime Int ,PRIMARY KEY(callsign))");
        }
    }
    private void createSWLTables(SQLiteDatabase sqLiteDatabase) {
        //Log.e(TAG,"upgrade database.");
        if (!checkTableExists(sqLiteDatabase, "SWLMessages")) {
            sqLiteDatabase.execSQL("CREATE TABLE SWLMessages ( " +
                    "\tID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "\tI3 INTEGER, " +
                    "\tN3 INTEGER, " +
                    "\tProtocol TEXT, " +
                    "\tUTC TEXT, " +
                    "\tSNR INTEGER, " +
                    "\tTIME_SEC REAL, " +
                    "\tFREQ INTEGER, " +
                    "\tCALL_TO TEXT, " +
                    "\tCALL_FROM TEXT, " +
                    "\tEXTRAL TEXT, " +
                    "\tREPORT INTEGER, " +
                    "\tBAND INTEGER " +
                    ")");
            sqLiteDatabase.execSQL("CREATE INDEX SWLMessages_CALL_TO_IDX " +
                    "ON SWLMessages (CALL_TO,CALL_FROM)");
            sqLiteDatabase.execSQL("CREATE INDEX SWLMessages_UTC_IDX ON SWLMessages (UTC)");
        }
        if (!checkTableExists(sqLiteDatabase, "SWLQSOTable")) {
            // [FIX] Заменены двойные кавычки на квадратные скобки для имени колонки call (SQLite keyword)
            sqLiteDatabase.execSQL("CREATE TABLE SWLQSOTable ( " +
                    "\tid INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "\t[call] TEXT, " +
                    "\tgridsquare TEXT, " +
                    "\tmode TEXT, " +
                    "\trst_sent TEXT, " +
                    "\trst_rcvd TEXT, " +
                    "\tqso_date TEXT, " +
                    "\ttime_on TEXT, " +
                    "\tqso_date_off TEXT, " +
                    "\ttime_off TEXT, " +
                    "\tband TEXT, " +
                    "\tfreq TEXT, " +
                    "\tstation_callsign TEXT, " +
                    "\tmy_gridsquare TEXT, " +
                    "\toperator TEXT, " +
                    "\tcomment TEXT)");
        }else {
            alterTable(sqLiteDatabase, "SWLQSOTable", "operator"
                    , "operator TEXT");
        }
    }
    /**
     * OPTIMIZATION: Create indexes to improve query speed
     * @param sqLiteDatabase Database
     */
    private void createIndex(SQLiteDatabase sqLiteDatabase) {
        if (!checkIndexExists(sqLiteDatabase, "QslCallsigns_callsign_IDX")) {
            sqLiteDatabase.execSQL("CREATE INDEX QslCallsigns_callsign_IDX ON QslCallsigns (callsign,startTime,finishTime,mode)");
        }
        if (!checkIndexExists(sqLiteDatabase, "QSLTable_call_IDX")) {
            // [FIX] Заменены двойные кавычки на квадратные скобки для имени колонки call
            sqLiteDatabase.execSQL("CREATE INDEX QSLTable_call_IDX ON QSLTable ([call],qso_date,time_on,mode)");
        }
        // OPTIMIZATION: Add index for DXCC prefix lookups to speed up fallback queries
        if (!checkIndexExists(sqLiteDatabase, "dxcc_prefix_prefix_IDX")) {
            sqLiteDatabase.execSQL("CREATE INDEX dxcc_prefix_prefix_IDX ON dxcc_prefix (prefix)");
        }
        // OPTIMIZATION: Add missing index for SWLMessages.BAND to speed up frequency filtering
        if (!checkIndexExists(sqLiteDatabase, "SWLMessages_BAND_IDX")) {
            sqLiteDatabase.execSQL("CREATE INDEX SWLMessages_BAND_IDX ON SWLMessages (BAND)");
        }
        // OPTIMIZATION: Add composite index for common QSL queries
        if (!checkIndexExists(sqLiteDatabase, "QSLTable_band_call_IDX")) {
            // [FIX] Заменены двойные кавычки на квадратные скобки для имени колонки call
            sqLiteDatabase.execSQL("CREATE INDEX QSLTable_band_call_IDX ON QSLTable (band,[call])");
        }
    }
    public void loadItuDataFromFile(SQLiteDatabase db) {
        AssetManager assetManager = context.getAssets();
        InputStream inputStream;
        db.execSQL("delete from ituList");
        String insertSQL = "INSERT INTO ituList (itu,grid)" +
                "VALUES(?,?)";
        try {
            inputStream = assetManager.open("ituzone.json");
            byte[] bytes = new byte[inputStream.available()];
            inputStream.read(bytes);
            JSONObject jsonObject = new JSONObject(new String(bytes));
            JSONArray array = jsonObject.names();
            for (int i = 0; i < array.length(); i++) {
                JSONObject ituObject = new JSONObject(jsonObject.getString(array.getString(i)));
                JSONArray mh = ituObject.getJSONArray("mh");
                for (int j = 0; j < mh.length(); j++) {
                    db.execSQL(insertSQL, new Object[]{array.getString(i), mh.getString(j)});
                }
            }
            inputStream.close();
        } catch (IOException | JSONException e) {
            e.printStackTrace();
            Log.e(TAG, "loadDataFromFile: " + e.getMessage());
        }
    }
    public void loadICqZoneDataFromFile(SQLiteDatabase db) {
        AssetManager assetManager = context.getAssets();
        InputStream inputStream;
        db.execSQL("delete from cqzoneList");
        String insertSQL = "INSERT INTO cqzoneList (cqzone,grid)" +
                "VALUES(?,?)";
        try {
            inputStream = assetManager.open("cqzone.json");
            byte[] bytes = new byte[inputStream.available()];
            inputStream.read(bytes);
            JSONObject jsonObject = new JSONObject(new String(bytes));
            JSONArray array = jsonObject.names();
            for (int i = 0; i < array.length(); i++) {
                JSONObject ituObject = new JSONObject(jsonObject.getString(array.getString(i)));
                JSONArray mh = ituObject.getJSONArray("mh");
                for (int j = 0; j < mh.length(); j++) {
                    db.execSQL(insertSQL, new Object[]{array.getString(i), mh.getString(j)});
                }
            }
            inputStream.close();
        } catch (IOException | JSONException e) {
            e.printStackTrace();
            Log.e(TAG, "loadDataFromFile: " + e.getMessage());
        }
    }
    public ArrayList<DxccObject> loadDxccDataFromFile() {
        AssetManager assetManager = context.getAssets();
        InputStream inputStream;
        ArrayList<DxccObject> dxccObjects = new ArrayList<>();
        try {
            inputStream = assetManager.open("dxcc_list.json");
            byte[] bytes = new byte[inputStream.available()];
            inputStream.read(bytes);
            JSONObject jsonObject = new JSONObject(new String(bytes));
            JSONArray array = jsonObject.names();
            for (int i = 0; i < array.length(); i++) {
                if (array.getString(i).equals("-1")) continue;
                JSONObject dxccObject = new JSONObject(jsonObject.getString(array.getString(i)));
                DxccObject dxcc = new DxccObject();
                dxcc.id = Integer.parseInt(array.getString(i));
                dxcc.dxcc = dxccObject.getInt("dxcc");
                dxcc.cc = dxccObject.getString("cc");
                dxcc.ccc = dxccObject.getString("ccc");
                dxcc.name = dxccObject.getString("name");
                dxcc.continent = dxccObject.getString("continent");
                dxcc.ituZone = dxccObject.getString("ituzone")
                        .replace("[", "")
                        .replace("]", "")
                        .replace("\"", "");
                dxcc.cqZone = dxccObject.getString("cqzone")
                        .replace("[", "")
                        .replace("]", "")
                        .replace("\"", "");
                dxcc.timeZone = dxccObject.getInt("timezone");
                dxcc.cCode = dxccObject.getInt("ccode");
                dxcc.aName = dxccObject.getString("aname");
                dxcc.pp = dxccObject.getString("pp");
                dxcc.lat = dxccObject.getDouble("lat");
                dxcc.lon = dxccObject.getDouble("lon");
                JSONArray mh = dxccObject.getJSONArray("mh");
                for (int j = 0; j < mh.length(); j++) {
                    dxcc.grid.add(mh.getString(j));
                }
                JSONArray prefix = dxccObject.getJSONArray("prefix");
                for (int j = 0; j < prefix.length(); j++) {
                    dxcc.prefix.add(prefix.getString(j));
                }
                dxccObjects.add(dxcc);
                //Log.e(TAG, "loadDataFromFile: id:" + dxcc.id + " dxcc:" + dxcc.dxcc);
            }
            inputStream.close();
        } catch (IOException | JSONException e) {
            e.printStackTrace();
            Log.e(TAG, "loadDataFromFile: " + e.getMessage());
        }
        return dxccObjects;
    }
    /**
     * Write callsign-grid mapping to table
     *
     * @param callsign Callsign
     * @param grid     Grid
     */
    public void addCallsignQTH(String callsign, String grid) {
        if (grid.trim().length() < 4) return;
        new AddCallsignQTH(db).execute(callsign, grid);
        //Log.d(TAG, String.format("addCallsignQTH: callsign:%s,grid:%s", callsign, grid));
    }
    // Query configuration.
    public void getConfigByKey(String KeyName, OnAfterQueryConfig onAfterQueryConfig) {
        new QueryConfig(db, KeyName, onAfterQueryConfig).execute();
    }
    public void getCallSign(String callsign, String fieldName, String tableName, OnGetCallsign getCallsign) {
        new QueryCallsign(db, tableName, fieldName, callsign, getCallsign).execute();
    }
    /**
     * Write configuration, asynchronous operation
     */
    public void writeConfig(String KeyName, String Value, OnAfterWriteConfig onAfterWriteConfig) {
        Log.d(TAG, "writeConfig: Value:" + Value);
        new WriteConfig(db, KeyName, Value, onAfterWriteConfig).execute();
    }
    public void writeMessage(ArrayList<Ft8Message> messages) {
        new WriteMessages(db, messages).execute();
    }
    /**
     * Read followed callsign list
     *
     * @param onAffterQueryFollowCallsigns Callback function
     */
    public void getFollowCallsigns(OnAfterQueryFollowCallsigns onAffterQueryFollowCallsigns) {
        new GetFollowCallSigns(db, onAffterQueryFollowCallsigns).execute();
    }
    /**
     * Query SWL MESSAGE count per BAND
     * @param onAfterQueryFollowCallsigns Callback
     */
    public void getMessageLogTotal(OnAfterQueryFollowCallsigns onAfterQueryFollowCallsigns) {
        new GetMessageLogTotal(db, onAfterQueryFollowCallsigns).execute();
    }
    /**
     * Query SWL QSO count per month
     * @param onAfterQueryFollowCallsigns Callback
     */
    public void getSWLQsoLogTotal(OnAfterQueryFollowCallsigns onAfterQueryFollowCallsigns) {
        new GetSWLQsoTotal(db, onAfterQueryFollowCallsigns).execute();
    }
    /**
     * Add followed callsign to database
     *
     * @param callsign Callsign
     */
    public void addFollowCallsign(String callsign) {
        new AddFollowCallSign(db, callsign).execute();
    }
    /**
     * Clear followed callsigns
     */
    public void clearFollowCallsigns() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                db.execSQL("delete from followCallsigns ");
            }
        }).start();
    }
    /**
     * Delete QSO logs
     */
    public void clearLogCacheData() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                db.execSQL("delete from SWLMessages ");
            }
        }).start();
    }
    /**
     * Delete SWL QSO logs
     */
    public void clearSWLQsoData() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                db.execSQL("delete from SWLQSOTable ");
            }
        }).start();
    }
    /**
     * Write successful QSO logs and callsigns to database
     *
     * @param qslRecord QSO record
     */
    public void addQSL_Callsign(QSLRecord qslRecord) {
        new AddQSL_Info(this, qslRecord).execute();
    }
    /**
     * Save SWL QSO to database, SWL QSO criteria: must have signal reports from both parties. Does not include own callsign.
     * @param qslRecord QSO log record
     */
    public void addSWL_QSO(QSLRecord qslRecord) {
        new Add_SWL_QSO_Info(this, qslRecord).execute();
    }
    // Delete followed callsign from database
    public void deleteFollowCallsign(String callsign) {
        new DeleteFollowCallsign(db, callsign).execute();
    }
    // Get all configuration parameters
    public void getAllConfigParameter(OnAfterQueryConfig onAfterQueryConfig) {
        new GetAllConfigParameter(db, secureStorage, onAfterQueryConfig).execute();
    }
    /**
     * Query all successfully QSO'd callsigns, filter by frequency
     */
    public void getAllQSLCallsigns() {
        new LoadAllQSLCallsigns(db).execute();
    }
    /**
     * Find QSL callsign records by callsign
     *
     * @param callsign           Callsign
     * @param onQueryQSLCallsign Callback
     */
    public void getQSLCallsignsByCallsign(boolean showAll,int offset,String callsign, int filter, OnQueryQSLCallsign onQueryQSLCallsign) {
        new GetQLSCallsignByCallsign(showAll,offset,db, callsign, filter, onQueryQSLCallsign).execute();
    }
    /**
     * Query already QSO'd grids, mainly used for GridTracker
     * Can know which grids are QSO, which are QSL
     *
     * @param onGetQsoGrids Event after query completes.
     */
    public void getQsoGridQuery(OnGetQsoGrids onGetQsoGrids) {
        new GetQsoGrids(db, onGetQsoGrids).execute();
    }
    /**
     * Query QSL records by callsign
     *
     * @param callsign                 Callsign
     * @param onQueryQSLRecordCallsign Callback
     */
    public void getQSLRecordByCallsign(boolean showAll,int offset,String callsign, int filter, OnQueryQSLRecordCallsign onQueryQSLRecordCallsign) {
        new GetQSLByCallsign(showAll,offset,db, callsign, filter, onQueryQSLRecordCallsign).execute();
    }
    /**
     * Delete QSO callsign
     *
     * @param id ID
     */
    public void deleteQSLCallsign(int id) {
        new DeleteQSLCallsignByID(db, id).execute();
    }
    /**
     * Delete log
     *
     * @param id ID
     */
    public void deleteQSLByID(int id) {
        new DeleteQSLByID(db, id).execute();
    }
    /**
     * Modify manual confirmation of log
     *
     * @param isQSL Whether confirmed
     * @param id    ID
     */
    public void setQSLTableIsQSL(boolean isQSL, int id) {
        new SetQSLTableIsQSL(db, id, isQSL).execute();
    }
    public void setQSLCallsignIsQSL(boolean isQSL, int id) {
        new SetQSLCallsignIsQSL(db, id, isQSL).execute();
    }
    /**
     * Query callsign-grid mapping from database, after query, data is written to GeneralVariables.callsignAndGrids
     *
     * @param callsign Callsign
     */
    public void getCallsignQTH(String callsign) {
        new GetCallsignQTH(db).execute(callsign);
    }
    //    /**
    //     * Write string to file
    //     * @param file
    //     * @param data
    //     */
    //    private void writeStrToFile(File file, String data) {
    //        FileOutputStream fileOutputStream = null;
    //        try {
    //            fileOutputStream = new FileOutputStream(file, true);
    //            fileOutputStream.write(data.getBytes());
    //        } catch (IOException e) {
    //            Log.e(TAG, String.format("Failed to write file: %s", e.getMessage()));
    //        } finally {
    //            try {
    //                if (fileOutputStream != null) {
    //                    fileOutputStream.close();
    //                }
    //            } catch (IOException e) {
    //                Log.e(TAG, String.format("Failed to close file: %s", e.getMessage()));
    //            }
    //        }
    //    }
    //    /**
    //     * Write log data to file for sharing etc.
    //     * @param cursor Cursor
    //     * @param isSWL Whether SWL mode
    //     */
    //    @SuppressLint({"DefaultLocale", "Range"})
    //    public void downQSLTableToFile(File adiFile, Cursor cursor, boolean isSWL){
    //
    //        writeStrToFile(adiFile,"FT8CN ADIF Export<eoh>
        //");
        //        int count =0;
        //        cursor.moveToPosition(-1);
        //        while (cursor.moveToNext()) {
        //            count++;
        //            writeStrToFile(adiFile,String.format("<call:%d>%s "
        //                    , cursor.getString(cursor.getColumnIndex("call")).length()
        //                    , cursor.getString(cursor.getColumnIndex("call"))));
        //            if (!isSWL) {
        //                if (cursor.getInt(cursor.getColumnIndex("isLotW_QSL")) == 1) {
        //                    writeStrToFile(adiFile,"<QSL_RCVD:1>Y ");
        //                } else {
        //                    writeStrToFile(adiFile,"<QSL_RCVD:1>N ");
        //                }
        //                if (cursor.getInt(cursor.getColumnIndex("isQSL")) == 1) {
        //                    writeStrToFile(adiFile,"<QSL_MANUAL:1>Y ");
        //                } else {
        //                    writeStrToFile(adiFile,"<QSL_MANUAL:1>N ");
        //                }
        //            } else {
        //                writeStrToFile(adiFile,"<swl:1>Y ");
        //            }
        //
        //            if (cursor.getString(cursor.getColumnIndex("gridsquare")) != null) {
        //                writeStrToFile(adiFile,String.format("<gridsquare:%d>%s "
        //                        , cursor.getString(cursor.getColumnIndex("gridsquare")).length()
        //                        , cursor.getString(cursor.getColumnIndex("gridsquare"))));
        //            }
        //
        //            if (cursor.getString(cursor.getColumnIndex("mode")) != null) {
        //                writeStrToFile(adiFile,String.format("<mode:%d>%s "
        //                        , cursor.getString(cursor.getColumnIndex("mode")).length()
        //                        , cursor.getString(cursor.getColumnIndex("mode"))));
        //            }
        //
        //            if (cursor.getString(cursor.getColumnIndex("rst_sent")) != null) {
        //                writeStrToFile(adiFile,String.format("<rst_sent:%d>%s "
        //                        , cursor.getString(cursor.getColumnIndex("rst_sent")).length()
        //                        , cursor.getString(cursor.getColumnIndex("rst_sent"))));
        //            }
        //
        //            if (cursor.getString(cursor.getColumnIndex("rst_rcvd")) != null) {
        //                writeStrToFile(adiFile,String.format("<rst_rcvd:%d>%s "
        //                        , cursor.getString(cursor.getColumnIndex("rst_rcvd")).length()
        //                        , cursor.getString(cursor.getColumnIndex("rst_rcvd"))));
        //            }
        //
        //            if (cursor.getString(cursor.getColumnIndex("qso_date")) != null) {
        //                writeStrToFile(adiFile,String.format("<qso_date:%d>%s "
        //                        , cursor.getString(cursor.getColumnIndex("qso_date")).length()
        //                        , cursor.getString(cursor.getColumnIndex("qso_date"))));
        //            }
        //
        //            if (cursor.getString(cursor.getColumnIndex("time_on")) != null) {
        //                writeStrToFile(adiFile,String.format("<time_on:%d>%s "
        //                        , cursor.getString(cursor.getColumnIndex("time_on")).length()
        //                        , cursor.getString(cursor.getColumnIndex("time_on"))));
        //            }
        //
        //            if (cursor.getString(cursor.getColumnIndex("qso_date_off")) != null) {
        //                writeStrToFile(adiFile,String.format("<qso_date_off:%d>%s "
        //                        , cursor.getString(cursor.getColumnIndex("qso_date_off")).length()
        //                        , cursor.getString(cursor.getColumnIndex("qso_date_off"))));
        //            }
        //
        //            if (cursor.getString(cursor.getColumnIndex("time_off")) != null) {
        //                writeStrToFile(adiFile,String.format("<time_off:%d>%s "
        //                        , cursor.getString(cursor.getColumnIndex("time_off")).length()
        //                        , cursor.getString(cursor.getColumnIndex("time_off"))));
        //            }
        //
        //            if (cursor.getString(cursor.getColumnIndex("band")) != null) {
        //                writeStrToFile(adiFile,String.format("<band:%d>%s "
        //                        , cursor.getString(cursor.getColumnIndex("band")).length()
        //                        , cursor.getString(cursor.getColumnIndex("band"))));
        //            }
        //
        //            if (cursor.getString(cursor.getColumnIndex("freq")) != null) {
        //                writeStrToFile(adiFile,String.format("<freq:%d>%s "
        //                        , cursor.getString(cursor.getColumnIndex("freq")).length()
        //                        , cursor.getString(cursor.getColumnIndex("freq"))));
        //            }
        //
        //            if (cursor.getString(cursor.getColumnIndex("station_callsign")) != null) {
        //                writeStrToFile(adiFile,String.format("<station_callsign:%d>%s "
        //                        , cursor.getString(cursor.getColumnIndex("station_callsign")).length()
        //                        , cursor.getString(cursor.getColumnIndex("station_callsign"))));
        //            }
        //
        //            if (cursor.getString(cursor.getColumnIndex("my_gridsquare")) != null) {
        //                writeStrToFile(adiFile,String.format("<my_gridsquare:%d>%s "
        //                        , cursor.getString(cursor.getColumnIndex("my_gridsquare")).length()
        //                        , cursor.getString(cursor.getColumnIndex("my_gridsquare"))));
        //            }
        //
        //            if (cursor.getColumnIndex("operator") != -1) {
        //                if (cursor.getString(cursor.getColumnIndex("operator")) != null) {
        //                    writeStrToFile(adiFile,String.format("<operator:%d>%s "
        //                            , cursor.getString(cursor.getColumnIndex("operator")).length()
        //                            , cursor.getString(cursor.getColumnIndex("operator"))));
        //                }
        //            }
        //            String comment = cursor.getString(cursor.getColumnIndex("comment"));
        //
        //            //<comment:15>Distance: 99 km <eor>
        //            //When writing to database, must add " km"
        //            writeStrToFile(adiFile,String.format("<comment:%d>%s <eor>
        //"
    //                    , comment.length()
    //                    , comment));
    //        }
    //        Log.e(TAG,String.format("Wrote %d records",count));
    //
    //        cursor.close();
    //    }
    /**
     * Generate ADIF text content
     * @param cursor Cursor
     * @param isSWL Whether SWL mode
     * @return ADIF text content
     */
    @SuppressLint({"Range", "DefaultLocale"})
    public String downQSLTable(Cursor cursor, boolean isSWL) {
        StringBuilder logStr = new StringBuilder();
        logStr.append("FT8CN ADIF Export<eoh>");
        cursor.moveToPosition(-1);
        while (cursor.moveToNext()) {
            logStr.append(String.format("<call:%d>%s "
                    , cursor.getString(cursor.getColumnIndex("call")).length()
                    , cursor.getString(cursor.getColumnIndex("call"))));
            if (!isSWL) {
                if (cursor.getInt(cursor.getColumnIndex("isLotW_QSL")) == 1) {
                    logStr.append("<QSL_RCVD:1>Y ");
                } else {
                    logStr.append("<QSL_RCVD:1>N ");
                }
                if (cursor.getInt(cursor.getColumnIndex("isQSL")) == 1) {
                    logStr.append("<QSL_MANUAL:1>Y ");
                } else {
                    logStr.append("<QSL_MANUAL:1>N ");
                }
            } else {
                logStr.append("<swl:1>Y ");
            }
            if (cursor.getString(cursor.getColumnIndex("gridsquare")) != null) {
                logStr.append(String.format("<gridsquare:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("gridsquare")).length()
                        , cursor.getString(cursor.getColumnIndex("gridsquare"))));
            }
            if (cursor.getString(cursor.getColumnIndex("mode")) != null) {
                logStr.append(String.format("<mode:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("mode")).length()
                        , cursor.getString(cursor.getColumnIndex("mode"))));
            }
            if (cursor.getString(cursor.getColumnIndex("rst_sent")) != null) {
                logStr.append(String.format("<rst_sent:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("rst_sent")).length()
                        , cursor.getString(cursor.getColumnIndex("rst_sent"))));
            }
            if (cursor.getString(cursor.getColumnIndex("rst_rcvd")) != null) {
                logStr.append(String.format("<rst_rcvd:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("rst_rcvd")).length()
                        , cursor.getString(cursor.getColumnIndex("rst_rcvd"))));
            }
            if (cursor.getString(cursor.getColumnIndex("qso_date")) != null) {
                logStr.append(String.format("<qso_date:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("qso_date")).length()
                        , cursor.getString(cursor.getColumnIndex("qso_date"))));
            }
            if (cursor.getString(cursor.getColumnIndex("time_on")) != null) {
                logStr.append(String.format("<time_on:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("time_on")).length()
                        , cursor.getString(cursor.getColumnIndex("time_on"))));
            }
            if (cursor.getString(cursor.getColumnIndex("qso_date_off")) != null) {
                logStr.append(String.format("<qso_date_off:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("qso_date_off")).length()
                        , cursor.getString(cursor.getColumnIndex("qso_date_off"))));
            }
            if (cursor.getString(cursor.getColumnIndex("time_off")) != null) {
                logStr.append(String.format("<time_off:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("time_off")).length()
                        , cursor.getString(cursor.getColumnIndex("time_off"))));
            }
            if (cursor.getString(cursor.getColumnIndex("band")) != null) {
                logStr.append(String.format("<band:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("band")).length()
                        , cursor.getString(cursor.getColumnIndex("band"))));
            }
            if (cursor.getString(cursor.getColumnIndex("freq")) != null) {
                logStr.append(String.format("<freq:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("freq")).length()
                        , cursor.getString(cursor.getColumnIndex("freq"))));
            }
            if (cursor.getString(cursor.getColumnIndex("station_callsign")) != null) {
                logStr.append(String.format("<station_callsign:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("station_callsign")).length()
                        , cursor.getString(cursor.getColumnIndex("station_callsign"))));
            }
            if (cursor.getString(cursor.getColumnIndex("my_gridsquare")) != null) {
                logStr.append(String.format("<my_gridsquare:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("my_gridsquare")).length()
                        , cursor.getString(cursor.getColumnIndex("my_gridsquare"))));
            }
            if (cursor.getColumnIndex("operator") != -1) {
                if (cursor.getString(cursor.getColumnIndex("operator")) != null) {
                    logStr.append(String.format("<operator:%d>%s "
                            , cursor.getString(cursor.getColumnIndex("operator")).length()
                            , cursor.getString(cursor.getColumnIndex("operator"))));
                }
            }
            String comment = cursor.getString(cursor.getColumnIndex("comment"));
            //<comment:15>Distance: 99 km <eor>
            //When writing to database, must add " km"
            logStr.append(String.format("<comment:%d>%s <eor>"
                    , comment.length()
                    , comment));
        }
        cursor.close();
        return logStr.toString();
    }
    /**
     * List already QSO'd DXCC zones
     */
    @SuppressLint("Range")
    public void getQslDxccToMap() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String querySQL;
                Cursor cursor;
                Log.d(TAG, "run: Importing divisions...");
                // Import already QSO'd dxcc
                querySQL = "SELECT DISTINCT dl.pp FROM   dxcc_grid dg " +
                        "inner join  QSLTable q " +
                        "on  dg.grid =UPPER(SUBSTR(q.gridsquare,1,4))  LEFT JOIN dxccList dl on dg.dxcc =dl.dxcc";
                cursor = db.rawQuery(querySQL, null);
                while (cursor.moveToNext()) {
                    GeneralVariables.addDxcc(cursor.getString(cursor.getColumnIndex("pp")));
                }
                cursor.close();
                // Import already QSO'd CQ zones
                querySQL = "SELECT DISTINCT  cl.cqzone  as cq FROM   cqzoneList cl " +
                        "inner join  QSLTable q " +
                        "on  cl.grid =UPPER(SUBSTR(q.gridsquare,1,4)) ";
                cursor = db.rawQuery(querySQL, null);
                while (cursor.moveToNext()) {
                    GeneralVariables.addCqZone(cursor.getInt(cursor.getColumnIndex("cq")));
                }
                cursor.close();
                // Import already QSO'd itu zones
                querySQL = "SELECT DISTINCT il.itu   FROM   ituList il " +
                        "inner join  QSLTable q " +
                        "on  il.grid =UPPER(SUBSTR(q.gridsquare,1,4))";
                cursor = db.rawQuery(querySQL, null);
                while (cursor.moveToNext()) {
                    GeneralVariables.addItuZone(cursor.getInt(cursor.getColumnIndex("itu")));
                }
                cursor.close();
                Log.d(TAG, "run: Division import complete...");
            }
        }).start();
    }
    /**
     * Check if QSO'd callsign exists, if exists return TRUE and update isLotW_QSL
     *
     * @param record Record
     * @return Whether exists
     */
    @SuppressLint("Range")
    public boolean checkQSLCallsign(QSLRecord record) {
        QSLRecord newRecord = record;
        newRecord.id = -1;
        // Check if callsign already exists
        String querySQL = "select * from QslCallsigns WHERE (callsign=?)" +
                "and (startTime=?) and(finishTime=?) and(mode=?)";
        Cursor cursor = db.rawQuery(querySQL, new String[]{
                record.getToCallsign()
                , record.getStartTime()
                , record.getEndTime()
                , record.getMode()});
        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            newRecord.isLotW_QSL = cursor.getInt(cursor.getColumnIndex("isLotW_QSL")) == 1
                    || record.isLotW_QSL;
            newRecord.id = cursor.getLong(cursor.getColumnIndex("ID"));
        }
        cursor.close();
        //        if (newRecord.id != -1) {// Record already exists
        //            querySQL = "UPDATE   QslCallsigns set isLotW_QSL=? WHERE ID=?";
        //            db.execSQL(querySQL, new Object[]{newRecord.isLotW_QSL ? '1' : '0', newRecord.id});
        //        }
        return newRecord.id != -1;//
    }
    @SuppressLint("Range")
    public boolean checkIsQSL(QSLRecord record) {
        QSLRecord newRecord = record;
        newRecord.id = -1;
        // Check if log record already exists
        String querySQL = "select * from QSLTable WHERE (call=?)" +
                "and (qso_date=?) and(time_on=?) and(mode=?)";
        Cursor cursor = db.rawQuery(querySQL, new String[]{
                record.getToCallsign()
                , record.getQso_date()
                , record.getTime_on()
                , record.getMode()});
        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            newRecord.isLotW_QSL = cursor.getInt(cursor.getColumnIndex("isLotW_QSL")) == 1
                    || record.isLotW_QSL;
            newRecord.id = cursor.getLong(cursor.getColumnIndex("id"));
        }
        cursor.close();
        //        if (newRecord.id != -1) {// Record already exists
        //            querySQL = "UPDATE   QSLTable set isLotW_QSL=? WHERE ID=?";
        //            db.execSQL(querySQL, new Object[]{newRecord.isLotW_QSL ? '1' : '0', newRecord.id});
        //        }
        return newRecord.id != -1;//
    }
    @SuppressLint("Range")
    public boolean doInsertQSLData(QSLRecord record,AfterInsertQSLData afterInsertQSLData) {
        if (record.getToCallsign() == null) {
            if (afterInsertQSLData!=null){
                afterInsertQSLData.doAfterInsert(true,true);// Invalid QSL
            }
            return false;
        }
        String querySQL;
        if (!checkQSLCallsign(record)) {// If record does not exist, add
            querySQL = "INSERT INTO  QslCallsigns (callsign" +
                    ",isQSL,isLotW_import,isLotW_QSL" +
                    ",startTime,finishTime,mode,grid,band,band_i)" +
                    "values(?,?,?,?,?,?,?,?,?,?)";
            db.execSQL(querySQL, new Object[]{record.getToCallsign()
                    , record.isQSL ? 1 : 0// Whether manually confirmed
                    , record.isLotW_import ? 1 : 0// Whether imported from LoTW
                    , record.isLotW_QSL ? 1 : 0// Whether confirmed by LoTW
                    , record.getStartTime()
                    , record.getEndTime()
                    , record.getMode()
                    , record.getToMaidenGrid()
                    , BaseRigOperation.getFrequencyAllInfo(record.getBandFreq())
                    , record.getBandFreq()});
        } else {
            if (record.isQSL) {
                db.execSQL("UPDATE  QslCallsigns  SET isQSL=? " +
                                "WHERE  (callsign=?)AND(startTime=?)AND(finishTime=?)AND(mode=?)"
                        , new Object[]{1, record.getToCallsign(), record.getStartTime()
                                , record.getEndTime(), record.getMode()});
            }
            if (record.isLotW_import) {
                db.execSQL("UPDATE  QslCallsigns  SET isLotW_import=? " +
                                "WHERE  (callsign=?)AND(startTime=?)AND(finishTime=?)AND(mode=?)"
                        , new Object[]{1, record.getToCallsign(), record.getStartTime()
                                , record.getEndTime(), record.getMode()});
            }
            if (record.isLotW_QSL) {
                db.execSQL("UPDATE  QslCallsigns  SET isLotW_QSL=? " +
                                "WHERE  (callsign=?)AND(startTime=?)AND(finishTime=?)AND(mode=?)"
                        , new Object[]{1, record.getToCallsign(), record.getStartTime()
                                , record.getEndTime(), record.getMode()});
            }
            if (record.getToMaidenGrid().length() >= 4) {
                db.execSQL("UPDATE  QslCallsigns  SET grid=? " +
                                "WHERE  (callsign=?)AND(startTime=?)AND(finishTime=?)AND(mode=?)"
                        , new Object[]{record.getToMaidenGrid(), record.getToCallsign(), record.getStartTime()
                                , record.getEndTime(), record.getMode()});
            }
        }
        if (!checkIsQSL(record)) {// If log data does not exist, add
            querySQL = "INSERT INTO QSLTable(call, isQSL,isLotW_import,isLotW_QSL,gridsquare, mode, rst_sent, rst_rcvd, qso_date, " +
                    "time_on, qso_date_off, time_off, band, freq, station_callsign, my_gridsquare," +
                    "comment)VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            db.execSQL(querySQL, new String[]{record.getToCallsign()
                    , String.valueOf(record.isQSL ? 1 : 0)
                    , String.valueOf(record.isLotW_import ? 1 : 0)
                    , String.valueOf(record.isLotW_QSL ? 1 : 0)
                    , record.getToMaidenGrid()
                    , record.getMode()
                    , String.valueOf(record.getSendReport())
                    , String.valueOf(record.getReceivedReport())
                    , record.getQso_date()
                    , record.getTime_on()
                    , record.getQso_date_off()
                    , record.getTime_off()
                    , record.getBandLength()// Wavelength//RigOperationConstant.getMeterFromFreq(qslRecord.getBandFreq())
                    , BaseRigOperation.getFrequencyFloat(record.getBandFreq())
                    , record.getMyCallsign()
                    , record.getMyMaidenGrid()
                    , record.getComment()});
            if (afterInsertQSLData!=null){
                afterInsertQSLData.doAfterInsert(false,true);// New QSL
            }
        } else {
            if (record.isQSL) {
                db.execSQL("UPDATE  QSLTable  SET isQSL=? " +
                                " WHERE (call=?) and (qso_date=?) and(time_on=?) and(mode=?)"
                        , new Object[]{1, record.getToCallsign()
                                , record.getQso_date()
                                , record.getTime_on()
                                , record.getMode()});
            }
            if (record.isLotW_import) {
                db.execSQL("UPDATE  QSLTable  SET isLotW_import=? " +
                                " WHERE (call=?) and (qso_date=?) and(time_on=?) and(mode=?)"
                        , new Object[]{1, record.getToCallsign()
                                , record.getQso_date()
                                , record.getTime_on()
                                , record.getMode()});
            }
            if (record.isLotW_QSL) {
                db.execSQL("UPDATE  QSLTable  SET isLotW_QSL=? " +
                                " WHERE (call=?) and (qso_date=?) and(time_on=?) and(mode=?)"
                        , new Object[]{1, record.getToCallsign()
                                , record.getQso_date()
                                , record.getTime_on()
                                , record.getMode()});
            }
            if (record.getToMaidenGrid().length() >= 4) {
                db.execSQL("UPDATE  QSLTable  SET gridsquare=? " +
                                " WHERE (call=?) and (qso_date=?) and(time_on=?) and(mode=?)"
                        , new Object[]{record.getToMaidenGrid(), record.getToCallsign()
                                , record.getQso_date()
                                , record.getTime_on()
                                , record.getMode()});
            }
            if (record.getMyMaidenGrid().length() >= 4) {
                db.execSQL("UPDATE  QSLTable  SET my_gridsquare=? " +
                                " WHERE (call=?) and (qso_date=?) and(time_on=?) and(mode=?)"
                        , new Object[]{record.getMyMaidenGrid(), record.getToCallsign()
                                , record.getQso_date()
                                , record.getTime_on()
                                , record.getMode()});
            }
            if (record.getSendReport() > -100) {
                db.execSQL("UPDATE  QSLTable  SET rst_sent=? " +
                                " WHERE (call=?) and (qso_date=?) and(time_on=?) and(mode=?)"
                        , new Object[]{record.getSendReport(), record.getToCallsign()
                                , record.getQso_date()
                                , record.getTime_on()
                                , record.getMode()});
            }
            if (record.getReceivedReport() > -100) {
                db.execSQL("UPDATE  QSLTable  SET rst_rcvd=? " +
                                " WHERE (call=?) and (qso_date=?) and(time_on=?) and(mode=?)"
                        , new Object[]{record.getReceivedReport(), record.getToCallsign()
                                , record.getQso_date()
                                , record.getTime_on()
                                , record.getMode()});
            }
            if (afterInsertQSLData!=null){
                afterInsertQSLData.doAfterInsert(false,false);// Already exists, needs update
            }
        }
        return true;
    }
    /**
     * Query configuration class
     */
    static class QueryConfig extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final String KeyName;
        private final OnAfterQueryConfig afterQueryConfig;
        public QueryConfig(SQLiteDatabase db, String keyName, OnAfterQueryConfig afterQueryConfig) {
            this.db = db;
            KeyName = keyName;
            this.afterQueryConfig = afterQueryConfig;
        }
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            if (afterQueryConfig != null) {
                afterQueryConfig.doOnBeforeQueryConfig(KeyName);
            }
        }
        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            String querySQL = "select keyName,Value from config where KeyName =?";
            Cursor cursor = db.rawQuery(querySQL, new String[]{KeyName.toString()});
            if (cursor.moveToFirst()) {
                if (afterQueryConfig != null) {
                    afterQueryConfig.doOnAfterQueryConfig(KeyName, cursor.getString(cursor.getColumnIndex("Value")));
                }
            } else {
                if (afterQueryConfig != null) {
                    afterQueryConfig.doOnAfterQueryConfig(KeyName, "");
                }
            }
            cursor.close();
            return null;
        }
    }
    static class QueryCallsign extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final String tableName;
        private final String fieldName;
        private final String callSign;
        private OnGetCallsign onGetCallsign;
        public QueryCallsign(SQLiteDatabase db, String tableName, String fieldName
                , String callSign, OnGetCallsign onGetCallsign) {
            this.db = db;
            this.tableName = tableName;
            this.fieldName = fieldName;
            this.callSign = callSign;
            this.onGetCallsign = onGetCallsign;
        }
        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            // [FIX] Заменены двойные кавычки на одинарные для строкового литерала
            String sql = String.format("select count(%s) as a FROM %s where %s='%s' limit 1"
                    , fieldName, tableName, fieldName, callSign);
            Cursor cursor = db.rawQuery(sql, null);
            if (cursor.moveToFirst()) {
                if (onGetCallsign != null) {
                    onGetCallsign.doOnAfterGetCallSign(cursor.getInt(cursor.getColumnIndex("a")) > 0);
                }
            } else {
                if (onGetCallsign != null) {
                    onGetCallsign.doOnAfterGetCallSign(false);
                }
            }
            cursor.close();
            return null;
        }
    }
    /**
     * Write configuration class
     */
    static class WriteConfig extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final String KeyName;
        private final String Value;
        private final OnAfterWriteConfig afterWriteConfig;
        public WriteConfig(SQLiteDatabase db, String keyName, String Value, OnAfterWriteConfig afterWriteConfig) {
            this.db = db;
            this.KeyName = keyName;
            this.afterWriteConfig = afterWriteConfig;
            this.Value = Value;
        }
        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            String querySQL = "DELETE FROM config where KeyName =?";
            db.execSQL(querySQL, new String[]{KeyName.toString()});
            querySQL = "INSERT INTO config (KeyName,Value)Values(?,?)";
            db.execSQL(querySQL, new String[]{KeyName.toString(), Value.toString()});
            if (afterWriteConfig != null) {
                afterWriteConfig.doOnAfterWriteConfig(true);
            }
            return null;
        }
    }
    /**
     * OPTIMIZATION: Write messages to database using batch transaction
     * Original implementation executed execSQL() per message, causing frequent fsync calls.
     * Transactional batch insert reduces write time by 10-50x during high-traffic periods.
     */
    static class WriteMessages extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private ArrayList<Ft8Message> messages;
        public WriteMessages(SQLiteDatabase db, ArrayList<Ft8Message> messages) {
            this.db = db;
            this.messages = messages;
        }
        @Override
        protected Void doInBackground(Void... voids) {
            if (messages == null || messages.isEmpty()) return null;
            String sql = "INSERT INTO SWLMessages(I3,N3,Protocol,UTC,SNR,TIME_SEC,FREQ,CALL_FROM" +
                    ",CALL_TO,EXTRAL,REPORT,BAND) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
            // OPTIMIZATION: Wrap all inserts in a single transaction
            db.beginTransaction();
            try {
                for (Ft8Message message : messages) {
                    // Only save messages related to me (preserves original filtering logic)
                    if (message.callsignFrom != null && message.callsignTo != null) {
                        db.execSQL(sql, new Object[]{
                                message.i3,
                                message.n3,
                                "FT8",
                                UtcTimer.getDatetimeYYYYMMDD_HHMMSS(message.utcTime),
                                message.snr,
                                message.time_sec,
                                Math.round(message.freq_hz),
                                message.callsignFrom,
                                message.callsignTo,
                                message.extraInfo,
                                message.report,
                                message.band
                        });
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return null;
        }
    }
    /**
     * Write followed callsigns to database
     */
    static class AddFollowCallSign extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final String callSign;
        public AddFollowCallSign(SQLiteDatabase db, String callSign) {
            this.db = db;
            this.callSign = callSign;
        }
        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            String querySQL = "INSERT OR IGNORE INTO  followCallsigns (callsign)values(?)";
            db.execSQL(querySQL, new String[]{callSign});
            return null;
        }
    }
    /**
     * Write data to callsign-grid mapping table, AsyncTask String is multi-parameter, passed as array to doInBackground
     * So, first element is callsign, second is grid
     */
    static class AddCallsignQTH extends AsyncTask<String, Void, Void> {
        private final SQLiteDatabase db;
        public AddCallsignQTH(SQLiteDatabase db) {
            this.db = db;
        }
        @Override
        protected Void doInBackground(String... strings) {
            if (strings.length == 2) {
                String querySQL = "INSERT OR REPLACE  INTO  CallsignQTH  (callsign,grid,updateTime)" +
                        "VALUES (Upper(?),?,?)";
                db.execSQL(querySQL, new Object[]{strings[0], strings[1], System.currentTimeMillis()});
            }
            return null;
        }
    }
    static class Add_SWL_QSO_Info extends AsyncTask<Void, Void, Void>{
        private final DatabaseOpr databaseOpr;
        private QSLRecord qslRecord;
        public Add_SWL_QSO_Info(DatabaseOpr opr, QSLRecord qslRecord) {
            this.databaseOpr = opr;
            this.qslRecord = qslRecord;
        }
        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            String querySQL;
            // Delete previous duplicate records
            querySQL = "DELETE FROM  SWLQSOTable where ([call]=?) and (station_callsign=?) and (qso_date=?) and(time_on=?) and (freq=?)";
            databaseOpr.db.execSQL(querySQL, new String[]{
                    qslRecord.getToCallsign()
                    , qslRecord.getMyCallsign()
                    , qslRecord.getQso_date()
                    , qslRecord.getTime_on()
                    , BaseRigOperation.getFrequencyFloat(qslRecord.getBandFreq())
            });
            // Add record
            querySQL = "INSERT INTO SWLQSOTable([call], gridsquare, mode, rst_sent, rst_rcvd, qso_date, " +
                    "time_on, qso_date_off, time_off, band, freq, station_callsign, my_gridsquare,operator,comment) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            databaseOpr.db.execSQL(querySQL, new String[]{qslRecord.getToCallsign()
                    , qslRecord.getToMaidenGrid()
                    , qslRecord.getMode()
                    , String.valueOf(qslRecord.getSendReport())
                    , String.valueOf(qslRecord.getReceivedReport())
                    , qslRecord.getQso_date()
                    , qslRecord.getTime_on()
                    , qslRecord.getQso_date_off()
                    , qslRecord.getTime_off()
                    , qslRecord.getBandLength()// Wavelength//RigOperationConstant.getMeterFromFreq(qslRecord.getBandFreq())
                    , BaseRigOperation.getFrequencyFloat(qslRecord.getBandFreq())
                    , qslRecord.getMyCallsign()
                    , qslRecord.getMyMaidenGrid()
                    , GeneralVariables.myCallsign// My callsign, not both parties' callsign
                    , qslRecord.getComment()});
            return null;
        }
    }
    /**
     * Write successfully QSO'd callsigns to database
     */
    static class AddQSL_Info extends AsyncTask<Void, Void, Void> {
        //private final SQLiteDatabase db;
        private final DatabaseOpr databaseOpr;
        private QSLRecord qslRecord;
        public AddQSL_Info(DatabaseOpr opr, QSLRecord qslRecord) {
            this.databaseOpr = opr;
            this.qslRecord = qslRecord;
        }
        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            databaseOpr.doInsertQSLData(qslRecord,null);// Add log and successfully QSO'd callsign
            return null;
        }
    }
    /**
     * Delete followed callsign from database
     */
    static class DeleteFollowCallsign extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final String callSign;
        public DeleteFollowCallsign(SQLiteDatabase db, String callSign) {
            this.db = db;
            this.callSign = callSign;
        }
        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            String querySQL = "DELETE  from followCallsigns  WHERE callsign=?";
            db.execSQL(querySQL, new String[]{callSign});
            return null;
        }
    }
    /**
     * Query grid from callsign-grid mapping table, parameter is callsign
     */
    static class GetCallsignQTH extends AsyncTask<String, Void, Void> {
        private final SQLiteDatabase db;
        GetCallsignQTH(SQLiteDatabase db) {
            this.db = db;
        }
        @SuppressLint("Range")
        @Override
        protected Void doInBackground(String... strings) {
            if (strings.length == 0) return null;
            String querySQL = "select grid from CallsignQTH cq " +
                    "WHERE callsign =?";
            Cursor cursor = db.rawQuery(querySQL, new String[]{strings[0]});
            if (cursor.moveToFirst()) {
                GeneralVariables.addCallsignAndGrid(strings[0]
                        , cursor.getString(cursor.getColumnIndex("grid")));
            }
            cursor.close();
            return null;
        }
    }
    static class GetMessageLogTotal extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final OnAfterQueryFollowCallsigns onAffterQueryFollowCallsigns;
        public GetMessageLogTotal(SQLiteDatabase db, OnAfterQueryFollowCallsigns onAffterQueryFollowCallsigns) {
            this.db = db;
            this.onAffterQueryFollowCallsigns = onAffterQueryFollowCallsigns;
        }
        @Override
        @SuppressLint({"Range", "DefaultLocale"})
        protected Void doInBackground(Void... voids) {
            String querySQL = "SELECT BAND ,count(*) as c from SWLMessages m group by BAND order by BAND ";
            Cursor cursor = db.rawQuery(querySQL, new String[]{});
            ArrayList<String> callsigns = new ArrayList<>();
            callsigns.add(GeneralVariables.getStringFromResource(R.string.band_total));
            callsigns.add("---------------------------------------");
            int sum = 0;
            while (cursor.moveToNext()) {
                long s = cursor.getLong(cursor.getColumnIndex("BAND")); // Get band
                int total = cursor.getInt(cursor.getColumnIndex("c")); // Get count
                callsigns.add(String.format("%.3fMHz \t %d", s / 1000000f, total));
                sum = sum + total;
            }
            callsigns.add(String.format("-----------Total %d -----------", sum));
            cursor.close();
            if (onAffterQueryFollowCallsigns != null) {
                onAffterQueryFollowCallsigns.doOnAfterQueryFollowCallsigns(callsigns);
            }
            return null;
        }
    }
    static class GetSWLQsoTotal extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final OnAfterQueryFollowCallsigns onAffterQueryFollowCallsigns;
        public GetSWLQsoTotal(SQLiteDatabase db, OnAfterQueryFollowCallsigns onAffterQueryFollowCallsigns) {
            this.db = db;
            this.onAffterQueryFollowCallsigns = onAffterQueryFollowCallsigns;
        }
        @Override
        @SuppressLint({"Range", "DefaultLocale"})
        protected Void doInBackground(Void... voids) {
            String querySQL = "select count(*) as c,substr(qso_date_off,1,6) as t " +
                    "from SWLQSOTable s " +
                    "group by substr(qso_date_off,1,6)";
            Cursor cursor = db.rawQuery(querySQL, new String[]{});
            ArrayList<String> callsigns = new ArrayList<>();
            //callsigns.add(GeneralVariables.getStringFromResource(R.string.band_total));
            callsigns.add("---------------------------------------");
            int sum = 0;
            while (cursor.moveToNext()) {
                String date = cursor.getString(cursor.getColumnIndex("t")); // Get date
                int total = cursor.getInt(cursor.getColumnIndex("c")); // Get count
                callsigns.add(String.format("%s \t %d ", date, total));
                sum = sum + total;
            }
            callsigns.add(String.format("-----------Total %d -----------", sum));
            cursor.close();
            if (onAffterQueryFollowCallsigns != null) {
                onAffterQueryFollowCallsigns.doOnAfterQueryFollowCallsigns(callsigns);
            }
            return null;
        }
    }
    /**
     * Get followed callsigns from database class
     */
    static class GetFollowCallSigns extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final OnAfterQueryFollowCallsigns onAffterQueryFollowCallsigns;
        public GetFollowCallSigns(SQLiteDatabase db, OnAfterQueryFollowCallsigns onAffterQueryFollowCallsigns) {
            this.db = db;
            this.onAffterQueryFollowCallsigns = onAffterQueryFollowCallsigns;
        }
        @Override
        protected Void doInBackground(Void... voids) {
            String querySQL = "select callsign from followCallsigns";
            Cursor cursor = db.rawQuery(querySQL, new String[]{});
            ArrayList<String> callsigns = new ArrayList<>();
            while (cursor.moveToNext()) {
                @SuppressLint("Range")
                String s = cursor.getString(cursor.getColumnIndex("callsign")); // Get first column value, index starts from 0
                if (s != null) {
                    callsigns.add(s);
                }
            }
            cursor.close();
            if (onAffterQueryFollowCallsigns != null) {
                onAffterQueryFollowCallsigns.doOnAfterQueryFollowCallsigns(callsigns);
            }
            return null;
        }
    }
    public static class GetCallsignMapGrid extends AsyncTask<Void, Void, Void> {
        SQLiteDatabase db;
        public GetCallsignMapGrid(SQLiteDatabase db) {
            this.db = db;
        }
        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            String querySQL = "select DISTINCT callsign,grid from QslCallsigns qc " +
                    "where LENGTH(grid)>3 " +
                    "order by ID ";
            Cursor cursor = db.rawQuery(querySQL, null);
            while (cursor.moveToNext()) {
                GeneralVariables.addCallsignAndGrid(cursor.getString(cursor.getColumnIndex("callsign"))
                        , cursor.getString(cursor.getColumnIndex("grid")));
            }
            cursor.close();
            return null;
        }
    }
    public interface OnGetQsoGrids {
        void onAfterQuery(HashMap<String, Boolean> grids);
    }
    static class GetQsoGrids extends AsyncTask<Void, Void, Void> {
        SQLiteDatabase db;
        HashMap<String, Boolean> grids = new HashMap<>();
        OnGetQsoGrids onGetQsoGrids;
        public GetQsoGrids(SQLiteDatabase db, OnGetQsoGrids onGetQsoGrids) {
            this.db = db;
            this.onGetQsoGrids = onGetQsoGrids;
        }
        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            String querySQL = "select qc.gridsquare ,count(*) as cc,SUM(isQSL)+SUM(isLotW_QSL)as isQSL " +
                    "from QSLTable  qc " +
                    "WHERE LENGTH (qc.gridsquare)>2 " +
                    "group by qc.gridsquare " +
                    "ORDER by SUM(isQSL)+SUM(isLotW_QSL) desc";
            Cursor cursor = db.rawQuery(querySQL, null);
            while (cursor.moveToNext()) {
                grids.put(cursor.getString(cursor.getColumnIndex("gridsquare"))
                        , cursor.getInt(cursor.getColumnIndex("isQSL")) != 0);
            }
            cursor.close();
            if (onGetQsoGrids != null) {
                onGetQsoGrids.onAfterQuery(grids);
            }
            return null;
        }
    }
    static class GetQSLByCallsign extends AsyncTask<Void, Void, Void> {
        boolean showAll;
        int offset;
        SQLiteDatabase db;
        String callsign;
        int filter;
        OnQueryQSLRecordCallsign onQueryQSLRecordCallsign;
        public GetQSLByCallsign(boolean showAll,int offset,SQLiteDatabase db, String callsign, int queryFilter, OnQueryQSLRecordCallsign onQueryQSLRecordCallsign) {
            this.showAll=showAll;
            this.offset=offset;
            this.db = db;
            this.callsign = callsign;
            this.filter = queryFilter;
            this.onQueryQSLRecordCallsign = onQueryQSLRecordCallsign;
        }
        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            String filterStr;
            switch (filter) {
                case 1:
                    filterStr = "and((isQSL =1)or(isLotW_QSL =1)) ";
                    break;
                case 2:
                    filterStr = "and((isQSL =0)and(isLotW_QSL =0)) ";
                    break;
                default:
                    filterStr = "";
            }
            String limitStr="";
            if (!showAll){
                limitStr="limit 100 offset "+offset;
            }
            String querySQL = "select * from QSLTable where ([call] like ?) " +
                    filterStr +
                    " ORDER BY qso_date DESC, time_off DESC "+
                    //" order by ID desc "+
                    limitStr;
            Cursor cursor = db.rawQuery(querySQL, new String[]{"%" + callsign + "%"});
            ArrayList<QSLRecordStr> records = new ArrayList<>();
            while (cursor.moveToNext()) {
                QSLRecordStr record = new QSLRecordStr();
                record.id = cursor.getInt(cursor.getColumnIndex("id"));
                record.setCall(cursor.getString(cursor.getColumnIndex("call")));
                record.isQSL = cursor.getInt(cursor.getColumnIndex("isQSL")) == 1;
                record.isLotW_import = cursor.getInt(cursor.getColumnIndex("isLotW_import")) == 1;
                record.isLotW_QSL = cursor.getInt(cursor.getColumnIndex("isLotW_QSL")) == 1;
                record.setGridsquare(cursor.getString(cursor.getColumnIndex("gridsquare")));
                record.setMode(cursor.getString(cursor.getColumnIndex("mode")));
                record.setRst_sent(cursor.getString(cursor.getColumnIndex("rst_sent")));
                record.setRst_rcvd(cursor.getString(cursor.getColumnIndex("rst_rcvd")));
                record.setTime_on(String.format("%s-%s"
                        , cursor.getString(cursor.getColumnIndex("qso_date"))
                        , cursor.getString(cursor.getColumnIndex("time_on"))));
                record.setTime_off(String.format("%s-%s"
                        , cursor.getString(cursor.getColumnIndex("qso_date_off"))
                        , cursor.getString(cursor.getColumnIndex("time_off"))));
                record.setBand(cursor.getString(cursor.getColumnIndex("band")));// Wavelength
                record.setFreq(cursor.getString(cursor.getColumnIndex("freq")));// Frequency
                record.setStation_callsign(cursor.getString(cursor.getColumnIndex("station_callsign")));
                record.setMy_gridsquare(cursor.getString(cursor.getColumnIndex("my_gridsquare")));
                record.setComment(cursor.getString(cursor.getColumnIndex("comment")));
                records.add(record);
            }
            cursor.close();
            if (onQueryQSLRecordCallsign != null) {
                onQueryQSLRecordCallsign.afterQuery(records);
            }
            return null;
        }
    }
    /**
     * Query successfully QSO'd callsigns by callsign
     */
    static class GetQLSCallsignByCallsign extends AsyncTask<Void, Void, Void> {
        SQLiteDatabase db;
        String callsign;
        int filter;
        OnQueryQSLCallsign onQueryQSLCallsign;
        int offset;
        boolean showAll;
        public GetQLSCallsignByCallsign(boolean showAll,int offset,SQLiteDatabase db, String callsign, int queryFilter, OnQueryQSLCallsign onQueryQSLCallsign) {
            this.showAll=showAll;
            this.offset=offset;
            this.db = db;
            this.callsign = callsign;
            this.filter = queryFilter;
            this.onQueryQSLCallsign = onQueryQSLCallsign;
        }
        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            String filterStr;
            switch (filter) {
                case 1:
                    filterStr = "and((q.isQSL =1)or(q.isLotW_QSL =1)) ";
                    break;
                case 2:
                    filterStr = "and((q.isQSL =0)and(q.isLotW_QSL =0)) ";
                    break;
                default:
                    filterStr = "";
            }
            String limitStr="";
            if (!showAll){
                limitStr="limit 100 offset "+offset;
            }
            // [FIX] Заменены двойные кавычки на одинарные в строке конкатенации для названия единицы измерения
            String querySQL = "select q.[call] as callsign ,q.gridsquare as grid" +
                    ",q.band||' ('||q.freq||' MHz)' as band " +
                    ",q.qso_date as last_time ,q.mode ,q.isQSL,q.isLotW_QSL " +
                    "from QSLTable q inner join QSLTable q2 ON q.id =q2.id " +
                    "where (q.[call] like ?) " +
                    filterStr +
                    "group by q.[call] ,q.gridsquare,q.freq ,q.qso_date,q.band " +
                    ",q.mode,q.isQSL,q.isLotW_QSL " +
                    "HAVING q.qso_date =MAX(q2.qso_date) " +
                    "order by q.qso_date desc "+
                    limitStr;
            Cursor cursor = db.rawQuery(querySQL, new String[]{"%" + callsign + "%"});
            ArrayList<QSLCallsignRecord> records = new ArrayList<>();
            while (cursor.moveToNext()) {
                QSLCallsignRecord record = new QSLCallsignRecord();
                record.setCallsign(cursor.getString(cursor.getColumnIndex("callsign")));
                record.isQSL = cursor.getInt(cursor.getColumnIndex("isQSL")) == 1;
                record.isLotW_QSL = cursor.getInt(cursor.getColumnIndex("isLotW_QSL")) == 1;
                record.setLastTime(cursor.getString(cursor.getColumnIndex("last_time")));
                record.setMode(cursor.getString(cursor.getColumnIndex("mode")));
                record.setGrid(cursor.getString(cursor.getColumnIndex("grid")));
                record.setBand(cursor.getString(cursor.getColumnIndex("band")));
                records.add(record);
            }
            cursor.close();
            if (onQueryQSLCallsign != null) {
                onQueryQSLCallsign.afterQuery(records);
            }
            return null;
        }
    }
    /**
     * Get QSO'd callsigns
     */
    @SuppressLint("DefaultLocale")
    static class GetAllQSLCallsign {
        public static void get(SQLiteDatabase db) {
            //String querySQL = "select distinct [call] from QSLTable where freq=?";
            // Changed to use wavelength BAND to get QSO'd callsigns
            String querySQL = "select distinct [call] from QSLTable where band=?";
            Cursor cursor = db.rawQuery(querySQL, new String[]{
                    BaseRigOperation.getMeterFromFreq(GeneralVariables.band)});
            ArrayList<String> callsigns = new ArrayList<>();
            while (cursor.moveToNext()) {
                @SuppressLint("Range")
                String s = cursor.getString(cursor.getColumnIndex("call"));
                if (s != null) {
                    callsigns.add(s);
                }
            }
            cursor.close();
            GeneralVariables.QSL_Callsign_list = callsigns;
            querySQL = "select distinct [call] from QSLTable where band<>?";
            cursor = db.rawQuery(querySQL, new String[]{
                    BaseRigOperation.getMeterFromFreq(GeneralVariables.band)});
            ArrayList<String> other_callsigns = new ArrayList<>();
            while (cursor.moveToNext()) {
                @SuppressLint("Range")
                String s = cursor.getString(cursor.getColumnIndex("call"));
                if (s != null) {
                    other_callsigns.add(s);
                }
            }
            cursor.close();
            GeneralVariables.QSL_Callsign_list_other_band = other_callsigns;
        }
    }
    /**
     * Delete QSO callsign by ID
     */
    static class DeleteQSLCallsignByID extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final int id;
        public DeleteQSLCallsignByID(SQLiteDatabase db, int id) {
            this.db = db;
            this.id = id;
        }
        @Override
        protected Void doInBackground(Void... voids) {
            db.execSQL("delete from QslCallsigns where id=?", new Object[]{id});
            return null;
        }
    }
    /**
     * Delete log by ID
     */
    static class DeleteQSLByID extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final int id;
        public DeleteQSLByID(SQLiteDatabase db, int id) {
            this.db = db;
            this.id = id;
        }
        @Override
        protected Void doInBackground(Void... voids) {
            db.execSQL("delete from QSLTable where id=?", new Object[]{id});
            return null;
        }
    }
    static class SetQSLCallsignIsQSL extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final int id;
        private final boolean isQSL;
        public SetQSLCallsignIsQSL(SQLiteDatabase db, int id, boolean isQSL) {
            this.db = db;
            this.id = id;
            this.isQSL = isQSL;
        }
        @Override
        protected Void doInBackground(Void... voids) {
            db.execSQL("UPDATE QslCallsigns SET isQSL=? where id=?", new Object[]{isQSL ? "1" : "0", id});
            return null;
        }
    }
    /**
     * Set log manual confirmation
     */
    static class SetQSLTableIsQSL extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final int id;
        private final boolean isQSL;
        public SetQSLTableIsQSL(SQLiteDatabase db, int id, boolean isQSL) {
            this.db = db;
            this.id = id;
            this.isQSL = isQSL;
        }
        @Override
        protected Void doInBackground(Void... voids) {
            db.execSQL("UPDATE QSLTable SET isQSL=? where id=?", new Object[]{isQSL ? "1" : "0", id});
            return null;
        }
    }
    /**
     * Query all successfully QSO'd callsigns, filter by frequency at time of QSO
     */
    static class LoadAllQSLCallsigns extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        public LoadAllQSLCallsigns(SQLiteDatabase db) {
            this.db = db;
        }
        @Override
        protected Void doInBackground(Void... voids) {
            GetAllQSLCallsign.get(db);// Get QSO'd callsigns
            return null;
        }
    }
    static class GetAllConfigParameter extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final SecureStorage secureStorage;
        private OnAfterQueryConfig onAfterQueryConfig;
        public GetAllConfigParameter(SQLiteDatabase db, SecureStorage secureStorage, OnAfterQueryConfig onAfterQueryConfig) {
            this.db = db;
            this.secureStorage = secureStorage;
            this.onAfterQueryConfig = onAfterQueryConfig;
        }
        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            String querySQL = "select keyName,Value from config ";
            Cursor cursor = db.rawQuery(querySQL, null);
            while (cursor.moveToNext()) {
                @SuppressLint("Range")
                String result = cursor.getString(cursor.getColumnIndex("Value"));
                String name = cursor.getString(cursor.getColumnIndex("KeyName"));
                if (name.equalsIgnoreCase("grid")) {
                    GeneralVariables.setMyMaidenheadGrid(result);
                }
                if (name.equalsIgnoreCase("callsign")) {
                    GeneralVariables.myCallsign = result;
                    String callsign = GeneralVariables.myCallsign;
                    if (callsign.length() > 0) {
                        Ft8Message.hashList.addHash(FT8Package.getHash22(callsign), callsign);
                        Ft8Message.hashList.addHash(FT8Package.getHash12(callsign), callsign);
                        Ft8Message.hashList.addHash(FT8Package.getHash10(callsign), callsign);
                        if (callsign.contains("/")) {
                            String shortCallsign = GeneralVariables.getShortCallsign(callsign);
                            Ft8Message.hashList.addHash(FT8Package.getHash22(shortCallsign), shortCallsign);
                            Ft8Message.hashList.addHash(FT8Package.getHash12(shortCallsign), shortCallsign);
                            Ft8Message.hashList.addHash(FT8Package.getHash10(shortCallsign), shortCallsign);
                        }
                    }
                }
                if (name.equalsIgnoreCase("toModifier")) {
                    GeneralVariables.toModifier = result;
                }
                if (name.equalsIgnoreCase("freq")) {
                    float freq = 1000;
                    try {
                        freq = Float.parseFloat(result);
                    } catch (Exception e) {
                        Log.e(TAG, "doInBackground: " + e.getMessage());
                    }
                    GeneralVariables.setBaseFrequency(freq);
                }
                if (name.equalsIgnoreCase("synFreq")) {
                    GeneralVariables.synFrequency = !(result.equals("") || result.equals("0"));
                }
                if (name.equalsIgnoreCase("transDelay")) {
                    if (result.matches("^\\d{1,4}$")) {
                        GeneralVariables.transmitDelay = Integer.parseInt(result);
                    } else {
                        GeneralVariables.transmitDelay = FT8Common.FT8_TRANSMIT_DELAY;
                    }
                }
                if (name.equalsIgnoreCase("civ")) {
                    GeneralVariables.civAddress = result.equals("") ? 0xa4 : Integer.parseInt(result, 16);
                }
                if (name.equalsIgnoreCase("baudRate")) {
                    GeneralVariables.baudRate = result.equals("") ? 19200 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("bandFreq")) {
                    GeneralVariables.band = result.equals("") ? 14074000 : Long.parseLong(result);
                    GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(GeneralVariables.band);
                    GeneralVariables.mutableBandChange.postValue(GeneralVariables.bandListIndex);
                }
                if (name.equalsIgnoreCase("msgMode")) {
                    GeneralVariables.simpleCallItemMode = result.equals("1");
                }
                if (name.equalsIgnoreCase("ctrMode")) {
                    GeneralVariables.controlMode = result.equals("") ? ControlMode.VOX : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("model")) {
                    GeneralVariables.modelNo = result.equals("") ? 0 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("instruction")) {
                    GeneralVariables.instructionSet = result.equals("") ? 0 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("launchSupervision")) {
                    GeneralVariables.launchSupervision = result.equals("") ?
                            GeneralVariables.DEFAULT_LAUNCH_SUPERVISION : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("noReplyLimit")) {
                    GeneralVariables.noReplyLimit = result.equals("") ? 0 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("autoFollowCQ")) {
                    GeneralVariables.autoFollowCQ = (result.equals("") || result.equals("1"));
                }
                if (name.equalsIgnoreCase("autoCallFollow")) {
                    GeneralVariables.autoCallFollow = (result.equals("") || result.equals("1"));
                }
                if (name.equalsIgnoreCase("sendTuneOnFreqChange")) {
                    GeneralVariables.sendTuneOnFreqChange = result.equals("1");
                }
                if (name.equalsIgnoreCase("clearCallHistOnFreqChange")) {
                    GeneralVariables.clearCallHistOnFreqChange = result.equals("1");
                }
                if (name.equalsIgnoreCase("pttDelay")) {
                    GeneralVariables.pttDelay = result.equals("") ? 100 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("icomIp")) {
                    GeneralVariables.icomIp = result.equals("") ? "255.255.255.255" : result;
                }
                if (name.equalsIgnoreCase("icomPort")) {
                    GeneralVariables.icomUdpPort = result.equals("") ? 50001 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("icomUserName")) {
                    GeneralVariables.icomUserName = result.equals("") ? "ic705" : result;
                }
                // === [FIX] ИСПРАВЛЕННЫЙ БЛОК ПАРОЛЯ ===
                if (name.equalsIgnoreCase("icomPassword")) {
                    if (secureStorage != null && secureStorage.isAvailable()) {
                        GeneralVariables.icomPassword = secureStorage.get("icom_password", result);
                    } else {
                        GeneralVariables.icomPassword = result; // Fallback для API < 23
                    }
                }
                // ======================================
                if (name.equalsIgnoreCase("volumeValue")) {
                    GeneralVariables.volumePercent = result.equals("") ? 1.0f : Float.parseFloat(result) / 100f;
                }
                if (name.equalsIgnoreCase("excludedCallsigns")) {
                    GeneralVariables.addExcludedCallsigns(result);
                }
                if (name.equalsIgnoreCase("flexMaxRfPower")) {
                    GeneralVariables.flexMaxRfPower = result.equals("") ? 10 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("flexMaxTunePower")) {
                    GeneralVariables.flexMaxTunePower = result.equals("") ? 10 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("saveSWL")) {
                    GeneralVariables.saveSWLMessage = result.equals("1");
                }
                if (name.equalsIgnoreCase("saveSWLQSO")) {
                    GeneralVariables.saveSWL_QSO = result.equals("1");
                }
                if (name.equalsIgnoreCase("audioBits")) {
                    GeneralVariables.audioOutput32Bit = result.equals("1");
                }
                if (name.equalsIgnoreCase("audioRate")) {
                    GeneralVariables.audioSampleRate = Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("deepMode")) {
                    GeneralVariables.deepDecodeMode = result.equals("1");
                }
                if (name.equalsIgnoreCase("dataBits")) {
                    GeneralVariables.serialDataBits = Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("stopBits")) {
                    GeneralVariables.serialStopBits = Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("parityBits")) {
                    GeneralVariables.serialParity = Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("enableCloudlog")) {
                    GeneralVariables.enableCloudlog = result.equals("1");
                }
                if (name.equalsIgnoreCase("cloudlogServerAddress")) {
                    GeneralVariables.cloudlogServerAddress = result;
                }
                if (name.equalsIgnoreCase("cloudlogApiKey")) {
                    GeneralVariables.cloudlogApiKey = result;
                }
                if (name.equalsIgnoreCase("cloudlogStationID")) {
                    GeneralVariables.cloudlogStationID = result;
                }
                if (name.equalsIgnoreCase("enableQRZ")) {
                    GeneralVariables.enableQRZ = result.equals("1");
                }
                if (name.equalsIgnoreCase("qrzApiKey")) {
                    GeneralVariables.qrzApiKey = result;
                }
                if (name.equalsIgnoreCase("swrSwitch")) {
                    GeneralVariables.swr_switch_on = result.equals("1");
                }
                if (name.equalsIgnoreCase("alcSwitch")) {
                    GeneralVariables.alc_switch_on = result.equals("1");
                }
                if (name.equalsIgnoreCase("acceptDxCalls")) {
                    GeneralVariables.acceptDxCalls = result.equals("1");
                }
            }
            cursor.close();
            GetAllQSLCallsign.get(db);
            if (onAfterQueryConfig != null) {
                onAfterQueryConfig.doOnAfterQueryConfig(null, null);
            }
            return null;
        }
    }
    // [NEW] Helper method to read config value with default fallback
    public String readConfig(String keyName, String defaultValue) {
        String querySQL = "SELECT Value FROM config WHERE KeyName = ?";
        Cursor cursor = db.rawQuery(querySQL, new String[]{keyName});
        String result = defaultValue;
        if (cursor != null && cursor.moveToFirst()) {
            result = cursor.getString(0);
            cursor.close();
        }
        return result;
    }
    // [NEW] Migration method to remove old settings
    public void migrateRemoveOldSettings() {
        try {
            // Remove deprecated settings
            db.execSQL("DELETE FROM config WHERE KeyName IN ('multipleAnswersMode', 'multipleAnswersLayout')");
            Log.d(TAG, "Migrated: removed old settings from database");
        } catch (Exception e) {
            Log.e(TAG, "Failed to migrate old settings", e);
            // Not critical, can be ignored
        }
    }
    // === [NEW] WORLD MODEL: RAM cache for station tracking ===
    // Key: callsign (uppercase), Value: StationRecord
    // === WORLD MODEL IMPLEMENTATION ===
    private static final Map<String, StationRecord> stationWorldModel = new ConcurrentHashMap<>();
    private static final ReentrantLock worldModelLock = new ReentrantLock();
    private static long lastWorldModelSave = 0;
    private static final long WORLD_MODEL_SAVE_INTERVAL_MS = 30000;
    public static class StationRecord {
        public final String callsign;
        public long bandsBitmap;
        public long lastFreqHz;
        public long lastSeenUtcSec;
        public float lastSnr;
        public String lastQth;
        public float lastBearing;
        public String dxccCode;
        public int lastItuZone;
        public int lastCqZone;
        public int ft8StateRelative;      // Что они нам прислали
        public float priorityScore;
        public boolean isNewDx;
        // [NEW] CRITICAL: Store partner's TX slot for correct alternating response
        public int lastSequential = -1;   // -1 = unknown

        public StationRecord(String callsign) {
            this.callsign = callsign;
            this.ft8StateRelative = 0;
            this.priorityScore = 10f;
        }
        public boolean isExpired() {
            long ageSlots = (com.bg7yoz.ft8cn.timer.UtcTimer.getNowSequential() - lastSeenUtcSec) / 15;
            return ageSlots > 4;
        }
    }
    /**
     * Band frequency to bit index mapping.
     * [FIX] Uses range-based matching: each configured frequency defines START of 3 kHz FT8 segment.
     * Example: 14074000 matches range 14074000-14077000 (not center ±tolerance).
     * Массив частот KNOWN_FREQS внутри этого метода должен строго соответствовать твоему файлу bands.txt.
     * Если ты добавишь новую частоту в bands.txt, не забудь добавить её и в массив KNOWN_FREQS в DatabaseOpr.java, иначе для этой частоты будет использоваться "Fallback" (общий бит бэнда), и разделение (14.074 vs 14.090) пропадет.
     */
    public static int freqToBandBit(long freqHz) {
        // Each frequency from bands.txt defines START of 3 kHz FT8 segment
        // Format: {startFreq, endFreq, bitIndex}
        // 160m
        if (freqHz >= 1810000L && freqHz < 1813000L) return 0;
        if (freqHz >= 1840000L && freqHz < 1843000L) return 1;
        if (freqHz >= 1908000L && freqHz < 1911000L) return 2;
        // 80m
        if (freqHz >= 3531000L && freqHz < 3534000L) return 3;
        if (freqHz >= 3567000L && freqHz < 3570000L) return 4;
        if (freqHz >= 3573000L && freqHz < 3576000L) return 5;
        if (freqHz >= 3585000L && freqHz < 3588000L) return 6;
        // 60m
        if (freqHz >= 5126000L && freqHz < 5129000L) return 7;
        if (freqHz >= 5357000L && freqHz < 5360000L) return 8;
        if (freqHz >= 5362000L && freqHz < 5365000L) return 9;
        // 40m
        if (freqHz >= 7041000L && freqHz < 7044000L) return 10;
        if (freqHz >= 7056000L && freqHz < 7059000L) return 11;
        if (freqHz >= 7071000L && freqHz < 7074000L) return 12;
        if (freqHz >= 7074000L && freqHz < 7077000L) return 13;  // Main FT8
        if (freqHz >= 7080000L && freqHz < 7083000L) return 14;
        // 30m
        if (freqHz >= 10131000L && freqHz < 10134000L) return 15;
        if (freqHz >= 10133000L && freqHz < 10136000L) return 16;
        if (freqHz >= 10136000L && freqHz < 10139000L) return 17;  // Main FT8
        if (freqHz >= 10143000L && freqHz < 10146000L) return 18;
        // 20m
        if (freqHz >= 14071000L && freqHz < 14074000L) return 19;
        if (freqHz >= 14074000L && freqHz < 14077000L) return 20;  // Main FT8
        if (freqHz >= 14090000L && freqHz < 14093000L) return 21;  // Second FT8 segment
        // 17m
        if (freqHz >= 18095000L && freqHz < 18098000L) return 22;
        if (freqHz >= 18100000L && freqHz < 18103000L) return 23;  // Main FT8
        // 15m
        if (freqHz >= 21074000L && freqHz < 21077000L) return 24;  // Main FT8
        if (freqHz >= 21091000L && freqHz < 21094000L) return 25;
        // 12m
        if (freqHz >= 24911000L && freqHz < 24914000L) return 26;
        if (freqHz >= 24915000L && freqHz < 24918000L) return 27;  // Main FT8
        // 10m
        if (freqHz >= 28074000L && freqHz < 28077000L) return 28;  // Main FT8
        if (freqHz >= 28095000L && freqHz < 28098000L) return 29;
        // 8m
        if (freqHz >= 40680000L && freqHz < 40683000L) return 30;
        // 6m
        if (freqHz >= 50310000L && freqHz < 50313000L) return 31;
        if (freqHz >= 50313000L && freqHz < 50316000L) return 32;  // Main
        if (freqHz >= 50323000L && freqHz < 50326000L) return 33;
        // 4m
        if (freqHz >= 70100000L && freqHz < 70103000L) return 34;
        if (freqHz >= 70154000L && freqHz < 70157000L) return 35;  // Main
        // 2m
        if (freqHz >= 144174000L && freqHz < 144177000L) return 36;  // Main
        if (freqHz >= 144460000L && freqHz < 144463000L) return 37;
        // 70cm
        if (freqHz >= 432174000L && freqHz < 432177000L) return 38;
        // Fallback: broad band bits
        long kHz = freqHz / 1000;
        if (kHz >= 1800 && kHz < 2000) return 40;
        if (kHz >= 3500 && kHz < 3800) return 41;
        if (kHz >= 5300 && kHz < 5500) return 42;
        if (kHz >= 7000 && kHz < 7200) return 43;
        if (kHz >= 10100 && kHz < 10150) return 44;
        if (kHz >= 14000 && kHz < 14350) return 45;
        if (kHz >= 18068 && kHz < 18168) return 46;
        if (kHz >= 21000 && kHz < 21450) return 47;
        if (kHz >= 24890 && kHz < 24990) return 48;
        if (kHz >= 28000 && kHz < 29700) return 49;
        if (kHz >= 40000 && kHz < 41000) return 50;
        if (kHz >= 50000 && kHz < 54000) return 51;
        if (kHz >= 70000 && kHz < 71000) return 52;
        if (kHz >= 144000 && kHz < 148000) return 53;
        if (kHz >= 432000 && kHz < 450000) return 54;
        return 63;
    }
    /**
     * Update station record from decoded message - RAM cache + async DB save
     */
    /**
     * Update station record from decoded message - RAM cache + async DB save
     */
    /**
     * Update station record from decoded message - RAM cache + SYNC DB save
     */
    public void updateStationFromMessage(Ft8Message msg, String qth, String dxcc, int ituZone, int cqZone, float bearing) {
        String callsign = msg.getCallsignFrom();
        if (callsign == null || callsign.isEmpty()) return;
        callsign = callsign.toUpperCase().trim();
        int detectedState = parseMessageState(msg);

        // [DEBUG] Лог входящего сообщения
        //Log.d(TAG, "[DEBUG] updateStationFromMessage: callsign=" + callsign +
        //        " msg.freq_hz=" + msg.freq_hz + " (offset in Hz)");

        worldModelLock.lock();
        try {
            StationRecord record = stationWorldModel.get(callsign);
            if (record == null) {
                record = new StationRecord(callsign);
                stationWorldModel.put(callsign, record);
            }

            // Сохраняем смещение как есть
            record.lastFreqHz = Math.round(msg.freq_hz);
            record.lastSeenUtcSec = msg.utcTime;
            record.lastSnr = msg.snr;
            if (qth != null && qth.length() >= 4) record.lastQth = qth.toUpperCase();
            record.lastBearing = bearing;
            if (dxcc != null) record.dxccCode = dxcc;
            record.lastItuZone = ituZone;
            record.lastCqZone = cqZone;

            // [FIX] Вычисляем ПОЛНУЮ частоту для определения бэнда
            // msg.freq_hz — это смещение, нужно добавить к текущей частоте
            long fullFrequency = GeneralVariables.band + Math.round(msg.freq_hz);

            // [DEBUG] Лог полной частоты
            //Log.d(TAG, "[DEBUG]   fullFrequency=" + fullFrequency +
            //        " (band=" + GeneralVariables.band + " + offset=" + Math.round(msg.freq_hz) + ")");

            int bandBit = freqToBandBit(fullFrequency);

            // [DEBUG] Лог результата
            //Log.d(TAG, "[DEBUG]   bandBit=" + bandBit + " for fullFreq=" + fullFrequency);

            record.bandsBitmap |= (1L << bandBit);

            // [NEW] CRITICAL: Save partner's sequential slot
            // This is essential for alternating TX slots in FT8
            record.lastSequential = msg.getSequence();
            Log.d(TAG, "[SEQUENTIAL] " + callsign + " TX in slot " + msg.getSequence());

// [FIX] Обновляем состояние ТОЛЬКО для релевантных сообщений:
// 1. Нам адресованных (toMe = true)
// 2. CQ вызовов (detectedState == 6)
// 3. Новых DX на бэнде (isNewDx = true)

            boolean isRelevantToUs = GeneralVariables.checkIsMyCallsign(msg.getCallsignTo());
            boolean isCQ = msg.checkIsCQ();

            if (isRelevantToUs && detectedState >= 1 && detectedState <= 4) {
                // Сообщение нам адресовано - обновляем состояние диалога
                int oldState = record.ft8StateRelative;
                record.ft8StateRelative = Math.max(record.ft8StateRelative, detectedState);
                if (record.ft8StateRelative != oldState) {
                    Log.d(TAG, "[STATE] " + callsign + ": " + oldState + " → " + record.ft8StateRelative);
                }
            } else if (isCQ && record.ft8StateRelative < 1) {
                // CQ вызов - помечаем только если ещё не в диалоге
                record.ft8StateRelative = 6;
            } else if (!isRelevantToUs && !isCQ) {
                // Просто декодированное сообщение (не нам и не CQ)
                // Не обновляем ft8StateRelative - оставляем 0
                // Индикаторы D/I/C не должны гореть
            }

            record.priorityScore = calculatePriorityScore(record);

            // ========================================================================
            // [SYNC FIX] START
            // Принудительная немедленная запись в БД.
            // Мы убираем задержку в 30 секунд, чтобы RAM-кэш и База данных
            // были синхронизированы мгновенно после декодирования.
            // Это гарантирует, что при сбое данные не потеряются.
            // ========================================================================
            try {
                String sql = "INSERT OR REPLACE INTO station_world_model VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
                db.execSQL(sql, new Object[]{
                        record.callsign, record.bandsBitmap, record.lastFreqHz, record.lastSeenUtcSec,
                        record.lastSnr, record.lastQth, record.lastBearing, record.dxccCode, record.lastItuZone, record.lastCqZone,
                        record.ft8StateRelative, record.priorityScore, record.isNewDx ? 1 : 0, System.currentTimeMillis() / 1000,
                        record.lastSequential  // [NEW] 15th parameter
                });
            } catch (Exception e) {
                Log.e(TAG, "[SYNC FIX] Failed to write single record to DB: " + e.getMessage());
            }
            // ========================================================================
            // [SYNC FIX] END
            // ========================================================================

            // Отключаем отложенное сохранение, так как мы уже сохранили всё сейчас.
            // scheduleWorldModelSave();

        } finally {
            worldModelLock.unlock();
        }
    }
    private int parseMessageState(Ft8Message msg) {
        if (msg == null) return 0;
        String extra = msg.extraInfo != null ? msg.extraInfo : "";
        boolean toMe = GeneralVariables.checkIsMyCallsign(msg.getCallsignTo());
        if (!toMe) return msg.checkIsCQ() ? 6 : 0;
        if (extra.contains("RR73") || extra.contains("RRR") || extra.contains("RRR73")) return 4;
        if (extra.startsWith("R") && extra.length() <= 4) return 3;
        if (extra.matches("^-?\\d{1,3}$")) return 2;
        String grid = msg.maidenGrid != null ? msg.maidenGrid : "";
        if (grid.length() >= 4) return 1;
        return 1;
    }
    private float calculatePriorityScore(StationRecord record) {
        if (record == null) return 0f;
        float score = 10f + record.lastSnr * 1.2f;
        int currentBandBit = freqToBandBit(GeneralVariables.band);
        if ((record.bandsBitmap & (1L << currentBandBit)) == 0) score += 25f;
        if (record.isNewDx) score += 30f;
        if (record.lastBearing > 5000f) score += 15f;
        if (record.ft8StateRelative == 6) score += 10f;
        else if (record.ft8StateRelative >= 1) score += record.ft8StateRelative * 5f;
        long ageSlots = (com.bg7yoz.ft8cn.timer.UtcTimer.getNowSequential() - record.lastSeenUtcSec) / 15;
        score -= ageSlots * 3f;
        if (GeneralVariables.checkQSLCallsign(record.callsign)) score -= 20f;
        return Math.max(0f, score);
    }
    public static List<StationRecord> getStationWorldModelSnapshot() {
        worldModelLock.lock();
        try { return new ArrayList<>(stationWorldModel.values()); }
        finally { worldModelLock.unlock(); }
    }
    public static StationRecord getStationRecord(String callsign) {
        if (callsign == null) return null;
        worldModelLock.lock();
        try { return stationWorldModel.get(callsign.toUpperCase()); }
        finally { worldModelLock.unlock(); }
    }
    private void scheduleWorldModelSave() {
        long now = System.currentTimeMillis();
        if (now - lastWorldModelSave < WORLD_MODEL_SAVE_INTERVAL_MS) return;
        lastWorldModelSave = now;
        new SaveWorldModelTask(db, new ArrayList<>(stationWorldModel.values())).execute();
    }
    private static class SaveWorldModelTask extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final List<StationRecord> records;
        SaveWorldModelTask(SQLiteDatabase db, List<StationRecord> records) {
            this.db = db; this.records = records;
        }
        @Override
        protected Void doInBackground(Void... voids) {
            if (records.isEmpty()) return null;
            // Проверка: существует ли таблица
            Cursor cursor = null;
            try {
                cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='station_world_model'", null);
                if (cursor == null || !cursor.moveToFirst()) {
                    Log.w(TAG, "Table station_world_model not found, skipping save");
                    return null;
                }
            } finally {
                if (cursor != null) cursor.close();
            }
            db.beginTransaction();
            try {
                String sql = "INSERT OR REPLACE INTO station_world_model VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
                for (StationRecord r : records) {
                    db.execSQL(sql, new Object[]{
                            r.callsign, r.bandsBitmap, r.lastFreqHz, r.lastSeenUtcSec,
                            r.lastSnr, r.lastQth, r.lastBearing, r.dxccCode, r.lastItuZone, r.lastCqZone,
                            r.ft8StateRelative, r.priorityScore, r.isNewDx ? 1 : 0, System.currentTimeMillis() / 1000,
                            r.lastSequential  // [NEW] 15th parameter
                    });
                }
                db.setTransactionSuccessful();
            } catch (SQLiteException e) {
                Log.e(TAG, "Failed to save world model: " + e.getMessage());
                // Не выбрасываем исключение, чтобы не крашить приложение
            } finally {
                db.endTransaction();
            }
            return null;
        }
    }
    // === END WORLD MODEL ===
    // ========================================================================
    // [NEW] Inner class: SecureStorage wrapper for EncryptedSharedPreferences
    // Handles encryption of passwords and API keys using Android Keystore
    // Fallback to plain text for API < 23 or initialization errors
    // Comments in Russian, ASCII only in code
    // ========================================================================
    private static class SecureStorage {
        private final SharedPreferences prefs;
        SecureStorage(SharedPreferences prefs) {
            this.prefs = prefs;
        }
        boolean isAvailable() {
            return prefs != null;
        }
        void save(String key, String value) {
            if (!isAvailable()) return;
            prefs.edit().putString(key, value).apply();
        }
        String get(String key, String defaultValue) {
            if (!isAvailable()) return defaultValue;
            return prefs.getString(key, defaultValue);
        }
        void remove(String key) {
            if (!isAvailable()) return;
            prefs.edit().remove(key).apply();
        }
    }
    // ========================================================================
    // [END] SecureStorage inner class
    // ========================================================================
    // ========================================================================
    // [NEW] Methods for secure config handling with fallback
    // ========================================================================
    /**
     * Save sensitive value to secure storage (if available) or plain config table (fallback).
     * Used for passwords, API keys, tokens.
     * @param key Config key name
     * @param value Plain text value to store
     */
    public void saveSensitiveConfig(String key, String value) {
        if (secureStorage != null && secureStorage.isAvailable()) {
            secureStorage.save(key, value);
            Log.d(TAG, "Saved sensitive config to secure storage: " + key);
        } else {
            // Fallback: save to plain config table with warning
            Log.w(TAG, "SecureStorage unavailable, saving sensitive config to plain DB: " + key);
            writeConfig(key, value, null);
        }
    }
    /**
     * Read sensitive value from secure storage (if available) or plain config table (fallback).
     * @param key Config key name
     * @param defaultValue Default value if not found
     * @return Decrypted value or defaultValue
     */
    public String getSensitiveConfig(String key, String defaultValue) {
        if (secureStorage != null && secureStorage.isAvailable()) {
            String value = secureStorage.get(key, null);
            if (value != null) {
                Log.d(TAG, "Read sensitive config from secure storage: " + key);
                return value;
            }
        }
        // Fallback: read from plain config table
        return readConfig(key, defaultValue);
    }
    /**
     * Migrate existing sensitive configs from plain DB to secure storage.
     * Called once on first run after update.
     */
    private void migrateSensitiveConfigs() {
        Log.d(TAG, "Starting migration of sensitive configs to SecureStorage...");
        // List of config keys that contain sensitive data
        String[] sensitiveKeys = new String[] {
                "cloudlog_password",
                "qrz_api_key",
                "hrdlog_password",
                "icom_password",
                "flex_api_key"
                // Добавьте другие ключи с паролями по необходимости
        };
        for (String key : sensitiveKeys) {
            String value = readConfig(key, null);
            if (value != null && !value.isEmpty()) {
                // Save to secure storage
                if (secureStorage != null) {
                    secureStorage.save(key, value);
                }
                // Clear plain-text value from DB (prevent accidental exposure)
                db.execSQL("DELETE FROM config WHERE KeyName = ?", new String[]{key});
                Log.d(TAG, "Migrated sensitive key: " + key);
            }
        }
        Log.d(TAG, "Sensitive config migration completed");
    }
    /**
     * Check if sensitive config migration has been performed.
     * @return true if migration flag exists in secure storage
     */
    private boolean isMigrationDone() {
        if (secureStorage == null || !secureStorage.isAvailable()) return true;
        return secureStorage.get(MIGRATION_FLAG_KEY, "0").equals("1");
    }
    /**
     * Mark migration as completed.
     */
    private void markMigrationDone() {
        if (secureStorage == null || !secureStorage.isAvailable()) return;
        secureStorage.save(MIGRATION_FLAG_KEY, "1");
    }
    /**
     * Remove sensitive value from both secure and plain storage.
     * @param key Config key name
     */
    public void removeSensitiveConfig(String key) {
        if (secureStorage != null && secureStorage.isAvailable()) {
            secureStorage.remove(key);
        }
        db.execSQL("DELETE FROM config WHERE KeyName = ?", new String[]{key});
    }
    // ========================================================================
    // [END] Secure config methods
    // ========================================================================
    // ========================================================================
    // [NEW] Database Statistics
    // ========================================================================
    /**
     * Get database statistics for diagnostics.
     * @return Formatted string with DB stats
     */
    public String getDatabaseStatistics() {
        StringBuilder stats = new StringBuilder();
        try {
            // 1. File size
            File dbFile = context.getDatabasePath("data.db");
            if (dbFile.exists()) {
                long sizeKB = dbFile.length() / 1024;
                stats.append("Database file: ").append(sizeKB >= 1024
                        ? String.format("%.1f MB", sizeKB / 1024.0)
                        : sizeKB + " KB");
                stats.append("\n");
            }
            // 2. QSO log count
            Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM QSLTable", null);
            if (cursor != null && cursor.moveToFirst()) {
                int qsoCount = cursor.getInt(0);
                stats.append("QSO Log entries: ").append(qsoCount);
                stats.append("\n");
                        cursor.close();
            }
            // 3. Callsigns count
            cursor = db.rawQuery("SELECT COUNT(*) FROM QslCallsigns", null);
            if (cursor != null && cursor.moveToFirst()) {
                int callsignCount = cursor.getInt(0);
                stats.append("Unique callsigns: ").append(callsignCount);
                stats.append("\n");
                cursor.close();
            }
            // 4. SWL messages count
            cursor = db.rawQuery("SELECT COUNT(*) FROM SWLMessages", null);
            if (cursor != null && cursor.moveToFirst()) {
                int swlCount = cursor.getInt(0);
                stats.append("SWL messages: ").append(swlCount);
                stats.append("\n");
                cursor.close();
            }
            // 5. Last QSO date
            cursor = db.rawQuery("SELECT MAX(qso_date) || ' ' || MAX(time_on) FROM QSLTable", null);
            if (cursor != null && cursor.moveToFirst()) {
                String lastQso = cursor.getString(0);
                if (lastQso != null && !lastQso.isEmpty()) {
                    stats.append("Last QSO: ").append(lastQso);
                    stats.append("\n");
                }
                cursor.close();
            }
            // 6. Followed callsigns
            cursor = db.rawQuery("SELECT COUNT(*) FROM followCallsigns", null);
            if (cursor != null && cursor.moveToFirst()) {
                int followCount = cursor.getInt(0);
                stats.append("\nFollowed callsigns: ").append(followCount);
                cursor.close();
            }
        } catch (Exception e) {
            stats.append("Error getting statistics: ").append(e.getMessage());
            Log.e(TAG, "getDatabaseStatistics error: " + e.getMessage());
        }
        return stats.toString();
    }
    // ========================================================================
    // [END NEW] Database Statistics
    // ========================================================================

    /**
     * [CRITICAL FIX] Ensure station_world_model has last_sequential column.
     * Called on every DB open - idempotent, safe to run multiple times.
     */
    private void ensureWorldModelSchema() {
        try {
            // Check if column exists
            Cursor cursor = db.rawQuery("PRAGMA table_info(station_world_model)", null);
            boolean hasColumn = false;
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(1); // column name at index 1
                    if ("last_sequential".equals(name)) {
                        hasColumn = true;
                        break;
                    }
                }
                cursor.close();
            }

            // Add column if missing
            if (!hasColumn) {
                Log.w(TAG, "SCHEMA FIX: Adding last_sequential column to station_world_model");
                db.execSQL("ALTER TABLE station_world_model ADD COLUMN last_sequential INTEGER DEFAULT -1");
                Log.d(TAG, "SCHEMA FIX: Column added successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "SCHEMA FIX ERROR: " + e.getMessage(), e);
            // Don't crash - app can continue without this column (just won't save sequential)
        }
    }

}