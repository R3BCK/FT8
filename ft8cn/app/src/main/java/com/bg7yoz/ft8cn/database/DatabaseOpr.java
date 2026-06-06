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

// [НОВОЕ] Импорты для единого классификатора FT8-сообщений
import com.bg7yoz.ft8cn.protocol.FT8MessageClassifier;
import com.bg7yoz.ft8cn.protocol.ProtocolStep;

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
    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
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
     */
    private void alterTable(SQLiteDatabase db, String tableName, String fieldName, String sql) {
        Cursor cursor = db.rawQuery("select * from sqlite_master where name=? and sql like ?"
                , new String[]{tableName, "%" + fieldName + "%"});
        if (!cursor.moveToNext()) {
            db.execSQL(String.format("ALTER TABLE %s ADD COLUMN %s", tableName, sql));
        }
        cursor.close();
    }

    private boolean checkTableExists(SQLiteDatabase db, String tableName) {
        Cursor cursor = db.rawQuery("select * from sqlite_master where type = 'table' and name = ?"
                , new String[]{tableName});
        if (cursor.moveToNext()) {
            cursor.close();
            return true;
        }
        return false;
    }

    private boolean checkIndexExists(SQLiteDatabase db, String indexName) {
        Cursor cursor = db.rawQuery("select * from sqlite_master where type = 'index' and name = ?"
                , new String[]{indexName});
        if (cursor.moveToNext()) {
            cursor.close();
            return true;
        }
        return false;
    }

    private void deleteDxccPrefixEqual(SQLiteDatabase db) {
        db.execSQL("DELETE from dxcc_prefix where prefix LIKE '=%'");
    }

    private void createQSLTable(SQLiteDatabase sqLiteDatabase) {
        if (checkTableExists(sqLiteDatabase, "QSLTable")) {
            alterTable(sqLiteDatabase, "QSLTable", "isQSL", "isQSL INTEGER DEFAULT 0");
            alterTable(sqLiteDatabase, "QSLTable", "isLotW_import", "isLotW_import INTEGER DEFAULT 0");
            alterTable(sqLiteDatabase, "QSLTable", "isLotW_QSL", "isLotW_QSL INTEGER DEFAULT 0");
        } else {
            sqLiteDatabase.execSQL("CREATE TABLE QSLTable ( " +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "isQSL INTEGER DEFAULT 0, " +
                    "isLotW_import INTEGER DEFAULT 0, " +
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
            alterTable(sqLiteDatabase, "QslCallsigns", "isQSL", "isQSL INTEGER DEFAULT 0");
            alterTable(sqLiteDatabase, "QslCallsigns", "isLotW_import", "isLotW_import INTEGER DEFAULT 0");
            alterTable(sqLiteDatabase, "QslCallsigns", "isLotW_QSL", "isLotW_QSL INTEGER DEFAULT 0");
            alterTable(sqLiteDatabase, "QslCallsigns", "startTime", "startTime TEXT DEFAULT '0'");
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
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ArrayList<DxccObject> dxccObjects = loadDxccDataFromFile();
                    for (DxccObject obj : dxccObjects) {
                        obj.insertToDb(sqLiteDatabase);
                    }
                    populateDxccPrefixCache();
                }
            }).start();
        }
    }

    private void populateDxccPrefixCache() {
        cacheLock.lock();
        try {
            if (dxccCacheLoaded) return;
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

    public String getCountryByCallsign(String callsign) {
        if (callsign == null || callsign.isEmpty()) return "";
        String prefix = extractPrefix(callsign).toUpperCase();
        if (prefix.isEmpty()) return "";
        String country = dxccPrefixCache.get(prefix);
        if (country != null) {
            return country;
        }
        if (!dxccCacheLoaded) {
            populateDxccPrefixCache();
            country = dxccPrefixCache.get(prefix);
            if (country != null) return country;
        }
        Cursor cursor = db.rawQuery("SELECT name FROM dxcc_prefix dp " +
                        "INNER JOIN dxccList dl ON dp.dxcc = dl.dxcc WHERE dp.prefix = ?",
                new String[]{prefix});
        if (cursor != null && cursor.moveToFirst()) {
            country = cursor.getString(0);
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

    private String extractPrefix(String callsign) {
        if (callsign == null || callsign.isEmpty()) return "";
        String cleaned = callsign.toUpperCase();
        int endPos = cleaned.length();
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c == '/' || c == 'P' || c == 'M' || c == 'A' || c == 'Q') {
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
        String prefix = cleaned.substring(0, Math.min(endPos, 4));
        return prefix;
    }

    public void clearDxccPrefixCache() {
        cacheLock.lock();
        try {
            dxccPrefixCache.clear();
            dxccCacheLoaded = false;
        } finally {
            cacheLock.unlock();
        }
    }

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

    private void createCallsignQTHTables(SQLiteDatabase sqLiteDatabase) {
        if (!checkTableExists(sqLiteDatabase, "CallsignQTH")) {
            sqLiteDatabase.execSQL("CREATE TABLE CallsignQTH(callsign text, grid text" +
                    ",updateTime Int ,PRIMARY KEY(callsign))");
        }
    }

    private void createSWLTables(SQLiteDatabase sqLiteDatabase) {
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
            alterTable(sqLiteDatabase, "SWLQSOTable", "operator", "operator TEXT");
        }
    }

    private void createIndex(SQLiteDatabase sqLiteDatabase) {
        if (!checkIndexExists(sqLiteDatabase, "QslCallsigns_callsign_IDX")) {
            sqLiteDatabase.execSQL("CREATE INDEX QslCallsigns_callsign_IDX ON QslCallsigns (callsign,startTime,finishTime,mode)");
        }
        if (!checkIndexExists(sqLiteDatabase, "QSLTable_call_IDX")) {
            sqLiteDatabase.execSQL("CREATE INDEX QSLTable_call_IDX ON QSLTable ([call],qso_date,time_on,mode)");
        }
        if (!checkIndexExists(sqLiteDatabase, "dxcc_prefix_prefix_IDX")) {
            sqLiteDatabase.execSQL("CREATE INDEX dxcc_prefix_prefix_IDX ON dxcc_prefix (prefix)");
        }
        if (!checkIndexExists(sqLiteDatabase, "SWLMessages_BAND_IDX")) {
            sqLiteDatabase.execSQL("CREATE INDEX SWLMessages_BAND_IDX ON SWLMessages (BAND)");
        }
        if (!checkIndexExists(sqLiteDatabase, "QSLTable_band_call_IDX")) {
            sqLiteDatabase.execSQL("CREATE INDEX QSLTable_band_call_IDX ON QSLTable (band,[call])");
        }
    }

    public void loadItuDataFromFile(SQLiteDatabase db) {
        AssetManager assetManager = context.getAssets();
        InputStream inputStream;
        db.execSQL("delete from ituList");
        String insertSQL = "INSERT INTO ituList (itu,grid) VALUES(?,?)";
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
        String insertSQL = "INSERT INTO cqzoneList (cqzone,grid) VALUES(?,?)";
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
            }
            inputStream.close();
        } catch (IOException | JSONException e) {
            e.printStackTrace();
            Log.e(TAG, "loadDataFromFile: " + e.getMessage());
        }
        return dxccObjects;
    }

    public void addCallsignQTH(String callsign, String grid) {
        if (grid.trim().length() < 4) return;
        new AddCallsignQTH(db).execute(callsign, grid);
    }

    public void getConfigByKey(String KeyName, OnAfterQueryConfig onAfterQueryConfig) {
        new QueryConfig(db, KeyName, onAfterQueryConfig).execute();
    }

    public void getCallSign(String callsign, String fieldName, String tableName, OnGetCallsign getCallsign) {
        new QueryCallsign(db, tableName, fieldName, callsign, getCallsign).execute();
    }

    public void writeConfig(String KeyName, String Value, OnAfterWriteConfig onAfterWriteConfig) {
        Log.d(TAG, "writeConfig: Value:" + Value);
        new WriteConfig(db, KeyName, Value, onAfterWriteConfig).execute();
    }

    public void writeMessage(ArrayList<Ft8Message> messages) {
        new WriteMessages(db, messages).execute();
    }

    public void getFollowCallsigns(OnAfterQueryFollowCallsigns onAffterQueryFollowCallsigns) {
        new GetFollowCallSigns(db, onAffterQueryFollowCallsigns).execute();
    }

    public void getMessageLogTotal(OnAfterQueryFollowCallsigns onAfterQueryFollowCallsigns) {
        new GetMessageLogTotal(db, onAfterQueryFollowCallsigns).execute();
    }

    public void getSWLQsoLogTotal(OnAfterQueryFollowCallsigns onAfterQueryFollowCallsigns) {
        new GetSWLQsoTotal(db, onAfterQueryFollowCallsigns).execute();
    }

    public void addFollowCallsign(String callsign) {
        new AddFollowCallSign(db, callsign).execute();
    }

    public void clearFollowCallsigns() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                db.execSQL("delete from followCallsigns ");
            }
        }).start();
    }

    public void clearLogCacheData() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                db.execSQL("delete from SWLMessages ");
            }
        }).start();
    }

    public void clearSWLQsoData() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                db.execSQL("delete from SWLQSOTable ");
            }
        }).start();
    }

    public void addQSL_Callsign(QSLRecord qslRecord) {
        new AddQSL_Info(this, qslRecord).execute();
    }

    public void addSWL_QSO(QSLRecord qslRecord) {
        new Add_SWL_QSO_Info(this, qslRecord).execute();
    }

    public void deleteFollowCallsign(String callsign) {
        new DeleteFollowCallsign(db, callsign).execute();
    }

    public void getAllConfigParameter(OnAfterQueryConfig onAfterQueryConfig) {
        new GetAllConfigParameter(db, secureStorage, onAfterQueryConfig).execute();
    }

    public void getAllQSLCallsigns() {
        new LoadAllQSLCallsigns(db).execute();
    }

    public void getQSLCallsignsByCallsign(boolean showAll,int offset,String callsign, int filter, OnQueryQSLCallsign onQueryQSLCallsign) {
        new GetQLSCallsignByCallsign(showAll,offset,db, callsign, filter, onQueryQSLCallsign).execute();
    }

    public void getQsoGridQuery(OnGetQsoGrids onGetQsoGrids) {
        new GetQsoGrids(db, onGetQsoGrids).execute();
    }

    public void getQSLRecordByCallsign(boolean showAll,int offset,String callsign, int filter, OnQueryQSLRecordCallsign onQueryQSLRecordCallsign) {
        new GetQSLByCallsign(showAll,offset,db, callsign, filter, onQueryQSLRecordCallsign).execute();
    }

    public void deleteQSLCallsign(int id) {
        new DeleteQSLCallsignByID(db, id).execute();
    }

    public void deleteQSLByID(int id) {
        new DeleteQSLByID(db, id).execute();
    }

    public void setQSLTableIsQSL(boolean isQSL, int id) {
        new SetQSLTableIsQSL(db, id, isQSL).execute();
    }

    public void setQSLCallsignIsQSL(boolean isQSL, int id) {
        new SetQSLCallsignIsQSL(db, id, isQSL).execute();
    }

    public void getCallsignQTH(String callsign) {
        new GetCallsignQTH(db).execute(callsign);
    }

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
            logStr.append(String.format("<comment:%d>%s <eor>"
                    , comment.length()
                    , comment));
        }
        cursor.close();
        return logStr.toString();
    }

    @SuppressLint("Range")
    public void getQslDxccToMap() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String querySQL;
                Cursor cursor;
                Log.d(TAG, "run: Importing divisions...");
                querySQL = "SELECT DISTINCT dl.pp FROM   dxcc_grid dg " +
                        "inner join  QSLTable q " +
                        "on  dg.grid =UPPER(SUBSTR(q.gridsquare,1,4))  LEFT JOIN dxccList dl on dg.dxcc =dl.dxcc";
                cursor = db.rawQuery(querySQL, null);
                while (cursor.moveToNext()) {
                    GeneralVariables.addDxcc(cursor.getString(cursor.getColumnIndex("pp")));
                }
                cursor.close();
                querySQL = "SELECT DISTINCT  cl.cqzone  as cq FROM   cqzoneList cl " +
                        "inner join  QSLTable q " +
                        "on  cl.grid =UPPER(SUBSTR(q.gridsquare,1,4)) ";
                cursor = db.rawQuery(querySQL, null);
                while (cursor.moveToNext()) {
                    GeneralVariables.addCqZone(cursor.getInt(cursor.getColumnIndex("cq")));
                }
                cursor.close();
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

    @SuppressLint("Range")
    public boolean checkQSLCallsign(QSLRecord record) {
        QSLRecord newRecord = record;
        newRecord.id = -1;
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
        return newRecord.id != -1;
    }

    @SuppressLint("Range")
    public boolean checkIsQSL(QSLRecord record) {
        QSLRecord newRecord = record;
        newRecord.id = -1;
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
        return newRecord.id != -1;
    }

    @SuppressLint("Range")
    public boolean doInsertQSLData(QSLRecord record,AfterInsertQSLData afterInsertQSLData) {
        if (record.getToCallsign() == null) {
            if (afterInsertQSLData!=null){
                afterInsertQSLData.doAfterInsert(true,true);
            }
            return false;
        }
        String querySQL;
        if (!checkQSLCallsign(record)) {
            querySQL = "INSERT INTO  QslCallsigns (callsign" +
                    ",isQSL,isLotW_import,isLotW_QSL" +
                    ",startTime,finishTime,mode,grid,band,band_i)" +
                    "values(?,?,?,?,?,?,?,?,?,?)";
            db.execSQL(querySQL, new Object[]{record.getToCallsign()
                    , record.isQSL ? 1 : 0
                    , record.isLotW_import ? 1 : 0
                    , record.isLotW_QSL ? 1 : 0
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
        if (!checkIsQSL(record)) {
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
                    , record.getBandLength()
                    , BaseRigOperation.getFrequencyFloat(record.getBandFreq())
                    , record.getMyCallsign()
                    , record.getMyMaidenGrid()
                    , record.getComment()});
            if (afterInsertQSLData!=null){
                afterInsertQSLData.doAfterInsert(false,true);
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
                afterInsertQSLData.doAfterInsert(false,false);
            }
        }
        return true;
    }

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
            db.beginTransaction();
            try {
                for (Ft8Message message : messages) {
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
            querySQL = "DELETE FROM  SWLQSOTable where ([call]=?) and (station_callsign=?) and (qso_date=?) and(time_on=?) and (freq=?)";
            databaseOpr.db.execSQL(querySQL, new String[]{
                    qslRecord.getToCallsign()
                    , qslRecord.getMyCallsign()
                    , qslRecord.getQso_date()
                    , qslRecord.getTime_on()
                    , BaseRigOperation.getFrequencyFloat(qslRecord.getBandFreq())
            });
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
                    , qslRecord.getBandLength()
                    , BaseRigOperation.getFrequencyFloat(qslRecord.getBandFreq())
                    , qslRecord.getMyCallsign()
                    , qslRecord.getMyMaidenGrid()
                    , GeneralVariables.myCallsign
                    , qslRecord.getComment()});
            return null;
        }
    }

    static class AddQSL_Info extends AsyncTask<Void, Void, Void> {
        private final DatabaseOpr databaseOpr;
        private QSLRecord qslRecord;
        public AddQSL_Info(DatabaseOpr opr, QSLRecord qslRecord) {
            this.databaseOpr = opr;
            this.qslRecord = qslRecord;
        }
        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            databaseOpr.doInsertQSLData(qslRecord,null);
            return null;
        }
    }

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
                long s = cursor.getLong(cursor.getColumnIndex("BAND"));
                int total = cursor.getInt(cursor.getColumnIndex("c"));
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
            callsigns.add("---------------------------------------");
            int sum = 0;
            while (cursor.moveToNext()) {
                String date = cursor.getString(cursor.getColumnIndex("t"));
                int total = cursor.getInt(cursor.getColumnIndex("c"));
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
                String s = cursor.getString(cursor.getColumnIndex("callsign"));
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
                record.setBand(cursor.getString(cursor.getColumnIndex("band")));
                record.setFreq(cursor.getString(cursor.getColumnIndex("freq")));
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

    @SuppressLint("DefaultLocale")
    static class GetAllQSLCallsign {
        public static void get(SQLiteDatabase db) {
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

    static class LoadAllQSLCallsigns extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        public LoadAllQSLCallsigns(SQLiteDatabase db) {
            this.db = db;
        }
        @Override
        protected Void doInBackground(Void... voids) {
            GetAllQSLCallsign.get(db);
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
                if (name.equalsIgnoreCase("icomPassword")) {
                    if (secureStorage != null && secureStorage.isAvailable()) {
                        GeneralVariables.icomPassword = secureStorage.get("icom_password", result);
                    } else {
                        GeneralVariables.icomPassword = result;
                    }
                }
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

    public void migrateRemoveOldSettings() {
        try {
            db.execSQL("DELETE FROM config WHERE KeyName IN ('multipleAnswersMode', 'multipleAnswersLayout')");
            Log.d(TAG, "Migrated: removed old settings from database");
        } catch (Exception e) {
            Log.e(TAG, "Failed to migrate old settings", e);
        }
    }

    // === [NEW] WORLD MODEL: RAM cache for station tracking ===
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
        public int ft8StateRelative;
        public float priorityScore;
        public boolean isNewDx;
        public int lastSequential = -1;

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

    public static int freqToBandBit(long freqHz) {
        if (freqHz >= 1810000L && freqHz < 1813000L) return 0;
        if (freqHz >= 1840000L && freqHz < 1843000L) return 1;
        if (freqHz >= 1908000L && freqHz < 1911000L) return 2;
        if (freqHz >= 3531000L && freqHz < 3534000L) return 3;
        if (freqHz >= 3567000L && freqHz < 3570000L) return 4;
        if (freqHz >= 3573000L && freqHz < 3576000L) return 5;
        if (freqHz >= 3585000L && freqHz < 3588000L) return 6;
        if (freqHz >= 5126000L && freqHz < 5129000L) return 7;
        if (freqHz >= 5357000L && freqHz < 5360000L) return 8;
        if (freqHz >= 5362000L && freqHz < 5365000L) return 9;
        if (freqHz >= 7041000L && freqHz < 7044000L) return 10;
        if (freqHz >= 7056000L && freqHz < 7059000L) return 11;
        if (freqHz >= 7071000L && freqHz < 7074000L) return 12;
        if (freqHz >= 7074000L && freqHz < 7077000L) return 13;
        if (freqHz >= 7080000L && freqHz < 7083000L) return 14;
        if (freqHz >= 10131000L && freqHz < 10134000L) return 15;
        if (freqHz >= 10133000L && freqHz < 10136000L) return 16;
        if (freqHz >= 10136000L && freqHz < 10139000L) return 17;
        if (freqHz >= 10143000L && freqHz < 10146000L) return 18;
        if (freqHz >= 14071000L && freqHz < 14074000L) return 19;
        if (freqHz >= 14074000L && freqHz < 14077000L) return 20;
        if (freqHz >= 14090000L && freqHz < 14093000L) return 21;
        if (freqHz >= 18095000L && freqHz < 18098000L) return 22;
        if (freqHz >= 18100000L && freqHz < 18103000L) return 23;
        if (freqHz >= 21074000L && freqHz < 21077000L) return 24;
        if (freqHz >= 21091000L && freqHz < 21094000L) return 25;
        if (freqHz >= 24911000L && freqHz < 24914000L) return 26;
        if (freqHz >= 24915000L && freqHz < 24918000L) return 27;
        if (freqHz >= 28074000L && freqHz < 28077000L) return 28;
        if (freqHz >= 28095000L && freqHz < 28098000L) return 29;
        if (freqHz >= 40680000L && freqHz < 40683000L) return 30;
        if (freqHz >= 50310000L && freqHz < 50313000L) return 31;
        if (freqHz >= 50313000L && freqHz < 50316000L) return 32;
        if (freqHz >= 50323000L && freqHz < 50326000L) return 33;
        if (freqHz >= 70100000L && freqHz < 70103000L) return 34;
        if (freqHz >= 70154000L && freqHz < 70157000L) return 35;
        if (freqHz >= 144174000L && freqHz < 144177000L) return 36;
        if (freqHz >= 144460000L && freqHz < 144463000L) return 37;
        if (freqHz >= 432174000L && freqHz < 432177000L) return 38;
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
     * Update station record from decoded message - RAM cache + SYNC DB save
     */
    public void updateStationFromMessage(Ft8Message msg, String qth, String dxcc,
                                         int ituZone, int cqZone, float bearing) {
        String callsign = msg.getCallsignFrom();
        if (callsign == null || callsign.isEmpty()) return;
        callsign = callsign.toUpperCase().trim();

        worldModelLock.lock();
        try {
            StationRecord record = stationWorldModel.get(callsign);
            if (record == null) {
                record = new StationRecord(callsign);
                stationWorldModel.put(callsign, record);
            }

            // Обновляем ТОЛЬКО технические данные
            record.lastFreqHz = Math.round(msg.freq_hz);
            record.lastSeenUtcSec = msg.utcTime;
            record.lastSnr = msg.snr;
            if (qth != null && qth.length() >= 4) record.lastQth = qth.toUpperCase();
            record.lastBearing = bearing;
            if (dxcc != null) record.dxccCode = dxcc;
            record.lastItuZone = ituZone;
            record.lastCqZone = cqZone;

            long fullFrequency = GeneralVariables.band + Math.round(msg.freq_hz);
            int bandBit = freqToBandBit(fullFrequency);
            record.bandsBitmap |= (1L << bandBit);
            record.lastSequential = msg.getSequence();

            // НЕ обновляем ft8StateRelative — это бизнес-логика!
            // DecisionEngine сам решит, что делать

            record.priorityScore = calculatePriorityScore(record);

            // Сохраняем в БД
            saveToDb(record);
        } finally {
            worldModelLock.unlock();
        }
    }

    private void saveToDb(StationRecord record) {
        try {
            String sql = "INSERT OR REPLACE INTO station_world_model VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            db.execSQL(sql, new Object[]{
                    record.callsign, record.bandsBitmap, record.lastFreqHz, record.lastSeenUtcSec,
                    record.lastSnr, record.lastQth, record.lastBearing, record.dxccCode,
                    record.lastItuZone, record.lastCqZone,
                    record.ft8StateRelative, record.priorityScore, record.isNewDx ? 1 : 0,
                    System.currentTimeMillis() / 1000,
                    record.lastSequential
            });
        } catch (Exception e) {
            Log.e(TAG, "[SYNC FIX] Failed to write single record to DB: " + e.getMessage());
        }
    }

    /**
     * Update only the ft8StateRelative field in database.
     * Called by DecisionEngine after business logic determines the state.
     */
    public void updateStationStateInDb(StationRecord record) {
        try {
            String sql = "UPDATE station_world_model SET ft8_state_relative = ?, priority_score = ? WHERE callsign = ?";
            db.execSQL(sql, new Object[]{
                    record.ft8StateRelative,
                    record.priorityScore,
                    record.callsign
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to update station state in DB: " + e.getMessage());
        }
    }

    /**
     * [НОВОЕ] Классификация сообщения с использованием FT8MessageClassifier.
     * Возвращает legacy-код для обратной совместимости с существующей логикой.
     *
     * Legacy коды:
     *   0 = Unknown
     *   1 = Grid
     *   2 = Report
     *   3 = R-Report
     *   4 = RR73/RRR
     *   5 = 73
     *   6 = CQ
     */
    private int classifyMessageToLegacyCode(Ft8Message msg) {
        if (msg == null) return 0;

        // Проверяем, адресовано ли сообщение нам
        boolean toMe = GeneralVariables.checkIsMyCallsign(msg.getCallsignTo());

        // Если не нам — это либо CQ, либо нерелевантное сообщение
        if (!toMe) {
            return FT8MessageClassifier.isCQ(msg) ? 6 : 0;
        }

        // Используем новый классификатор
        ProtocolStep step = FT8MessageClassifier.classify(msg);

        // Конвертируем ProtocolStep в legacy-коды
        switch (step) {
            case RR73:
                return 4;
            case R_REPORT:
                return 3;
            case REPORT:
                return 2;
            case GRID:
                return 1;
            case SEVENTY_THREE:
                return 5;
            case CQ:
                return 6;
            case UNKNOWN:
            default:
                return 1; // По умолчанию — Grid
        }
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
                            r.lastSequential
                    });
                }
                db.setTransactionSuccessful();
            } catch (SQLiteException e) {
                Log.e(TAG, "Failed to save world model: " + e.getMessage());
            } finally {
                db.endTransaction();
            }
            return null;
        }
    }

    // === END WORLD MODEL ===

    // === [NEW] SecureStorage inner class ===
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
    // === [END] SecureStorage inner class ===

    public void saveSensitiveConfig(String key, String value) {
        if (secureStorage != null && secureStorage.isAvailable()) {
            secureStorage.save(key, value);
            Log.d(TAG, "Saved sensitive config to secure storage: " + key);
        } else {
            Log.w(TAG, "SecureStorage unavailable, saving sensitive config to plain DB: " + key);
            writeConfig(key, value, null);
        }
    }

    public String getSensitiveConfig(String key, String defaultValue) {
        if (secureStorage != null && secureStorage.isAvailable()) {
            String value = secureStorage.get(key, null);
            if (value != null) {
                Log.d(TAG, "Read sensitive config from secure storage: " + key);
                return value;
            }
        }
        return readConfig(key, defaultValue);
    }

    private void migrateSensitiveConfigs() {
        Log.d(TAG, "Starting migration of sensitive configs to SecureStorage...");
        String[] sensitiveKeys = new String[] {
                "cloudlog_password",
                "qrz_api_key",
                "hrdlog_password",
                "icom_password",
                "flex_api_key"
        };
        for (String key : sensitiveKeys) {
            String value = readConfig(key, null);
            if (value != null && !value.isEmpty()) {
                if (secureStorage != null) {
                    secureStorage.save(key, value);
                }
                db.execSQL("DELETE FROM config WHERE KeyName = ?", new String[]{key});
                Log.d(TAG, "Migrated sensitive key: " + key);
            }
        }
        Log.d(TAG, "Sensitive config migration completed");
    }

    private boolean isMigrationDone() {
        if (secureStorage == null || !secureStorage.isAvailable()) return true;
        return secureStorage.get(MIGRATION_FLAG_KEY, "0").equals("1");
    }

    private void markMigrationDone() {
        if (secureStorage == null || !secureStorage.isAvailable()) return;
        secureStorage.save(MIGRATION_FLAG_KEY, "1");
    }

    public void removeSensitiveConfig(String key) {
        if (secureStorage != null && secureStorage.isAvailable()) {
            secureStorage.remove(key);
        }
        db.execSQL("DELETE FROM config WHERE KeyName = ?", new String[]{key});
    }

    public String getDatabaseStatistics() {
        StringBuilder stats = new StringBuilder();
        try {
            File dbFile = context.getDatabasePath("data.db");
            if (dbFile.exists()) {
                long sizeKB = dbFile.length() / 1024;
                stats.append("Database file: ").append(sizeKB >= 1024
                        ? String.format("%.1f MB", sizeKB / 1024.0)
                        : sizeKB + " KB");
                stats.append("\n");
            }
            Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM QSLTable", null);
            if (cursor != null && cursor.moveToFirst()) {
                int qsoCount = cursor.getInt(0);
                stats.append("QSO Log entries: ").append(qsoCount);
                stats.append("\n");
                cursor.close();
            }
            cursor = db.rawQuery("SELECT COUNT(*) FROM QslCallsigns", null);
            if (cursor != null && cursor.moveToFirst()) {
                int callsignCount = cursor.getInt(0);
                stats.append("Unique callsigns: ").append(callsignCount);
                stats.append("\n");
                cursor.close();
            }
            cursor = db.rawQuery("SELECT COUNT(*) FROM SWLMessages", null);
            if (cursor != null && cursor.moveToFirst()) {
                int swlCount = cursor.getInt(0);
                stats.append("SWL messages: ").append(swlCount);
                stats.append("\n");
                cursor.close();
            }
            cursor = db.rawQuery("SELECT MAX(qso_date) || ' ' || MAX(time_on) FROM QSLTable", null);
            if (cursor != null && cursor.moveToFirst()) {
                String lastQso = cursor.getString(0);
                if (lastQso != null && !lastQso.isEmpty()) {
                    stats.append("Last QSO: ").append(lastQso);
                    stats.append("\n");
                }
                cursor.close();
            }
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

    private void ensureWorldModelSchema() {
        try {
            Cursor cursor = db.rawQuery("PRAGMA table_info(station_world_model)", null);
            boolean hasColumn = false;
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(1);
                    if ("last_sequential".equals(name)) {
                        hasColumn = true;
                        break;
                    }
                }
                cursor.close();
            }
            if (!hasColumn) {
                Log.w(TAG, "SCHEMA FIX: Adding last_sequential column to station_world_model");
                db.execSQL("ALTER TABLE station_world_model ADD COLUMN last_sequential INTEGER DEFAULT -1");
                Log.d(TAG, "SCHEMA FIX: Column added successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "SCHEMA FIX ERROR: " + e.getMessage(), e);
        }
    }
}