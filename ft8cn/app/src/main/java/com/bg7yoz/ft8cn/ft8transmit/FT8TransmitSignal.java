//FT8TransmitSignal.java
package com.bg7yoz.ft8cn.ft8transmit;
/**
 * Class related to transmitting signals.
 *
 * ARCHITECTURE NOTE (IMPORTANT!):
 * This class is now a PASSIVE EXECUTOR only. It does NOT make any decisions
 * about WHAT or WHEN to transmit. All decision logic is in:
 *   - MainViewModel.evaluateStateMachine()
 *   - DecisionEngine.evaluate()
 *
 * MainViewModel calls:
 *   - setTransmit(...) to prepare transmission parameters
 *   - transmitNow() to explicitly trigger transmission
 *
 * The autonomous UtcTimer that used to auto-trigger doTransmit() every 15 seconds
 * has been DISABLED to prevent conflicts with DecisionEngine (which caused the
 * infinite "73" transmission bug).
 *
 * @author BGY7YOZ
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.connector.ConnectMode;
import com.bg7yoz.ft8cn.database.ControlMode;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.log.QSLRecord;
import com.bg7yoz.ft8cn.rigs.BaseRigOperation;
import com.bg7yoz.ft8cn.timer.OnUtcTimer;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.ui.ToastMessage;

// [НОВОЕ] Импорты для единого классификатора FT8-сообщений
import com.bg7yoz.ft8cn.protocol.FT8MessageClassifier;
import com.bg7yoz.ft8cn.protocol.ProtocolStep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FT8TransmitSignal {
    private static final String TAG = "FT8TransmitSignal";

    private boolean transmitFreeText = false;
    private String freeText = "FREE TEXT";

    private final DatabaseOpr databaseOpr;
    private TransmitCallsign toCallsign;
    public MutableLiveData<TransmitCallsign> mutableToCallsign = new MutableLiveData<>();

    private int functionOrder = 6;
    public MutableLiveData<Integer> mutableFunctionOrder = new MutableLiveData<>();
    private boolean activated = false;
    public MutableLiveData<Boolean> mutableIsActivated = new MutableLiveData<>();
    public int sequential;
    public MutableLiveData<Integer> mutableSequential = new MutableLiveData<>();
    private boolean isTransmitting = false;
    public MutableLiveData<Boolean> mutableIsTransmitting = new MutableLiveData<>();
    public MutableLiveData<String> mutableTransmittingMessage = new MutableLiveData<>();

    private long messageStartTime = 0;
    private long messageEndTime = 0;
    private String toMaidenheadGrid = "";
    private int sendReport = 0;
    private int sentTargetReport = -100;

    private int receivedReport = 0;
    private int receiveTargetReport = -100;

    private final OnTransmitSuccess onTransmitSuccess;

    private AudioAttributes attributes = null;
    private AudioFormat myFormat = null;
    private AudioTrack audioTrack = null;

    private float[] currentAudioBuffer = null;

    // [ARCHITECTURE FIX] utcTimer is no longer used for autonomous transmission triggering.
    // Kept as field for potential future use, but NOT started automatically.
    // Transmission is now triggered ONLY by explicit transmitNow() calls from MainViewModel.
    public UtcTimer utcTimer;

    public ArrayList<FunctionOfTransmit> functionList = new ArrayList<>();
    public MutableLiveData<ArrayList<FunctionOfTransmit>> mutableFunctions = new MutableLiveData<>();

    private final OnDoTransmitted onDoTransmitted;
    private final ExecutorService doTransmitThreadPool = Executors.newCachedThreadPool();
    private final DoTransmitRunnable doTransmitRunnable = new DoTransmitRunnable(this);

    // [CHANGED] Pending QSO structure for DX mode (queue for multiple answers)
    private static class PendingQSO {
        String callsign;
        int functionOrder;
        int sequential;
        int ageCycles;
        PendingQSO(String c, int fo, int sq) {
            callsign = c; functionOrder = fo; sequential = sq; ageCycles = 0;
        }
    }
    // [CHANGED] Queue for pending QSOs in DX mode
    private final ArrayList<PendingQSO> pendingQSOs = new ArrayList<>();
    private static final int MAX_PENDING_CYCLES = 14;
    private static final int MAX_PENDING_COUNT = 14;

    // [EXISTING] Interrupted QSO tracking (for fallback when connection lost)
    private static class InterruptedQSO {
        String callsign;
        int lastFunctionOrder;
        int lastSentReport;
        int lastReceivedReport;
        long timestamp;
        InterruptedQSO(String c, int fo, int sentRep, int recvRep) {
            callsign = c; lastFunctionOrder = fo;
            lastSentReport = sentRep; lastReceivedReport = recvRep;
            timestamp = System.currentTimeMillis();
        }
    }
    private final ArrayList<InterruptedQSO> interruptedQSOs = new ArrayList<>();
    private static final int MAX_INTERRUPTED_QSO_COUNT = 20;
    private static final long INTERRUPTED_QSO_MAX_AGE_MS = 30 * 60 * 1000;

    private int cyclesWithoutBeingCalled = 0;
    private static final int CYCLES_WITHOUT_BEING_CALLED_LIMIT = 10;

    private ArrayList<String> decodeHistoryFallbackCache = new ArrayList<>();
    private long lastFallbackUpdate = 0;
    private static final long FALLBACK_CACHE_REFRESH_MS = 60000;
    private static final int MIN_HEARD_COUNT_FALLBACK = 3;

    private boolean ignoreCQForNextStep = false;

    // [NEW] Flag: use step from external source (DecisionEngine) instead of auto-detect
    private boolean useExternalStep = false;

    // [CHANGED] Library loading management for dual-library support
    private static boolean libraryLoaded = false;

    public static void loadLibrary(boolean dxMode) {
        if (libraryLoaded) return;
        try {
            if (dxMode) {
                System.loadLibrary("ft8cn_dx");
                Log.d(TAG, "Loaded DX multistream library");
            } else {
                System.loadLibrary("ft8cn_std");
                Log.d(TAG, "Loaded standard library");
            }
            libraryLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load library: " + e.getMessage());
            // Fallback to standard library
            if (!dxMode) {
                System.loadLibrary("ft8cn_std");
                libraryLoaded = true;
            }
        }
    }

    @SuppressLint("DefaultLocale")
    public FT8TransmitSignal(DatabaseOpr databaseOpr, OnDoTransmitted doTransmitted, OnTransmitSuccess onTransmitSuccess) {
        // [CHANGED] Load appropriate library BEFORE any native calls
        loadLibrary(GeneralVariables.acceptDxCalls);

        this.onDoTransmitted = doTransmitted;
        this.onTransmitSuccess = onTransmitSuccess;
        this.databaseOpr = databaseOpr;
        setTransmitting(false);
        setActivated(false);

        GeneralVariables.mutableVolumePercent.observeForever(aFloat -> {
            if (audioTrack != null) audioTrack.setVolume(aFloat);
        });

        // ========================================================================
        // [ARCHITECTURE FIX] AUTONOMOUS TIMER DISABLED
        // ========================================================================
        // The following block has been COMMENTED OUT to fix the infinite "73" bug.
        //
        // PROBLEM: This timer used to call doTransmit() every 15 seconds whenever
        // (currentSlot == sequential && activated), regardless of what DecisionEngine
        // decided. This caused:
        //   1. MainViewModel decides: "Send 73 once, then go to SOFT_FINISH"
        //   2. FT8TransmitSignal's timer: "Keep sending 73 every 15 sec forever!"
        //   Result: Conflict → infinite 73 transmissions
        //
        // SOLUTION: FT8TransmitSignal is now a PASSIVE executor.
        // MainViewModel controls WHEN to transmit via explicit transmitNow() calls.
        // ========================================================================
        /*
        utcTimer = new UtcTimer(FT8Common.FT8_SLOT_TIME_M, false, new OnUtcTimer() {
            @Override public void doHeartBeatTimer(long utc) {}
            @Override public void doOnSecTimer(long utc) {
                if (GeneralVariables.isLaunchSupervisionTimeout()) { setActivated(false); return; }

                int currentSlot = UtcTimer.getNowSequential();

                if (currentSlot == sequential && activated) {
                    if (GeneralVariables.myCallsign.length() < 3) {
                        ToastMessage.show(GeneralVariables.getStringFromResource(R.string.callsign_error));
                        return;
                    }
                    doTransmit();
                }
            }
        });
        utcTimer.start();
        */
        // ========================================================================
        // [END ARCHITECTURE FIX]
        // ========================================================================

        Log.d(TAG, "[ARCHITECTURE] Autonomous timer DISABLED. " +
                "FT8TransmitSignal is now a passive executor. " +
                "Transmission is triggered only by explicit transmitNow() calls from MainViewModel.");
    }

    /**
     * Explicitly trigger transmission.
     * Called by MainViewModel when DecisionEngine decides to transmit.
     *
     * This is the ONLY way to start transmission now (autonomous timer disabled).
     */
    public void transmitNow() {
        if (GeneralVariables.myCallsign.length() < 3) {
            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.callsign_error));
            return;
        }

        int currentSlot = UtcTimer.getNowSequential();
        long systemTime = UtcTimer.getSystemTime();
        long timeInSlot = systemTime % 15000;

        Log.w(TAG, "[TRANSMIT_NOW] ========================================");
        Log.w(TAG, "[TRANSMIT_NOW] transmitNow() CALLED");
        Log.w(TAG, "[TRANSMIT_NOW]   target=" + (toCallsign != null ? toCallsign.callsign : "NULL"));
        Log.w(TAG, "[TRANSMIT_NOW]   functionOrder=" + functionOrder);
        Log.w(TAG, "[TRANSMIT_NOW]   targetSequential=" + sequential);
        Log.w(TAG, "[TRANSMIT_NOW]   currentSlot=" + currentSlot);
        Log.w(TAG, "[TRANSMIT_NOW]   systemTime=" + systemTime + " timeInSlot=" + timeInSlot);
        Log.w(TAG, "[TRANSMIT_NOW]   isActivated=" + isActivated());
        Log.w(TAG, "[TRANSMIT_NOW]   isTransmitting=" + isTransmitting());

        ToastMessage.show(String.format(GeneralVariables.getStringFromResource(R.string.adjust_call_target), toCallsign.callsign));
        resetTargetReport();

        // [FIX] НЕ игнорируем проверку слота!
        // Передаём только если текущий слот совпадает с целевым
        if (currentSlot == sequential && timeInSlot < 2500) {
            Log.w(TAG, "[TRANSMIT_NOW] STARTING TRANSMISSION (correct slot)");
            setTransmitting(false);
            doTransmit();
        } else {
            Log.w(TAG, "[TRANSMIT_NOW] WAITING for correct slot (current=" + currentSlot + " target=" + sequential + ")");
            // Передача произойдёт автоматически через utcTimer когда придёт нужный слот
        }
        Log.w(TAG, "[TRANSMIT_NOW] ========================================");
    }

    public void doTransmit() {
        Log.w(TAG, "[DO_TRANSMIT] ========================================");
        Log.w(TAG, "[DO_TRANSMIT] doTransmit() CALLED");
        Log.w(TAG, "[DO_TRANSMIT]   isActivated=" + isActivated());

        if (!isActivated()) {
            Log.w(TAG, "[DO_TRANSMIT] ❌ BLOCKED: not activated");
            Log.w(TAG, "[DO_TRANSMIT] ========================================");
            return;
        }

        long rfFreq = GeneralVariables.band + Math.round(GeneralVariables.getBaseFrequency());
        boolean isWSPR2 = BaseRigOperation.checkIsWSPR2(rfFreq);
        Log.w(TAG, "[DO_TRANSMIT]   rfFreq=" + rfFreq + " isWSPR2=" + isWSPR2);

        if (isWSPR2) {
            ToastMessage.show(String.format(GeneralVariables.getStringFromResource(R.string.use_wspr2_error),
                    BaseRigOperation.getFrequencyAllInfo(GeneralVariables.band)));
            setActivated(false);
            Log.w(TAG, "[DO_TRANSMIT]  BLOCKED: WSPR2 frequency");
            Log.w(TAG, "[DO_TRANSMIT] ========================================");
            return;
        }

        Log.w(TAG, "[DO_TRANSMIT]  Submitting to thread pool");
        Log.w(TAG, "[DO_TRANSMIT] ========================================");
        doTransmitThreadPool.execute(doTransmitRunnable);
        mutableFunctions.postValue(functionList);
    }

    @SuppressLint("DefaultLocale")
    public void setTransmit(TransmitCallsign transmitCallsign, int functionOrder, String toMaidenheadGrid) {
        // [TX_DEBUG] Детальное логирование всех параметров вызова setTransmit
        Log.d(TAG, "[TX_DEBUG] setTransmit() called:");
        Log.d(TAG, "  target=" + (transmitCallsign != null ? transmitCallsign.callsign : "NULL"));
        Log.d(TAG, "  functionOrder=" + functionOrder);
        Log.d(TAG, "  extraInfo=\"" + toMaidenheadGrid + "\"");
        Log.d(TAG, "  freq_hz=" + (transmitCallsign != null ? transmitCallsign.frequency : "N/A"));
        Log.d(TAG, "  sequential=" + (transmitCallsign != null ? transmitCallsign.sequential : "N/A"));
        Log.d(TAG, "  snr=" + (transmitCallsign != null ? transmitCallsign.snr : "N/A"));
        Log.d(TAG, "  current UTC slot=" + UtcTimer.getNowSequential());
        Log.d(TAG, "  isActivated=" + isActivated());

        // [DEBUG] Трассировка всех вызовов setTransmit
        Log.w(TAG, "[SET_TRANSMIT_CALLED] targetCallsign=" + transmitCallsign.callsign +
                " functionOrder=" + functionOrder +
                " frequency=" + transmitCallsign.frequency +
                " sequential=" + transmitCallsign.sequential +
                " snr=" + transmitCallsign.snr +
                " activated=" + isActivated() +
                " transmitting=" + isTransmitting() +
                " stack=" + android.util.Log.getStackTraceString(new Throwable()));
        // [/DEBUG]

        messageStartTime = 0;

        // [ИСПРАВЛЕНО] Используем FT8MessageClassifier напрямую со строкой (без создания Ft8Message)
        if (FT8MessageClassifier.isGrid(toMaidenheadGrid)) {
            this.toMaidenheadGrid = toMaidenheadGrid;
        } else {
            this.toMaidenheadGrid = "";
        }

        mutableToCallsign.postValue(transmitCallsign);
        toCallsign = transmitCallsign;

        if (functionOrder == -1) {
            this.functionOrder = FT8MessageClassifier.getLegacyCode(toMaidenheadGrid) + 1;
            if (this.functionOrder == 6) this.functionOrder = 1;
        } else {
            this.functionOrder = functionOrder;
        }

        GeneralVariables.noReplyCount = 0;
        cyclesWithoutBeingCalled = 0;

        if (this.functionOrder <= 1) {
            ignoreCQForNextStep = true;
        }

        if (transmitCallsign.frequency == 0) transmitCallsign.frequency = GeneralVariables.getBaseFrequency();
        if (GeneralVariables.synFrequency) setBaseFrequency(transmitCallsign.frequency);

        // === [FIX] SEQUENTIAL SLOT LOGIC ===
        // Мы ожидаем, что MainViewModel уже вычислил правильный слот (0 или 1).
        // Если передан -1, используем fallback (инверсия текущего UTC).
        if (transmitCallsign.sequential >= 0) {
            this.sequential = transmitCallsign.sequential; // Берем готовое значение
            Log.d(TAG, "[SEQUENTIAL] Applied explicit slot: " + this.sequential);
        } else {
            // Fallback для старых вызовов или CQ без контекста
            this.sequential = 1 - UtcTimer.getNowSequential();
            Log.w(TAG, "[SEQUENTIAL] Calculated fallback slot: " + this.sequential);
        }
        // ==================================

        mutableSequential.postValue(sequential);
        generateFun();
        mutableFunctionOrder.postValue(functionOrder);
    }

    @SuppressLint("DefaultLocale")
    public void setBaseFrequency(float freq) {
        GeneralVariables.setBaseFrequency(freq);
        databaseOpr.writeConfig("freq", String.format("%.0f", freq), null);
    }

    public Ft8Message getFunctionCommand(int order) {
        // [TX_DEBUG] Логирование формирования сообщения для каждого шага
        Log.d(TAG, "[TX_DEBUG] getFunctionCommand() called with order=" + order);
        Log.d(TAG, "  targetCallsign=" + (toCallsign != null ? toCallsign.callsign : "NULL"));
        Log.d(TAG, "  myCallsign=" + GeneralVariables.myCallsign);
        Log.d(TAG, "  myGrid=" + GeneralVariables.getMyMaidenhead4Grid());

        switch (order) {
            case 1:
                Log.d(TAG, "  → STEP 1: Call with grid");
                resetTargetReport();
                return new Ft8Message(1, 0, toCallsign.callsign, GeneralVariables.myCallsign, GeneralVariables.getMyMaidenhead4Grid());

            case 2: {
                Log.d(TAG, "  → STEP 2: Send signal report");
                int snr = toCallsign.snr;
                if (snr == -100 || snr < -30 || snr > 30) snr = -10;
                sentTargetReport = snr;
                Log.d(TAG, "    report value=" + snr);
                return new Ft8Message(1, 0, toCallsign.callsign, GeneralVariables.myCallsign, String.valueOf(snr));
            }
            case 3: {
                Log.d(TAG, "  → STEP 3: Send R-report");
                int snr = toCallsign.snr;
                if (snr == -100 || snr < -30 || snr > 30) snr = -10;
                sentTargetReport = snr;
                Log.d(TAG, "    R-report value=R" + snr);
                return new Ft8Message(1, 0, toCallsign.callsign, GeneralVariables.myCallsign, "R" + snr);
            }
            case 4:
                Log.d(TAG, "  → STEP 4: Send RR73");
                return new Ft8Message(1, 0, toCallsign.callsign, GeneralVariables.myCallsign, "RR73");
            case 5:
                Log.d(TAG, "  → STEP 5: Send 73");
                return new Ft8Message(1, 0, toCallsign.callsign, GeneralVariables.myCallsign, "73");
            case 6:
                Log.d(TAG, "  → STEP 6: Send CQ");
                resetTargetReport();
                Ft8Message msg = new Ft8Message(1, 0, "CQ", GeneralVariables.myCallsign, GeneralVariables.getMyMaidenhead4Grid());
                msg.modifier = GeneralVariables.toModifier;
                Log.d(TAG, "    CQ modifier=" + GeneralVariables.toModifier);
                return msg;
        }
        Log.w(TAG, "  → UNKNOWN ORDER " + order + ", fallback to CQ");
        return new Ft8Message("CQ", GeneralVariables.myCallsign, GeneralVariables.getMyMaidenhead4Grid());
    }

    public void generateFun() {
        functionList.clear();
        for (int i = 1; i <= 6; i++) {
            if (functionOrder == 6) {
                functionList.add(new FunctionOfTransmit(6, getFunctionCommand(6), false));
                break;
            } else {
                functionList.add(new FunctionOfTransmit(i, getFunctionCommand(i), false));
            }
        }
        mutableFunctions.postValue(functionList);
        setCurrentFunctionOrder(functionOrder);
    }

    private short[] float2Short(float[] buffer) {
        short[] temp = new short[buffer.length + 8];
        for (int i = 0; i < buffer.length; i++) {
            float x = buffer[i];
            if (x > 1.0f) x = 1.0f; else if (x < -1.0f) x = -1.0f;
            temp[i] = (short)(x * 32767.0);
        }
        return temp;
    }

    private void playFT8Signal(Ft8Message msg) {
        Log.w(TAG, "[PLAY_FT8] ========================================");
        Log.w(TAG, "[PLAY_FT8] playFT8Signal() CALLED");
        Log.w(TAG, "[PLAY_FT8]   message=" + msg.getMessageText());
        Log.w(TAG, "[PLAY_FT8]   connectMode=" + GeneralVariables.connectMode);

        if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
            if (onDoTransmitted != null) onDoTransmitted.onTransmitByWifi(msg);
            long now = System.currentTimeMillis();
            while (isTransmitting) {
                try { Thread.sleep(1); if (System.currentTimeMillis() - now > 13100) { isTransmitting = false; break; } }
                catch (InterruptedException e) { e.printStackTrace(); }
            }
            afterPlayAudio();
            return;
        }

        if (GeneralVariables.controlMode == ControlMode.CAT && onDoTransmitted != null && onDoTransmitted.supportTransmitOverCAT()) {
            onDoTransmitted.onTransmitOverCAT(msg);
            long now = System.currentTimeMillis();
            while (isTransmitting) {
                try { Thread.sleep(1); if (System.currentTimeMillis() - now > 13000) { isTransmitting = false; break; } }
                catch (InterruptedException e) { e.printStackTrace(); }
            }
            afterPlayAudio();
            return;
        }

        float[] buffer = GenerateFT8.generateFt8(msg, GeneralVariables.getBaseFrequency(), GeneralVariables.audioSampleRate);
        if (buffer == null) {
            afterPlayAudio();
            return;
        }

        currentAudioBuffer = buffer;

        attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        myFormat = new AudioFormat.Builder()
                .setSampleRate(GeneralVariables.audioSampleRate)
                .setEncoding(GeneralVariables.audioOutput32Bit ? AudioFormat.ENCODING_PCM_FLOAT : AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();

        // [FIX] Используем MODE_STREAM вместо MODE_STATIC
        // MODE_STATIC не работает для больших буферов (>1MB)
        int minBufferSize = AudioTrack.getMinBufferSize(
                GeneralVariables.audioSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                GeneralVariables.audioOutput32Bit ? AudioFormat.ENCODING_PCM_FLOAT : AudioFormat.ENCODING_PCM_16BIT
        );

        // Берем максимум из минимального размера и размера данных
        int bufferSize = Math.max(minBufferSize,
                GeneralVariables.audioOutput32Bit ? buffer.length * 4 : (buffer.length + 8) * 2);

        audioTrack = new AudioTrack(
                attributes,
                myFormat,
                bufferSize,
                AudioTrack.MODE_STREAM,  // STREAM вместо STATIC
                0
        );

        if (audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "[PLAY_FT8] AudioTrack init failed");
            afterPlayAudio();
            return;
        }

        int writeResult;
        if (GeneralVariables.audioOutput32Bit) {
            writeResult = audioTrack.write(buffer, 0, buffer.length, AudioTrack.WRITE_BLOCKING);
        } else {
            writeResult = audioTrack.write(float2Short(buffer), 0, buffer.length + 8, AudioTrack.WRITE_BLOCKING);
        }

        if (writeResult <= 0) {
            afterPlayAudio();
            return;
        }

        audioTrack.setNotificationMarkerPosition(buffer.length);
        audioTrack.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
            @Override public void onMarkerReached(AudioTrack track) { afterPlayAudio(); }
            @Override public void onPeriodicNotification(AudioTrack track) {}
        });

        audioTrack.play();
        audioTrack.setVolume(GeneralVariables.volumePercent);
    }

    private void afterPlayAudio() {
        if (onDoTransmitted != null) onDoTransmitted.onAfterTransmit(getFunctionCommand(functionOrder), functionOrder);
        isTransmitting = false; mutableIsTransmitting.postValue(false);

        // Clear transmitting message display
        mutableTransmittingMessage.postValue("");

        if (currentAudioBuffer != null) {
            GenerateFT8.releaseAudioBuffer(currentAudioBuffer);
            currentAudioBuffer = null;
        }

        if (audioTrack != null) { audioTrack.release(); audioTrack = null; }
    }

    private void doComplete() {
        messageEndTime = UtcTimer.getSystemTime();
        toMaidenheadGrid = GeneralVariables.getGridByCallsign(toCallsign.callsign, databaseOpr);
        if (messageStartTime == 0) messageStartTime = UtcTimer.getSystemTime();

        for (int i = GeneralVariables.transmitMessages.size() - 1; i >= 0; i--) {
            Ft8Message m = GeneralVariables.transmitMessages.get(i);
            // [ИСПРАВЛЕНО] Используем FT8MessageClassifier вместо checkFun3/checkFun2
            if ((FT8MessageClassifier.isRReport(m) || FT8MessageClassifier.isReport(m))
                    && m.callsignFrom.equals(toCallsign.callsign) && GeneralVariables.checkIsMyCallsign(m.callsignTo)) {
                receiveTargetReport = getReportFromExtraInfo(m.extraInfo); break;
            }
        }

        for (int i = GeneralVariables.transmitMessages.size() - 1; i >= 0; i--) {
            Ft8Message m = GeneralVariables.transmitMessages.get(i);
            // [ИСПРАВЛЕНО] Используем FT8MessageClassifier вместо checkFun3/checkFun2
            if ((FT8MessageClassifier.isRReport(m) || FT8MessageClassifier.isReport(m))
                    && m.callsignTo.equals(toCallsign.callsign) && GeneralVariables.checkIsMyCallsign(m.callsignFrom)) {
                sentTargetReport = getReportFromExtraInfo(m.extraInfo); break;
            }
        }

        if (onDoTransmitted != null) {
            onTransmitSuccess.doAfterTransmit(new QSLRecord(messageStartTime, messageEndTime,
                    GeneralVariables.myCallsign, GeneralVariables.getMyMaidenhead4Grid(),
                    toCallsign.callsign, toMaidenheadGrid,
                    sentTargetReport != -100 ? sentTargetReport : sendReport,
                    receiveTargetReport != -100 ? receiveTargetReport : receivedReport,
                    "FT8", GeneralVariables.band, Math.round(GeneralVariables.getBaseFrequency())));
            GeneralVariables.addQSLCallsign(toCallsign.callsign);
            ToastMessage.show(String.format("QSO : %s , at %s", toCallsign.callsign, BaseRigOperation.getFrequencyAllInfo(GeneralVariables.band)));
        }

        // [DX MODE] Switch to next pending QSO after completing current one
        if (GeneralVariables.acceptDxCalls && !pendingQSOs.isEmpty()) {
            PendingQSO next = pendingQSOs.remove(0);
            Log.d(TAG, "DX mode: switching to next pending QSO: " + next.callsign);

            // Find frequency from decode history
            float freq = GeneralVariables.getBaseFrequency();
            if (GeneralVariables.transmitMessages != null) {
                for (Ft8Message m : GeneralVariables.transmitMessages) {
                    if (m != null && m.getCallsignFrom().equals(next.callsign)) {
                        freq = m.freq_hz;
                        break;
                    }
                }
            }

            setTransmit(new TransmitCallsign(0, 0, next.callsign, freq, next.sequential, -100),
                    next.functionOrder, "");
        }
        // [END DX MODE]
    }

    public void setCurrentFunctionOrder(int order) {
        functionOrder = order;
        for (FunctionOfTransmit f : functionList) f.setCurrentOrder(order);
        if (order == 1) resetTargetReport();
        if (order == 4 || order == 5) updateQSlRecordList(order, toCallsign);
        mutableFunctions.postValue(functionList);
    }

    private boolean checkCallsignIsCallTo(String fromCall, String toCall) {
        return toCall.contains("/") ? toCall.contains(fromCall) : fromCall.equals(toCall);
    }

    private int checkTargetCallMe(ArrayList<Ft8Message> messages) {
        int count = 1;
        for (Ft8Message m : messages) {
            if (m.getSequence() == sequential || toCallsign == null) continue;
            if (GeneralVariables.checkIsMyCallsign(m.getCallsignTo()) && checkCallsignIsCallTo(m.getCallsignFrom(), toCallsign.callsign)) return 0;
            if (checkCallsignIsCallTo(m.getCallsignFrom(), toCallsign.callsign)) count++;
        }
        return count;
    }

    private int checkFunctionOrdFromMessages(ArrayList<Ft8Message> messages) {
        for (Ft8Message m : messages) {
            if (m.getSequence() == sequential || toCallsign == null) continue;
            if (GeneralVariables.checkIsMyCallsign(m.getCallsignTo()) && checkCallsignIsCallTo(m.getCallsignFrom(), toCallsign.callsign)) {
                // [ИСПРАВЛЕНО] Используем FT8MessageClassifier вместо checkFun3/checkFun2
                if (FT8MessageClassifier.isRReport(m) || FT8MessageClassifier.isReport(m)) {
                    receivedReport = getReportFromExtraInfo(m.extraInfo);
                    receiveTargetReport = receivedReport;
                    if (receivedReport == -100) receivedReport = m.report;
                }
                sendReport = m.snr;
                // [ИСПРАВЛЕНО] Используем FT8MessageClassifier вместо checkFunOrder
                int ord = FT8MessageClassifier.getLegacyCode(m);
                if (ord != -1) return ord;
            }
        }
        return -1;
    }

    private int getReportFromExtraInfo(String extraInfo) {
        try { return Integer.parseInt(extraInfo.replace("R", "").trim()); }
        catch (Exception e) { return -100; }
    }

    private boolean isExcludeMessage(Ft8Message msg) {
        return msg.getSequence() == sequential || msg.band != GeneralVariables.band || GeneralVariables.checkIsExcludeCallsign(msg.callsignFrom);
    }

    private void saveInterruptedQSO(String callsign, int functionOrder) {
        if (callsign == null || callsign.equals("CQ")) return;
        for (int i = interruptedQSOs.size() - 1; i >= 0; i--) {
            if (interruptedQSOs.get(i).callsign.equals(callsign)) {
                interruptedQSOs.remove(i);
                break;
            }
        }
        interruptedQSOs.add(new InterruptedQSO(callsign, functionOrder, sentTargetReport, receiveTargetReport));
        while (interruptedQSOs.size() > MAX_INTERRUPTED_QSO_COUNT) {
            interruptedQSOs.remove(0);
        }
        Log.d(TAG, "Saved interrupted QSO with " + callsign + " at functionOrder " + functionOrder);
    }

    private void cleanupInterruptedQSOs() {
        long now = System.currentTimeMillis();
        for (int i = interruptedQSOs.size() - 1; i >= 0; i--) {
            if (now - interruptedQSOs.get(i).timestamp > INTERRUPTED_QSO_MAX_AGE_MS) {
                interruptedQSOs.remove(i);
            }
        }
    }

    private boolean checkInterruptedQSOs(ArrayList<Ft8Message> messages) {
        for (Ft8Message msg : messages) {
            if (isExcludeMessage(msg)) continue;
            String fromCall = msg.getCallsignFrom();
            for (int i = interruptedQSOs.size() - 1; i >= 0; i--) {
                InterruptedQSO iq = interruptedQSOs.get(i);
                if (fromCall.equals(iq.callsign)) {
                    interruptedQSOs.remove(i);
                    Log.d(TAG, "Resuming interrupted QSO with " + fromCall + " from functionOrder " + iq.lastFunctionOrder);
                    int resumeOrder = Math.min(iq.lastFunctionOrder + 1, 5);
                    setTransmit(new TransmitCallsign(msg.i3, msg.n3, fromCall, msg.freq_hz, msg.getSequence(), msg.snr),
                            resumeOrder, msg.extraInfo);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean checkCQMeOrFollowCQMessage(ArrayList<Ft8Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Ft8Message msg = messages.get(i);
            if (isExcludeMessage(msg) || toCallsign == null) continue;
            if (GeneralVariables.checkIsMyCallsign(msg.getCallsignTo())
                    && msg.getCallsignFrom().equals(toCallsign.callsign)
                    // [ИСПРАВЛЕНО] Используем FT8MessageClassifier вместо checkFun5
                    && !FT8MessageClassifier.isSeventyThree(msg)) {
                // [ИСПРАВЛЕНО] Используем FT8MessageClassifier вместо checkFunOrder
                setTransmit(new TransmitCallsign(msg.i3, msg.n3, msg.getCallsignFrom(), msg.freq_hz, msg.getSequence(), msg.snr),
                        FT8MessageClassifier.getLegacyCode(msg) + 1, msg.extraInfo);
                return true;
            }
        }

        boolean isManualCallMode = (functionOrder != 6);
        for (int i = messages.size() - 1; i >= 0; i--) {
            Ft8Message msg = messages.get(i);
            if (isExcludeMessage(msg)) continue;
            // [ИСПРАВЛЕНО] Используем FT8MessageClassifier вместо checkFun5
            if (GeneralVariables.checkIsMyCallsign(msg.getCallsignTo()) && !FT8MessageClassifier.isSeventyThree(msg)) {
                if (isManualCallMode) {
                    // [ИСПРАВЛЕНО] Используем FT8MessageClassifier вместо checkFun2/checkFun3/checkFun4
                    if (FT8MessageClassifier.isReport(msg)
                            || FT8MessageClassifier.isRReport(msg)
                            || FT8MessageClassifier.isRR73(msg)) {
                        // [ИСПРАВЛЕНО] Используем FT8MessageClassifier вместо checkFunOrder
                        setTransmit(new TransmitCallsign(msg.i3, msg.n3, msg.getCallsignFrom(), msg.freq_hz, msg.getSequence(), msg.snr),
                                FT8MessageClassifier.getLegacyCode(msg) + 1, msg.extraInfo);
                        return true;
                    }
                    continue;
                }
                // [ИСПРАВЛЕНО] Используем FT8MessageClassifier вместо checkFunOrder
                setTransmit(new TransmitCallsign(msg.i3, msg.n3, msg.getCallsignFrom(), msg.freq_hz, msg.getSequence(), msg.snr),
                        FT8MessageClassifier.getLegacyCode(msg) + 1, msg.extraInfo);
                return true;
            }
        }

        if (ignoreCQForNextStep) {
            return false;
        }
        if (functionOrder == 6 && GeneralVariables.noReplyCount <= GeneralVariables.noReplyLimit) {
            return false;
        }
        if (cyclesWithoutBeingCalled >= CYCLES_WITHOUT_BEING_CALLED_LIMIT) {
            return false;
        }
        if (!GeneralVariables.autoCallFollow || toCallsign == null || toCallsign.haveTargetCallsign()) return false;

        for (int i = GeneralVariables.transmitMessages.size() - 1; i >= 0; i--) {
            Ft8Message msg = GeneralVariables.transmitMessages.get(i);
            if (isExcludeMessage(msg)) continue;
            if (msg.checkIsCQ() && ((GeneralVariables.autoFollowCQ) || GeneralVariables.callsignInFollow(msg.getCallsignFrom()))
                    && !GeneralVariables.checkQSLCallsign(msg.getCallsignFrom()) && !GeneralVariables.checkIsMyCallsign(msg.callsignFrom)) {
                resetTargetReport();
                setTransmit(new TransmitCallsign(msg.i3, msg.n3, msg.getCallsignFrom(), msg.freq_hz, msg.getSequence(), msg.snr), 1, msg.extraInfo);
                return true;
            }
        }
        return false;
    }

    public void updateQSlRecordList(int order, TransmitCallsign toCall) {
        if (toCall == null || toCall.callsign.equals("CQ")) return;
        QSLRecord record = GeneralVariables.qslRecordList.getRecordByCallsign(toCall.callsign);
        if (record == null) {
            toMaidenheadGrid = GeneralVariables.getGridByCallsign(toCallsign.callsign, databaseOpr);
            record = GeneralVariables.qslRecordList.addQSLRecord(new QSLRecord(messageStartTime, messageEndTime,
                    GeneralVariables.myCallsign, GeneralVariables.getMyMaidenhead4Grid(), toCallsign.callsign, toMaidenheadGrid,
                    sentTargetReport != -100 ? sentTargetReport : sendReport, receiveTargetReport != -100 ? receiveTargetReport : receivedReport,
                    "FT8", GeneralVariables.band, Math.round(GeneralVariables.getBaseFrequency())));
        }
        switch (order) {
            case 1: record.setToMaidenGrid(toMaidenheadGrid); record.setSendReport(sentTargetReport != -100 ? sentTargetReport : sendReport); GeneralVariables.qslRecordList.deleteIfSaved(record); break;
            case 2: case 3: record.setSendReport(sentTargetReport != -100 ? sentTargetReport : sendReport); record.setReceivedReport(receiveTargetReport != -100 ? receiveTargetReport : receivedReport); GeneralVariables.qslRecordList.deleteIfSaved(record); break;
            case 4: case 5: if (!record.saved) { doComplete(); record.saved = true; } break;
        }
    }

    private boolean checkDelayedReplies(ArrayList<Ft8Message> messages) {
        for (Ft8Message msg : messages) {
            if (isExcludeMessage(msg)) continue;
            for (int i = pendingQSOs.size() - 1; i >= 0; i--) {
                PendingQSO pq = pendingQSOs.get(i);
                if (GeneralVariables.checkIsMyCallsign(msg.getCallsignTo())
                        && msg.getCallsignFrom().equals(pq.callsign)
                        // [ИСПРАВЛЕНО] Используем FT8MessageClassifier вместо checkFun5
                        && !FT8MessageClassifier.isSeventyThree(msg)) {
                    pendingQSOs.remove(i);
                    Log.d(TAG, "Delayed reply from " + pq.callsign + " accepted. Resuming QSO.");
                    // [ИСПРАВЛЕНО] Используем FT8MessageClassifier вместо checkFunOrder
                    setTransmit(new TransmitCallsign(msg.i3, msg.n3, pq.callsign, msg.freq_hz, msg.getSequence(), msg.snr),
                            FT8MessageClassifier.getLegacyCode(msg) + 1, msg.extraInfo);
                    return true;
                }
            }
        }
        return false;
    }

    private ArrayList<String> getFallbackCandidatesFromDecodeHistory(ArrayList<Ft8Message> decodeHistory) {
        long now = System.currentTimeMillis();
        if (now - lastFallbackUpdate < FALLBACK_CACHE_REFRESH_MS && !decodeHistoryFallbackCache.isEmpty()) {
            return decodeHistoryFallbackCache;
        }

        Map<String, Integer> countMap = new HashMap<>();
        if (decodeHistory != null) {
            for (Ft8Message msg : decodeHistory) {
                if (msg == null || msg.getCallsignFrom() == null) continue;
                if (GeneralVariables.checkIsExcludeCallsign(msg.getCallsignFrom())) continue;
                if (GeneralVariables.checkIsMyCallsign(msg.getCallsignFrom())) continue;
                if (GeneralVariables.checkQSLCallsign(msg.getCallsignFrom())) continue;
                if (GeneralVariables.callsignInFollow(msg.getCallsignFrom())) continue;
                countMap.put(msg.getCallsignFrom(), countMap.getOrDefault(msg.getCallsignFrom(), 0) + 1);
            }
        }

        ArrayList<String> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : countMap.entrySet()) {
            if (entry.getValue() >= MIN_HEARD_COUNT_FALLBACK) {
                result.add(entry.getKey());
            }
        }
        Collections.sort(result, (a, b) -> Integer.compare(countMap.get(b), countMap.get(a)));

        decodeHistoryFallbackCache = result;
        lastFallbackUpdate = now;
        return result;
    }

    // [DX MODE] New method to handle multistream/multiple answers
    private void handleDxMultistream(ArrayList<Ft8Message> msgList) {
        ArrayList<Ft8Message> messages = new ArrayList<>(msgList);
        ConcurrentHashMap<Ft8Message, Boolean> localIsMyCall = new ConcurrentHashMap<>();

        // Filter messages: only those addressed to me, in current slot/band
        for (Ft8Message msg : messages) {
            if (msg.getSequence() == sequential || msg.band != GeneralVariables.band) continue;
            String from = msg.getCallsignFrom();
            String to = msg.getCallsignTo();
            if (GeneralVariables.checkIsExcludeCallsign(from)) continue;
            localIsMyCall.put(msg, GeneralVariables.checkIsMyCallsign(to));
        }

        // Collect all replies to my CQ in this slot
        ArrayList<Ft8Message> repliesToMe = new ArrayList<>();
        for (Ft8Message msg : messages) {
            // [ИСПРАВЛЕНО] Используем FT8MessageClassifier вместо checkFun5
            if (!Boolean.TRUE.equals(localIsMyCall.get(msg)) || FT8MessageClassifier.isSeventyThree(msg)) continue;
            repliesToMe.add(msg);
        }

        // If we have replies and we're in CQ mode
        if (!repliesToMe.isEmpty() && functionOrder == 6) {
            // Take the first reply as active QSO
            Ft8Message firstReply = repliesToMe.get(0);
            setTransmit(new TransmitCallsign(firstReply.i3, firstReply.n3,
                    firstReply.getCallsignFrom(), firstReply.freq_hz,
                    firstReply.getSequence(), firstReply.snr), 1, firstReply.extraInfo);

            // Add remaining replies to pending queue
            for (int i = 1; i < repliesToMe.size() && pendingQSOs.size() < MAX_PENDING_COUNT; i++) {
                Ft8Message reply = repliesToMe.get(i);
                // Avoid duplicates
                boolean alreadyPending = false;
                for (PendingQSO pq : pendingQSOs) {
                    if (pq.callsign.equals(reply.getCallsignFrom())) {
                        alreadyPending = true;
                        break;
                    }
                }
                if (!alreadyPending) {
                    pendingQSOs.add(new PendingQSO(
                            reply.getCallsignFrom(),
                            1, // Start from function 1
                            reply.getSequence()
                    ));
                    Log.d(TAG, "DX mode: added to pending queue: " + reply.getCallsignFrom());
                }
            }
            return; // Exit early, don't run standard logic
        }

        // If we're in an active QSO (functionOrder < 6), run standard logic for progression
        // but also check for new replies to add to queue
        int newOrder = checkFunctionOrdFromMessages(messages);
        if (newOrder != -1) {
            GeneralVariables.noReplyCount = 0;
            cyclesWithoutBeingCalled = 0;
            updateQSlRecordList(newOrder, toCallsign);

            if (newOrder == 1 || newOrder == 2) { resetTargetReport(); generateFun(); }
            functionOrder = newOrder + 1;
            mutableFunctions.postValue(functionList); mutableFunctionOrder.postValue(functionOrder);
            setCurrentFunctionOrder(functionOrder);
            return;
        }

        // Check for interrupted QSOs (standard fallback)
        for (Ft8Message msg : messages) {
            if (!Boolean.TRUE.equals(localIsMyCall.get(msg))) continue;
            String fromCall = msg.getCallsignFrom();
            for (int i = interruptedQSOs.size() - 1; i >= 0; i--) {
                if (fromCall.equals(interruptedQSOs.get(i).callsign)) {
                    interruptedQSOs.remove(i);
                    Log.d(TAG, "Resuming interrupted QSO with " + fromCall);
                    int resumeOrder = Math.min(interruptedQSOs.get(i).lastFunctionOrder + 1, 5);
                    setTransmit(new TransmitCallsign(msg.i3, msg.n3, fromCall, msg.freq_hz, msg.getSequence(), msg.snr),
                            resumeOrder, msg.extraInfo);
                    return;
                }
            }
        }

        // Standard fallback: check CQ, auto-follow, etc.
        checkCQMeOrFollowCQMessage(messages);

        // Age and clean pending QSOs
        for (int i = pendingQSOs.size() - 1; i >= 0; i--) {
            pendingQSOs.get(i).ageCycles++;
            if (pendingQSOs.get(i).ageCycles > MAX_PENDING_CYCLES) {
                pendingQSOs.remove(i);
            }
        }
        cleanupInterruptedQSOs();
        localIsMyCall.clear();
    }

    // ========================================================================
    // [DISABLED] Old auto-answer logic - commented out to avoid conflicts with DecisionEngine
    // All decision logic is now handled by DecisionEngine.evaluate() in MainViewModel
    // ========================================================================
    /*
    public void parseMessageToFunction(ArrayList<Ft8Message> msgList) {
        if (GeneralVariables.myCallsign.length() < 3 || msgList.isEmpty()) return;
        if (toCallsign == null) return;
        if (isTransmitting) return;

        for (Ft8Message msg : msgList) {
            if (msg.getSequence() != sequential || msg.band != GeneralVariables.band) continue;
            if (GeneralVariables.checkIsMyCallsign(msg.getCallsignTo())
                    && !msg.getCallsignFrom().equals(toCallsign.callsign)
                    && !GeneralVariables.checkFun5(msg.extraInfo)) {
                Log.d(TAG, "Direct call from " + msg.getCallsignFrom() + " detected! Switching from " + toCallsign.callsign);
                setTransmit(new TransmitCallsign(msg.i3, msg.n3,
                                msg.getCallsignFrom(), msg.freq_hz,
                                msg.getSequence(), msg.snr),
                        GeneralVariables.checkFunOrder(msg) + 1, msg.extraInfo);
                return;
            }
        }

        if (GeneralVariables.acceptDxCalls) {
            handleDxMultistream(msgList);
            return;
        }

        ArrayList<Ft8Message> messages = new ArrayList<>(msgList);
        ConcurrentHashMap<Ft8Message, Boolean> localIsMyCall = new ConcurrentHashMap<>();

        for (Ft8Message msg : messages) {
            if (msg.getSequence() == sequential || msg.band != GeneralVariables.band) continue;
            String from = msg.getCallsignFrom();
            String to = msg.getCallsignTo();
            if (GeneralVariables.checkIsExcludeCallsign(from)) continue;
            localIsMyCall.put(msg, GeneralVariables.checkIsMyCallsign(to));
        }

        int newOrder = checkFunctionOrdFromMessages(messages);
        boolean someoneCalledMe = false;
        if (newOrder != -1) {
            GeneralVariables.noReplyCount = 0;
            cyclesWithoutBeingCalled = 0;
            someoneCalledMe = true;
        }

        updateQSlRecordList(newOrder, toCallsign);

        if (newOrder == 5 || (functionOrder == 5 && newOrder == -1) ||
                (functionOrder == 4 && GeneralVariables.noReplyLimit > 0 && GeneralVariables.noReplyCount > GeneralVariables.noReplyLimit * 2) ||
                (functionOrder == 4 && checkTargetCallMe(messages) > 1) ||
                (functionOrder == 4 && GeneralVariables.noReplyLimit == 0 && GeneralVariables.noReplyCount > 20)) {
            if (functionOrder >= 2 && functionOrder <= 4 && toCallsign != null && !toCallsign.callsign.equals("CQ")) {
                saveInterruptedQSO(toCallsign.callsign, functionOrder);
            }
            resetToCQ(); checkCQMeOrFollowCQMessage(messages);
            setCurrentFunctionOrder(functionOrder); mutableFunctionOrder.postValue(functionOrder); return;
        }

        if (newOrder != -1) {
            if (newOrder == 1 || newOrder == 2) { resetTargetReport(); generateFun(); }
            functionOrder = newOrder + 1;
            mutableFunctions.postValue(functionList); mutableFunctionOrder.postValue(functionOrder);
            setCurrentFunctionOrder(functionOrder); return;
        }

        for (Ft8Message msg : messages) {
            if (!Boolean.TRUE.equals(localIsMyCall.get(msg))) continue;
            String fromCall = msg.getCallsignFrom();
            for (int i = interruptedQSOs.size() - 1; i >= 0; i--) {
                if (fromCall.equals(interruptedQSOs.get(i).callsign)) {
                    interruptedQSOs.remove(i);
                    Log.d(TAG, "Resuming interrupted QSO with " + fromCall + " from functionOrder " + interruptedQSOs.get(i).lastFunctionOrder);
                    int resumeOrder = Math.min(interruptedQSOs.get(i).lastFunctionOrder + 1, 5);
                    setTransmit(new TransmitCallsign(msg.i3, msg.n3, fromCall, msg.freq_hz, msg.getSequence(), msg.snr),
                            resumeOrder, msg.extraInfo);
                    return;
                }
            }
        }

        for (Ft8Message msg : messages) {
            if (!Boolean.TRUE.equals(localIsMyCall.get(msg)) || GeneralVariables.checkFun5(msg.extraInfo)) continue;
            String fromCall = msg.getCallsignFrom();
            for (int i = pendingQSOs.size() - 1; i >= 0; i--) {
                if (fromCall.equals(pendingQSOs.get(i).callsign)) {
                    pendingQSOs.remove(i);
                    Log.d(TAG, "Delayed reply from " + fromCall + " accepted. Resuming QSO.");
                    setTransmit(new TransmitCallsign(msg.i3, msg.n3, fromCall, msg.freq_hz, msg.getSequence(), msg.snr),
                            GeneralVariables.checkFunOrder(msg) + 1, msg.extraInfo);
                    someoneCalledMe = true;
                    return;
                }
            }
        }

        boolean isManualCallMode = (functionOrder != 6);
        Ft8Message bestReply = null;
        for (Ft8Message msg : messages) {
            if (!Boolean.TRUE.equals(localIsMyCall.get(msg)) || GeneralVariables.checkFun5(msg.extraInfo)) continue;
            String fromCall = msg.getCallsignFrom();
            if (isManualCallMode) {
                if (GeneralVariables.checkFun2(msg.extraInfo) || GeneralVariables.checkFun3(msg.extraInfo) || GeneralVariables.checkFun4(msg.extraInfo)) {
                    bestReply = msg;
                    break;
                }
            } else {
                bestReply = msg;
                break;
            }
        }

        if (bestReply != null && !ignoreCQForNextStep) {
            setTransmit(new TransmitCallsign(bestReply.i3, bestReply.n3, bestReply.getCallsignFrom(),
                            bestReply.freq_hz, bestReply.getSequence(), bestReply.snr),
                    GeneralVariables.checkFunOrder(bestReply) + 1, bestReply.extraInfo);
            someoneCalledMe = true;
            return;
        }

        if (!ignoreCQForNextStep && GeneralVariables.autoCallFollow && (toCallsign == null || !toCallsign.haveTargetCallsign())) {
            boolean canFollow = (functionOrder != 6) || (cyclesWithoutBeingCalled >= CYCLES_WITHOUT_BEING_CALLED_LIMIT);
            if (canFollow) {
                for (Ft8Message msg : GeneralVariables.transmitMessages) {
                    if (msg.band != GeneralVariables.band || !msg.checkIsCQ()) continue;
                    String fromCall = msg.getCallsignFrom();
                    if (!fromCall.equals(toCallsign == null ? "" : toCallsign.callsign)
                            && !GeneralVariables.checkQSLCallsign(fromCall)) {
                        resetTargetReport();
                        setTransmit(new TransmitCallsign(msg.i3, msg.n3, fromCall, msg.freq_hz, msg.getSequence(), msg.snr), 1, msg.extraInfo);
                        return;
                    }
                }
            }
        }

        if (someoneCalledMe) {
            cyclesWithoutBeingCalled = 0;
        } else {
            cyclesWithoutBeingCalled++;
        }

        if (cyclesWithoutBeingCalled >= CYCLES_WITHOUT_BEING_CALLED_LIMIT && functionOrder == 6) {
            Log.d(TAG, "No one called me for " + cyclesWithoutBeingCalled + " cycles in CQ mode");
            cyclesWithoutBeingCalled = 0;
            return;
        }

        if (!messages.get(0).isWeakSignal) GeneralVariables.noReplyCount++;

        if (GeneralVariables.noReplyLimit > 0 && GeneralVariables.noReplyCount >= GeneralVariables.noReplyLimit) {
            if (!getNewTargetCallsign(messages)) {
                ArrayList<String> fallbackCandidates = getFallbackCandidatesFromDecodeHistory(GeneralVariables.transmitMessages);
                if (!fallbackCandidates.isEmpty()) {
                    String nextTarget = fallbackCandidates.get(0);
                    float fallbackFreq = GeneralVariables.getBaseFrequency();
                    if (GeneralVariables.transmitMessages != null) {
                        for (Ft8Message m : GeneralVariables.transmitMessages) {
                            if (m != null && m.getCallsignFrom().equals(nextTarget)) {
                                fallbackFreq = m.freq_hz;
                                break;
                            }
                        }
                    }
                    functionOrder = 1;
                    toCallsign.callsign = nextTarget;
                    setTransmit(new TransmitCallsign(0, 0, nextTarget, fallbackFreq, sequential, -100), 1, "");
                    Log.d(TAG, "Fallback: switching to history candidate " + nextTarget);
                } else {
                    functionOrder = 6;
                    if (toCallsign != null) toCallsign.callsign = "CQ";
                }
            }
            generateFun();
            setCurrentFunctionOrder(functionOrder);
            mutableToCallsign.postValue(toCallsign);
            mutableFunctionOrder.postValue(functionOrder);
        }

        for (int i = pendingQSOs.size() - 1; i >= 0; i--) {
            pendingQSOs.get(i).ageCycles++;
            if (pendingQSOs.get(i).ageCycles > MAX_PENDING_CYCLES) {
                pendingQSOs.remove(i);
            }
        }
        cleanupInterruptedQSOs();

        if (ignoreCQForNextStep) {
            ignoreCQForNextStep = false;
        }
        localIsMyCall.clear();
    }
    */
    // ========================================================================
    // [END DISABLED] Old auto-answer logic
    // ========================================================================

    public boolean getNewTargetCallsign(ArrayList<Ft8Message> messages) {
        if (toCallsign == null) return false;
        for (Ft8Message m : messages) {
            if (m.band != GeneralVariables.band || !m.checkIsCQ()) continue;
            if (!m.getCallsignFrom().equals(toCallsign.callsign) && !GeneralVariables.checkQSLCallsign(m.getCallsignFrom())) {
                functionOrder = 1; toCallsign.callsign = m.getCallsignFrom(); return true;
            }
        }
        return false;
    }

    public boolean isSynFrequency() { return GeneralVariables.synFrequency; }
    public boolean isActivated() { return activated; }
    public void setActivated(boolean activated) { this.activated = activated; if (!activated) setTransmitting(false); mutableIsActivated.postValue(activated); }
    public boolean isTransmitting() { return isTransmitting; }
    public void setTransmitting(boolean transmitting) {
        if (GeneralVariables.myCallsign.length() < 3 && transmitting) { ToastMessage.show(GeneralVariables.getStringFromResource(R.string.callsign_error)); return; }
        if (!transmitting && audioTrack != null) {
            if (audioTrack.getState() != AudioTrack.STATE_UNINITIALIZED) audioTrack.pause();
            if (onDoTransmitted != null) onDoTransmitted.onAfterTransmit(getFunctionCommand(functionOrder), functionOrder);
        }
        mutableIsTransmitting.postValue(transmitting); isTransmitting = transmitting;
    }
    public void restTransmitting() { if (GeneralVariables.myCallsign.length() < 3) return; int i3 = GenerateFT8.checkI3ByCallsign(GeneralVariables.myCallsign); setTransmit(new TransmitCallsign(i3, 0, "CQ", UtcTimer.getNowSequential()), 6, ""); }
    public void resetTargetReport() { receiveTargetReport = -100; sentTargetReport = -100; }
    public void resetToCQ() {
        resetTargetReport();
        // [DX MODE] Clear pending queue when manually resetting to CQ
        if (GeneralVariables.acceptDxCalls) {
            pendingQSOs.clear();
        }
        // [END DX MODE]
        if (toCallsign == null) { int i3 = GenerateFT8.checkI3ByCallsign(GeneralVariables.myCallsign); setTransmit(new TransmitCallsign(i3, 0, "CQ", (UtcTimer.getNowSequential() + 1) % 2), 6, ""); }
        else { functionOrder = 6; toCallsign.callsign = "CQ"; mutableToCallsign.postValue(toCallsign); generateFun(); }
    }

    // [ARCHITECTURE FIX] setTimer_sec is now a no-op since autonomous timer is disabled.
    // Kept for API compatibility but does nothing.
    /*
    public void setTimer_sec(int sec) { utcTimer.setTime_sec(sec); }
    */
    public void setTimer_sec(int sec) {
        Log.d(TAG, "[setTimer_sec] Called with sec=" + sec + " (no-op, autonomous timer disabled)");
    }

    public boolean isTransmitFreeText() { return transmitFreeText; }
    public void setFreeText(String freeText) { this.freeText = freeText; }
    public void setTransmitFreeText(boolean transmitFreeText) {
        this.transmitFreeText = transmitFreeText;
        if (transmitFreeText) ToastMessage.show(GeneralVariables.getStringFromResource(R.string.trans_free_text_mode));
        else ToastMessage.show(GeneralVariables.getStringFromResource(R.string.trans_standard_messge_mode));
    }

    private static class DoTransmitRunnable implements Runnable {
        FT8TransmitSignal transmitSignal;
        public DoTransmitRunnable(FT8TransmitSignal transmitSignal) { this.transmitSignal = transmitSignal; }

        @SuppressLint("DefaultLocale") @Override public void run() {
            Log.w(TAG, "[RUNNABLE] ========================================");
            Log.w(TAG, "[RUNNABLE] DoTransmitRunnable.run() STARTED");

            if (transmitSignal.functionOrder == 1 || transmitSignal.functionOrder == 2) {
                transmitSignal.messageStartTime = UtcTimer.getSystemTime();
            }
            if (transmitSignal.messageStartTime == 0) {
                transmitSignal.messageStartTime = UtcTimer.getSystemTime();
            }

            Ft8Message msg = transmitSignal.transmitFreeText
                    ? new Ft8Message("CQ", GeneralVariables.myCallsign, transmitSignal.freeText)
                    : transmitSignal.getFunctionCommand(transmitSignal.functionOrder);

            if (transmitSignal.transmitFreeText) { msg.i3 = 0; msg.n3 = 0; }
            msg.modifier = GeneralVariables.toModifier;

            Log.w(TAG, "[RUNNABLE] Message created: " + msg.getMessageText());
            Log.w(TAG, "[RUNNABLE] Calling onBeforeTransmit() — THIS ACTIVATES PTT!");

            if (transmitSignal.onDoTransmitted != null) {
                transmitSignal.onDoTransmitted.onBeforeTransmit(msg, transmitSignal.functionOrder);
                Log.w(TAG, "[RUNNABLE] onBeforeTransmit() completed — PTT should be ON now");
            } else {
                Log.w(TAG, "[RUNNABLE] onDoTransmitted is NULL — PTT will NOT be activated!");
            }

            transmitSignal.isTransmitting = true;
            transmitSignal.mutableIsTransmitting.postValue(true);
            transmitSignal.mutableTransmittingMessage.postValue(String.format(" (%.0fHz) %s", GeneralVariables.getBaseFrequency(), msg.getMessageText()));

            Log.w(TAG, "[RUNNABLE] Sleeping for PTT delay: " + GeneralVariables.pttDelay + "ms");
            try {
                Thread.sleep(GeneralVariables.pttDelay);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            Log.w(TAG, "[RUNNABLE] Calling playFT8Signal() — AUDIO GENERATION STARTS");
            transmitSignal.playFT8Signal(msg);
            Log.w(TAG, "[RUNNABLE] ========================================");
        }
    }
}