//MainViewModel.java
package com.bg7yoz.ft8cn;

/**
 * MainViewModel class for FT8 signal decoding and related data.
 * Lives for the entire APP lifecycle.
 * @author BG7YOZ
 * @date 2022.8.22
 *
 * ============================================================================
 *  INSTRUCTION FOR AI CODE ASSISTANTS (Qwen, Copilot, Claude, etc.):
 * ============================================================================
 * When modifying this file, PLEASE PRESERVE ALL EXISTING COMMENTS, especially:
 * 1. Comments marked with [NEW], [FIX], [CHANGED], [STATE MACHINE], etc.
 * 2. Block comments explaining architecture decisions (like this one)
 * 3. Inline comments next to complex logic or state transitions
 * 4. Russian-language comments - they contain important context for the author
 *
 * WHY THIS MATTERS:
 * - Comments document WHY code exists, not just WHAT it does
 * - State machine logic depends on precise transition conditions
 * - Future maintainers (human or AI) need to understand the architecture
 * - Removing comments breaks the "conversation history" of the codebase
 *
 * BEFORE DELETING ANY COMMENT, ask: "Does this explain a design decision,
 * a bug fix, or a state transition rule?" If yes -> KEEP IT.
 * ============================================================================
 */

import static com.bg7yoz.ft8cn.GeneralVariables.getStringFromResource;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.RectF;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.os.Build;              // [NEW] For Android version check
import android.view.WindowManager;    // [NEW] For window flag management

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.lifecycle.Observer;

import com.bg7yoz.ft8cn.rigs.IcomRigConstant;
import com.bg7yoz.ft8cn.rigs.OnConnectReceiveData;
import com.bg7yoz.ft8cn.callsign.CallsignDatabase;
import com.bg7yoz.ft8cn.callsign.CallsignInfo;
import com.bg7yoz.ft8cn.callsign.OnAfterQueryCallsignLocation;
import com.bg7yoz.ft8cn.connector.BluetoothRigConnector;
import com.bg7yoz.ft8cn.connector.CableConnector;
import com.bg7yoz.ft8cn.connector.CableSerialPort;
import com.bg7yoz.ft8cn.connector.ConnectMode;
import com.bg7yoz.ft8cn.connector.FlexConnector;
import com.bg7yoz.ft8cn.connector.IComWifiConnector;
import com.bg7yoz.ft8cn.connector.X6100Connector;
import com.bg7yoz.ft8cn.decisions.StationState;
import com.bg7yoz.ft8cn.database.ControlMode;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.database.OnAfterQueryFollowCallsigns;
import com.bg7yoz.ft8cn.database.OperationBand;

//import com.bg7yoz.ft8cn.database.SecureStorage;

