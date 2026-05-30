package com.bg7yoz.ft8cn.wave;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.content.Context;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import com.bg7yoz.ft8cn.GeneralVariables;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Audio recording class. Implements recording via AudioRecord object.
 * HamRecorder provides recorded data through the GetVoiceData listener class.
 * HamRecorder instance contains a listener list onGetVoiceList.
 * When audio data is available, HamRecorder triggers the OnReceiveData callback
 * for each listener in the list.
 *
 * Purpose: Prevent FT8 recording timing overlaps due to recording start time issues,
 * which could cause duplicate recording objects or recording duration shorter than
 * one cycle (15 seconds).
 *
 * @author BG7YOZ
 * @date 2022-05-31
 */

public class HamRecorder {
    private static final String TAG = "HamRecorder";
    //private int bufferSize = 0; // Minimum buffer size
    private static final int sampleRateInHz = 12000; // Sample rate
    private static final int channelConfig = AudioFormat.CHANNEL_IN_MONO; // Mono channel
    //private static final int audioFormat = AudioFormat.ENCODING_PCM_16BIT; // Bit depth
    private static final int audioFormat = AudioFormat.ENCODING_PCM_FLOAT; // Bit depth

    //private AudioRecord audioRecord = null; // AudioRecord object
    private boolean isRunning = false; // Is recording in progress

    private final ArrayList<VoiceDataMonitor> voiceDataMonitorList = new ArrayList<>(); // Listener callback list
    private OnVoiceMonitorChanged onVoiceMonitorChanged = null;

    private boolean isMicRecord = true;
    private MicRecorder micRecorder = new MicRecorder();

    // === Audio Focus Handling ===
    private AudioManager audioManager;
    private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;
    private boolean audioFocusGranted = false;
    // ============================

    // === WakeLock for CPU wake during recording ===
    private WakeLock wakeLock;
    // ==============================================

    public HamRecorder(OnVoiceMonitorChanged onVoiceMonitorChanged) {
        this.onVoiceMonitorChanged = onVoiceMonitorChanged;
        initAudioFocus();
        initWakeLock(); //  Initialize WakeLock
    }

