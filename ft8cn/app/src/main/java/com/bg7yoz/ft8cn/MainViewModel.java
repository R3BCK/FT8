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
 *
 * FREQUENCY NAMING CONVENTION (IMPORTANT!):
 * - audioFreqHz: Audio frequency 0-3000 Hz (FT8 tone in audio stream)
 *   Example: 1744 Hz (tone frequency of decoded signal)
 * - rfFreqHz / rfBandHz: Radio frequency in Hz (transceiver band)
 *   Example: 18100000 Hz = 18.1 MHz (17m amateur band)
 * - GeneralVariables.band: RF band frequency (always RF!)
 * - GeneralVariables.getBaseFrequency(): Audio base frequency (~1000-2000 Hz)
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
import android.os.Build;
import android.view.WindowManager;

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

    // [FIX] Prevent duplicate state machine evaluations for the same decode cycle
    // Uses UTC time from decoder (start of slot), NOT system time
    // This is unique per decode cycle and solves the empty-slot bug
    private long lastEvaluatedUtc = 0;

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
        public String currentTarget = "";
        public Set<String> recentTargets = new HashSet<>();
        public int noReplyCount = 0;
        public long lastReplySlot = 0;

        // Configuration flags
        public boolean dxModeEnabled = false;
        public boolean userOverrideActive = false;
        // [NEW] Flag: manual CQ request from UI
        public boolean manualCQRequested;

        // === Helper methods for state transitions ===

        public void resetToSeeking() {
            subState = StationState.OperatingSubState.SEEKING;
            step = StationState.DialogueStep.IDLE;
            currentTarget = "";
            noReplyCount = 0;
            userOverrideActive = false;
            Log.d(TAG, "State: resetToSeeking()");
        }

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

        public void resetManualCQRequest() {
            this.manualCQRequested = false;
        }

        public boolean isInDialogue() {
            return subState == StationState.OperatingSubState.IN_DIALOGUE;
        }
    }
    // ========================================================================
    // [END STATE MACHINE] Context
    // ========================================================================

    public StationContext stationContext;

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

        /**
         * Called when transceiver RF frequency changes.
         * @param rfFreqHz RF frequency in Hz (e.g. 18100000 = 18.1 MHz)
         */
        @Override
        public void onFreqChanged(long rfFreqHz) {
            ToastMessage.show(String.format(getStringFromResource(R.string.current_frequency)
                    , BaseRigOperation.getFrequencyAllInfo(rfFreqHz)));
            // [RF_FREQ] GeneralVariables.band stores RF band frequency (MHz range)
            GeneralVariables.band = rfFreqHz;
            GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(rfFreqHz);
            GeneralVariables.mutableBandChange.postValue(GeneralVariables.bandListIndex);
            databaseOpr.getAllQSLCallsigns();

            if (GeneralVariables.sendTuneOnFreqChange && baseRig != null && baseRig.isConnected()) {
                scheduleTuneCommand();
            }
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
        GeneralVariables.callsignDatabase = CallsignDatabase.getInstance(
                GeneralVariables.getMainContext(), "callsigns.db", 19);
        Log.d("DXCC_INIT", "callsignDatabase initialized: " + (GeneralVariables.callsignDatabase != null));

        databaseOpr = DatabaseOpr.getInstance(GeneralVariables.getMainContext(), "data.db");
        mutableIsDecoding.postValue(false);

        hamRecorder = new HamRecorder(null);
        RecordingForegroundService.start(GeneralVariables.getMainContext());
        Log.d(TAG, "Foreground recording service started");

        hamRecorder.startRecord();

        mutableIsFlexRadio.setValue(false);
        mutableIsXieguRadio.setValue(false);

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

        try {
            String libName = GeneralVariables.acceptDxCalls ? "ft8cn_dx" : "ft8cn_std";
            System.loadLibrary(libName);
            Log.d(TAG, "Loaded native library: " + libName);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load library: " + e.getMessage());
            try {
                System.loadLibrary("ft8cn_std");
                Log.d(TAG, "Fallback: loaded ft8cn_std");
            } catch (UnsatisfiedLinkError e2) {
                Log.e(TAG, "Fallback failed: " + e2.getMessage());
            }
        }

        stationContext = new StationContext();
        stationContext.dxModeEnabled = GeneralVariables.acceptDxCalls;
        Log.d(TAG, "State Machine: initialized with dxModeEnabled=" + stationContext.dxModeEnabled);

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

                Log.d(TAG, String.format("[DECODE] === Slot %d | Messages: %d | Deep: %s ===",
                        sequential, messages.size(), isDeep));

                for (Ft8Message msg : messages) {
                    String from = msg.getCallsignFrom() != null ? msg.getCallsignFrom() : "?";
                    String to = msg.getCallsignTo() != null ? msg.getCallsignTo() : "?";
                    String extra = msg.extraInfo != null ? msg.extraInfo : "";
                    int snr = msg.snr;
                    // [AUDIO_FREQ] msg.freq_hz is AUDIO frequency (FT8 tone, 0-3000 Hz)
                    // NOT RF frequency! This is the audio tone position in the decoded audio stream
                    float audioFreqHz = msg.freq_hz;

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

                    boolean toMe = GeneralVariables.checkIsMyCallsign(to);
                    String marker = toMe ? " → US" : "";

                    Log.d(TAG, String.format("[DECODE] %s → %s%s | %-8s | %-6s | SNR=%+d audioFreqHz=%.0fHz",
                            from, to, marker, msgType,
                            extra.isEmpty() ? "(empty)" : extra,
                            snr, audioFreqHz));
                }

                synchronized (ft8Messages) {
                    ft8Messages.addAll(messages);
                }
                GeneralVariables.deleteArrayListMore(ft8Messages);
                mutableFt8MessageList.postValue(ft8Messages);
                mutableTimerOffset.postValue(time_sec);

                for (Ft8Message msg : messages) {
                    databaseOpr.updateStationFromMessage(msg, msg.maidenGrid, null, 0, 0, 0);
                }

                // [FIX] Pass BOTH utc and sequential to evaluateStateMachine
                // utc is the unique identifier for this decode cycle (from decoder)
                // sequential is the slot number (0 or 1)
                evaluateStateMachine(messages, utc, sequential);
                findIncludedCallsigns(messages);

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

        spectrumListener = new SpectrumListener(hamRecorder);

        ft8TransmitSignal = new FT8TransmitSignal(databaseOpr, new OnDoTransmitted() {
            private boolean needControlSco() {
                if (GeneralVariables.connectMode == ConnectMode.NETWORK) return false;
                if (GeneralVariables.controlMode != ControlMode.CAT) return true;
                return baseRig != null && !baseRig.supportWaveOverCAT();
            }

            @Override
            public void onBeforeTransmit(Ft8Message message, int functionOder) {
                Log.w(TAG, "[ON_BEFORE_TX] ========================================");
                Log.w(TAG, "[ON_BEFORE_TX] onBeforeTransmit() CALLED");
                Log.w(TAG, "[ON_BEFORE_TX]   message=" + message.getMessageText());
                Log.w(TAG, "[ON_BEFORE_TX]   functionOrder=" + functionOder);
                Log.w(TAG, "[ON_BEFORE_TX]   controlMode=" + GeneralVariables.controlMode);

                if (GeneralVariables.controlMode == ControlMode.CAT
                        || GeneralVariables.controlMode == ControlMode.RTS
                        || GeneralVariables.controlMode == ControlMode.DTR) {
                    if (baseRig != null) {
                        if (needControlSco()) {
                            Log.w(TAG, "[ON_BEFORE_TX] Stopping SCO");
                            stopSco();
                        }
                        Log.w(TAG, "[ON_BEFORE_TX] CALLING baseRig.setPTT(true) — PTT ACTIVATION");
                        baseRig.setPTT(true);
                        Log.w(TAG, "[ON_BEFORE_TX]  baseRig.setPTT(true) completed");
                    } else {
                        Log.e(TAG, "[ON_BEFORE_TX]  baseRig is NULL, cannot set PTT");
                    }
                } else {
                    Log.w(TAG, "[ON_BEFORE_TX]  PTT blocked: controlMode=" + GeneralVariables.controlMode);
                }

                if (isBatteryTooLow(GeneralVariables.getMainContext())) {
                    ToastMessage.show("Transmit blocked: Low battery < " + BATTERY_LOW_THRESHOLD_PERCENT + "%");
                    Log.w(TAG, "[ON_BEFORE_TX] ========================================");
                    return;
                }

                if (GeneralVariables.connectMode == ConnectMode.USB_CABLE) {
                    if (baseRig != null && !baseRig.isConnected()) {
                        ToastMessage.show("Transmit blocked: USB device disconnected");
                        Log.w(TAG, "[ON_BEFORE_TX] ========================================");
                        return;
                    }
                }

                if (ft8TransmitSignal.isActivated()) {
                    GeneralVariables.transmitMessages.add(message);
                    mutableTransmitMessagesCount.postValue(1);
                    Log.w(TAG, "[ON_BEFORE_TX] Message added to transmit queue");
                }
                Log.w(TAG, "[ON_BEFORE_TX] ========================================");
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

        startTransmissionWatchdog();
    }

    private boolean isNetworkRigReachable() {
        if (GeneralVariables.connectMode != ConnectMode.NETWORK) return true;

        String rigIp = GeneralVariables.getNetworkRigIp();
        int rigPort = GeneralVariables.getNetworkRigPort();

        if (rigIp == null || rigIp.isEmpty() || rigPort <= 0) {
            Log.w(TAG, "Network rig: IP/port not configured");
            return false;
        }

        try {
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

        RecordingForegroundService.stop(GeneralVariables.getMainContext());
        Log.d(TAG, "Foreground recording service stopped");

        if (transmissionWatchdogHandler != null && transmissionWatchdogRunnable != null) {
            transmissionWatchdogHandler.removeCallbacks(transmissionWatchdogRunnable);
            Log.d(TAG, "Transmission watchdog stopped");
        }
        if (hamRecorder != null) {
            hamRecorder.stopRecord();
            Log.d(TAG, "HamRecorder stopped in onCleared()");
        }
    }

    private void startTransmissionWatchdog() {
        transmissionWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (ft8TransmitSignal.isActivated() && !hamRecorder.isRunning()) {
                        Log.w(TAG, "WATCHDOG: Transmit active but hamRecorder.isRunning()=false! Recovering...");

                        try {
                            hamRecorder.startRecord();
                            mutableIsRecording.postValue(true);
                            Log.d(TAG, "HamRecorder restarted by watchdog");
                            ToastMessage.show("Recording recovered");
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to restart HamRecorder: " + e.getMessage(), e);
                        }
                    }

                    if (GeneralVariables.connectMode != ConnectMode.NETWORK && !hamRecorder.isRunning()) {
                        //Log.d(TAG, "Watchdog: Mic mode but not recording - may need audio focus recovery");
                    }

                    if (baseRig != null && baseRig.isConnected() && baseRig.isPttOn()) {
                        // PTT has been on too long? Could indicate stuck state
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Watchdog error: " + e.getMessage(), e);
                }

                transmissionWatchdogHandler.postDelayed(this, WATCHDOG_CHECK_INTERVAL_MS);
            }
        };
        transmissionWatchdogHandler.post(transmissionWatchdogRunnable);
        Log.d(TAG, "Aggressive transmission watchdog started (10s interval)");
    }

    // ========================================================================
    // [STATE MACHINE + DECISION ENGINE] Core Evaluator Method
    // ========================================================================
    /**
     * Evaluates state transitions based on decoded messages and current context.
     * Called every decode slot (~15 seconds) from ft8SignalListener.afterDecode().
     *
     * @param messages List of newly decoded FT8 messages
     * @param utc UTC time from decoder - unique identifier for this decode cycle
     * @param currentSlot Current slot number (0 or 1)
     */
    private void evaluateStateMachine(ArrayList<Ft8Message> messages, long utc, int currentSlot) {
        // [FIX] Check uniqueness by UTC time from decoder, not by slot number
        // Slot numbers (0/1) repeat every 30 seconds, causing false SKIP
        // UTC from decoder is unique per decode cycle
        if (utc == lastEvaluatedUtc) {
            Log.d(TAG, "[SKIP] Slot " + currentSlot + " (UTC " + utc + ") already processed");
            return;
        }
        lastEvaluatedUtc = utc;
        Log.d(TAG, "[SLOT] Processing slot " + currentSlot + " (UTC " + utc + ")");

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
            } else {
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
     */
    private DecisionContext buildDecisionContext(ArrayList<Ft8Message> messages, long currentSlot) {
        StationContext ctx = stationContext;

        // [RF_FREQ] GeneralVariables.band is RF band frequency (e.g. 18100000 Hz = 18.1 MHz)
        long currentBandBit = DatabaseOpr.freqToBandBit(GeneralVariables.band);
        long bandMask = 1L << currentBandBit;

        List<DatabaseOpr.StationRecord> worldModelSnapshot = DatabaseOpr.getStationWorldModelSnapshot();

        Log.d(TAG, "[DEBUG] worldModelSnapshot count: " + worldModelSnapshot.size());
        for (DatabaseOpr.StationRecord s : worldModelSnapshot) {
            Log.d(TAG, "[DEBUG]   WM: " + s.callsign + " state=" + s.ft8StateRelative);
        }

        List<DatabaseOpr.StationRecord> visibleStations = new ArrayList<>();
        for (DatabaseOpr.StationRecord s : worldModelSnapshot) {
            if ((s.bandsBitmap & bandMask) != 0 && !s.isExpired()) {
                visibleStations.add(s);
            }
        }

        DatabaseOpr.StationRecord directCaller = null;
        if (messages != null) {
            for (Ft8Message msg : messages) {
                if (GeneralVariables.checkIsMyCallsign(msg.getCallsignTo()) && !msg.checkIsCQ()) {
                    directCaller = DatabaseOpr.getStationRecord(msg.getCallsignFrom());
                    Log.d(TAG, "[DEBUG] Found direct caller: " + msg.getCallsignFrom());
                    break;
                }
            }
        }

        float[] weights = new float[Criterion.values().length];
        for (Criterion c : Criterion.values()) {
            weights[c.ordinal()] = c.baseWeight;
        }

        long slotStartSec = (currentSlot * 15);
        long nowSec = System.currentTimeMillis() / 1000;
        long timeUntilTxDeadline = Math.max(0, (slotStartSec + 12) - nowSec) * 1000;

        return new DecisionContext(
                ctx.opMode,
                ctx.subState,
                ctx.step,
                ctx.currentTarget,
                ctx.noReplyCount,
                ctx.lastReplySlot,
                ctx.recentTargets,
                visibleStations,
                directCaller,
                currentSlot,
                timeUntilTxDeadline,
                GeneralVariables.noReplyLimit,
                GeneralVariables.autoFollowCQ,
                GeneralVariables.acceptDxCalls,
                weights,
                false,
                ctx.userOverrideActive,
                false,
                ft8TransmitSignal.isActivated()
        );
    }

    /**
     * Execute the decided action.
     */
    private void executeAction(StationAction action, long currentSlot) {
        // [RF_FREQ] action.freqHz contains RF band frequency from DecisionEngine
        // (set to GeneralVariables.band in createTransmitAction)
        Log.w(TAG, "[EXECUTE] type=" + action.type + " target=" + action.targetCallsign +
                " step=" + action.protocolStep + " rfBandHz=" + action.freqHz +
                " reason=\"" + action.reason + "\"");
        switch (action.type) {

            case TRANSMIT:
                if (!ft8TransmitSignal.isActivated()) {
                    Log.d(TAG, "[ACTION] TRANSMIT blocked: transmission manually disabled");
                    return;
                }
                Log.d(TAG, "[ACTION] TRANSMIT to " + action.targetCallsign +
                        " step=" + action.protocolStep + " reason=\"" + action.reason + "\"");
                Log.d(TAG, "[ACTION] Technical params: rfBandHz=" + action.freqHz +
                        " snr=" + action.snr + " i3=" + action.i3 + " n3=" + action.n3);

                if (!ft8TransmitSignal.isActivated()) {
                    ft8TransmitSignal.setActivated(true);
                    Log.d(TAG, "[ACTION] Transmit signal activated");
                }

                long txFrequency = (action.freqHz > 0) ? action.freqHz : GeneralVariables.band;
                String txExtraInfo = (action.extraInfo != null) ? action.extraInfo : "";

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

                Log.d(TAG, "[TX_TRIGGER] MainViewModel.executeAction(TRANSMIT) → setTransmit(" + action.targetCallsign + ", step=" + action.protocolStep + ", rfBandHz=" + txFrequency + ")");
                ft8TransmitSignal.setTransmit(
                        new TransmitCallsign(action.i3, action.n3, action.targetCallsign,
                                txFrequency, txSequential, action.snr),
                        action.protocolStep, txExtraInfo);

                // [FIX] ЯВНО запускаем передачу!
                // Раньше это делал автономный таймер в FT8TransmitSignal (мы его отключили).
                // Теперь MainViewModel сам решает КОГДА передавать.
                ft8TransmitSignal.transmitNow();
                Log.d(TAG, "[TX_TRIGGER] transmitNow() called — transmission started");

                if (action.protocolStep == 5) {
                    Log.d(TAG, "[STATE TRANSITION] 73 sent. QSO completed, returning to SEEKING");
                    // Сразу возвращаемся в SEEKING вместо SOFT_FINISH
                    stationContext.subState = StationState.OperatingSubState.SEEKING;
                    stationContext.step = StationState.DialogueStep.IDLE;
                    stationContext.currentTarget = "";
                    stationContext.noReplyCount = 0;
                    Log.d(TAG, "State: returned to SEEKING after 73");
                    return;
                }

                if (action.targetCallsign != null &&
                        !action.targetCallsign.equals(stationContext.currentTarget)) {
                    stationContext.noReplyCount = 0;
                    Log.d(TAG, "[NEW TARGET] Switched to " + action.targetCallsign + ", reset counter");
                }

                Log.d(TAG, "[ACTION] TX started (noReplyCount=" + stationContext.noReplyCount +
                        " to " + action.targetCallsign + ")");
                break;

            case WAIT:
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
                Log.d(TAG, "[TX_TRIGGER] MainViewModel.executeAction(ABORT) → resetToCQ()");
                ft8TransmitSignal.resetToCQ();
                stationContext.resetToSeeking();
                break;

            case RESUME:
                Log.d(TAG, "[ACTION] RESUME dialogue with " + action.targetCallsign);
                stationContext.currentTarget = action.targetCallsign;
                stationContext.subState = StationState.OperatingSubState.IN_DIALOGUE;
                stationContext.step = StationState.DialogueStep.CALLING;
                stationContext.lastReplySlot = currentSlot;
                stationContext.noReplyCount = 0;
                break;

            case NOMADIC_SWITCH:
                // [RF_FREQ] action.targetFrequency is RF frequency for new band
                Log.d(TAG, "[ACTION] NOMADIC_SWITCH rfBandHz=" + action.targetFrequency +
                        " reason=\"" + action.reason + "\"");
                // [RF_FREQ] nextRfFreqHz is RF frequency for the next band
                long nextRfFreqHz = getNextNomadicFrequency();
                if (nextRfFreqHz > 0) {
                    GeneralVariables.band = nextRfFreqHz;
                    GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(nextRfFreqHz);
                    if (baseRig != null && baseRig.isConnected()) {
                        setOperationBand();
                    }
                }
                break;

            case SCAN_AUDIO:
                Log.d(TAG, "[ACTION] SCAN_AUDIO reason=\"" + action.reason + "\"");
                stationContext.opMode = StationState.OperationalMode.SCANNING_AUDIO;
                stationContext.resetToSeeking();
                break;

            case NO_OP:
                Log.d(TAG, "[ACTION] NO_OP reason=\"" + action.reason + "\"");
                break;

            case TX_OWN_CQ:
                Log.d(TAG, "[ACTION] TX_OWN_CQ: Transmitting own CQ (priority reset)");
                Log.d(TAG, "[TX_TRIGGER] MainViewModel.executeAction(TX_OWN_CQ) → resetToCQ()");
                ft8TransmitSignal.resetToCQ();

                if (!ft8TransmitSignal.isActivated()) {
                    ft8TransmitSignal.setActivated(true);
                }

                stationContext.noReplyCount = 0;
                stationContext.lastReplySlot = currentSlot;
                break;

            default:
                Log.w(TAG, "[ACTION] Unknown action type: " + action.type);
                break;
        }
    }

    /**
     * Get next RF frequency for Nomadic mode (simplified: cycle through preset list).
     * @return RF band frequency in Hz (e.g. 14074000 = 14.074 MHz)
     * [NOMADIC MODE] Phase 1 implementation - can be extended with user presets.
     */
    private long getNextNomadicFrequency() {
        if (OperationBand.bandList == null || OperationBand.bandList.isEmpty()) {
            return 14074000L; // Default RF band: 14.074 MHz (20m FT8)
        }
        int nextIndex = (GeneralVariables.bandListIndex + 1) % OperationBand.bandList.size();
        return OperationBand.bandList.get(nextIndex).band;
    }

    // ========================================================================
    // [NEW] Reset state machine on frequency change / history clear
    // ========================================================================
    public void resetStateMachineOnFreqChange() {
        Log.d(TAG, "=== resetStateMachineOnFreqChange ===");

        // [FIX] Reset UTC tracker to allow evaluation on new frequency
        lastEvaluatedUtc = 0;

        stationContext.userOverrideActive = false;
        stationContext.currentTarget = "";
        stationContext.subState = StationState.OperatingSubState.SEEKING;
        stationContext.step = StationState.DialogueStep.IDLE;
        stationContext.noReplyCount = 0;
        stationContext.lastReplySlot = 0;

        if (decisionEngine != null) {
            decisionEngine.resetCache();
        }

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

    public void clearTransmittingMessage() {
        GeneralVariables.transmitMessages.clear();
        mutableTransmitMessagesCount.postValue(0);
    }

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

    public void clearFt8MessageList() {
        ft8Messages.clear();
        mutable_Decoded_Counter.postValue(ft8Messages.size());
        mutableFt8MessageList.postValue(ft8Messages);
    }

    public static void deleteFile(String fileName) {
        File file = new File(fileName);
        if (file.exists() && file.isFile()) file.delete();
    }

    public void addFollowCallsign(String callsign) {
        if (!GeneralVariables.followCallsign.contains(callsign)) {
            GeneralVariables.followCallsign.add(callsign);
            databaseOpr.addFollowCallsign(callsign);
        }
    }

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

    public void setOperationBand() {
        clearTransmittingMessage();

        if (GeneralVariables.myCallsign != null && !GeneralVariables.myCallsign.isEmpty()) {
            Log.d(TAG, "[TX_TRIGGER] MainViewModel.setOperationBand() → resetToCQ()");
            ft8TransmitSignal.resetToCQ();
        } else {
            Log.w(TAG, "setOperationBand: myCallsign not set, skipping resetToCQ");
        }

        Log.d(TAG, "=== setOperationBand DEBUG ===");
        Log.d(TAG, "controlMode=" + GeneralVariables.controlMode);
        Log.d(TAG, "connectMode=" + GeneralVariables.connectMode);
        Log.d(TAG, "baseRig=" + baseRig);
        Log.d(TAG, "isConnected=" + (baseRig != null && baseRig.isConnected()));
        Log.d(TAG, "supportWaveOverCAT=" + (baseRig != null ? baseRig.supportWaveOverCAT() : "N/A"));
        // [RF_FREQ] GeneralVariables.band is RF band frequency
        Log.d(TAG, "rfBandHz=" + GeneralVariables.band);

        if (!isRigConnected()) {
            Log.e(TAG, "ABORT: rig not connected");
            return;
        }
        if (GeneralVariables.controlMode != ControlMode.CAT) {
            Log.w(TAG, "WARNING: controlMode is not CAT, RF freq commands may be ignored");
        }
        if (!isRigConnected()) return;
        baseRig.setUsbModeToRig();
        new Handler().postDelayed(() -> {
            // [RF_FREQ] Setting RF frequency on transceiver
            baseRig.setFreq(GeneralVariables.band);
            baseRig.setFreqToRig();
        }, 800);
    }

    public void setCivAddress() {
        if (baseRig != null) baseRig.setCivAddress(GeneralVariables.civAddress);
    }

    public void setControlMode() {
        if (baseRig != null) baseRig.setControlMode(GeneralVariables.controlMode);
    }

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

    public boolean isRigConnected() {
        return baseRig != null && baseRig.isConnected();
    }

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

    public void getUsbDevice() {
        serialPorts = CableSerialPort.listSerialPorts(GeneralVariables.getMainContext());
        mutableSerialPorts.postValue(serialPorts);
    }

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

    public void stopSco() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (audioManager.isBluetoothScoOn()) {
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            audioManager.setSpeakerphoneOn(true);
        }
    }

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

    @SuppressLint("MissingPermission")
    public boolean isBTConnected() {
        BluetoothAdapter blueAdapter = BluetoothAdapter.getDefaultAdapter();
        if (blueAdapter == null) return false;
        int headset = blueAdapter.getProfileConnectionState(BluetoothProfile.HEADSET);
        int a2dp = blueAdapter.getProfileConnectionState(BluetoothProfile.A2DP);
        return headset == BluetoothAdapter.STATE_CONNECTED || a2dp == BluetoothAdapter.STATE_CONNECTED;
    }

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

    private static class SendWaveDataRunnable implements Runnable {
        BaseRig baseRig;
        Ft8Message message;
        @Override
        public void run() {
            if (baseRig != null && message != null) baseRig.sendWaveData(message);
        }
    }

    public void restartHttpServer(int newPort) {
        if (httpServer != null) httpServer.restartServer(newPort);
    }

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

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (baseRig != null && baseRig.isConnected()) {
                baseRig.setTune(IcomRigConstant.TUNER_START);

                if (GeneralVariables.clearCallHistOnFreqChange) {
                    clearTransmittingMessage();
                    ToastMessage.show("Calling history cleared");
                }
            }
        }, delayMs);
    }

    /**
     * Update persistent occupied zones from current decode results.
     * Call this when decoding finishes.
     * Uses AUDIO frequencies (0-3000 Hz) for spectrum display.
     */
    public void updatePersistentOccupiedZones(List<Ft8Message> messages, int viewWidth, int viewHeight) {
        if (messages == null || viewWidth == 0) return;

        persistentOccupiedZonesAge++;
        if (persistentOccupiedZonesAge >= ZONE_PERSIST_CYCLES) {
            persistentOccupiedZones.clear();
            persistentOccupiedZonesAge = 0;
        }

        for (Ft8Message msg : messages) {
            // [AUDIO_FREQ] msg.freq_hz is AUDIO frequency (0-3000 Hz) for spectrum display
            // NOT RF frequency! This maps FT8 audio tones to X-axis positions
            int audioFreqHz = (int) msg.freq_hz;
            if (audioFreqHz > 0 && audioFreqHz < 3000) {
                // Map audio frequency (0-3000 Hz) to pixel position on spectrum view
                float left = ((float)(audioFreqHz - 25) / 3000f) * viewWidth;
                float right = ((float)(audioFreqHz + 25) / 3000f) * viewWidth;
                left = Math.max(0, left);
                right = Math.min(viewWidth, right);

                persistentOccupiedZones.add(new RectF(left, 0, right, viewHeight * 0.35f));
            }
        }
    }

    private boolean rectsOverlap(RectF a, RectF b, float tolerance) {
        return (a.left - tolerance <= b.right && a.right + tolerance >= b.left);
    }

    public List<RectF> getPersistentOccupiedZones() {
        return new ArrayList<>(persistentOccupiedZones);
    }

    public void clearPersistentOccupiedZones() {
        persistentOccupiedZones.clear();
        persistentOccupiedZonesAge = 0;
    }

    public void saveSensitiveConfig(String key, String value) {
        if (databaseOpr != null) {
            databaseOpr.saveSensitiveConfig(key, value);
        }
    }

    public void requestManualCQ() {
        Log.d(TAG, "[UI_REQUEST] Manual CQ requested from UI");
        stationContext.manualCQRequested = true;

        // [FIX] Use current slot for immediate evaluation
        evaluateStateMachine(null, 0, UtcTimer.getNowSequential());
    }

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
            return StationAction.wait("Manual CQ rejected by state");
        }

        // [RF_FREQ] GeneralVariables.band is RF band frequency for CQ transmission
        StationAction cqAction = StationAction.transmit(
                "CQ",
                6,
                "Manual CQ requested from UI",
                GeneralVariables.band,
                0,
                0,
                0,
                ""
        );

        stationContext.resetManualCQRequest();

        Log.d(TAG, "[MANUAL_CQ] Created action: " + cqAction.type + " target=" + cqAction.targetCallsign);
        return cqAction;
    }

    public StationState.OperationalMode getStationContextOpMode() {
        return stationContext.opMode;
    }

    public StationState.OperatingSubState getStationContextSubState() {
        return stationContext.subState;
    }

    /**
     * Manual call to specific station - updates state machine context.
     *
     * @param callsign Target callsign
     * @param i3 Hash part 1 for FT8 encoding
     * @param n3 Hash part 2 + grid for FT8 encoding
     * @param extraInfo Additional info (grid, report, etc.)
     * @param audioFreqHz AUDIO frequency in Hz (0-3000 Hz) - the audio tone where target was heard
     *                    WARNING: This is passed to TransmitCallsign.frequency which has mixed semantics!
     * @param snr Signal-to-noise ratio
     */
    public void manualCallStation(String callsign, int i3, int n3, String extraInfo, long audioFreqHz, int snr) {
        Log.d(TAG, "[MANUAL_CALL] Requested for " + callsign + " at audioFreqHz=" + audioFreqHz);

        int partnerSequential = findPartnerSequentialFromMessages(callsign);

        if (partnerSequential >= 0) {
            DatabaseOpr.StationRecord record = DatabaseOpr.getStationRecord(callsign);
            if (record != null) {
                record.lastSequential = partnerSequential;
                Log.d(TAG, "[SEQUENTIAL] Updated cache: " + callsign + " seq=" + partnerSequential);
            }
        }

        int ourSequential;
        if (partnerSequential >= 0) {
            ourSequential = 1 - partnerSequential;
        } else {
            ourSequential = 1 - (int)(UtcTimer.getNowSequential() % 2);
        }

        Log.d(TAG, "[SEQUENTIAL] Partner=" + partnerSequential + " -> Our=" + ourSequential);

        if (!ft8TransmitSignal.isActivated()) {
            ft8TransmitSignal.setActivated(true);
            Log.d(TAG, "[MANUAL_CALL] Transmission signal activated");
        }

        // [AUDIO_FREQ] audioFreqHz is AUDIO frequency (from msg.freq_hz in UI)
        // Note: TransmitCallsign.frequency has mixed semantics (audio vs RF) - potential bug area!
        // In setTransmit(), if frequency==0, it uses GeneralVariables.getBaseFrequency() (audio)
        Log.d(TAG, "[TX_TRIGGER] MainViewModel.manualCallStation(" + callsign + ") → setTransmit(step=1, audioFreqHz=" + audioFreqHz + ")");
        ft8TransmitSignal.setTransmit(
                new TransmitCallsign(i3, n3, callsign, audioFreqHz, ourSequential, snr),
                1,
                extraInfo
        );
    }

    private int findPartnerSequentialFromMessages(String callsign) {
        if (GeneralVariables.transmitMessages == null) {
            return -1;
        }
        for (Ft8Message msg : GeneralVariables.transmitMessages) {
            if (msg != null && msg.getCallsignFrom().equals(callsign)) {
                Log.d(TAG, "[SEQ_DETECT] Found msg from " + callsign +
                        " in slot " + msg.getSequence() + ": " + msg.toString());
                return msg.getSequence();
            }
        }
        return -1;
    }

    public void sendCustomTransmission(String targetCallsign, String customSuffix) {
        Log.d(TAG, "[CUSTOM_TX] Requested: to=" + targetCallsign + " suffix=" + customSuffix);

        stationContext.userOverrideActive = true;
        stationContext.currentTarget = targetCallsign;
        stationContext.subState = StationState.OperatingSubState.IN_DIALOGUE;
        stationContext.step = StationState.DialogueStep.CALLING;
        stationContext.noReplyCount = 0;
        stationContext.lastReplySlot = UtcTimer.getNowSequential();

        if (!ft8TransmitSignal.isActivated()) {
            ft8TransmitSignal.setActivated(true);
        }

        String messageText = targetCallsign + " " + GeneralVariables.myCallsign + " " + customSuffix;
        String upperMsg = messageText.toUpperCase();

        // [RF_FREQ] GeneralVariables.band is RF band frequency for custom transmission
        Log.d(TAG, "[TX_TRIGGER] MainViewModel.sendCustomTransmission(" + targetCallsign + ", " + customSuffix + ") → setTransmit(rfBandHz=" + GeneralVariables.band + ")");
        ft8TransmitSignal.setTransmit(
                new TransmitCallsign(0, 0, targetCallsign, GeneralVariables.band, -1, 0),
                1,
                upperMsg
        );
        Log.d(TAG, "[TX_TRIGGER] MainViewModel.sendCustomTransmission(" + targetCallsign + ", " + customSuffix + ") → transmitNow()");
        ft8TransmitSignal.transmitNow();

        Log.d(TAG, "[CUSTOM_TX] Started: " + upperMsg);
    }

    private void advanceDialogueStep(Ft8Message msg, StationContext ctx) {
        if (ctx.currentTarget == null || ctx.currentTarget.isEmpty()) {
            return;
        }

        DatabaseOpr.StationRecord record = DatabaseOpr.getStationRecord(ctx.currentTarget);
        if (record == null) {
            Log.w(TAG, "[STEP_ADVANCE] No record found for " + ctx.currentTarget);
            return;
        }

        int theirState = record.ft8StateRelative;
        Log.d(TAG, "[STEP_ADVANCE] " + ctx.currentTarget + " ft8StateRelative=" + theirState +
                " (our step=" + ctx.step + ")");

        if (theirState >= 1 && theirState <= 4) {
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
                    ctx.lastReplySlot = UtcTimer.getNowSequential();
                }
            }
        }
    }

    private void initWorkedEntitiesFromQSL() {
        Log.d("DXCC_INIT", "=== initWorkedEntitiesFromQSL START ===");

        if (databaseOpr == null) {
            Log.e("DXCC_INIT", "databaseOpr is NULL!");
            return;
        }

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

            processCallsignsForDXCC(callsignList, 0);

        } catch (Exception e) {
            Log.e("DXCC_INIT", "Exception: " + e.getMessage(), e);
        }
    }

    public boolean isInRecentTargets(String callsign) {
        if (callsign == null || stationContext == null || stationContext.recentTargets == null) return false;
        for (String target : stationContext.recentTargets) {
            if (target != null && target.equalsIgnoreCase(callsign)) {
                return true;
            }
        }
        return false;
    }

    private void processCallsignsForDXCC(final ArrayList<String> callsignList, final int index) {
        if (index >= callsignList.size()) {
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

                    if (GeneralVariables.dxccMap.size() <= 10) {
                        Log.d("DXCC_INIT", "Loaded: " + callsign + " → " +
                                info.DXCC + " CQ" + info.CQZone + " ITU" + info.ITUZone);
                    }
                }
                processCallsignsForDXCC(callsignList, index + 1);
            }
        });
    }
}