import com.bg7yoz.ft8cn.protocol.FT8MessageClassifier;
import com.bg7yoz.ft8cn.decisions.Criterion;
import com.bg7yoz.ft8cn.decisions.DecisionContext;
import com.bg7yoz.ft8cn.decisions.DecisionEngine;
import com.bg7yoz.ft8cn.decisions.StationAction;
import com.bg7yoz.ft8cn.flex.FlexRadio;
import com.bg7yoz.ft8cn.flex.RadioTcpClient;
import com.bg7yoz.ft8cn.ft8listener.FT8SignalListener;
import com.bg7yoz.ft8cn.ft8listener.OnFt8Listen;
import com.bg7yoz.ft8cn.ft8transmit.FT8TransmitSignal;
import com.bg7yoz.ft8cn.ft8transmit.OnDoTransmitted;
import com.bg7yoz.ft8cn.ft8transmit.OnTransmitSuccess;
import com.bg7yoz.ft8cn.ft8transmit.TransmitCallsign;
import com.bg7yoz.ft8cn.html.LogHttpServer;
import android.database.Cursor;
import com.bg7yoz.ft8cn.icom.WifiRig;
import com.bg7yoz.ft8cn.log.QSLCallsignRecord;
import com.bg7yoz.ft8cn.log.QSLRecord;
import com.bg7yoz.ft8cn.log.SWLQsoList;
import com.bg7yoz.ft8cn.log.ThirdPartyService;
import com.bg7yoz.ft8cn.rigs.BaseRig;
import com.bg7yoz.ft8cn.rigs.BaseRigOperation;
import com.bg7yoz.ft8cn.rigs.ElecraftRig;
import com.bg7yoz.ft8cn.rigs.Flex6000Rig;
import com.bg7yoz.ft8cn.rigs.FlexNetworkRig;
import com.bg7yoz.ft8cn.rigs.GuoHeQ900Rig;
import com.bg7yoz.ft8cn.rigs.IcomRig;
import com.bg7yoz.ft8cn.rigs.InstructionSet;
import com.bg7yoz.ft8cn.rigs.KenwoodKT90Rig;
import com.bg7yoz.ft8cn.rigs.KenwoodTS2000Rig;
import com.bg7yoz.ft8cn.rigs.KenwoodTS570Rig;
import com.bg7yoz.ft8cn.rigs.KenwoodTS590Rig;
import com.bg7yoz.ft8cn.rigs.OnRigStateChanged;
import com.bg7yoz.ft8cn.rigs.TrUSDXRig;
import com.bg7yoz.ft8cn.rigs.Wolf_sdr_450Rig;
import com.bg7yoz.ft8cn.rigs.XieGu6100NetRig;
import com.bg7yoz.ft8cn.rigs.XieGu6100Rig;
import com.bg7yoz.ft8cn.rigs.XieGuRig;
import com.bg7yoz.ft8cn.rigs.Yaesu2Rig;
import com.bg7yoz.ft8cn.rigs.Yaesu2_847Rig;
import com.bg7yoz.ft8cn.rigs.Yaesu38Rig;
import com.bg7yoz.ft8cn.rigs.Yaesu38_450Rig;
import com.bg7yoz.ft8cn.rigs.Yaesu39Rig;
import com.bg7yoz.ft8cn.rigs.YaesuDX10Rig;
import com.bg7yoz.ft8cn.spectrum.SpectrumListener;
import com.bg7yoz.ft8cn.timer.OnUtcTimer;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.ui.ToastMessage;
import com.bg7yoz.ft8cn.wave.HamRecorder;
import com.bg7yoz.ft8cn.wave.OnGetVoiceDataDone;
import com.bg7yoz.ft8cn.x6100.X6100Radio;
import com.bg7yoz.ft8cn.service.RecordingForegroundService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class MainViewModel extends ViewModel {
    private static final String TAG = "ft8cn MainViewModel";
    public boolean configIsLoaded = false;

    private static MainViewModel viewModel = null;

    // NTP sync time tracking (event-driven, after transmit)
    private long lastNtpSyncTime = 0;
    private static final long NTP_SYNC_INTERVAL_MS = 5 * 60 * 1000L;

    // Battery low threshold for transmit blocking
    private static final int BATTERY_LOW_THRESHOLD_PERCENT = 1;

    // [FIX] Prevent duplicate state machine evaluations in the same slot
    // This can happen when afterDecode() is called multiple times (e.g., normal + deep decode)
    private long lastEvaluatedSlot = -1;

    // USB auto-connect receiver
    private BroadcastReceiver usbReceiver;

    public final ArrayList<Ft8Message> ft8Messages = new ArrayList<>();
    public UtcTimer utcTimer;

    public DatabaseOpr databaseOpr;

    public MutableLiveData<Integer> mutable_Decoded_Counter = new MutableLiveData<>();
    public int currentDecodeCount = 0;
    public MutableLiveData<ArrayList<Ft8Message>> mutableFt8MessageList = new MutableLiveData<>();
    public MutableLiveData<Long> timerSec = new MutableLiveData<>();
    public MutableLiveData<Boolean> mutableIsRecording = new MutableLiveData<>();
    public MutableLiveData<Boolean> mutableHamRecordIsRunning = new MutableLiveData<>();
    public MutableLiveData<Float> mutableTimerOffset = new MutableLiveData<>();
    public MutableLiveData<Boolean> mutableIsDecoding = new MutableLiveData<>();
    public ArrayList<Ft8Message> currentMessages = null;

    public MutableLiveData<Boolean> mutableIsFlexRadio = new MutableLiveData<>();
    public MutableLiveData<Boolean> mutableIsXieguRadio = new MutableLiveData<>();

    // Rig connection status
    public MutableLiveData<String> rigStatusText = new MutableLiveData<>("Disconnected");

    // === Persistent spectrum state (survives fragment switches) ===
    public final List<RectF> persistentOccupiedZones = new ArrayList<>();
    public int persistentOccupiedZonesAge = 0;
    public static final int ZONE_PERSIST_CYCLES = 8;
    // ============================================================

    private final ExecutorService getQTHThreadPool = Executors.newCachedThreadPool();
    private final ExecutorService sendWaveDataThreadPool = Executors.newCachedThreadPool();
    private final GetQTHRunnable getQTHRunnable = new GetQTHRunnable(this);
    private final SendWaveDataRunnable sendWaveDataRunnable = new SendWaveDataRunnable();

    // Shared log generation variables
    public MutableLiveData<String> mutableShareInfo = new MutableLiveData<>("");
    public MutableLiveData<Integer> mutableSharePosition = new MutableLiveData<>(0);
    public MutableLiveData<Boolean> mutableShareRunning = new MutableLiveData<>(false);
    public MutableLiveData<Integer> mutableShareCount = new MutableLiveData<>(0);
    public MutableLiveData<Boolean> mutableImportShareRunning = new MutableLiveData<>(false);

    public HamRecorder hamRecorder;
    public FT8SignalListener ft8SignalListener;
    public FT8TransmitSignal ft8TransmitSignal;
    public SpectrumListener spectrumListener;
    public boolean markMessage = true;

    public OperationBand operationBand = null;

    private SWLQsoList swlQsoList = new SWLQsoList();

    public MutableLiveData<ArrayList<CableSerialPort.SerialPort>> mutableSerialPorts = new MutableLiveData<>();
    private ArrayList<CableSerialPort.SerialPort> serialPorts;
    public BaseRig baseRig;

    // === Transmission Watchdog ===
    private Handler transmissionWatchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable transmissionWatchdogRunnable;
    private static final long WATCHDOG_CHECK_INTERVAL_MS = 10000;
    // ===============================

    // ========================================================================
    // [STATE MACHINE] Context for My Station State Management
    // ========================================================================

    public static class StationContext {
        // Current state triple: (opMode, subState, step)
        public StationState.OperationalMode opMode = StationState.OperationalMode.OPERATING;
        public StationState.OperatingSubState subState = StationState.OperatingSubState.SEEKING;
        public StationState.DialogueStep step = StationState.DialogueStep.IDLE;

        // Dialogue tracking
        public String currentTarget = "";  // Callsign of current dialogue partner
        public Set<String> recentTargets = new HashSet<>(); // For SOFT_FINISH resume
        public int noReplyCount = 0;       // Consecutive slots without reply
        public long lastReplySlot = 0;     // UTC slot of last received reply

        // Configuration flags (mirrors GeneralVariables for quick access)
        public boolean dxModeEnabled = false;  // Allow pileup queueing
        public boolean userOverrideActive = false;
        // [NEW] Flag: manual CQ request from UI
        // This flag is set by UI and processed by evaluateStateMachine()
        public boolean manualCQRequested;

        // === Helper methods for state transitions ===

        /**
         * Reset to SEEKING state - called when dialogue fails or completes.
         * [STATE MACHINE] This is the "default" state when not in active QSO.
         */
        public void resetToSeeking() {
            subState = StationState.OperatingSubState.SEEKING;
            step = StationState.DialogueStep.IDLE;
            currentTarget = "";
            noReplyCount = 0;
            userOverrideActive = false; // [FIX] Clear override flag
            Log.d(TAG, "State: resetToSeeking()");
        }

        /**
         * Enter SOFT_FINISH state - stop transmitting but listen for resume.
         * [STATE MACHINE] Used after QSO completion to allow graceful re-entry.
         * Saves current target to recentTargets for potential resume.
         */
        public void enterSoftFinish() {
            if (!currentTarget.isEmpty()) {
                recentTargets.add(currentTarget);
                Log.d(TAG, "State: added " + currentTarget + " to recentTargets for SOFT_FINISH");
            }
            subState = StationState.OperatingSubState.SOFT_FINISH;
            step = StationState.DialogueStep.IDLE;
            currentTarget = "";
            Log.d(TAG, "State: entered SOFT_FINISH");
        }
        // [NEW] Reset manual CQ request flag
        public void resetManualCQRequest() {
            this.manualCQRequested = false;
        }

        /**
         * Check if we're currently in an active dialogue.
         * [STATE MACHINE] Convenience method for condition checks.
         */
        public boolean isInDialogue() {
            return subState == StationState.OperatingSubState.IN_DIALOGUE;
        }
    }
    // ========================================================================
    // [END STATE MACHINE] Context
    // ========================================================================

    // [STATE MACHINE] Instance of the context - holds current state
    public StationContext stationContext;

    // [DECISION ENGINE] Core decision-making module (Phase 1)
    private DecisionEngine decisionEngine;

    private final OnRigStateChanged onRigStateChanged = new OnRigStateChanged() {
        @Override
        public void onDisconnected() {
            ToastMessage.show(getStringFromResource(R.string.disconnect_rig));
            updateRigStatus();
        }

        @Override
        public void onConnected() {
            ToastMessage.show(getStringFromResource(R.string.connected_rig));
            updateRigStatus();
        }

        @Override
        public void onPttChanged(boolean isOn) {}

        @Override
        public void onFreqChanged(long freq) {
            ToastMessage.show(String.format(getStringFromResource(R.string.current_frequency)
                    , BaseRigOperation.getFrequencyAllInfo(freq)));
            GeneralVariables.band = freq;
            GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(freq);
            GeneralVariables.mutableBandChange.postValue(GeneralVariables.bandListIndex);
            databaseOpr.getAllQSLCallsigns();

            // === TUNE on RADIO frequency change (with FT8-safe timing) ===
            if (GeneralVariables.sendTuneOnFreqChange && baseRig != null && baseRig.isConnected()) {
                scheduleTuneCommand();
            }
            // ===============================================================
        }

        @Override
        public void onRunError(String message) {
            ToastMessage.show(String.format(getStringFromResource(R.string.radio_communication_error), message));
        }
    };

    public MutableLiveData<Integer> mutableTransmitMessagesCount = new MutableLiveData<>();

    public boolean deNoise = false;

    public boolean logListShowCallsign = false;
    public String queryKey = "";
    public int queryFilter = 0;
    public MutableLiveData<Integer> mutableQueryFilter = new MutableLiveData<>();
    public ArrayList<QSLCallsignRecord> callsignRecords = new ArrayList<>();

    public final LogHttpServer httpServer;

    public static MainViewModel getInstance(ViewModelStoreOwner owner) {
        if (viewModel == null) {
            viewModel = new ViewModelProvider(owner).get(MainViewModel.class);
        }
        return viewModel;
    }

    public Ft8Message getFt8Message(int position) {
        return Objects.requireNonNull(ft8Messages.get(position));
    }

    private boolean isBatteryTooLow(Context context) {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = context.registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    float batteryPct = (level / (float) scale) * 100f;
                    return batteryPct < BATTERY_LOW_THRESHOLD_PERCENT;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Battery check failed: " + e.getMessage());
        }
        return false;
    }

    public MainViewModel() {
        // [FIX-2026-05-17] Initialize callsignDatabase BEFORE initWorkedEntitiesFromQSL
        // This ensures we can query DXCC/CQ/ITU info for each callsign in the log
        GeneralVariables.callsignDatabase = CallsignDatabase.getInstance(
                GeneralVariables.getMainContext(), "callsigns.db", 19);
        Log.d("DXCC_INIT", "callsignDatabase initialized: " + (GeneralVariables.callsignDatabase != null));

        databaseOpr = DatabaseOpr.getInstance(GeneralVariables.getMainContext(), "data.db");
        mutableIsDecoding.postValue(false);

        // === Initialize HamRecorder FIRST ===
        hamRecorder = new HamRecorder(null);
        // [NEW] Start foreground service to keep recording alive in background
        // Must be called AFTER hamRecorder.startRecord() for reliable audio capture
        RecordingForegroundService.start(GeneralVariables.getMainContext());
        Log.d(TAG, "Foreground recording service started");

        hamRecorder.startRecord();

        mutableIsFlexRadio.setValue(false);
        mutableIsXieguRadio.setValue(false);

        // [FIX-2026-05-17] Now callsignDatabase is ready, so this will work correctly
        initWorkedEntitiesFromQSL();

        utcTimer = new UtcTimer(10, false, new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) {}
            @Override
            public void doOnSecTimer(long utc) {
                timerSec.postValue(utc);
                mutableIsRecording.postValue(hamRecorder.isRunning());
                mutableHamRecordIsRunning.postValue(hamRecorder.isRunning());
            }
        });
        utcTimer.start();

        UtcTimer.syncTime(null);
        lastNtpSyncTime = System.currentTimeMillis();

        mutableFt8MessageList.setValue(ft8Messages);

        // [CHANGED] Initialize library BEFORE creating FT8SignalListener
        // Load ft8cn_dx or ft8cn_std based on acceptDxCalls setting
        try {
            String libName = GeneralVariables.acceptDxCalls ? "ft8cn_dx" : "ft8cn_std";
            System.loadLibrary(libName);
            Log.d(TAG, "Loaded native library: " + libName);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load library: " + e.getMessage());
            // Fallback: try to load standard library
            try {
                System.loadLibrary("ft8cn_std");
                Log.d(TAG, "Fallback: loaded ft8cn_std");
            } catch (UnsatisfiedLinkError e2) {
                Log.e(TAG, "Fallback failed: " + e2.getMessage());
                // Do not throw exception to avoid immediate crash
            }
        }

        // [STATE MACHINE] Initialize station context with current settings
        stationContext = new StationContext();
        stationContext.dxModeEnabled = GeneralVariables.acceptDxCalls;
        Log.d(TAG, "State Machine: initialized with dxModeEnabled=" + stationContext.dxModeEnabled);

        // [DECISION ENGINE] Initialize decision module
        decisionEngine = new DecisionEngine();
        Log.d(TAG, "Decision Engine: initialized");

        ft8SignalListener = new FT8SignalListener(databaseOpr, new OnFt8Listen() {
            @Override
            public void beforeListen(long utc) {
                mutableIsDecoding.postValue(true);
            }

            @Override
            public void afterDecode(long utc, float time_sec, int sequential,
                                    ArrayList<Ft8Message> messages, boolean isDeep) {
                if (messages.size() == 0) return;

                // [NEW] Логируем начало цикла декодирования
                Log.d(TAG, String.format("[DECODE] === Slot %d | Messages: %d | Deep: %s ===",
                        sequential, messages.size(), isDeep));

                // [NEW] Логируем каждое декодированное сообщение
                for (Ft8Message msg : messages) {
                    String from = msg.getCallsignFrom() != null ? msg.getCallsignFrom() : "?";
                    String to = msg.getCallsignTo() != null ? msg.getCallsignTo() : "?";
                    String extra = msg.extraInfo != null ? msg.extraInfo : "";
                    int snr = msg.snr;
                    float freq = msg.freq_hz;

                    // Определяем тип сообщения через FT8MessageClassifier (единая точка правды)
                    String msgType;
                    if (msg.checkIsCQ()) {
                        msgType = "CQ";
                    } else if (FT8MessageClassifier.isSeventyThree(msg)) {
                        msgType = "73";
                    } else if (FT8MessageClassifier.isRR73(msg)) {
                        msgType = "RR73";
                    } else if (FT8MessageClassifier.isRReport(msg)) {
                        msgType = "R-REPORT";
                    } else if (FT8MessageClassifier.isReport(msg)) {
                        msgType = "REPORT";
                    } else if (FT8MessageClassifier.isGrid(msg)) {
                        msgType = "GRID";
                    } else {
                        msgType = "UNKNOWN";
                    }

                    // Проверяем, адресовано ли сообщение нам
                    boolean toMe = GeneralVariables.checkIsMyCallsign(to);
                    String marker = toMe ? " → US" : "";

                    Log.d(TAG, String.format("[DECODE] %s → %s%s | %-8s | %-6s | SNR=%+d freq=%.0fHz",
                            from, to, marker, msgType,
                            extra.isEmpty() ? "(empty)" : extra,
                            snr, freq));
                }

                synchronized (ft8Messages) {
                    ft8Messages.addAll(messages);
                }
                GeneralVariables.deleteArrayListMore(ft8Messages);
                mutableFt8MessageList.postValue(ft8Messages);
                mutableTimerOffset.postValue(time_sec);

                // === [STATE MACHINE] Update World Model & Evaluate State ===
                // This is the entry point for state machine evaluation.
                // We update the database's world model first, then run state logic.
                for (Ft8Message msg : messages) {
                    // Update station record in World Model (RAM + async DB save)
                    // Parameters: message, QTH, DXCC, ITU, CQ, bearing
                    // We pass minimal data here; full enrichment happens in DatabaseOpr
                    databaseOpr.updateStationFromMessage(msg, msg.maidenGrid, null, 0, 0, 0);
                }
                // Evaluate state transitions based on fresh messages and current context
                evaluateStateMachine(messages, UtcTimer.getNowSequential());
                //evaluateStateMachine(messages, sequential);
                findIncludedCallsigns(messages);
                // ============================================================

                // findIncludedCallsigns(messages); // [DEBUG] Temporarily disabled for testing
    /* old logic
    if (!ft8TransmitSignal.isTransmitting()
            && !isDeep
            && (ft8SignalListener.timeSec + GeneralVariables.pttDelay
            + GeneralVariables.transmitDelay <= 2000)) {
        ft8TransmitSignal.parseMessageToFunction(messages);
    }
    */

                currentMessages = messages;
                currentDecodeCount = isDeep ? currentDecodeCount + messages.size() : messages.size();
                mutableIsDecoding.postValue(false);

                getQTHRunnable.messages = messages;
                getQTHThreadPool.execute(getQTHRunnable);

                mutable_Decoded_Counter.postValue(currentDecodeCount);

                if (GeneralVariables.saveSWLMessage) {
                    databaseOpr.writeMessage(messages);
                }
                if (GeneralVariables.saveSWL_QSO) {
                    swlQsoList.findSwlQso(messages, ft8Messages, new SWLQsoList.OnFoundSwlQso() {
                        @Override
                        public void doFound(QSLRecord record) {
                            databaseOpr.addSWL_QSO(record);
                            ToastMessage.show(record.swlQSOInfo());
                        }
                    });
                }
                getCallsignAndGrid(messages);
            }
        });

        ft8SignalListener.setOnWaveDataListener(new FT8SignalListener.OnWaveDataListener() {
            @Override
            public void getVoiceData(int duration, boolean afterDoneRemove, OnGetVoiceDataDone getVoiceDataDone) {
                hamRecorder.getVoiceData(duration, afterDoneRemove, getVoiceDataDone);
            }
        });

        ft8SignalListener.startListen();

        // === Create SpectrumListener AFTER hamRecorder is running ===
        spectrumListener = new SpectrumListener(hamRecorder);

        ft8TransmitSignal = new FT8TransmitSignal(databaseOpr, new OnDoTransmitted() {
            private boolean needControlSco() {
                if (GeneralVariables.connectMode == ConnectMode.NETWORK) return false;
                if (GeneralVariables.controlMode != ControlMode.CAT) return true;
                return baseRig != null && !baseRig.supportWaveOverCAT();
            }

            @Override
            public void onBeforeTransmit(Ft8Message message, int functionOder) {
                //Log.d(TAG, "=== onBeforeTransmit DEBUG ===");
                //Log.d(TAG, "controlMode=" + GeneralVariables.controlMode + "needControlSco=" + needControlSco() + "supportTransmitOverCAT=" + supportTransmitOverCAT());
                if (GeneralVariables.controlMode == ControlMode.CAT
                        || GeneralVariables.controlMode == ControlMode.RTS
                        || GeneralVariables.controlMode == ControlMode.DTR) {
                    if (baseRig != null) {
                        //Log.d(TAG, "Calling baseRig.setPTT(true)");
                        if (needControlSco()) stopSco();
                        baseRig.setPTT(true);
                    } else {
                        Log.e(TAG, "baseRig is NULL, cannot set PTT");
                    }
                } else {
                    Log.w(TAG, "PTT blocked: controlMode=" + GeneralVariables.controlMode);
                }
                if (isBatteryTooLow(GeneralVariables.getMainContext())) {
                    ToastMessage.show("Transmit blocked: Low battery < " + BATTERY_LOW_THRESHOLD_PERCENT + "%");
                    return;
                }
                if (GeneralVariables.connectMode == ConnectMode.USB_CABLE) {
                    if (baseRig != null && !baseRig.isConnected()) {
                        ToastMessage.show("Transmit blocked: USB device disconnected");
                        return;
                    }
                }
                if (GeneralVariables.controlMode == ControlMode.CAT
                        || GeneralVariables.controlMode == ControlMode.RTS
                        || GeneralVariables.controlMode == ControlMode.DTR) {
                    if (baseRig != null) {
                        if (needControlSco()) stopSco();
                        baseRig.setPTT(true);
                    }
                }
                if (ft8TransmitSignal.isActivated()) {
                    GeneralVariables.transmitMessages.add(message);
                    mutableTransmitMessagesCount.postValue(1);
                }
            }

            @Override
            public void onAfterTransmit(Ft8Message message, int functionOder) {
                if (GeneralVariables.controlMode == ControlMode.CAT
                        || GeneralVariables.controlMode == ControlMode.RTS
                        || GeneralVariables.controlMode == ControlMode.DTR) {
                    if (baseRig != null) {
                        baseRig.setPTT(false);
                        if (needControlSco()) startSco();
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastNtpSyncTime >= NTP_SYNC_INTERVAL_MS) {
                    UtcTimer.syncTime(null);
                    lastNtpSyncTime = now;
                }
            }

            @Override
            public void onTransmitByWifi(Ft8Message msg) {
                if (GeneralVariables.connectMode == ConnectMode.NETWORK && baseRig != null && baseRig.isConnected()) {
                    sendWaveDataRunnable.baseRig = baseRig;
                    sendWaveDataRunnable.message = msg;
                    sendWaveDataThreadPool.execute(sendWaveDataRunnable);
                }
            }

            @Override
            public boolean supportTransmitOverCAT() {
                if (GeneralVariables.controlMode != ControlMode.CAT) return false;
                if (baseRig == null) return false;
                return baseRig.isConnected() && baseRig.supportWaveOverCAT();
            }

            @Override
            public void onTransmitOverCAT(Ft8Message msg) {
                if (supportTransmitOverCAT()) {
                    sendWaveDataRunnable.baseRig = baseRig;
                    sendWaveDataRunnable.message = msg;
                    sendWaveDataThreadPool.execute(sendWaveDataRunnable);
                }
            }

        }, new OnTransmitSuccess() {
            @Override
            public void doAfterTransmit(QSLRecord qslRecord) {
                databaseOpr.addQSL_Callsign(qslRecord);
                new Thread(() -> {
                    if (GeneralVariables.enableCloudlog) ThirdPartyService.UploadToCloudLog(qslRecord);
                    if (GeneralVariables.enableQRZ) ThirdPartyService.UploadToQRZ(qslRecord);
                    if (GeneralVariables.enableHrdlog) ThirdPartyService.UploadToHrdlog(qslRecord);
                }).start();

                if (qslRecord.getToCallsign() != null) {
                    GeneralVariables.callsignDatabase.getCallsignInformation(qslRecord.getToCallsign()
                            , new OnAfterQueryCallsignLocation() {
                                @Override
                                public void doOnAfterQueryCallsignLocation(CallsignInfo callsignInfo) {
                                    GeneralVariables.addDxcc(callsignInfo.DXCC);
                                    GeneralVariables.addItuZone(callsignInfo.ITUZone);
                                    GeneralVariables.addCqZone(callsignInfo.CQZone);
                                }
                            });
                }
            }
        });

        int savedPort = LogHttpServer.DEFAULT_PORT;
        Cursor cursor = databaseOpr.getDb().rawQuery("SELECT Value FROM config WHERE KeyName='webPort'", null);
        if (cursor != null && cursor.moveToFirst()) {
            try {
                int port = Integer.parseInt(cursor.getString(0));
                if (port >= GeneralVariables.MIN_WEB_PORT && port <= GeneralVariables.MAX_WEB_PORT) {
                    savedPort = port;
                }
            } catch (Exception ignored) {}
            cursor.close();
        }
        GeneralVariables.webPort = savedPort;
        httpServer = new LogHttpServer(this, savedPort);
        try {
            httpServer.start();
            Log.i(TAG, "HTTP server started on port " + savedPort);
        } catch (IOException e) {
            Log.e(TAG, "http server error: " + e.getMessage());
        }

        IntentFilter usbFilter = new IntentFilter();
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        GeneralVariables.getMainContext().registerReceiver(usbReceiver, usbFilter);

        updateRigStatus();

        // === Start transmission watchdog ===
        startTransmissionWatchdog();
        // ===================================
    }
    /**
     * [NEW] Check if network rig is reachable (ping test)
     * @return true if rig responds, false otherwise
     */
    private boolean isNetworkRigReachable() {
        if (GeneralVariables.connectMode != ConnectMode.NETWORK) return true;

        String rigIp = GeneralVariables.getNetworkRigIp();
        int rigPort = GeneralVariables.getNetworkRigPort();

        if (rigIp == null || rigIp.isEmpty() || rigPort <= 0) {
            Log.w(TAG, "Network rig: IP/port not configured");
            return false;
        }

        try {
            // Quick connect test (1 second timeout)
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress(rigIp, rigPort), 1000);
            socket.close();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Network rig not reachable: " + rigIp + ":" + rigPort);
            return false;
        }
    }
    @Override
    protected void onCleared() {
        super.onCleared();

        // [NEW] Stop foreground service before stopping recorder
        // Ensures clean shutdown of background audio capture
        RecordingForegroundService.stop(GeneralVariables.getMainContext());
        Log.d(TAG, "Foreground recording service stopped");

        // Stop watchdog to prevent memory leaks
        if (transmissionWatchdogHandler != null && transmissionWatchdogRunnable != null) {
            transmissionWatchdogHandler.removeCallbacks(transmissionWatchdogRunnable);
            Log.d(TAG, "Transmission watchdog stopped");
        }
        // Ensure recording is stopped and resources released
        if (hamRecorder != null) {
            hamRecorder.stopRecord();
            Log.d(TAG, "HamRecorder stopped in onCleared()");
        }
    }

    /**
     * Start aggressive watchdog to monitor and recover transmission state.
     * Checks every 10 seconds if transmit is activated but recording stopped.
     */
    private void startTransmissionWatchdog() {
        transmissionWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    // Check 1: Transmit activated but recording not running
                    if (ft8TransmitSignal.isActivated() && !hamRecorder.isRunning()) {
                        Log.w(TAG, "WATCHDOG: Transmit active but hamRecorder.isRunning()=false! Recovering...");

                        // Try to restart HamRecorder
                        try {
                            hamRecorder.startRecord();
                            mutableIsRecording.postValue(true);
                            Log.d(TAG, "HamRecorder restarted by watchdog");
                            ToastMessage.show("Recording recovered");
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to restart HamRecorder: " + e.getMessage(), e);
                        }
                    }

                    // Check 2: Audio focus lost? (optional enhancement)
                    if (GeneralVariables.connectMode != ConnectMode.NETWORK && !hamRecorder.isRunning()) {
                        //Log.d(TAG, "Watchdog: Mic mode but not recording - may need audio focus recovery");
                    }

                    // Check 3: Rig connected but PTT stuck? (optional)
                    if (baseRig != null && baseRig.isConnected() && baseRig.isPttOn()) {
                        // PTT has been on too long? Could indicate stuck state
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Watchdog error: " + e.getMessage(), e);
                }

                // Schedule next check - aggressive: every 10 seconds
                transmissionWatchdogHandler.postDelayed(this, WATCHDOG_CHECK_INTERVAL_MS);
            }
        };
        transmissionWatchdogHandler.post(transmissionWatchdogRunnable);
        Log.d(TAG, "Aggressive transmission watchdog started (10s interval)");
    }

    // ========================================================================
    // [STATE MACHINE + DECISION ENGINE] Core Evaluator Method (UPDATED)
    // ========================================================================
    /**
     * Evaluates state transitions based on decoded messages and current context.
     * Called every decode slot (~15 seconds) from ft8SignalListener.afterDecode().
     *
     * [STATE MACHINE LOGIC FLOW]
     * 1. If not in OPERATING mode -> skip (scanning modes ignore responses)
     * 2. Build DecisionContext from current state + world model
     * 3. Call DecisionEngine.evaluate() -> get StationAction
     * 4. Log decision for debugging/learning
     * 5. Execute action via executeAction()
     *
     * [PRIORITY ORDER in DecisionEngine] (first match wins)
     * 1. Emergency stop -> ABORT
     * 2. User manual override -> TRANSMIT to selected target
     * 3. Direct call to MY callsign -> immediate dialogue start
     * 4. State-specific logic (SEEKING/IN_DIALOGUE/SOFT_FINISH/NOMADIC)
     *
     * @param messages List of newly decoded FT8 messages
     * @param currentSlot Current UTC slot number (for timeout calculations)
     */
    private void evaluateStateMachine(ArrayList<Ft8Message> messages, long currentSlot) {
        // [FIX] Prevent duplicate evaluations in the same slot
// [FIX] Allow re-evaluation if we're in the same slot but different messages
// This handles the case when decode finishes late in the slot
        if (currentSlot != lastEvaluatedSlot) {
            lastEvaluatedSlot = currentSlot;
            Log.d(TAG, "[SLOT] Processing slot " + currentSlot);
        } else {
            // Same slot - check if we have new messages that weren't processed
            // For now, skip to avoid duplicate processing
            Log.d(TAG, "[SKIP] Slot " + currentSlot + " already processed");
            return;
        }

        Log.d(TAG, "[DEBUG] evaluateStateMachine called. Mode: " + stationContext.opMode +
                ", Messages: " + (messages != null ? messages.size() : 0));

        // Шаг 1: Обновляем технические данные в World Model (DAO)
        for (Ft8Message msg : messages) {
            databaseOpr.updateStationFromMessage(msg, msg.maidenGrid, null, 0, 0, 0);
        }

        // Шаг 2: Обновляем бизнес-логику состояния диалога (DecisionEngine)
        decisionEngine.updateWorldModelState(messages);

        // Шаг 3: Проверяем ответы от текущего партнёра
        StationContext ctx = stationContext;
        if (ctx.isInDialogue() && !ctx.currentTarget.isEmpty() && messages != null && !messages.isEmpty()) {
            for (Ft8Message msg : messages) {
                if (msg.getCallsignFrom().equalsIgnoreCase(ctx.currentTarget) &&
                        GeneralVariables.checkIsMyCallsign(msg.getCallsignTo())) {
                    Log.d(TAG, "[DIALOGUE] Reply detected from " + ctx.currentTarget +
                            ": " + msg.getMessageText(false));
                    advanceDialogueStep(msg, ctx);
                    ctx.lastReplySlot = currentSlot;
                    ctx.noReplyCount = 0;
                    break;
                }
            }
        }

        // Шаг 4: Проверяем ручной CQ запрос
        if (stationContext.manualCQRequested) {
            StationAction manualAction = processManualCQRequest();
            executeAction(manualAction, currentSlot);
            return;
        }

        // Шаг 5: Пропускаем, если не в OPERATING mode
        if (ctx.opMode != StationState.OperationalMode.OPERATING) {
            return;
        }

        // Шаг 6: DecisionEngine принимает решение
        DecisionContext decisionCtx = buildDecisionContext(messages, currentSlot);
        StationAction action = decisionEngine.evaluate(decisionCtx, messages, databaseOpr);

        // Шаг 7: Обновляем контекст на основе решения
        if (action.type == StationAction.ActionType.TRANSMIT && action.targetCallsign != null && !action.targetCallsign.isEmpty()) {
            // [FIX] Check if transmission is actually allowed BEFORE updating context
            if (!ft8TransmitSignal.isActivated()) {
                Log.d(TAG, "[CONTEXT SKIP] Transmission blocked (isActivated=false), NOT entering IN_DIALOGUE");
                // Do NOT update context - stay in SEEKING
            } else {
                // Transmission allowed - update context normally
                stationContext.currentTarget = action.targetCallsign;
                stationContext.subState = StationState.OperatingSubState.IN_DIALOGUE;
                Log.d(TAG, "[CONTEXT UPDATE] currentTarget=" + stationContext.currentTarget +
                        " subState=" + stationContext.subState);
                if (action.protocolStep > 0 && action.protocolStep <= StationState.DialogueStep.values().length) {
                    stationContext.step = StationState.DialogueStep.values()[action.protocolStep - 1];
                }
                stationContext.lastReplySlot = currentSlot;
            }
        }

        // Шаг 8: Логируем решение
        Log.d(TAG, String.format("[DECISION] slot=%d state=%s action=%s target=%s priority=%.2f reason=\"%s\"",
                currentSlot,
                ctx.subState,
                action.type,
                action.targetCallsign != null ? action.targetCallsign : "",
                action.priority,
                action.reason));

        // Шаг 9: Выполняем действие
        executeAction(action, currentSlot);
    }

    /**
     * Build DecisionContext from current state and messages.
     * [DECISION ENGINE] Aggregates all data needed for decision making.
     */
    /**
     * Build DecisionContext from current state and messages.
     * [DECISION ENGINE] Aggregates all data needed for decision making.
     *
     * @param messages List of newly decoded FT8 messages
     * @param currentSlot Current UTC slot number
     * @return DecisionContext ready for DecisionEngine.evaluate()
     */
    private DecisionContext buildDecisionContext(ArrayList<Ft8Message> messages, long currentSlot) {
        StationContext ctx = stationContext;

        // === [STEP 1] Prepare band filter ===
        // Convert current frequency to bit index for bitmask filtering
        long currentBandBit = DatabaseOpr.freqToBandBit(GeneralVariables.band);
        long bandMask = 1L << currentBandBit;

        // [DEBUG] Log current band info
        //Log.d(TAG, "[DEBUG] buildDecisionContext: band=" + GeneralVariables.band +
        //        " bandBit=" + currentBandBit + " mask=0b" + Long.toBinaryString(bandMask));

        // === [STEP 2] Get snapshot of World Model ===
        // Thread-safe copy of all stations tracked in RAM
        List<DatabaseOpr.StationRecord> worldModelSnapshot = DatabaseOpr.getStationWorldModelSnapshot();

        // [DEBUG] Log World Model contents
        Log.d(TAG, "[DEBUG] worldModelSnapshot count: " + worldModelSnapshot.size());
        for (DatabaseOpr.StationRecord s : worldModelSnapshot) {
            boolean bandMatch = (s.bandsBitmap & bandMask) != 0;
            boolean notExpired = !s.isExpired();
            Log.d(TAG, "[DEBUG]   WM: " + s.callsign +
                    //" bitmap=0b" + Long.toBinaryString(s.bandsBitmap) +
                    //" bandMatch=" + bandMatch +
                    //" notExpired=" + notExpired +
                    //" lastSeen=" + s.lastSeenUtcSec +
                    " state=" + s.ft8StateRelative);
        }

        // === [STEP 3] Filter stations by current band and freshness ===
        List<DatabaseOpr.StationRecord> visibleStations = new ArrayList<>();
        for (DatabaseOpr.StationRecord s : worldModelSnapshot) {
            // Keep only stations heard on current band AND not expired (>4 slots ago)
            if ((s.bandsBitmap & bandMask) != 0 && !s.isExpired()) {
                visibleStations.add(s);
            }
        }

        // [DEBUG] Log filtered result
/*        Log.d(TAG, "[DEBUG] visibleStations after filter: " + visibleStations.size());
        for (DatabaseOpr.StationRecord s : visibleStations) {
            Log.d(TAG, "[DEBUG]   Visible: " + s.callsign + " snr=" + s.lastSnr + " state=" + s.ft8StateRelative);
        }
*/
        // === [STEP 4] Find direct caller (priority P2) ===
        // Check if someone called MY callsign in this decode slot
        DatabaseOpr.StationRecord directCaller = null;
        if (messages != null) {
            for (Ft8Message msg : messages) {
                // Check: message addressed to me AND not a CQ call
                if (GeneralVariables.checkIsMyCallsign(msg.getCallsignTo()) && !msg.checkIsCQ()) {
                    // Get full record from World Model for this caller
                    directCaller = DatabaseOpr.getStationRecord(msg.getCallsignFrom());
                    Log.d(TAG, "[DEBUG] Found direct caller: " + msg.getCallsignFrom());
                    break; // Stop at first match
                }
            }
        }

        // === [STEP 5] Prepare criterion weights for scoring ===
        // Use default weights from Criterion enum (can be customized later)
        float[] weights = new float[Criterion.values().length];
        for (Criterion c : Criterion.values()) {
            weights[c.ordinal()] = c.baseWeight;
        }

        // === [STEP 6] Calculate time until TX deadline ===
        // FT8 slots: transmission must start ~12 seconds into 15-sec slot
        long slotStartSec = (currentSlot * 15);
        long nowSec = System.currentTimeMillis() / 1000;
        long timeUntilTxDeadline = Math.max(0, (slotStartSec + 12) - nowSec) * 1000;

        // [DEBUG] Log timing info
        //Log.d(TAG, "[DEBUG] Timing: slotStart=" + slotStartSec + " now=" + nowSec +
        //        " timeUntilTxDeadline=" + timeUntilTxDeadline + "ms");

        // === [STEP 7] Build and return DecisionContext ===
        return new DecisionContext(
                ctx.opMode,                           // Current operational mode
                ctx.subState,                         // Current substate (SEEKING, IN_DIALOGUE, etc.)
                ctx.step,                             // Current protocol step (IDLE, CALLING, etc.)
                ctx.currentTarget,                    // Callsign of current dialogue partner
                ctx.noReplyCount,                     // Consecutive slots without reply
                ctx.lastReplySlot,                    // UTC slot of last received reply
                ctx.recentTargets,                    // Set of recent targets for SOFT_FINISH resume
                visibleStations,                      // Filtered list of stations on current band
                directCaller,                         // Station that called us directly (if any)
                currentSlot,                          // Current UTC slot number
                timeUntilTxDeadline,                  // Milliseconds until TX window closes
                GeneralVariables.noReplyLimit,        // Max attempts before abort
                GeneralVariables.autoFollowCQ,        // Whether to auto-answer CQ calls
                GeneralVariables.acceptDxCalls,       // DX mode enabled flag
                weights,                              // Scoring weights for HybridScorer
                false,                                // emergencyStop
                ctx.userOverrideActive,               // userOverrideActive
                false                                 // forceOwnCQ (default)
        );
    }

    /**
     * Execute the decided action.
     * [DECISION ENGINE] Translates abstract actions into concrete operations.
     */
    /**
     * Execute the decided action.
     * [EXECUTOR] Pure execution: no guards, no decisions, just perform the command.
     * All logic decisions are made in DecisionEngine; this method only acts.
     *
     * @param action The action to execute (from DecisionEngine.evaluate())
     * @param currentSlot Current UTC slot number (for timing-sensitive actions)
     */
    /**
     * Execute the decided action.
     * [EXECUTOR] Pure execution: no guards, no decisions, just perform the command.
     * All logic decisions are made in DecisionEngine; this method only acts.
     *
     * @param action The action to execute (from DecisionEngine.evaluate())
     * @param currentSlot Current UTC slot number (for timing-sensitive actions)
     */
    private void executeAction(StationAction action, long currentSlot) {
        // [ENHANCED LOG] Full action details
        Log.w(TAG, "[EXECUTE] type=" + action.type + " target=" + action.targetCallsign +
                " step=" + action.protocolStep + " freqHz=" + action.freqHz +
                " reason=\"" + action.reason + "\"");
        switch (action.type) {
// MainViewModel.java — метод executeAction(), замена блоков TRANSMIT и WAIT

            case TRANSMIT:
                if (!ft8TransmitSignal.isActivated()) {
                    Log.d(TAG, "[ACTION] TRANSMIT blocked: transmission manually disabled");
                    return;
                }
                Log.d(TAG, "[ACTION] TRANSMIT to " + action.targetCallsign +
                        " step=" + action.protocolStep + " reason=\"" + action.reason + "\"");
                Log.d(TAG, "[ACTION] Technical params: freqHz=" + action.freqHz +
                        " snr=" + action.snr + " i3=" + action.i3 + " n3=" + action.n3);

                if (!ft8TransmitSignal.isActivated()) {
                    ft8TransmitSignal.setActivated(true);
                    Log.d(TAG, "[ACTION] Transmit signal activated");
                }

                long txFrequency = (action.freqHz > 0) ? action.freqHz : GeneralVariables.band;
                String txExtraInfo = (action.extraInfo != null) ? action.extraInfo : "";

                // CALCULATE SEQUENTIAL SLOT BEFORE TRANSMIT
                int txSequential;
                if (action.targetCallsign != null && !action.targetCallsign.equals("CQ")) {
                    DatabaseOpr.StationRecord record = DatabaseOpr.getStationRecord(action.targetCallsign);
                    if (record != null && record.lastSequential >= 0) {
                        txSequential = 1 - record.lastSequential;
                        Log.d(TAG, "[SEQUENTIAL] Using partner's slot: their=" + record.lastSequential + " -> our=" + txSequential);
                    } else {
                        txSequential = 1 - (int)(UtcTimer.getNowSequential() % 2);
                        Log.d(TAG, "[SEQUENTIAL] Fallback (no record): current=" + UtcTimer.getNowSequential() + " -> our=" + txSequential);
                    }
                } else {
                    txSequential = 1 - (int)(UtcTimer.getNowSequential() % 2);
                }

                ft8TransmitSignal.setTransmit(
                        new TransmitCallsign(action.i3, action.n3, action.targetCallsign,
                                txFrequency, txSequential, action.snr),
                        action.protocolStep, txExtraInfo);

                // [FIX 2026-05-23] STATE MACHINE TRANSITION AFTER 73
                // If we just transmitted step 5 (73), we MUST transition state machine to terminal state
                // to prevent DecisionEngine from generating "Transmit 73" again in the next slot.
                if (action.protocolStep == 5) {
                    Log.d(TAG, "[STATE TRANSITION] 73 sent. Forcing transition to SOFT_FINISH to stop loop.");
                    // [FIX] НЕ добавляем в recentTargets - это вызывает бесконечный цикл!
                    stationContext.subState = StationState.OperatingSubState.SOFT_FINISH;
                    stationContext.step = StationState.DialogueStep.IDLE;
                    stationContext.currentTarget = "";  // Очищаем target
                    Log.d(TAG, "State: entered SOFT_FINISH (no recentTargets)");
                    return;
                }

                // [FIX 2026-05-23] Reset counter on new target
                if (action.targetCallsign != null &&
                        !action.targetCallsign.equals(stationContext.currentTarget)) {
                    stationContext.noReplyCount = 0;
                    Log.d(TAG, "[NEW TARGET] Switched to " + action.targetCallsign + ", reset counter");
                }

                Log.d(TAG, "[ACTION] TX started (noReplyCount=" + stationContext.noReplyCount +
                        " to " + action.targetCallsign + ")");
                break;

            case WAIT:
                // [FIX 2026-05-23] Increment noReplyCount ONLY in dialogue
                // In SEEKING, waiting is normal behavior (no suitable CQ yet)
                if (stationContext.subState == StationState.OperatingSubState.IN_DIALOGUE &&
                        stationContext.currentTarget != null &&
                        !stationContext.currentTarget.isEmpty()) {
                    stationContext.noReplyCount++;
                    Log.d(TAG, "[WAIT] Dialogue idle, noReplyCount=" + stationContext.noReplyCount +
                            " (target=" + stationContext.currentTarget + ")");
                } else {
                    Log.d(TAG, "[WAIT] state=" + stationContext.subState + ", noReplyCount unchanged");
                }
                break;

            case ABORT:
                Log.d(TAG, "[ACTION] ABORT reason=\"" + action.reason + "\"");
                ft8TransmitSignal.resetToCQ();
                stationContext.resetToSeeking();  // resetToSeeking() already sets noReplyCount=0
                break;

            case RESUME:
                // === [ACTION] RESUME: Restart dialogue with recent target ===
                Log.d(TAG, "[ACTION] RESUME dialogue with " + action.targetCallsign);
                stationContext.currentTarget = action.targetCallsign;
                stationContext.subState = StationState.OperatingSubState.IN_DIALOGUE;
                stationContext.step = StationState.DialogueStep.CALLING;
                stationContext.lastReplySlot = currentSlot;
                stationContext.noReplyCount = 0;        // Reset attempt counter
                break;

            case NOMADIC_SWITCH:
                // === [ACTION] NOMADIC_SWITCH: Change frequency in nomadic mode ===
                Log.d(TAG, "[ACTION] NOMADIC_SWITCH freq=" + action.targetFrequency +
                        " reason=\"" + action.reason + "\"");
                long nextFreq = getNextNomadicFrequency();
                if (nextFreq > 0) {
                    GeneralVariables.band = nextFreq;
                    GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(nextFreq);
                    // If rig is connected, send frequency change command
                    if (baseRig != null && baseRig.isConnected()) {
                        setOperationBand();
                    }
                }
                break;

            case SCAN_AUDIO:
                // === [ACTION] SCAN_AUDIO: Switch to audio calibration mode ===
                Log.d(TAG, "[ACTION] SCAN_AUDIO reason=\"" + action.reason + "\"");
                stationContext.opMode = StationState.OperationalMode.SCANNING_AUDIO;
                stationContext.resetToSeeking();        // Clear dialogue state
                break;

            case NO_OP:
                // === [ACTION] NO_OP: Neutral action for state transitions ===
                Log.d(TAG, "[ACTION] NO_OP reason=\"" + action.reason + "\"");
                // Nothing to do - just a marker for state machine transition
                break;

            case TX_OWN_CQ:
                Log.d(TAG, "[ACTION] TX_OWN_CQ: Transmitting own CQ (priority reset)");

                // Reset transmission to CQ mode
                ft8TransmitSignal.resetToCQ();

                // Activate transmit if needed
                if (!ft8TransmitSignal.isActivated()) {
                    ft8TransmitSignal.setActivated(true);
                }

                stationContext.noReplyCount = 0;
                stationContext.lastReplySlot = currentSlot;
                break;

            default:
                // === [ACTION] Unknown type: log warning ===
                Log.w(TAG, "[ACTION] Unknown action type: " + action.type);
                break;
        }
    }

    /**
     * Get next frequency for Nomadic mode (simplified: cycle through preset list).
     * [NOMADIC MODE] Phase 1 implementation - can be extended with user presets.
     */
    /**
     * Get next frequency for Nomadic mode (simplified: cycle through preset list).
     * [NOMADIC MODE] Phase 1 implementation - can be extended with user presets.
     */
    private long getNextNomadicFrequency() {
        if (OperationBand.bandList == null || OperationBand.bandList.isEmpty()) {
            return 14074000L;
        }
        int nextIndex = (GeneralVariables.bandListIndex + 1) % OperationBand.bandList.size();
        return OperationBand.bandList.get(nextIndex).band;
    }

    // ========================================================================
    // [STATE MACHINE] Legacy State Handlers (COMMENTED FOR REFERENCE)
    // ========================================================================
    /**
     * [LEGACY - COMMENTED FOR DEBUGGING] Original state evaluation logic.
     * Preserved for comparison during Phase 1 development.
     *
     * @param messages List of newly decoded FT8 messages
     * @param currentSlot Current UTC slot number (for timeout calculations)
     */
    /*
    private void evaluateStateMachine(ArrayList<Ft8Message> messages, long currentSlot) {
        StationContext ctx = stationContext;

        // [STATE MACHINE] Skip evaluation if not in OPERATING mode
        // Scanning modes (RF/Audio) don't participate in dialogue logic
        if (ctx.opMode != OperationalMode.OPERATING) {
            // Optional: update world model even in scanning modes for logging
            return;
        }

        // [STATE MACHINE] Main dispatch based on operating substate
        switch (ctx.subState) {
            case SEEKING:
                processSeekingState(messages, ctx, currentSlot);
                break;

            case IN_DIALOGUE:
                processInDialogueState(messages, ctx, currentSlot);
                break;

            case SOFT_FINISH:
                processSoftFinishState(messages, ctx, currentSlot);
                break;

            case MANUAL_TARGET:
                // [TODO] Implement manual target override logic
                // Priority: user selection > all auto logic
                break;

            case PILEUP_MODE:
                // [TODO] Implement pileup queue management
                // When >=2 stations call us simultaneously, queue and process
                break;
        }
    }

    /**
     * [LEGACY - COMMENTED FOR DEBUGGING] SEEKING state logic.
     * Preserved for reference during DecisionEngine integration.
     */
    /*
    private void processSeekingState(ArrayList<Ft8Message> messages, StationContext ctx, long currentSlot) {
        if (messages == null || messages.isEmpty()) return;

        // [PRIORITY 1] Check for direct calls to MY callsign
        // If someone calls us directly, start dialogue immediately (interrupts seeking)
        for (Ft8Message msg : messages) {
            if (GeneralVariables.checkIsMyCallsign(msg.getCallsignTo()) && !msg.checkIsCQ()) {
                Log.d(TAG, "State: SEEKING -> IN_DIALOGUE (direct call from " + msg.getCallsignFrom() + ")");
                ctx.currentTarget = msg.getCallsignFrom();
                ctx.subState = OperatingSubState.IN_DIALOGUE;
                ctx.step = DialogueStep.CALLING;
                ctx.lastReplySlot = currentSlot;
                ctx.noReplyCount = 0;

                // Start dialogue via existing transmit signal
                ft8TransmitSignal.setTransmit(
                        new FT8TransmitSignal.TransmitCallsign(msg.i3, msg.n3, ctx.currentTarget,
                                msg.freq_hz, msg.getSequence(), msg.snr),
                        1, msg.extraInfo);
                return; // Exit early - dialogue started
            }
        }

        // [PRIORITY 2] Evaluate new CQs for potential answer
        // (Logic delegated to ft8TransmitSignal.parseMessageToFunction for now)
        // Future: add scoring here to pick best CQ based on DX/new band/etc.

        // [PRIORITY 3] Fallback: if no activity, consider calling CQ ourselves
        // (Handled by ft8TransmitSignal's existing CQ logic)
    }
    */

    /**
     * [LEGACY - COMMENTED FOR DEBUGGING] IN_DIALOGUE state logic.
     * Preserved for reference during DecisionEngine integration.
     */
    /*
    private void processInDialogueState(ArrayList<Ft8Message> messages, StationContext ctx, long currentSlot) {
        // [TIMEOUT CHECK] If target hasn't replied within limit, abort dialogue
        int noReplyLimit = GeneralVariables.noReplyLimit;
        if (noReplyLimit > 0 && (currentSlot - ctx.lastReplySlot) >= noReplyLimit) {
            Log.w(TAG, "State: IN_DIALOGUE timeout (" + noReplyLimit + " slots) for " + ctx.currentTarget);
            ctx.enterSoftFinish(); // Graceful exit to listening mode
            ft8TransmitSignal.resetToCQ(); // Stop transmitting, go to CQ mode
            return;
        }

        // [REPLY DETECTION] Scan for replies from current target
        if (messages != null) {
            for (Ft8Message msg : messages) {
                if (msg.getCallsignFrom().equals(ctx.currentTarget) &&
                        GeneralVariables.checkIsMyCallsign(msg.getCallsignTo())) {
                    // Target replied! Reset timeout counter and update last reply time
                    ctx.lastReplySlot = currentSlot;
                    ctx.noReplyCount = 0;
                    Log.d(TAG, "State: Reply from " + ctx.currentTarget + " at step " + ctx.step.description);

                    // [PROTOCOL PROGRESSION] Delegate step advancement to FT8TransmitSignal
                    // It already handles functionOrder progression via parseMessageToFunction()
                    // Here we just track the high-level state for logging/decisions
                    Integer fOrder = ft8TransmitSignal.mutableFunctionOrder.getValue();
                    if (fOrder != null && fOrder >= 1 && fOrder <= 6) {
                        ctx.step = DialogueStep.values()[fOrder]; // Map functionOrder to DialogueStep
                    }

                    // [COMPLETION CHECK] If QSO finished (step 5 or 6), transition to SOFT_FINISH
                    if (fOrder != null && (fOrder == 5 || fOrder == 6)) {
                        Log.d(TAG, "State: QSO completed with " + ctx.currentTarget + " -> SOFT_FINISH");
                        ctx.enterSoftFinish();
                    }
                    return; // Exit after processing reply
                }
            }
        }

        // [NO REPLY] Increment counter if no reply received this slot
        ctx.noReplyCount++;
    }
    */

    /**
     * [LEGACY - COMMENTED FOR DEBUGGING] SOFT_FINISH state logic.
     * Preserved for reference during DecisionEngine integration.
     */
    /*
    private void processSoftFinishState(ArrayList<Ft8Message> messages, StationContext ctx, long currentSlot) {
        // [RESUME TRIGGER] Check if a recent target calls us -> resume dialogue
        if (messages != null && !ctx.recentTargets.isEmpty()) {
            for (Ft8Message msg : messages) {
                if (ctx.recentTargets.contains(msg.getCallsignFrom()) &&
                        GeneralVariables.checkIsMyCallsign(msg.getCallsignTo())) {
                    Log.d(TAG, "State: SOFT_FINISH -> IN_DIALOGUE (resume with " + msg.getCallsignFrom() + ")");
                    ctx.currentTarget = msg.getCallsignFrom();
                    ctx.subState = OperatingSubState.IN_DIALOGUE;
                    ctx.step = DialogueStep.CALLING;
                    ctx.lastReplySlot = currentSlot;
                    ctx.noReplyCount = 0;

                    // Resume dialogue via transmit signal
                    ft8TransmitSignal.setTransmit(
                            new FT8TransmitSignal.TransmitCallsign(msg.i3, msg.n3, ctx.currentTarget,
                                    msg.freq_hz, msg.getSequence(), msg.snr),
                            1, msg.extraInfo);
                    return; // Exit early - dialogue resumed
                }
            }
        }

        // [TIMEOUT TO SCAN_AUDIO] If no activity for extended period, switch to audio calibration
        int softFinishTimeout = 8; // 8 slots = 2 minutes of silence
        if (currentSlot - ctx.lastReplySlot > softFinishTimeout) {
            Log.d(TAG, "State: SOFT_FINISH timeout (" + softFinishTimeout + " slots) -> SCANNING_AUDIO");
            ctx.opMode = OperationalMode.SCANNING_AUDIO;
            ctx.resetToSeeking(); // Clear dialogue state
            // Optional: trigger audio calibration routine here
        }
    }
    */
    // ========================================================================
    // [END LEGACY STATE HANDLERS]
    // ========================================================================

    // ========================================================================
    // [NEW] Reset state machine on frequency change / history clear
    // ========================================================================
    /**
     * [NEW] Call on frequency change or history clear.
     * Resets state machine and prioritizes own CQ.
     *
     * This ensures that after condition changes, system starts fresh
     * and first tries to transmit own CQ before answering others.
     */
    public void resetStateMachineOnFreqChange() {
        Log.d(TAG, "=== resetStateMachineOnFreqChange ===");

        // [FIX] Reset slot tracker to allow evaluation on new frequency
        lastEvaluatedSlot = -1;

        // Reset decision context
        stationContext.userOverrideActive = false;
        stationContext.currentTarget = "";
        stationContext.subState = StationState.OperatingSubState.SEEKING;
        stationContext.step = StationState.DialogueStep.IDLE;
        stationContext.noReplyCount = 0;
        stationContext.lastReplySlot = 0;

        // Reset DecisionEngine cache
        if (decisionEngine != null) {
            decisionEngine.resetCache();
        }

        // Clear transmit queue if enabled
        if (GeneralVariables.clearCallHistOnFreqChange) {
            clearTransmittingMessage();
            Log.d(TAG, "Transmit queue cleared");
        }

        Log.d(TAG, "Machine reset: Next action will prioritize own CQ");
    }
    // ========================================================================
    // [END NEW] Reset state machine
    // ========================================================================

    public void setTransmitIsFreeText(boolean isFreeText) {
        if (ft8TransmitSignal != null) ft8TransmitSignal.setTransmitFreeText(isFreeText);
    }

    public boolean getTransitIsFreeText() {
        return ft8TransmitSignal != null && ft8TransmitSignal.isTransmitFreeText();
    }

    /**
     * Find messages that match my callsign or followed callsigns.
     * Add matching messages to transmit queue.
     */
    private synchronized void findIncludedCallsigns(ArrayList<Ft8Message> messages) {
        if (ft8TransmitSignal.isActivated() && ft8TransmitSignal.sequential != UtcTimer.getNowSequential()) return;
        int count = 0;
        for (Ft8Message msg : messages) {
            if (GeneralVariables.checkIsMyCallsign(msg.getCallsignFrom())
                    || GeneralVariables.checkIsMyCallsign(msg.getCallsignTo())
                    || GeneralVariables.callsignInFollow(msg.getCallsignFrom())
                    || (GeneralVariables.callsignInFollow(msg.getCallsignTo()) && msg.getCallsignTo() != null)
                    || (GeneralVariables.autoFollowCQ && msg.checkIsCQ())) {
                msg.isQSL_Callsign = GeneralVariables.checkQSLCallsign(msg.getCallsignFrom());
                if (!GeneralVariables.checkIsExcludeCallsign(msg.callsignFrom)) {
                    count++;
                    GeneralVariables.transmitMessages.add(msg);
                }
            }
        }
        GeneralVariables.deleteArrayListMore(GeneralVariables.transmitMessages);
        mutableTransmitMessagesCount.postValue(count);
    }

    /**
     * Clear the transmit message queue.
     */
    public void clearTransmittingMessage() {
        GeneralVariables.transmitMessages.clear();
        mutableTransmitMessagesCount.postValue(0);
    }

    /**
     * Extract callsign and grid information from decoded messages.
     * Store in database and GeneralVariables cache.
     */
    private void getCallsignAndGrid(ArrayList<Ft8Message> messages) {
        for (Ft8Message msg : messages) {
            if (FT8MessageClassifier.isGrid(msg.extraInfo)) {
                if (!GeneralVariables.getCallsignHasGrid(msg.getCallsignFrom(), msg.maidenGrid)) {
                    databaseOpr.addCallsignQTH(msg.getCallsignFrom(), msg.maidenGrid);
                }
                GeneralVariables.addCallsignAndGrid(msg.getCallsignFrom(), msg.maidenGrid);
            }
        }
    }

    /**
     * Clear the FT8 message list (Calling history).
     * Used when changing frequency to avoid calling old callsigns.
     */
    public void clearFt8MessageList() {
        ft8Messages.clear();
        mutable_Decoded_Counter.postValue(ft8Messages.size());
        mutableFt8MessageList.postValue(ft8Messages);
    }

    /**
     * Delete a file by path.
     */
    public static void deleteFile(String fileName) {
        File file = new File(fileName);
        if (file.exists() && file.isFile()) file.delete();
    }

    /**
     * Add a callsign to the followed list.
     */
    public void addFollowCallsign(String callsign) {
        if (!GeneralVariables.followCallsign.contains(callsign)) {
            GeneralVariables.followCallsign.add(callsign);
            databaseOpr.addFollowCallsign(callsign);
        }
    }

    /**
     * Load followed callsigns from database.
     */
    public void getFollowCallsignsFromDataBase() {
        databaseOpr.getFollowCallsigns(new OnAfterQueryFollowCallsigns() {
            @Override
            public void doOnAfterQueryFollowCallsigns(ArrayList<String> callsigns) {
                for (String s : callsigns) {
                    if (!GeneralVariables.followCallsign.contains(s)) GeneralVariables.followCallsign.add(s);
                }
            }
        });
    }

    /**
     * Set the operation band on the connected rig.
     */
    public void setOperationBand() {
        clearTransmittingMessage();     // Clear transmit queue

        // [FIX] Check if myCallsign is set before resetting CQ
        // This prevents StringIndexOutOfBoundsException in GenerateFT8.checkI3ByCallsign()
        if (GeneralVariables.myCallsign != null && !GeneralVariables.myCallsign.isEmpty()) {
            ft8TransmitSignal.resetToCQ();  // Reset CQ to state 6
        } else {
            Log.w(TAG, "setOperationBand: myCallsign not set, skipping resetToCQ");
        }

        Log.d(TAG, "=== setOperationBand DEBUG ===");
        Log.d(TAG, "controlMode=" + GeneralVariables.controlMode);
        Log.d(TAG, "connectMode=" + GeneralVariables.connectMode);
        Log.d(TAG, "baseRig=" + baseRig);
        Log.d(TAG, "isConnected=" + (baseRig != null && baseRig.isConnected()));
        Log.d(TAG, "supportWaveOverCAT=" + (baseRig != null ? baseRig.supportWaveOverCAT() : "N/A"));

        if (!isRigConnected()) {
            Log.e(TAG, "ABORT: rig not connected");
            return;
        }
        if (GeneralVariables.controlMode != ControlMode.CAT) {
            Log.w(TAG, "WARNING: controlMode is not CAT, freq commands may be ignored");
        }
        if (!isRigConnected()) return;
        baseRig.setUsbModeToRig();
        new Handler().postDelayed(() -> {
            baseRig.setFreq(GeneralVariables.band);
            baseRig.setFreqToRig();
        }, 800);
    }

    /**
     * Set the CI-V address for ICOM radios.
     */
    public void setCivAddress() {
        if (baseRig != null) baseRig.setCivAddress(GeneralVariables.civAddress);
    }

    /**
     * Set the control mode (VOX, CAT, RTS, DTR).
     */
    public void setControlMode() {
        if (baseRig != null) baseRig.setControlMode(GeneralVariables.controlMode);
    }

    /**
     * Connect to a rig via USB cable.
     */
    public void connectCableRig(Context context, CableSerialPort.SerialPort port) {
        if (ft8TransmitSignal != null && ft8TransmitSignal.isTransmitting()) {
            Log.i(TAG, "Interrupting transmit before connecting USB device");
            ft8TransmitSignal.setTransmitting(false);
            ToastMessage.show("Transmit stopped: connecting device");
        }
        if (GeneralVariables.controlMode == ControlMode.VOX) GeneralVariables.controlMode = ControlMode.CAT;
        connectRig();
        if (baseRig == null) return;
        baseRig.setControlMode(GeneralVariables.controlMode);

        CableConnector connector = new CableConnector(context, port, GeneralVariables.baudRate, GeneralVariables.controlMode, baseRig);

        connector.setOnConnectReceiveData(new OnConnectReceiveData() {
            @Override
            public void onData(byte[] data) {
                if (baseRig != null) {
                    baseRig.onReceiveData(data);
                }
            }
        });

        connector.setOnCableDataReceived(new CableConnector.OnCableDataReceived() {
            @Override
            public void OnWaveReceived(int bufferLen, float[] buffer) {
                Log.i(TAG, "call hamRecorder.doOnWaveDataReceived");
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
        });
        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(connector);
        connector.connect();
        new Handler().postDelayed(this::setOperationBand, 1000);
    }

    /**
     * Connect to a rig via Bluetooth.
     */
    public void connectBluetoothRig(Context context, BluetoothDevice device) {
        GeneralVariables.controlMode = ControlMode.CAT;
        connectRig();
        if (baseRig == null) return;
        baseRig.setControlMode(GeneralVariables.controlMode);
        BluetoothRigConnector connector = BluetoothRigConnector.getInstance(context, device.getAddress(), GeneralVariables.controlMode);
        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(connector);
        new Handler().postDelayed(this::setOperationBand, 5000);
    }

    /**
     * Connect to a rig via WiFi (ICOM).
     */
    public void connectWifiRig(WifiRig wifiRig) {
        if (GeneralVariables.connectMode == ConnectMode.NETWORK && baseRig != null && baseRig.getConnector() != null) {
            baseRig.getConnector().disconnect();
        }
        GeneralVariables.controlMode = ControlMode.CAT;
        IComWifiConnector iComWifiConnector = new IComWifiConnector(GeneralVariables.controlMode, wifiRig);
        iComWifiConnector.setOnWifiDataReceived(new IComWifiConnector.OnWifiDataReceived() {
            @Override
            public void OnWaveReceived(int bufferLen, float[] buffer) {
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
            @Override
            public void OnCivReceived(byte[] data) {}
        });
        iComWifiConnector.connect();
        connectRig();
        baseRig.setControlMode(GeneralVariables.controlMode);
        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(iComWifiConnector);
        new Handler().postDelayed(this::setOperationBand, 1000);
    }

    /**
     * Connect to a FlexRadio rig.
     */
    public void connectFlexRadioRig(Context context, FlexRadio flexRadio) {
        if (GeneralVariables.connectMode == ConnectMode.NETWORK && baseRig != null && baseRig.getConnector() != null) {
            baseRig.getConnector().disconnect();
        }
        GeneralVariables.controlMode = ControlMode.CAT;
        FlexConnector flexConnector = new FlexConnector(context, flexRadio, GeneralVariables.controlMode);
        flexConnector.setOnWaveDataReceived(new FlexConnector.OnWaveDataReceived() {
            @Override
            public void OnDataReceived(int bufferLen, float[] buffer) {
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
        });
        flexConnector.connect();
        connectRig();
        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(flexConnector);
        new Handler().postDelayed(this::setOperationBand, 3000);
    }

    /**
     * Connect to a Xiegu X6100 rig.
     */
    public void connectXieguRadioRig(Context context, X6100Radio xieguRadio) {
        if (GeneralVariables.connectMode == ConnectMode.NETWORK && baseRig != null && baseRig.getConnector() != null) {
            baseRig.getConnector().disconnect();
        }
        GeneralVariables.controlMode = ControlMode.CAT;
        X6100Connector xieguConnector = new X6100Connector(context, xieguRadio, GeneralVariables.controlMode);
        xieguConnector.setOnWaveDataReceived(new X6100Connector.OnWaveDataReceived() {
            @Override
            public void OnDataReceived(int bufferLen, float[] buffer) {
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
        });
        xieguConnector.connect();
        connectRig();
        xieguConnector.setBaseRig(baseRig);
        xieguRadio.setOnReceiveDataListener(new X6100Radio.OnReceiveDataListener() {
            @Override
            public void onDataReceive(byte[] data) {
                baseRig.onReceiveData(data);
            }
        });
        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(xieguConnector);
        new Handler().postDelayed(this::setOperationBand, 3000);
    }

    /**
     * Initialize the rig instance based on instruction set.
     */
    private void connectRig() {
        baseRig = null;
        switch (GeneralVariables.instructionSet) {
            case InstructionSet.ICOM: baseRig = new IcomRig(GeneralVariables.civAddress, true); break;
            case InstructionSet.ICOM_756: baseRig = new IcomRig(GeneralVariables.civAddress, false); break;
            case InstructionSet.YAESU_2: baseRig = new Yaesu2Rig(); break;
            case InstructionSet.YAESU_847: baseRig = new Yaesu2_847Rig(); break;
            case InstructionSet.YAESU_3_9: baseRig = new Yaesu39Rig(false); break;
            case InstructionSet.YAESU_3_9_U_DIG: baseRig = new Yaesu39Rig(true); break;
            case InstructionSet.YAESU_3_8: baseRig = new Yaesu38Rig(); break;
            case InstructionSet.YAESU_3_450: baseRig = new Yaesu38_450Rig(); break;
            case InstructionSet.KENWOOD_TK90: baseRig = new KenwoodKT90Rig(); break;
            case InstructionSet.YAESU_DX10: baseRig = new YaesuDX10Rig(); break;
            case InstructionSet.KENWOOD_TS590: baseRig = new KenwoodTS590Rig(); break;
            case InstructionSet.GUOHE_Q900: baseRig = new GuoHeQ900Rig(); break;
            case InstructionSet.XIEGUG90S: baseRig = new XieGuRig(GeneralVariables.civAddress); break;
            case InstructionSet.ELECRAFT: baseRig = new ElecraftRig(); break;
            case InstructionSet.FLEX_CABLE: baseRig = new Flex6000Rig(); break;
            case InstructionSet.FLEX_NETWORK: baseRig = new FlexNetworkRig(); break;
            case InstructionSet.XIEGU_6100_FT8CNS:
                baseRig = GeneralVariables.connectMode == ConnectMode.NETWORK
                        ? new XieGu6100NetRig(GeneralVariables.civAddress)
                        : new XieGu6100Rig(GeneralVariables.civAddress);
                break;
            case InstructionSet.XIEGU_6100: baseRig = new XieGu6100Rig(GeneralVariables.civAddress); break;
            case InstructionSet.KENWOOD_TS2000: baseRig = new KenwoodTS2000Rig(); break;
            case InstructionSet.WOLF_SDR_DIGU: baseRig = new Wolf_sdr_450Rig(false); break;
            case InstructionSet.WOLF_SDR_USB: baseRig = new Wolf_sdr_450Rig(true); break;
            case InstructionSet.TRUSDX: baseRig = new TrUSDXRig(); break;
            case InstructionSet.KENWOOD_TS570: baseRig = new KenwoodTS570Rig(); break;
        }
        if ((GeneralVariables.instructionSet == InstructionSet.FLEX_NETWORK)
                || ((GeneralVariables.instructionSet == InstructionSet.ICOM
                || GeneralVariables.instructionSet == InstructionSet.XIEGU_6100
                || GeneralVariables.instructionSet == InstructionSet.XIEGU_6100_FT8CNS)
                && GeneralVariables.connectMode == ConnectMode.NETWORK)) {
            hamRecorder.setDataFromLan();
        } else {
            if (GeneralVariables.controlMode != ControlMode.CAT || baseRig == null || !baseRig.supportWaveOverCAT()) {
                hamRecorder.setDataFromMic();
            } else {
                hamRecorder.setDataFromLan();
            }
        }
        mutableIsFlexRadio.postValue(GeneralVariables.instructionSet == InstructionSet.FLEX_NETWORK);
        mutableIsXieguRadio.postValue(GeneralVariables.instructionSet == InstructionSet.XIEGU_6100_FT8CNS);
    }

    /**
     * Check if a rig is currently connected.
     */
    public boolean isRigConnected() {
        return baseRig != null && baseRig.isConnected();
    }

    /**
     * Update the rig connection status text for UI.
     */
    public void updateRigStatus() {
        if (GeneralVariables.controlMode == ControlMode.VOX) {
            rigStatusText.postValue("VOX Mode (Audio PTT)");
            return;
        }
        if (baseRig == null || !baseRig.isConnected()) {
            rigStatusText.postValue("Disconnected");
            return;
        }
        switch (GeneralVariables.connectMode) {
            case ConnectMode.USB_CABLE: rigStatusText.postValue("Connected: USB Cable"); break;
            case ConnectMode.BLUE_TOOTH: rigStatusText.postValue("Connected: Bluetooth"); break;
            case ConnectMode.NETWORK: rigStatusText.postValue("Connected: Network"); break;
            default: rigStatusText.postValue("Connected: CAT");
        }
    }

    /**
     * Get list of available USB serial ports.
     */
    public void getUsbDevice() {
        serialPorts = CableSerialPort.listSerialPorts(GeneralVariables.getMainContext());
        mutableSerialPorts.postValue(serialPorts);
    }

    /**
     * Start Bluetooth SCO for audio routing.
     */
    public void startSco() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            ToastMessage.show(getStringFromResource(R.string.does_not_support_recording));
            return;
        }
        audioManager.setBluetoothScoOn(true);
        audioManager.startBluetoothSco();
        audioManager.setSpeakerphoneOn(false);
    }

    /**
     * Stop Bluetooth SCO.
     */
    public void stopSco() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (audioManager.isBluetoothScoOn()) {
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            audioManager.setSpeakerphoneOn(true);
        }
    }

    /**
     * Enable Bluetooth headset mode.
     */
    public void setBlueToothOn() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            ToastMessage.show(getStringFromResource(R.string.does_not_support_recording));
            return;
        }
        audioManager.setMode(AudioManager.MODE_NORMAL);
        audioManager.setBluetoothScoOn(true);
        audioManager.stopBluetoothSco();
        audioManager.startBluetoothSco();
        audioManager.setSpeakerphoneOn(false);
        ToastMessage.show(getStringFromResource(R.string.bluetooth_headset_mode));
    }

    /**
     * Disable Bluetooth headset mode.
     */
    public void setBlueToothOff() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (audioManager.isBluetoothScoOn()) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            audioManager.setSpeakerphoneOn(true);
        }
        ToastMessage.show(getStringFromResource(R.string.bluetooth_Headset_mode_cancelled));
    }

    /**
     * Check if Bluetooth headset is connected.
     */
    @SuppressLint("MissingPermission")
    public boolean isBTConnected() {
        BluetoothAdapter blueAdapter = BluetoothAdapter.getDefaultAdapter();
        if (blueAdapter == null) return false;
        int headset = blueAdapter.getProfileConnectionState(BluetoothProfile.HEADSET);
        int a2dp = blueAdapter.getProfileConnectionState(BluetoothProfile.A2DP);
        return headset == BluetoothAdapter.STATE_CONNECTED || a2dp == BluetoothAdapter.STATE_CONNECTED;
    }

    /**
     * Runnable to get QTH information for decoded messages.
     */
    private static class GetQTHRunnable implements Runnable {
        MainViewModel mainViewModel;
        ArrayList<Ft8Message> messages;
        public GetQTHRunnable(MainViewModel mainViewModel) { this.mainViewModel = mainViewModel; }
        @Override
        public void run() {
            CallsignDatabase.getMessagesLocation(GeneralVariables.callsignDatabase.getDb(), messages);
            mainViewModel.mutableFt8MessageList.postValue(mainViewModel.ft8Messages);
        }
    }

    /**
     * Runnable to send wave data to rig over CAT.
     */
    private static class SendWaveDataRunnable implements Runnable {
        BaseRig baseRig;
        Ft8Message message;
        @Override
        public void run() {
            if (baseRig != null && message != null) baseRig.sendWaveData(message);
        }
    }

    /**
     * Restart the HTTP server on a new port.
     */
    public void restartHttpServer(int newPort) {
        if (httpServer != null) httpServer.restartServer(newPort);
    }

    /**
     * Toggle rig connection based on current mode.
     */
    public void toggleRigConnection(Context context) {
        if (GeneralVariables.controlMode == ControlMode.VOX) {
            ToastMessage.show("VOX mode does not use CAT connection");
            return;
        }

        if (isRigConnected()) {
            if (baseRig != null && baseRig.getConnector() != null) {
                baseRig.getConnector().disconnect();
                baseRig = null;
                updateRigStatus();
                ToastMessage.show("Disconnected");
            }
            return;
        }

        switch (GeneralVariables.connectMode) {
            case ConnectMode.USB_CABLE:
                getUsbDevice();
                break;
            case ConnectMode.BLUE_TOOTH:
            case ConnectMode.NETWORK:
                ToastMessage.show("Open settings to select " +
                        (GeneralVariables.connectMode == ConnectMode.BLUE_TOOTH ? "Bluetooth" : "Network") +
                        " device");
                break;
        }
    }

    /**
     * Send immediate TUNE command to rig.
     */
    private void sendTuneCommand() {
        if (baseRig != null && baseRig.isConnected()) {
            baseRig.setTune(IcomRigConstant.TUNER_START);
            Log.d(TAG, "TUNE START command sent via baseRig.setTune()");
            ToastMessage.show("TUNE START command sent via baseRig.setTune()");
        } else {
            Log.w(TAG, "Cannot send TUNE: rig not connected");
            ToastMessage.show("Cannot send TUNE: rig not connected");
        }
    }

    /**
     * Schedule TUNE command to execute in the safe gap between FT8 transmission slots.
     * FT8 slots: ~0-13s, 15-28s, 30-43s, 45-58s of each minute (transmission windows)
     * Safe gaps: ~13-15s, 28-30s, 43-45s, 58-60s (quiet periods for tuning)
     * This avoids interfering with ongoing QSOs.
     * If clearCallHistOnFreqChange is enabled, also clears the transmit queue.
     */
    private void scheduleTuneCommand() {
        long nowSec = (System.currentTimeMillis() / 1000) % 60;
        int slotStart = ((int) nowSec / 15) * 15;
        int secondsIntoSlot = (int) nowSec - slotStart;

        long delayMs;
        if (secondsIntoSlot < 12) {
            delayMs = (13 - secondsIntoSlot) * 1000L + 300;
        } else {
            delayMs = 400;
        }
        delayMs = Math.max(delayMs, 300);
        delayMs = Math.min(delayMs, 15000);

        //Log.d(TAG, "Scheduling TUNE: sec=" + nowSec + ", delay=" + delayMs + "ms");

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (baseRig != null && baseRig.isConnected()) {
                baseRig.setTune(IcomRigConstant.TUNER_START);

                // === Clear Calling history if enabled ===
                if (GeneralVariables.clearCallHistOnFreqChange) {
                    clearTransmittingMessage(); //Critical: DO NOT TOUCH! DO NOT DELETE, DO NOT MODIFY
                    ToastMessage.show("Calling history cleared");
                }
                // =======================================
            }
        }, delayMs);
    }

    // === Persistent occupied zones methods ===

    /**
     * Update persistent occupied zones from current decode results.
     * Call this when decoding finishes.
     */
    /**
     * Update persistent occupied zones from current decode results.
     * Deduplication removed: adds zones for every decoded signal.
     */
    public void updatePersistentOccupiedZones(List<Ft8Message> messages, int viewWidth, int viewHeight) {
        if (messages == null || viewWidth == 0) return;

        // Age existing zones
        persistentOccupiedZonesAge++;
        if (persistentOccupiedZonesAge >= ZONE_PERSIST_CYCLES) {
            persistentOccupiedZones.clear();
            persistentOccupiedZonesAge = 0;
        }

        // Add zones for ALL decoded messages (deduplication removed)
        for (Ft8Message msg : messages) {
            int freq = (int) msg.freq_hz;
            if (freq > 0 && freq < 3000) {
                float left = ((float)(freq - 25) / 3000f) * viewWidth;
                float right = ((float)(freq + 25) / 3000f) * viewWidth;
                left = Math.max(0, left);
                right = Math.min(viewWidth, right);

                // Directly add without overlap check
                persistentOccupiedZones.add(new RectF(left, 0, right, viewHeight * 0.35f));
            }
        }
    }

    /**
     * Helper: check if two rectangles overlap with tolerance
     */
    private boolean rectsOverlap(RectF a, RectF b, float tolerance) {
        return (a.left - tolerance <= b.right && a.right + tolerance >= b.left);
    }

    /**
     * Get copy of persistent zones for UI
     */
    public List<RectF> getPersistentOccupiedZones() {
        return new ArrayList<>(persistentOccupiedZones);
    }

    /**
     * Clear persistent zones (e.g., on band change)
     */
    public void clearPersistentOccupiedZones() {
        persistentOccupiedZones.clear();
        persistentOccupiedZonesAge = 0;
    }
    // =======================================

    // ========================================================================
    // [NEW] Secure config wrapper for UI convenience
    // Delegates to DatabaseOpr.saveSensitiveConfig() for encryption
    // ========================================================================
    /**
     * Save sensitive value using secure storage (if available).
     * Wrapper for databaseOpr.saveSensitiveConfig() for UI convenience.
     * @param key Config key name
     * @param value Plain text value to store
     */
    public void saveSensitiveConfig(String key, String value) {
        if (databaseOpr != null) {
            databaseOpr.saveSensitiveConfig(key, value);
        }
    }
    // ========================================================================
    // [END NEW] Secure config wrapper
    // ========================================================================

    // [NEW] Public method for UI: request manual CQ transmission
    // This method sets a flag and triggers state machine evaluation
    public void requestManualCQ() {
        Log.d(TAG, "[UI_REQUEST] Manual CQ requested from UI");
        stationContext.manualCQRequested = true;

        // Immediately re-evaluate state (do not wait for next slot)
        // [FIX] executeAction requires (StationAction, long currentSlot)
        evaluateStateMachine(null, UtcTimer.getNowSequential());

    }

    // [NEW] Check if manual control is allowed in current state
    // Returns true only if we are in OPERATING mode and SEEKING substate
    public boolean isManualControlAllowed() {
        return stationContext.opMode == StationState.OperationalMode.OPERATING &&
                stationContext.subState == StationState.OperatingSubState.SEEKING &&
                !stationContext.userOverrideActive;
    }

    private StationAction processManualCQRequest() {
        Log.d(TAG, "[MANUAL_CQ] Processing request in state=" + stationContext.subState);

        if (!isManualControlAllowed()) {
            Log.w(TAG, "[MANUAL_CQ] Rejected: manual control not allowed (opMode=" +
                    stationContext.opMode + " subState=" + stationContext.subState + ")");
            stationContext.resetManualCQRequest();
            // [FIX] Use factory method instead of private constructor
            return StationAction.wait("Manual CQ rejected by state");
        }

        // Create TRANSMIT action for CQ using factory method
        // Factory method signature (based on StationAction.java):
        // transmit(String callsign, int step, String reason, long freqHz, int snr, int i3, int n3, String extraInfo)
        StationAction cqAction = StationAction.transmit(
                "CQ",                                    // callsign - target to transmit to
                6,                                       // step - protocol step (6 = CQ_MODE)
                "Manual CQ requested from UI",           // reason - for logging/debugging
                GeneralVariables.band,                   // freqHz - frequency in Hz for transmission
                0,                                       // snr - measured SNR (0 for CQ)
                0,                                       // i3 - hash part 1 (0 for CQ)
                0,                                       // n3 - hash part 2 + grid (0 for CQ)
                ""                                       // extraInfo - additional text (grid, report, etc.)
        );

        // Reset flag after creating action
        stationContext.resetManualCQRequest();

        Log.d(TAG, "[MANUAL_CQ] Created action: " + cqAction.type + " target=" + cqAction.targetCallsign);
        return cqAction;
    }
    // MainViewModel.java, add getters for Fragment access

    // [NEW] Getter for stationContext.opMode (for UI feedback)
    public StationState.OperationalMode getStationContextOpMode() {
        return stationContext.opMode;
    }

    // [NEW] Getter for stationContext.subState (for UI feedback)
    public StationState.OperatingSubState getStationContextSubState() {
        return stationContext.subState;
    }

    // [NEW] Manual call to specific station - updates state machine context
    public void manualCallStation(String callsign, int i3, int n3, String extraInfo, long freqHz, int snr) {
        Log.d(TAG, "[MANUAL_CALL] Requested for " + callsign);

        // [FIX] DETERMINE PARTNER SEQUENTIAL FROM DECODED MESSAGES
        // Prepare context for state machine consistency without bypassing logic
        int partnerSequential = findPartnerSequentialFromMessages(callsign);

        // Update RAM cache if partner slot is known (public field, safe to modify)
        if (partnerSequential >= 0) {
            DatabaseOpr.StationRecord record = DatabaseOpr.getStationRecord(callsign);
            if (record != null) {
                // Record exists in cache - update sequential field directly
                record.lastSequential = partnerSequential;
                Log.d(TAG, "[SEQUENTIAL] Updated cache: " + callsign + " seq=" + partnerSequential);
            }
            // If record is null, cache will be populated later by normal decode flow
            // We still use partnerSequential for immediate transmission
        }

        // Calculate our transmission sequential based on partner slot
        int ourSequential;
        if (partnerSequential >= 0) {
            ourSequential = 1 - partnerSequential; // Invert partner's TX slot
        } else {
            // Fallback: invert current UTC slot when partner info is missing
            ourSequential = 1 - (int)(UtcTimer.getNowSequential() % 2);
        }

        Log.d(TAG, "[SEQUENTIAL] Partner=" + partnerSequential + " -> Our=" + ourSequential);

        // [FIX] Activate transmission signal if not already active
        if (!ft8TransmitSignal.isActivated()) {
            ft8TransmitSignal.setActivated(true);
            Log.d(TAG, "[MANUAL_CALL] Transmission signal activated");
        }

        // Trigger transmission with PRE-CALCULATED sequential (not -1!)
        // This preserves state machine context: next decode will update cache properly
        ft8TransmitSignal.setTransmit(
                new TransmitCallsign(i3, n3, callsign, freqHz, ourSequential, snr),
                1,
                extraInfo
        );

    /* OLD CODE COMMENTED OUT
    DatabaseOpr.StationRecord record = DatabaseOpr.getStationRecord(callsign);
    if (record != null && record.lastSequential >= 0) {
        int expectedOurSlot = (record.lastSequential == 0) ? 1 : 0;
        long currentSlot = UtcTimer.getNowSequential();
        if (currentSlot % 2 != expectedOurSlot) {
            Log.w(TAG, "[MANUAL_CALL] Wrong slot! ... Waiting for correct slot...");
            return;
        }
    }
    ft8TransmitSignal.setTransmit(
            new TransmitCallsign(i3, n3, callsign, freqHz, -1, snr),
            1,
            extraInfo
    );
    */
    }

    private int findPartnerSequentialFromMessages(String callsign) {
        if (GeneralVariables.transmitMessages == null) {
            return -1;
        }
        // Search for messages FROM this callsign (they are transmitting CQ or reply)
        for (Ft8Message msg : GeneralVariables.transmitMessages) {
            if (msg != null && msg.getCallsignFrom().equals(callsign)) {
                Log.d(TAG, "[SEQ_DETECT] Found msg from " + callsign +
                        " in slot " + msg.getSequence() + ": " + msg.toString());
                return msg.getSequence();
            }
        }
        return -1;
    }
    /**
     * [NEW] Send custom quick-message to target station.
     * Formats: "TARGET MYCALL SWR" or "TARGET MYCALL RSWR"
     * Updates state machine context to prevent automatic CQ override.
     * @param targetCallsign Station to call
     * @param customSuffix Suffix to append (e.g., "SWR", "RSWR")
     */
    public void sendCustomTransmission(String targetCallsign, String customSuffix) {
        Log.d(TAG, "[CUSTOM_TX] Requested: to=" + targetCallsign + " suffix=" + customSuffix);

        // [STATE LOCK] Prevent DecisionEngine from switching to CQ mode
        stationContext.userOverrideActive = true;
        stationContext.currentTarget = targetCallsign;
        stationContext.subState = StationState.OperatingSubState.IN_DIALOGUE;
        stationContext.step = StationState.DialogueStep.CALLING;
        stationContext.noReplyCount = 0;
        stationContext.lastReplySlot = UtcTimer.getNowSequential();

        // Activate transmitter if idle
        if (!ft8TransmitSignal.isActivated()) {
            ft8TransmitSignal.setActivated(true);
        }

        // Build message: TO_CALLSIGN MY_CALLSIGN [SUFFIX]
        String messageText = targetCallsign + " " + GeneralVariables.myCallsign + " " + customSuffix;
        String upperMsg = messageText.toUpperCase();

        // [FIX] Skip creating Ft8Message object - no no-arg constructor available
        // Transmission is handled directly by setTransmit(), UI will show it via normal decode flow

        // === [CRITICAL] FT8 SEQUENTIAL LOGIC ===
        // Pass -1 for sequential to trigger auto-inversion in FT8TransmitSignal
        // This ensures we always respond in the opposite slot from our partner
        // ==========================================

        // Trigger transmission directly
        ft8TransmitSignal.setTransmit(
                new TransmitCallsign(0, 0, targetCallsign, GeneralVariables.band, -1, 0),  // [FIX] -1 for auto-inverted sequential
                1,  // Protocol step 1 (initial call)
                upperMsg
        );
        ft8TransmitSignal.transmitNow();

        Log.d(TAG, "[CUSTOM_TX] Started: " + upperMsg);
    }

    // ========================================================================
