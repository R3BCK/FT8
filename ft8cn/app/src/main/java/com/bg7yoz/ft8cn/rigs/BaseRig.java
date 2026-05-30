package com.bg7yoz.ft8cn.rigs;

import androidx.lifecycle.MutableLiveData;
import com.bg7yoz.ft8cn.ui.ToastMessage;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.connector.BaseRigConnector;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Abstract base class for radio rigs.
 * @author BGY70Z
 * @date 2023-03-20
 */
public abstract class BaseRig {
    private static final String TAG = "BaseRig";

    private long freq; // Current frequency value
    public MutableLiveData<Long> mutableFrequency = new MutableLiveData<>();
    private int controlMode; // Control mode (CAT/RTS/DTR/VOX)
    private OnRigStateChanged onRigStateChanged; // Callback for rig state changes
    private int civAddress; // CI-V address
    private int baudRate; // Baud rate
    private boolean isPttOn = false; // PTT status
    private BaseRigConnector connector = null; // Connector object

    // === SMART POLLING ===
    private long lastPollTime = 0;
    private long pollIntervalMs = 1000; // Default: 1 second
    private boolean transceiveEnabled = false; // CI-V Transceive mode status

    // === TUNE AFTER FREQ CHANGE ===
    private boolean pendingTuneAfterFreqChange = false;
    private static final long TUNE_DELAY_AFTER_FREQ_MS = 300; // Wait 300ms after freq set

    // === CI-V TUNE CONSTANTS ===
    public static final byte TUNER_OFF = 0x00;
    public static final byte TUNER_ON = 0x01;
    public static final byte TUNER_START = 0x02;

    public abstract boolean isConnected();
    public abstract void setUsbModeToRig();
    public abstract void setFreqToRig();
    public abstract void onReceiveData(byte[] data);
    public abstract void readFreqFromRig();
    public abstract String getName();

    private final OnConnectReceiveData onConnectReceiveData = new OnConnectReceiveData() {
        @Override
        public void onData(byte[] data) {
            onReceiveData(data);
        }
    };

    public void setPTT(boolean on) {
        isPttOn = on;
        if (onRigStateChanged != null) {
            onRigStateChanged.onPttChanged(on);
        }
    }

    public void sendWaveData(Ft8Message message) {
        // Override in subclasses that support wave over CAT
    }

    public long getFreq() {
        return freq;
    }

    /**
     * Set frequency and trigger callbacks.
     * Also schedules TUNE command if pending (for "Tune on frequency change" feature).
     */
    public void setFreq(long freq) {
        if (freq == this.freq) return;
        if (freq == 0) return;
        if (freq == -1) return;

        mutableFrequency.postValue(freq);
        this.freq = freq;

        // === ✅ TUNE AFTER FREQUENCY CHANGE ===
        if (pendingTuneAfterFreqChange) {
            pendingTuneAfterFreqChange = false;
            scheduleTuneAfterFreqChange();
        }

        if (onRigStateChanged != null) {
            onRigStateChanged.onFreqChanged(freq);
        }
    }

    /**
     * Request that TUNE command be sent after the next frequency change.
     * Called from MainViewModel when "Tune on frequency change" is enabled.
     */
    public void requestTuneAfterNextFreqChange() {
        pendingTuneAfterFreqChange = true;
    }

