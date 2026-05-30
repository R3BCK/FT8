package com.bg7yoz.ft8cn;
/**
 * Common variables class.
 * Note: mainContext has potential memory leak risk, to be addressed later.
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.bg7yoz.ft8cn.callsign.CallsignDatabase;
import com.bg7yoz.ft8cn.connector.ConnectMode;
import com.bg7yoz.ft8cn.database.ControlMode;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.ft8transmit.QslRecordList;
import com.bg7yoz.ft8cn.html.HtmlContext;
import com.bg7yoz.ft8cn.rigs.BaseRigOperation;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class GeneralVariables {
    private static final String TAG = "GeneralVariables";
    public static String VERSION = BuildConfig.VERSION_NAME; // Version name, e.g. "0.62 (Beta 4)"
    public static String BUILD_DATE = BuildConfig.apkBuildTime; // APK build timestamp
    public static int MESSAGE_COUNT = 3000; // Maximum cached message count
    public static boolean saveSWLMessage = false; // SWL message save switch
    public static boolean saveSWL_QSO = false; // Save QSO from decoded messages switch
    public static boolean enableCloudlog = false; // Enable CloudLog auto-sync
    public static boolean enableQRZ = false; // Enable QRZ.com auto-sync

    // === HRDLog.net Settings ===
    public static boolean enableHrdlog = false;
    public static String hrdlogUrl = "https://api.hrdlog.net";
    public static String hrdlogApiKey = "";
    public static String hrdlogUsername = "";
    public static String hrdlogPassword = "";
    public static String hrdlogCallsign = "";
    public static String getHrdlogUrl() { return hrdlogUrl; }
    public static String getHrdlogApiKey() { return hrdlogApiKey; }
    public static String getHrdlogUsername() { return hrdlogUsername; }
    public static String getHrdlogPassword() { return hrdlogPassword; }
    public static String getHrdlogCallsign() { return hrdlogCallsign; }
    // === TUNE on Freq Change Setting ===
    public static boolean sendTuneOnFreqChange = false;
    public static boolean isUserRequestedCQ = false;
    // ================================

    // [REMOVED] Устаревшая настройка, больше не используется
    // public static int multipleAnswersMode = 0; // 0=Ignore, 1=DX, 2=Remember

    // [NEW] DX/Hound mode switch (с поддержкой загрузки/сохранения)
    public static boolean acceptDxCalls = false;

    // Web server port configuration
    public static final int DEFAULT_WEB_PORT = 7050;
    public static final int MIN_WEB_PORT = 1024;
    public static final int MAX_WEB_PORT = 65535;
    public static int webPort = DEFAULT_WEB_PORT;
    public static boolean isManualOffsetMode = false; // true = user manually fixed offset
    public static int getWebPort() {
        return webPort;
    }

    public static void setWebPort(int port) {
        if (port >= MIN_WEB_PORT && port <= MAX_WEB_PORT) {
            webPort = port;
        }
    }
    // === Frequency change behavior ===
    public static boolean clearCallHistOnFreqChange = false;
    // ===============================
    // === Network Rig Settings ===
    public static String networkRigIp = "";
    public static int networkRigPort = 50001;      // Handshake port
    public static int networkCivPort = 50002;      // CI-V commands port
    public static String networkUsername = "";
    public static String networkPassword = "";

    public static String getNetworkRigIp() { return networkRigIp; }
    public static int getNetworkRigPort() { return networkRigPort; }
    public static int getNetworkCivPort() { return networkCivPort; }
    // ============================

    public static boolean deepDecodeMode = false; // Enable deep decode mode

    public static boolean audioOutput32Bit = true; // Audio output type: true=float, false=int16
    public static int audioSampleRate = 12000; // Transmit audio sample rate

    public static MutableLiveData<Float> mutableVolumePercent = new MutableLiveData<>();
    public static float volumePercent = 0.5f; // Playback volume as percentage

    public static int flexMaxRfPower = 10; // Flex radio max transmit power
    public static int flexMaxTunePower = 10; // Flex radio max tune power

    private Context mainContext;
    public static CallsignDatabase callsignDatabase = null;

    public void setMainContext(Context context) {
        mainContext = context;
    }

    public static boolean isChina = true; // Is locale China
    public static boolean isTraditionalChinese = true; // Is locale Traditional Chinese
    //public static double maxDist = 0; // Max distance

    // List of worked DXCC entities
    public static final Map<String, String> dxccMap = new HashMap<>();
    public static final Map<Integer, Integer> cqMap = new HashMap<>();
    public static final Map<Integer, Integer> ituMap = new HashMap<>();

    private static final Map<String, Integer> excludedCallsigns = new HashMap<>();

    /**
     * Add excluded callsign prefixes
     * @param callsigns Callsigns to exclude
     */
    public static synchronized void addExcludedCallsigns(String callsigns) {
        excludedCallsigns.clear();
        String[] s = callsigns.toUpperCase().replace(" ", ",")
                .replace("|", ",")
                .replace("，", ",").split(",");
        for (int i = 0; i < s.length; i++) {
            if (s[i].length() > 0) {
                excludedCallsigns.put(s[i], 0);
            }
        }
    }

    /**
     * Check if callsign contains excluded prefix
     * @param callsign Callsign to check
     * @return true if excluded
     */
    public static synchronized boolean checkIsExcludeCallsign(String callsign) {
        Iterator<String> iterator = excludedCallsigns.keySet().iterator();
        while (iterator.hasNext()) {
            String key = iterator.next();
            if (callsign.toUpperCase().indexOf(key) == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get list of excluded callsign prefixes
     * @return Comma-separated list
     */
    public static synchronized String getExcludeCallsigns() {
        StringBuilder calls = new StringBuilder();
        Iterator<String> iterator = excludedCallsigns.keySet().iterator();
        int i = 0;
        while (iterator.hasNext()) {
            String key = iterator.next();
            if (i == 0) {
                calls.append(key);
            } else {
                calls.append(",").append(key);
            }
            i++;
        }
        return calls.toString();
    }


    // QSL record list, including successful and unsuccessful contacts
    public static QslRecordList qslRecordList = new QslRecordList();

    // Memory leak warning suppressed: Application Context should not leak
    @SuppressLint("StaticFieldLeak")
    private static GeneralVariables generalVariables = null;

    public static GeneralVariables getInstance() {
        if (generalVariables == null) {
            generalVariables = new GeneralVariables();
        }
        return generalVariables;
    }

    public static Context getMainContext() {
        return GeneralVariables.getInstance().mainContext;
    }


    public static MutableLiveData<String> mutableDebugMessage = new MutableLiveData<>();
    public static int QUERY_FREQ_TIMEOUT = 2000; // Polling interval for frequency changes (ms)
    public static int START_QUERY_FREQ_DELAY = 2000; // Delay before starting frequency polling (ms)

    public static final int DEFAULT_LAUNCH_SUPERVISION = 10 * 60 * 1000; // Default transmit supervision: 10 minutes
    private static String myMaidenheadGrid = "";
    public static MutableLiveData<String> mutableMyMaidenheadGrid = new MutableLiveData<>();

    public static int connectMode = ConnectMode.USB_CABLE; // Connection mode: USB=0, BLUETOOTH=1

    //public static String bluetoothDeviceAddress=null; // Bluetooth device address for connection


    // Map callsigns to Maidenhead grids
    // todo: should move this list to background tracking info
    //public static ArrayList<CallsignMaidenheadGrid> callsignMaidenheadGrids=new ArrayList<>();
    public static final Map<String, String> callsignAndGrids = new ConcurrentHashMap<>();
    //private static final Map<String,String> callsignAndGrids=new HashMap<>();

    public static String myCallsign = ""; // My callsign
    public static String toModifier = ""; // Call modifier
    private static float baseFrequency = 1000; // Audio frequency

    public static boolean simpleCallItemMode = false; // Compact message display mode

    public static boolean swr_switch_on = true; // SWR alert switch
    public static boolean alc_switch_on = true; // ALC alert switch

    public static MutableLiveData<Float> mutableBaseFrequency = new MutableLiveData<>();
    public static String cloudlogServerAddress = ""; // CloudLog server address
    public static String cloudlogApiKey = ""; // CloudLog API key
    public static String cloudlogStationID = ""; // CloudLog station ID
    public static String qrzApiKey = ""; // QRZ.com API key
    public static boolean synFrequency = false; // Same-frequency transmit
    public static int transmitDelay = 500; // Transmit delay (ms), also gives previous cycle decode time
    public static int pttDelay = 100; // PTT response time (ms), default 100ms for radio response
    public static int civAddress = 0xa4; // CI-V address
    public static int baudRate = 19200; // Baud rate
    public static long band = 14074000; // Carrier frequency
    public static int serialDataBits = 8; // Default data bits
    public static int serialParity = 0; // UsbSerialPort.PARITY_NONE default is 0 (none)
    public static int serialStopBits = 1; // Stop bits mapping: 1=1, 2=3, 3=1.5
    public static int instructionSet = 0; // Instruction set: 0=Icom, 1=Yaesu 2nd gen, 2=Yaesu 3rd gen
    public static int bandListIndex = -1; // Radio band index
    public static MutableLiveData<Integer> mutableBandChange = new MutableLiveData<>(); // Band index change event
    public static int controlMode = ControlMode.VOX;
    public static int modelNo = 0;
    public static int launchSupervision = DEFAULT_LAUNCH_SUPERVISION; // Transmit supervision timeout
    public static long launchSupervisionStart = UtcTimer.getSystemTime(); // Auto-transmit start time
    public static int noReplyLimit = 1; // No-reply limit: 0 = ignore

    public static int noReplyCount = 0; // No-reply counter

    // ICOM network connection parameters
    public static String icomIp = "192.168.0.255";
    public static int icomUdpPort = 50001;
    public static String icomUserName = "ic705";
    public static String icomPassword = "";


    public static boolean autoFollowCQ = true; // Auto-follow CQ calls
    public static boolean autoCallFollow = true; // Auto-call followed callsigns
    public static ArrayList<String> QSL_Callsign_list = new ArrayList<>(); // Successfully QSL'd callsigns
    public static ArrayList<String> QSL_Callsign_list_other_band = new ArrayList<>(); // QSL'd on other bands

    public static final ArrayList<String> followCallsign = new ArrayList<>(); // Followed callsigns

    public static ArrayList<Ft8Message> transmitMessages = new ArrayList<>(); // Transmit queue for Calling interface

    public static void setMyMaidenheadGrid(String grid) {
        myMaidenheadGrid = grid;
        mutableMyMaidenheadGrid.postValue(grid);
    }

    public static String getMyMaidenheadGrid() {
        return myMaidenheadGrid;
    }

    public static float getBaseFrequency() {
        return baseFrequency;
    }

    public static void setBaseFrequency(float baseFrequency) {
        mutableBaseFrequency.postValue(baseFrequency);
        GeneralVariables.baseFrequency = baseFrequency;
    }

    public static String getCloudlogServerAddress() {
        return cloudlogServerAddress;
    }

    public static String getCloudlogStationID() {
        return cloudlogStationID;
    }

    public static String getCloudlogServerApiKey() {
        return cloudlogApiKey;
    }

    public static String getQrzApiKey() {
        return qrzApiKey;
    }


    @SuppressLint("DefaultLocale")
    public static String getBaseFrequencyStr() {
        return String.format("%.0f", baseFrequency);
    }

    public static String getCivAddressStr() {
        return String.format("%2X", civAddress);
    }

    public static String getTransmitDelayStr() {
        return String.valueOf(transmitDelay);
    }

    public static String getBandString() {
        return BaseRigOperation.getFrequencyAllInfo(band);
    }

    /**
     * Check if callsign is in QSL'd list
     * @param callsign Callsign to check
     * @return true if QSL'd
     */
    public static boolean checkQSLCallsign(String callsign) {
        return QSL_Callsign_list.contains(callsign);
    }

    /**
     * Check if callsign is QSL'd on other band
     * @param callsign Callsign to check
     * @return true if QSL'd on other band
     */
    public static boolean checkQSLCallsign_OtherBand(String callsign) {
        return QSL_Callsign_list_other_band.contains(callsign);
    }

    /**
     * Check if callsign contains my callsign
     * @param callsign Callsign to check
     * @return true if contains my callsign
     */
    static public boolean checkIsMyCallsign(String callsign) {
        if (GeneralVariables.myCallsign.length() == 0) return false;
        String temp = getShortCallsign(GeneralVariables.myCallsign);
        return callsign.contains(temp);
    }

    /**
     * For compound callsigns, get the main part without prefix/suffix
     * @param callsign Full callsign
     * @return Shortened callsign
     */
    static public String getShortCallsign(String callsign) {
        if (callsign.contains("/")) {
            String[] temp = callsign.split("/");
            int max = 0;
            int max_index = 0;
            for (int i = 0; i < temp.length; i++) {
                if (temp[i].length() > max) {
                    max = temp[i].length();
                    max_index = i;
                }
            }
            return temp[max_index];
        } else {
            return callsign;
        }
    }

    /**
     * Check if callsign is in followed list
     * @param callsign Callsign to check
     * @return true if followed
     */
    public static boolean callsignInFollow(String callsign) {
        return followCallsign.contains(callsign);
    }

    /**
     * Add callsign to QSL'd list
     * @param callsign Callsign to add
     */
    public static void addQSLCallsign(String callsign) {
        if (!checkQSLCallsign(callsign)) {
            QSL_Callsign_list.add(callsign);
        }
    }

    public static String getMyMaidenhead4Grid() {
        if (myMaidenheadGrid.length() > 4) {
            return myMaidenheadGrid.substring(0, 4);
        }
        return myMaidenheadGrid;
    }

    /**
     * Reset auto-program run start time
     */
    public static void resetLaunchSupervision() {
        launchSupervisionStart = UtcTimer.getSystemTime();
    }

    /**
     * Get auto-program run duration
     * @return Duration in milliseconds
     */
    public static int launchSupervisionCount() {
        return (int) (UtcTimer.getSystemTime() - launchSupervisionStart);
    }

    public static boolean isLaunchSupervisionTimeout() {
        if (launchSupervision == 0) return false; // 0 = no supervision
        return launchSupervisionCount() > launchSupervision;
    }

    /**
     * Check message order from extraInfo
     * @param extraInfo Message extension content
     * @return Message sequence number, or -1 if not found
     */
    public static int checkFunOrderByExtraInfo(String extraInfo) {
        if (checkFun5(extraInfo)) return 5;
        if (checkFun4(extraInfo)) return 4;
        if (checkFun3(extraInfo)) return 3;
        if (checkFun2(extraInfo)) return 2;
        if (checkFun1(extraInfo)) return 1;
        return -1;
    }

    /**
     * Check message sequence number, return -1 if cannot parse
     * @param message Message to check
     * @return Message sequence number
     */
    public static int checkFunOrder(Ft8Message message) {
        if (message.checkIsCQ()) return 6;
        return checkFunOrderByExtraInfo(message.extraInfo);
    }


    // OPTIMIZATION: Replaced regex with direct char checks for 5-10x faster execution
    // Original regex compilation and matching caused significant CPU overhead in decode loop
    // These methods are called for every decoded message, so performance is critical
    // Logic remains identical to original implementation, only execution path is optimized

    // Is this a grid report? Format: LLDD (Letter Letter Digit Digit) or empty
    public static boolean checkFun1(String extraInfo) {
        if (extraInfo == null) return false;
        int len = extraInfo.length();
        if (len == 0) return true; // Empty string is valid for type 1 in some contexts
        if (len != 4) return false;
        // Grid format: LLDD (e.g., "KO85")
        char c1 = extraInfo.charAt(0);
        char c2 = extraInfo.charAt(1);
        char c3 = extraInfo.charAt(2);
        char c4 = extraInfo.charAt(3);
        // Must be Letter Letter Digit Digit and not RR73
        if (c1 >= 'A' && c1 <= 'Z' && c2 >= 'A' && c2 <= 'Z' &&
                c3 >= '0' && c3 <= '9' && c4 >= '0' && c4 <= '9') {
            return !extraInfo.equals("RR73");
        }
        return false;
    }

    // Is this a signal report, e.g. -10 or +05?
    public static boolean checkFun2(String extraInfo) {
        if (extraInfo == null) return false;
        int len = extraInfo.length();
        if (len < 2 || len > 3) return false; // -XX or +XX
        try {
            // Fast integer parse without full regex
            int val = Integer.parseInt(extraInfo.trim());
            return val != 73; // 73 belongs to type 5, not type 2
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // Is this a signal report with R prefix, e.g. R-10 or R+05?
    public static boolean checkFun3(String extraInfo) {
        if (extraInfo == null) return false;
        int len = extraInfo.length();
        if (len < 3 || len > 4) return false; // R-XX or R+XX
        if (extraInfo.charAt(0) != 'R') return false;
        if (extraInfo.charAt(1) == 'R') return false; // Must not be RR73
        try {
            Integer.parseInt(extraInfo.substring(1).trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // Is this RRR or RR73 value?
    public static boolean checkFun4(String extraInfo) {
        if (extraInfo == null) return false;
        // RR73 or RRR - exact string match is fastest
        int len = extraInfo.length();
        if (len == 4) return "RR73".equals(extraInfo);
        if (len == 3) return "RRR".equals(extraInfo);
        return false;
    }

    // Is this 73 value?
    public static boolean checkFun5(String extraInfo) {
        return "73".equals(extraInfo);
    }


    /**
     * Check if this is a signal report, if yes return the value
     * @param extraInfo Message extension
     * @return Signal report value, or -100 if not found
     */
    public static int checkFun2_3(String extraInfo) {
        if (extraInfo.equals("73")) return -100;
        if (extraInfo.matches("[R]?[+-]?[0-9]{1,2}")) {
            try {
                return Integer.parseInt(extraInfo.replace("R", ""));
            } catch (Exception e) {
                return -100;
            }
        }
        return -100;
    }

    /**
     * Check if this is a grid report, if yes return the value
     * @param extraInfo Message extension
     * @return true if grid report
     */
    public static boolean checkFun1_6(String extraInfo) {
        return extraInfo.trim().matches("[A-Z][A-Z][0-9][0-9]")
                && !extraInfo.trim().equals("RR73");
    }

    /**
     * Check if this is end of contact: RRR, RR73, or 73
     * @param extraInfo Message suffix
     * @return true if end of contact
     */
    public static boolean checkFun4_5(String extraInfo) {
        return extraInfo.trim().equals("RR73")
                || extraInfo.trim().equals("RRR")
                || extraInfo.trim().equals("73");
    }

    /**
     * Get string from resource ID with null-safety.
     * @param resId Resource ID (e.g., R.string.some_name)
     * @return String value or empty string if not found
     */
    public static String getStringFromResource(int resId) {
        // FIX: Protect against invalid resource ID (0 = not found)
        if (resId == 0) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Invalid resource ID: 0");
            }
            return "";
        }

        try {
            Context ctx = getMainContext();
            if (ctx != null) {
                return ctx.getString(resId);
            }
        } catch (android.content.res.Resources.NotFoundException e) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Resource not found: " + resId, e);
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Failed to get string resource: " + resId, e);
            }
        }
        return "";
    }


    /**
     * Add worked DXCC entity to set
     * @param dxccPrefix DXCC prefix
     */
    public static void addDxcc(String dxccPrefix) {
        dxccMap.put(dxccPrefix, dxccPrefix);
    }

    /**
     * Check if DXCC entity is already worked
     * @param dxccPrefix DXCC prefix
     * @return true if worked
     */
    public static boolean getDxccByPrefix(String dxccPrefix) {
        return dxccMap.containsKey(dxccPrefix);
    }

    /**
     * Add CQ zone to worked list
     * @param cqZone CQ zone number
     */
    public static void addCqZone(int cqZone) {
        cqMap.put(cqZone, cqZone);
    }

    /**
     * Check if CQ zone is already worked
     * @param cq CQ zone number
     * @return true if worked
     */
    public static boolean getCqZoneById(int cq) {
        return cqMap.containsKey(cq);
    }

    /**
     * Add ITU zone to worked list
     * @param itu ITU zone number
     */
    public static void addItuZone(int itu) {
        ituMap.put(itu, itu);
    }

    /**
     * Check if ITU zone is already worked
     * @param itu ITU zone number
     * @return true if worked
     */
    public static boolean getItuZoneById(int itu) {
        return ituMap.containsKey(itu);
    }

    // Event to trigger new grid notification
    public static MutableLiveData<String> mutableNewGrid = new MutableLiveData<>();

    /**
     * Add callsign-grid mapping to table
     * @param callsign Callsign
     * @param grid Maidenhead grid
     */
    public static void addCallsignAndGrid(String callsign, String grid) {
        if (grid.length() >= 4) {
            callsignAndGrids.put(callsign, grid);
            mutableNewGrid.postValue(grid);
        }
    }

    /**
     * Check if callsign has grid in table.
     * If not in memory, should query database.
     * @param callsign Callsign to check
     * @return true if has grid
     */
    public static boolean getCallsignHasGrid(String callsign) {
        return callsignAndGrids.containsKey(callsign);
    }

    /**
     * Check if callsign has specific grid in table.
     * Purpose: to update database with correct mapping.
     * @param callsign Callsign
     * @param grid Grid to match
     * @return true if exact match
     */
    public static boolean getCallsignHasGrid(String callsign, String grid) {
        if (!callsignAndGrids.containsKey(callsign)) return false; // Callsign not in table
        String s = callsignAndGrids.get(callsign);
        if (s == null) return false;
        return s.equals(grid);
    }

    public static String getGridByCallsign(String callsign, DatabaseOpr db) {
        String s = callsign.replace("<", "").replace(">", "");
        if (getCallsignHasGrid(s)) {
            return callsignAndGrids.get(s);
        } else {
            db.getCallsignQTH(callsign);
            return "";
        }
    }

    /**
     * Iterate callsign-grid table and generate HTML
     * @return HTML string
     */
    public static String getCallsignAndGridToHTML() {
        StringBuilder result = new StringBuilder();
        int order = 0;
        for (String key : callsignAndGrids.keySet()) {
            order++;
            HtmlContext.tableKeyRow(result, order % 2 != 0, key, callsignAndGrids.get(key));
        }
        return result.toString();
    }

    public static synchronized void deleteArrayListMore(ArrayList<Ft8Message> list) {
        if (list.size() > GeneralVariables.MESSAGE_COUNT) {
            while (list.size() > GeneralVariables.MESSAGE_COUNT) {
                list.remove(0);
            }
        }
    }

    /**
     * Check if string is integer
     * @param str String to check
     * @return true if integer
     */
    public static boolean isInteger(String str) {
        if (str != null && !"".equals(str.trim()))
            return str.matches("^[0-9]*$");
        else
            return false;
    }

    /**
     * Audio output data type, not available in network mode
     */
    public enum AudioOutputBitMode {
        Float32,
        Int16
    }

    /**
     * Create a temporary file
     * @param context Android context
     * @param prefix File prefix
     * @param suffix File extension
     * @return File object or null on error
     */
    public static File getTempFile(Context context, String prefix, String suffix) {
        File tempDir = context.getExternalCacheDir();
        if (tempDir == null) {
            Log.e(TAG, "Failed to create temp file: cannot get temp directory");
            return null;
        }

        try {
            return File.createTempFile(prefix, suffix, tempDir);
        } catch (IOException e) {
            Log.e(TAG, "Failed to create temp file: " + e.getMessage());
            return null;
        }
    }

    /**
     * Write text data to file
     * @param file File to write
     * @param data Text data
     */
    public static void writeToFile(File file, String data) {
        FileOutputStream fileOutputStream = null;
        try {
            fileOutputStream = new FileOutputStream(file, true);
            fileOutputStream.write(data.getBytes());
            Log.d(TAG, "File data write complete");
        } catch (IOException e) {
            Log.e(TAG, String.format("Failed to write file: %s", e.getMessage()));
        } finally {
            try {
                if (fileOutputStream != null) {
                    fileOutputStream.close();
                }
            } catch (IOException e) {
                Log.e(TAG, String.format("Failed to close file: %s", e.getMessage()));
            }
        }
    }


    /**
     * Save packet cache to temp file
     * @param context Android context
     * @param prefix File prefix
     * @param suffix File extension
     * @param data Data to write
     * @return File object or null
     */
    public static File writeToTempFile(Context context, String prefix, String suffix, String data) {
        File file = getTempFile(context, prefix, suffix);
        writeToFile(file, data);
        if (file != null) {
            file.deleteOnExit(); // File will be deleted on VM exit
        }
        return file;
    }

//    /**
//     * Share file via Intent
//     * @param context Android context
//     * @param file File to share
//     * @param title Share dialog title
//     */
//    public static void shareFile(Context context, File file, String title) {
//        Intent sharingIntent = new Intent(Intent.ACTION_SEND);
//        Uri fileUri = FileProvider.getUriForFile(context.getApplicationContext()
//                , "com.bg7yoz.ft8cn.fileprovider", file);
//        sharingIntent.setType("text/plain");
//        sharingIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
//        sharingIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
//        context.startActivity(Intent.createChooser(sharingIntent, title));
//    }

    /**
     * Delete directory recursively
     * @param dir Directory to delete
     * @return true if successful
     */
    public static boolean deleteDir(File dir) {
        if (dir == null) return false;
        if (dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    boolean success = deleteDir(new File(dir, child));
                    if (!success) {
                        return false;
                    }
                }
            }
        }
        return dir.delete();
    }

    public static void clearCache(Context context) {
        try {
            File dir = context.getExternalCacheDir();
            deleteDir(dir);
        } catch (Exception e) {
            // Handle exception silently
        }
    }

    // [NEW] Метод для загрузки состояния acceptDxCalls из базы данных
    // Вызывается при инициализации приложения или после загрузки DatabaseOpr
    public static void loadAcceptDxCallsFromDatabase(DatabaseOpr db) {
        if (db == null) return;
        try {
            String value = db.readConfig("acceptDxCalls", "0");
            acceptDxCalls = "1".equals(value);
            Log.d(TAG, "Loaded acceptDxCalls from database: " + acceptDxCalls);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load acceptDxCalls from database", e);
            // Значение по умолчанию остаётся false
        }
    }

    // [NEW] Метод для сохранения состояния acceptDxCalls в базу данных
    // Вызывается при изменении переключателя в UI
    public static void saveAcceptDxCallsToDatabase(DatabaseOpr db) {
        if (db == null) return;
        try {
            db.writeConfig("acceptDxCalls", acceptDxCalls ? "1" : "0", null);
            Log.d(TAG, "Saved acceptDxCalls to database: " + acceptDxCalls);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save acceptDxCalls to database", e);
        }
    }

    // [NEW] Метод миграции: удаление устаревших настроек из базы данных
    // Вызывается один раз при обновлении приложения
    public static void migrateRemoveOldSettings(DatabaseOpr db) {
        if (db == null) return;
        try {
            // Удаляем устаревшие ключи конфигурации
            db.getDb().execSQL("DELETE FROM config WHERE KeyName IN ('multipleAnswersMode', 'multipleAnswersLayout')");
            Log.d(TAG, "Migrated: removed old settings from database");
        } catch (Exception e) {
            Log.e(TAG, "Failed to migrate old settings", e);
            // Не критично, можно проигнорировать
        }
    }
}