// [NEW] Dialogue step advancement helper
// Uses ft8StateRelative from World Model as single source of truth
// ========================================================================
    /**
     * Advance dialogue step based on partner's state in World Model.
     * [STATE MACHINE] This ensures we always respond to the correct step.
     *
     * ft8StateRelative meaning (what PARTNER sent to us):
     * 0 = unknown/CQ
     * 1 = grid (response to CQ)
     * 2 = report (-XX)
     * 3 = R-report (R-XX)
     * 4 = RR73/RRR
     *
     * We respond with step = theirState + 1
     *
     * @param msg The message that triggered this check (for logging)
     * @param ctx Current station context (will be updated)
     */
    private void advanceDialogueStep(Ft8Message msg, StationContext ctx) {
        // Only process if we have a current dialogue target
        if (ctx.currentTarget == null || ctx.currentTarget.isEmpty()) {
            return;
        }

        // Get station record from World Model (RAM cache)
        DatabaseOpr.StationRecord record = DatabaseOpr.getStationRecord(ctx.currentTarget);
        if (record == null) {
            Log.w(TAG, "[STEP_ADVANCE] No record found for " + ctx.currentTarget);
            return;
        }

        int theirState = record.ft8StateRelative;
        Log.d(TAG, "[STEP_ADVANCE] " + ctx.currentTarget + " ft8StateRelative=" + theirState +
                " (our step=" + ctx.step + ")");

        // Map their state to our expected response step
        // theirState: what THEY sent | our response: what WE should send next
        // 1 (grid)    -> we send report (step 2)
        // 2 (report)  -> we send R-report (step 3)
        // 3 (R-report)-> we send RR73 (step 4)
        // 4 (RR73)    -> we send 73 (step 5)

        if (theirState >= 1 && theirState <= 4) {
            // DialogueStep enum is 0-based: IDLE=0, CALLING=1, REPORT=2, CONFIRM=3, CLOSING=4, FINISHED=5
            // So theirState=1 (grid) -> we want step=2 (REPORT enum index)
            int targetStepIndex = theirState; // Direct mapping: theirState 1->enum[1]=CALLING, but we want next...

            // Actually: if they sent step N, we respond with step N+1
            // But ft8StateRelative is what they SENT, so:
            // they sent 1 (grid) -> we respond with 2 (report) -> enum index 2 = REPORT
            // they sent 2 (report) -> we respond with 3 (R-report) -> enum index 3 = CONFIRM
            // etc.

            StationState.DialogueStep[] steps = StationState.DialogueStep.values();
            if (theirState < steps.length) {
                StationState.DialogueStep expectedOurStep = steps[theirState];
                Log.d(TAG, "[STEP_ADVANCE DEBUG] target=" + ctx.currentTarget +
                        " theirState=" + theirState +
                        " expectedOurStep=" + expectedOurStep +
                        " currentCtxStep=" + ctx.step +
                        " willUpdate=" + (expectedOurStep != ctx.step) +
                        " record.ft8StateRelative=" + record.ft8StateRelative);
                if (expectedOurStep != ctx.step) {
                    Log.d(TAG, "[STEP_ADVANCE] Updating: " + ctx.step + " -> " + expectedOurStep +
                            " (based on their ft8StateRelative=" + theirState + ")");
                    ctx.step = expectedOurStep;
                    ctx.lastReplySlot = UtcTimer.getNowSequential(); // Reset timeout on progress
                }
            }
        }
    }