    /**
     * Schedule TUNE command with safe delay after frequency change.
     * Override in subclasses for rig-specific implementation.
     */
    protected void scheduleTuneAfterFreqChange() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isConnected()) {
                sendTuneCommand(TUNER_START);
                Log.d(TAG, "TUNE START sent after frequency change");
            }
        }, TUNE_DELAY_AFTER_FREQ_MS);
    }

    /**
     * Send TUNE command to the rig.
     * Override in subclasses (IcomRig, YaesuRig, etc.) with actual CI-V/CAT commands.
     * @param action One of: TUNER_OFF, TUNER_ON, TUNER_START
     */
    public void sendTuneCommand(byte action) {
        // Default: not supported. Override in subclasses.
        Log.w(TAG, "sendTuneCommand() not implemented for this rig type");
        ToastMessage.show("TUNE not supported for this rig");
    }

    // === SMART POLLING METHODS ===

    /**
     * Check if frequency polling should be performed now.
     * @return true if polling is needed, false if skipped (e.g., Transceive mode)
     */
    public boolean shouldPollFrequency() {
        // Skip polling if Transceive mode is enabled (radio pushes updates automatically)
        if (transceiveEnabled) {
            return false;
        }

        long now = System.currentTimeMillis();
        return (now - lastPollTime) >= pollIntervalMs;
    }

    /**
     * Mark that a poll cycle has completed. Updates lastPollTime.
     */
    public void markPollComplete() {
        lastPollTime = System.currentTimeMillis();
    }

    /**
     * Set polling interval in milliseconds.
     * @param intervalMs Interval (recommended: 1000 for 1 second)
     */
    public void setPollIntervalMs(long intervalMs) {
        this.pollIntervalMs = Math.max(intervalMs, 500); // Minimum 500ms
    }

    /**
     * Get current polling interval.
     */
    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    /**
     * Enable or disable CI-V Transceive mode.
     * When enabled, frequency polling is skipped (radio sends updates automatically).
     * @param enabled true to enable Transceive
     */
    public void setTransceiveEnabled(boolean enabled) {
        this.transceiveEnabled = enabled;
        Log.d(TAG, "Transceive mode: " + (enabled ? "ENABLED" : "DISABLED"));
    }

    /**
     * Check if Transceive mode is enabled.
     */
    public boolean isTransceiveEnabled() {
        return transceiveEnabled;
    }

    /**
     * Send CI-V command to enable Transceive mode.
     * Override in subclasses with actual command (e.g., Icom: FE FE 94 E0 1A 01 FD).
     */
    public void enableTransceiveMode() {
        // Default: no-op. Override in IcomRig, etc.
        Log.w(TAG, "enableTransceiveMode() not implemented for this rig");
    }

    public void setConnector(BaseRigConnector connector) {
        this.connector = connector;
        if (connector != null) {
            connector.setOnRigStateChanged(onRigStateChanged);
            connector.setOnConnectReceiveData(new OnConnectReceiveData() {
                @Override
                public void onData(byte[] data) {
                    onReceiveData(data);
                }
            });
        }
    }

    public void setControlMode(int mode) {
        controlMode = mode;
        if (connector != null) {
            connector.setControlMode(mode);
        }
    }

    public int getControlMode() {
        return controlMode;
    }

    public static String byteToStr(byte[] data) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            s.append(String.format("%02x ", data[i] & 0xff));
        }
        return s.toString();
    }

    public BaseRigConnector getConnector() {
        return connector;
    }

    public OnRigStateChanged getOnRigStateChanged() {
        return onRigStateChanged;
    }

    public void setOnRigStateChanged(OnRigStateChanged onRigStateChanged) {
        this.onRigStateChanged = onRigStateChanged;
    }

    public int getCivAddress() {
        return civAddress;
    }

    public void setCivAddress(int civAddress) {
        this.civAddress = civAddress;
    }

    public int getBaudRate() {
        return baudRate;
    }

    public void setBaudRate(int baudRate) {
        this.baudRate = baudRate;
    }

    public boolean isPttOn() {
        return isPttOn;
    }

    /**
     * Check if this rig supports audio waveform over CAT.
     * Override in subclasses that support it (e.g., uSDX, some Icom models).
     */
    public boolean supportWaveOverCAT() {
        return false;
    }

    public void onDisconnecting() {
        // Cleanup hook for subclasses
    }

    /**
     * Legacy method name for backward compatibility.
     * @deprecated Use sendTuneCommand() instead
     */
    @Deprecated
    public void setTune(byte action) {
        sendTuneCommand(action);
    }
}