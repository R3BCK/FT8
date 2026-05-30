package com.bg7yoz.ft8cn.wave;
/**
 * Microphone recording operations.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.ui.ToastMessage;

public class MicRecorder {
    private static final String TAG = "MicRecorder";
    private int bufferSize = 0; // Minimum buffer size
    private static final int sampleRateInHz = 12000; // Sample rate
    private static final int channelConfig = AudioFormat.CHANNEL_IN_MONO; // Mono channel
    //private static final int audioFormat = AudioFormat.ENCODING_PCM_16BIT; // Bit depth
    private static final int audioFormat = AudioFormat.ENCODING_PCM_FLOAT; // Bit depth

    private AudioRecord audioRecord = null; // AudioRecord object
    private boolean isRunning = false; // Is recording in progress
    private volatile boolean isRecordingActive = false; // ✅ Internal recording state flag
    private OnDataListener onDataListener;

    public interface OnDataListener {
        void onDataReceived(float[] data, int len);
    }

    @SuppressLint("MissingPermission")
    public MicRecorder() {
        // Calculate minimum buffer size
        bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        // audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRateInHz
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, sampleRateInHz
                , channelConfig, audioFormat, bufferSize); // Create AudioRecord object
    }

    public void start() {
        if (isRunning) return;

        float[] buffer = new float[bufferSize];
        try {
            audioRecord.startRecording(); // Start recording
        } catch (Exception e) {
            ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                    R.string.recorder_cannot_record), e.getMessage()));
            Log.e(TAG, "start: " + e.getMessage());
            isRunning = false;
            isRecordingActive = false;
            return;
        }

        isRunning = true;
        isRecordingActive = true; // ✅ Set internal flag

        new Thread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Recording thread started");
                while (isRunning) {
                    // Check if recording is actually active (state != 3 means not recording)
                    if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        Log.w(TAG, "Recording state changed to: " + audioRecord.getRecordingState());
                        isRunning = false;
                        isRecordingActive = false; // ✅ Update internal flag
                        Log.e(TAG, "Recording failed, state code: " + audioRecord.getRecordingState());
                        break;
                    }

                    // Read audio data
                    int bufferReadResult = audioRecord.read(buffer, 0, bufferSize, AudioRecord.READ_BLOCKING);

                    if (bufferReadResult > 0 && onDataListener != null) {
                        onDataListener.onDataReceived(buffer, bufferReadResult);
                    } else if (bufferReadResult < 0) {
                        Log.w(TAG, "AudioRecord read error: " + bufferReadResult);
                    }
                }

                // Cleanup: stop recording if still active
                try {
                    if (audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop(); // Stop recording
                        //Log.d(TAG, "AudioRecord stopped");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping AudioRecord: " + e.getMessage());
                    ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                            R.string.recorder_stop_record_error), e.getMessage()));
                } finally {
                    isRecordingActive = false; //  Ensure flag is cleared
                    Log.d(TAG, "Recording thread finished, isRecordingActive=false");
                }
            }
        }).start();
    }

    /**
     * Stop recording. When recording stops, all listeners in the list are removed.
     */
    public void stopRecord() {
        //Log.d(TAG, "stopRecord() called, isRunning=" + isRunning);
        isRunning = false;
        // isRecordingActive will be set to false by the recording thread
    }

    /**
     * Check if recording is actively in progress (internal state)
     * @return true if AudioRecord is actively recording
     */
    public boolean isRecording() {
        // ✅ Check both outer flag and actual AudioRecord state
        if (!isRunning) return false;
        if (audioRecord == null) return false;

        try {
            return audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING;
        } catch (Exception e) {
            Log.w(TAG, "Could not check recording state: " + e.getMessage());
            return isRecordingActive; // Fallback to last known state
        }
    }

    /**
     * Get the data listener
     * @return OnDataListener instance
     */
    public OnDataListener getOnDataListener() {
        return onDataListener;
    }

    /**
     * Set the data listener
     * @param onDataListener Listener to set
     */
    public void setOnDataListener(OnDataListener onDataListener) {
        this.onDataListener = onDataListener;
    }

    /**
     * Get the buffer size used for recording
     * @return Buffer size in samples
     */
    public int getBufferSize() {
        return bufferSize;
    }

    /**
     * Check if the outer running flag is set (may not reflect actual recording state)
     * @deprecated Use isRecording() for accurate state
     */
    @Deprecated
    public boolean isRunning() {
        return isRunning;
    }
}