    /**
     * Initialize WakeLock to prevent CPU sleep during recording
     */
    private void initWakeLock() {
        Context ctx = GeneralVariables.getMainContext();
        if (ctx != null) {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "FT8CN:RecordingWakeLock"
                );
                wakeLock.setReferenceCounted(false);
                Log.d(TAG, "WakeLock initialized");
            }
        }
    }

    /**
     * Initialize audio focus handling to prevent recording stops due to focus loss
     */
    private void initAudioFocus() {
        Context ctx = GeneralVariables.getMainContext();
        if (ctx != null) {
            audioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);

            audioFocusChangeListener = focusChange -> {
                switch (focusChange) {
                    case AudioManager.AUDIOFOCUS_LOSS:
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        Log.w(TAG, "Audio focus lost, stopping recording");
                        if (isRunning) {
                            // Do not fully stop - just pause and try to regain
                            audioFocusGranted = false;
                            // Notify UI if needed
                            if (onVoiceMonitorChanged != null) {
                                onVoiceMonitorChanged.onMonitorChanged(voiceDataMonitorList.size());
                            }
                        }
                        break;
                    case AudioManager.AUDIOFOCUS_GAIN:
                        Log.d(TAG, "Audio focus regained, resuming recording");
                        audioFocusGranted = true;
                        // Auto-restart if needed
                        if (isRunning && isMicRecord) {
                            Log.d(TAG, "Auto-restarting mic recording after focus regained");
                            // Check if micRecorder is actually recording before restarting
                            if (!micRecorder.isRecording()) {
                                micRecorder.start();
                            }
                        }
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                        // Can continue recording at lower volume if needed
                        //Log.d(TAG, "Audio focus ducked, continuing recording");
                        break;
                }
            };
        }
    }

    /**
     * Request audio focus before starting recording
     * @return true if focus granted or not required
     */
    private boolean requestAudioFocus() {
        if (audioManager == null) {
            //Log.w(TAG, "AudioManager not available, proceeding without focus");
            return true;
        }

        int result = audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);

        //Log.d(TAG, "Audio focus request result: " + result);
        audioFocusGranted = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        return audioFocusGranted || result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED;
    }

    /**
     * Release audio focus when stopping recording
     */
    private void releaseAudioFocus() {
        if (audioManager != null && audioFocusChangeListener != null) {
            audioManager.abandonAudioFocus(audioFocusChangeListener);
            audioFocusGranted = false;
            //Log.d(TAG, "Audio focus released");
        }
    }

    /**
     * Acquire WakeLock to prevent CPU sleep
     */
    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            // Acquire for max 10 minutes, auto-release to prevent leak
            wakeLock.acquire(10 * 60 * 1000L);
            Log.d(TAG, "WakeLock acquired (10 min timeout)");
        }
    }

    /**
     * Release WakeLock when stopping recording
     */
    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            //Log.d(TAG, "WakeLock released");
        }
    }

    public void setDataFromMic() {
        isMicRecord = true;
        startRecord();
    }

    public void setDataFromLan() {
        isMicRecord = false;
        micRecorder.stopRecord();
    }

    /**
     * Handle received audio data
     * @param bufferLen Data length
     * @param buffer Data buffer
     */
    public void doOnWaveDataReceived(int bufferLen, float[] buffer) {
        if (!isRunning) {
            Log.w(TAG, "doOnWaveDataReceived called but isRunning=false, data dropped");
            return;
        }
        if (!audioFocusGranted && isMicRecord) {
            Log.w(TAG, "doOnWaveDataReceived: no audio focus, attempting to regain");
            requestAudioFocus();
        }

        for (int i = 0; i < voiceDataMonitorList.size(); i++) {
            // Call each listener callback to provide data
            if (voiceDataMonitorList.get(i) != null) {
                voiceDataMonitorList.get(i).onHamRecord.OnReceiveData(buffer, bufferLen);
            }
        }
    }

    /**
     * Check if recording is truly in progress (both outer and inner state)
     * @return true only if both HamRecorder and MicRecorder are running
     */
    public boolean isRunning() {
        // Check outer flag first
        if (!isRunning) return false;

        // If using mic, check inner MicRecorder state
        if (isMicRecord && micRecorder != null) {
            try {
                // ✅ Use new method from MicRecorder for accurate state
                return micRecorder.isRecording();
            } catch (Exception e) {
                Log.w(TAG, "Could not check micRecorder state: " + e.getMessage());
                // Fallback to outer flag if check fails
                return isRunning;
            }
        }

        // For non-mic modes, just return outer flag
        return isRunning;
    }

    /**
     * Start recording. This method keeps the device in continuous recording state.
     * Recording data is obtained through the GetVoiceData listener class.
     * After the recording object reads data (audioRecord.read), it calls the
     * OnReceiveData callback for all listeners in the list.
     * Recording state is stored in isRunning.
     */
    @SuppressLint("MissingPermission")
    public void startRecord() {
        Log.d(TAG, "startRecord() called, isMicRecord=" + isMicRecord + ", thread=" + Thread.currentThread().getName());

        if (isMicRecord) { // If using MIC for audio capture
            // Request audio focus before starting mic recording
            if (!requestAudioFocus()) {
                Log.w(TAG, "Audio focus not granted, mic recording may be interrupted");
            }

            // Acquire WakeLock to prevent CPU sleep
            acquireWakeLock();

            micRecorder.start();
            micRecorder.setOnDataListener(new MicRecorder.OnDataListener() {
                @Override
                public void onDataReceived(float[] data, int len) {
                    if (!isRunning) {
                        Log.w(TAG, "onDataReceived called but isRunning=false, data dropped");
                        return;
                    }
                    doOnWaveDataReceived(len, data);
                }
            });
        }

        isRunning = true;
        Log.d(TAG, "Recording started, isRunning=" + isRunning);
    }

    private void doDataMonitorChanged() {
        if (onVoiceMonitorChanged != null) {
            onVoiceMonitorChanged.onMonitorChanged(voiceDataMonitorList.size());
        }
    }

    /**
     * Remove data listener
     * @param monitor Data listener to remove
     */
    public void deleteVoiceDataMonitor(VoiceDataMonitor monitor) {
        boolean removed = voiceDataMonitorList.remove(monitor);
        //Log.d(TAG, "deleteVoiceDataMonitor: removed=" + removed + ", remaining=" + voiceDataMonitorList.size());
        doDataMonitorChanged();
    }

    /**
     * Get number of listeners
     * @return Count of listeners
     */
    public int getVoiceMonitorCount() {
        return voiceDataMonitorList.size();
    }

    /**
     * Get list of listeners
     * @return Listener list
     */
    public ArrayList<VoiceDataMonitor> getVoiceDataMonitors() {
        return this.voiceDataMonitorList;
    }

    /**
     * Stop recording. When recording stops, all listeners in the list are removed.
     */
    public void stopRecord() {
        //Log.d(TAG, "stopRecord() called, isRunning=" + isRunning);

        // Release WakeLock first
        releaseWakeLock();

        // Stop mic recording
        micRecorder.stopRecord();

        // Update outer flag
        isRunning = false;

        // Release audio focus
        releaseAudioFocus();

        //Log.d(TAG, "Recording stopped, isRunning=" + isRunning);
    }

    /**
     * Method to obtain recording data by loading a data listener (VoiceDataMonitor).
     * Recording data is provided in the OnGetVoiceDataDone callback when recording
     * reaches the specified duration (milliseconds).
     *
     * To get recording data, load a listener object into the recording object.
     * In the listener's OnReceiveData callback, obtain data. When data reaches
     * the expected amount, trigger the OnGetVoiceDataDone callback. This callback
     * runs in another thread, so handle UI updates carefully.
     *
     * Two listener modes: one-time, looping.
     * One-time: After getting data, this listener is automatically removed.
     * Looping: Listener persists, resets after getting data, enters next listening state.
     * Listener is only removed when recording stops.
     *
     * @param duration Recording data duration (milliseconds)
     * @param afterDoneRemove Remove listener after getting data? false=loop for continuous data
     * @param getVoiceDataDone Callback triggered when recording reaches specified duration
     * @return VoiceDataMonitor object or null if not running
     */
    public VoiceDataMonitor getVoiceData(int duration, boolean afterDoneRemove, OnGetVoiceDataDone getVoiceDataDone) {
        if (isRunning) {
            VoiceDataMonitor dataMonitor = new VoiceDataMonitor(duration, this, afterDoneRemove, getVoiceDataDone);
            dataMonitor.voiceDataMonitor = dataMonitor; // For listener self-removal
            voiceDataMonitorList.add(dataMonitor);
            //Log.d(TAG, "getVoiceData: added monitor, duration=" + duration + "ms, afterDoneRemove=" + afterDoneRemove);
            doDataMonitorChanged();
            return dataMonitor;
        } else {
            //Log.w(TAG, "getVoiceData called but isRunning=false, returning null");
            return null;
        }
    }

    /**
     * Listener class for obtaining recording data.
     * When the listener needs to set recording duration (milliseconds), after reaching
     * the specified duration, it generates an OnGetVoiceDataDone callback where you
     * can obtain the recording data for that duration.
     *
     * Can set this listener as one-time (afterDoneRemove=true) or looping (afterDoneRemove=false).
     * One-time: After reaching specified duration, stop listening, recording instance removes this listener.
     * Looping: After reaching specified duration, reset and continue listening. Useful for waveform data.
     */
    static class VoiceDataMonitor {
        private final String TAG = "GetVoiceData";
        private final float[] voiceData; // Recording data. Size determined by duration, sample rate, bit depth.
        private int dataCount; // Counter for current data amount obtained

        // onHamRecord is the callback triggered when recording object has data.
        // Through this callback, fill the voiceData buffer. When buffer is full,
        // trigger the OnGetVoiceDataDone callback.
        public OnHamRecord onHamRecord;

        // getVoiceData is this listener's address, used to remove this listener
        // from the recording object's listener list.
        // IMPORTANT: After GetVoiceData construction, you MUST assign this variable!
        // Otherwise, this listener cannot be removed.
        public VoiceDataMonitor voiceDataMonitor = null;

        // FIX: Add reference to outer HamRecorder instance to access isRunning
        private final HamRecorder outerRecorder;

        /**
         * Listener class for obtaining recording data.
         * GetVoiceData class constructor. This class is added to HamRecorder's onGetVoiceList.
         * When recording data returns, callback is triggered.
         *
         * Purpose: During recording, multiple objects can obtain data from recording
         * without conflicts.
         *
         * @param duration Duration of recording data to obtain (milliseconds)
         * @param hamRecorder Recording class instance. Convenient for deleting this listener, etc.
         * @param afterDoneRemove After reaching recording duration, remove this listener instance?
         *                        true=remove, false=keep for loop listening
         * @param onGetVoiceDataDone Callback triggered after reaching recording duration.
         *                           To avoid occupying too much recording time, this callback
         *                           runs in another thread.
         */
        public VoiceDataMonitor(int duration, HamRecorder hamRecorder, boolean afterDoneRemove,
                                OnGetVoiceDataDone onGetVoiceDataDone) {
            // Duration in milliseconds
            // Host object, convenient for calling deletion of this instance from data acquisition action list

            this.outerRecorder = hamRecorder; // FIX: Store reference to outer class
            dataCount = 0; // Current data amount obtained
            // Generate expected size data buffer.
            // Because it's 16-bit sampling, byte*2.
            // voiceData = new byte[duration * HamRecorder.sampleRateInHz * 2 / 1000];
            voiceData = new float[duration * HamRecorder.sampleRateInHz / 1000];

            // Callback function triggered when recording data is available.
            onHamRecord = new OnHamRecord() {
                @Override
                public void OnReceiveData(float[] data, int size) {
                    //  FIX: Use outerRecorder.isRunning() instead of direct isRunning
                    if (!outerRecorder.isRunning()) {
                        //Log.w(TAG, "OnReceiveData called but isRunning=false, data dropped");
                        return;
                    }

                    int remainingSize = size + dataCount - voiceData.length; // If >0, this is remaining data amount

                    for (int i = 0; (i < size) && (dataCount < voiceData.length); i++) {
                        voiceData[dataCount] = data[i]; // Copy data from recording buffer to this listener
                        dataCount++;
                    }

                    if (dataCount >= voiceData.length) { // When data amount reaches required amount, trigger callback
                        //Log.d(TAG, "OnReceiveData: buffer full (" + dataCount + "), triggering callback");
                        onGetVoiceDataDone.onGetDone(voiceData);

                        if (afterDoneRemove) { // If one-time data acquisition, remove this callback from recording object's listener list
                            //Log.d(TAG, "OnReceiveData: afterDoneRemove=true, deleting self from list");
                            outerRecorder.deleteVoiceDataMonitor(voiceDataMonitor);
                        } else {
                            dataCount = 0; // If looping recording, reset counter
                            //Log.d(TAG, "OnReceiveData: afterDoneRemove=false, resetting counter for loop");

                            if (remainingSize > 0) { // Send remaining data to subsequent events
                                //Log.d(TAG, "OnReceiveData: remainingSize=" + remainingSize + ", forwarding to next cycle");
                                float[] remainingData = new float[remainingSize];
                                System.arraycopy(data, size - remainingSize, remainingData, 0, remainingSize);
                                OnReceiveData(remainingData, remainingSize);
                            }
                        }
                    }
                }
            };
        }
    }

    /**
     * Class method to save data to a temporary file.
     * @param data Data to save
     * @return Generated temporary filename or null on error
     */
    public static String saveDataToFile(byte[] data) {
        String audioFileName = null;
        File recordingFile;
        try {
            // Generate temporary filename
            recordingFile = File.createTempFile("Audio", ".wav", null);
            audioFileName = recordingFile.getPath();

            // Data stream file
            DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(audioFileName)));
            // Write Wav header
            new WriteWavHeader(data.length, sampleRateInHz, channelConfig, audioFormat).writeHeader(dos);
            for (int i = 0; i < data.length; i++) {
                dos.write(data[i]);
            }
            Log.d(TAG, String.format("File generation complete (%d bytes, %.2f seconds), file: %s",
                    data.length + 44, ((float) data.length / 2 / sampleRateInHz), audioFileName));
            dos.close(); // Close file stream

        } catch (IOException e) {
            //Log.e(TAG, String.format("Error creating temporary file! %s", e.getMessage()));
        }

        return audioFileName;
    }

    /**
     * Convert raw audio data to 16-bit integer array.
     * @param buffer Raw audio data (8-bit)
     * @return 16-bit int format array
     */
    public static int[] byteDataTo16BitData(byte[] buffer) {
        int[] data = new int[buffer.length / 2];
        for (int i = 0; i < buffer.length / 2; i++) {
            int res = (buffer[i * 2] & 0x000000FF) | (((int) buffer[i * 2 + 1]) << 8);
            data[i] = res;
        }
        return data;
    }

    /**
     * Convert raw audio data to float array.
     * @param bytes Raw audio data (float format in bytes)
     * @return Float array
     */
    public static float[] getFloatFromBytes(byte[] bytes) {
        float[] floats = new float[bytes.length / 4];
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
        for (int i = 0; i < floats.length; i++) {
            try {
                floats[i] = dis.readFloat();
            } catch (IOException e) {
                //Log.e("HamRecorder", "Error reading float: " + e.getMessage());
                e.printStackTrace();
                break;
            }
        }
        try {
            dis.close();
        } catch (IOException e) {
            //Log.e("HamRecorder", "Error closing stream: " + e.getMessage());
            e.printStackTrace();
        }
        return floats;
    }
}