// [FIX-2026-05-17] BEGIN: Rewritten to work with fork's database schema
    /**
     * Load worked DXCC/CQ/ITU entities from QSL log into memory maps.
     * Fork-specific: QslCallsigns table does not contain dxcc/cq_zone/itu_zone columns.
     * Solution: Query callsigns from log, then look up location info via CallsignDatabase.
     */
    private void initWorkedEntitiesFromQSL() {
        Log.d("DXCC_INIT", "=== initWorkedEntitiesFromQSL START ===");

        if (databaseOpr == null) {
            Log.e("DXCC_INIT", "databaseOpr is NULL!");
            return;
        }

        // [FIX-2026-05-17] Ensure callsignDatabase is initialized
        if (GeneralVariables.callsignDatabase == null) {
            Log.w("DXCC_INIT", "callsignDatabase not initialized, attempting now...");
            GeneralVariables.callsignDatabase = CallsignDatabase.getInstance(
                    GeneralVariables.getMainContext(), "callsigns.db", 19);
        }

        if (GeneralVariables.callsignDatabase == null) {
            Log.e("DXCC_INIT", "FAILED to initialize callsignDatabase - icons will not work!");
            return;
        }

        Log.d("DXCC_INIT", "callsignDatabase ready, clearing maps...");
        GeneralVariables.dxccMap.clear();
        GeneralVariables.cqMap.clear();
        GeneralVariables.ituMap.clear();

        try {
            // Get all unique callsigns from the QSL log
            Cursor c = databaseOpr.getDb().rawQuery(
                    "SELECT DISTINCT callsign FROM QslCallsigns WHERE callsign IS NOT NULL AND callsign != ''", null);

            ArrayList<String> callsignList = new ArrayList<>();
            if (c.moveToFirst()) {
                do {
                    String callsign = c.getString(0);
                    if (callsign != null && !callsign.isEmpty()) {
                        callsignList.add(callsign);
                    }
                } while (c.moveToNext());
            }
            c.close();

            Log.d("DXCC_INIT", "Found " + callsignList.size() + " unique callsigns in log");

            // Process callsigns sequentially using async callback
            processCallsignsForDXCC(callsignList, 0);

        } catch (Exception e) {
            Log.e("DXCC_INIT", "Exception: " + e.getMessage(), e);
        }
    }

    // Check if callsign is in recentTargets list
// [FIX] Check if callsign is in recentTargets list (for SOFT_FINISH logic)
    public boolean isInRecentTargets(String callsign) {
        if (callsign == null || stationContext == null || stationContext.recentTargets == null) return false;
        for (String target : stationContext.recentTargets) {
            if (target != null && target.equalsIgnoreCase(callsign)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recursively process callsign list to populate DXCC/CQ/ITU maps.
     * Uses async callback to avoid blocking UI thread.
     */
    private void processCallsignsForDXCC(final ArrayList<String> callsignList, final int index) {
        if (index >= callsignList.size()) {
            // Done - log final stats
            Log.d("DXCC_INIT", "Final maps: " + GeneralVariables.dxccMap.size() + " DXCC, " +
                    GeneralVariables.cqMap.size() + " CQ, " + GeneralVariables.ituMap.size() + " ITU");
            Log.d("DXCC_INIT", "dxccMap contains 'YO': " + GeneralVariables.dxccMap.containsKey("YO"));
            Log.d("DXCC_INIT", "dxccMap contains 'IU': " + GeneralVariables.dxccMap.containsKey("IU"));
            Log.d("DXCC_INIT", "=== initWorkedEntitiesFromQSL END ===");
            return;
        }

        final String callsign = callsignList.get(index);

        GeneralVariables.callsignDatabase.getCallsignInformation(callsign, new OnAfterQueryCallsignLocation() {
            @Override
            public void doOnAfterQueryCallsignLocation(CallsignInfo info) {
                if (info != null && info.DXCC != null && !info.DXCC.isEmpty()) {
                    GeneralVariables.dxccMap.put(info.DXCC.toUpperCase(), info.DXCC.toUpperCase());
                    if (info.CQZone > 0) GeneralVariables.cqMap.put(info.CQZone, info.CQZone);
                    if (info.ITUZone > 0) GeneralVariables.ituMap.put(info.ITUZone, info.ITUZone);

                    // Log first 10 for debugging
                    if (GeneralVariables.dxccMap.size() <= 10) {
                        Log.d("DXCC_INIT", "Loaded: " + callsign + " → " +
                                info.DXCC + " CQ" + info.CQZone + " ITU" + info.ITUZone);
                    }
                }
                // Process next callsign
                processCallsignsForDXCC(callsignList, index + 1);
            }
        });
    }
// [FIX-2026-05-17] END

}