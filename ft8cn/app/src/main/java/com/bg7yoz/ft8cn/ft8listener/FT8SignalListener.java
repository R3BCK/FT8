package com.bg7yoz.ft8cn.ft8listener;

import android.util.Log;
import androidx.lifecycle.MutableLiveData;
import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.ft8transmit.GenerateFT8;
import com.bg7yoz.ft8cn.timer.OnUtcTimer;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.wave.OnGetVoiceDataDone;
import com.bg7yoz.ft8cn.wave.WaveFileReader;
import com.bg7yoz.ft8cn.wave.WaveFileWriter;
import java.util.ArrayList;

public class FT8SignalListener {
    private static final String TAG = "FT8SignalListener";
    private final UtcTimer utcTimer;
    private final OnFt8Listen onFt8Listen;
    public MutableLiveData<Long> decodeTimeSec = new MutableLiveData<>();
    public long timeSec = 0;

    private OnWaveDataListener onWaveDataListener;
    private DatabaseOpr db;
    private final A91List a91List = new A91List();

    // [NEW] Флаг: разрешено ли декодирование
    private volatile boolean isDecodingEnabled = true;
    // [NEW] Флаг для немедленной остановки потока декодирования
    private volatile boolean decodeThreadShouldStop = false;
    // [NEW] Объект блокировки для синхронизации
    private final Object decodeLock = new Object();

    private static boolean libraryLoaded = false;

    static {
        try {
            String libName = GeneralVariables.acceptDxCalls ? "ft8cn_dx" : "ft8cn_std";
            System.loadLibrary(libName);
            libraryLoaded = true;
            Log.d(TAG, "Loaded native library: " + libName);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load library: " + e.getMessage());
            try {
                System.loadLibrary("ft8cn_std");
                libraryLoaded = true;
                Log.d(TAG, "Fallback: loaded ft8cn_std");
            } catch (UnsatisfiedLinkError e2) {
                Log.e(TAG, "Fallback failed: " + e2.getMessage());
            }
        }
    }

    public static synchronized void initializeLibrary(boolean dxMode) {
        if (libraryLoaded) return;
        try {
            String libName = dxMode ? "ft8cn_dx" : "ft8cn_std";
            System.loadLibrary(libName);
            libraryLoaded = true;
            Log.d(TAG, "Explicitly loaded library: " + libName);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to explicitly load library: " + e.getMessage());
            try {
                System.loadLibrary("ft8cn_std");
                libraryLoaded = true;
                Log.d(TAG, "Fallback: loaded ft8cn_std");
            } catch (UnsatisfiedLinkError e2) {
                Log.e(TAG, "Fallback failed: " + e2.getMessage());
            }
        }
    }

    public static boolean isLibraryLoaded() { return libraryLoaded; }

    public interface OnWaveDataListener {
        void getVoiceData(int duration, boolean afterDoneRemove, OnGetVoiceDataDone getVoiceDataDone);
    }

    public FT8SignalListener(DatabaseOpr db, OnFt8Listen onFt8Listen) {
        this.onFt8Listen = onFt8Listen;
        this.db = db;
        if (!libraryLoaded) {
            Log.w(TAG, "Native library not loaded, attempting fallback initialization...");
            initializeLibrary(GeneralVariables.acceptDxCalls);
        }
        utcTimer = new UtcTimer(FT8Common.FT8_SLOT_TIME_M, false, new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) { }
            @Override
            public void doOnSecTimer(long utc) {
                runRecorde(utc);
            }
        });
    }

    // === [NEW] Методы управления декодированием с синхронизацией ===

    public void setDecodingEnabled(boolean enabled) {
        synchronized (decodeLock) {
            isDecodingEnabled = enabled;
            Log.d(TAG, "Decoding " + (enabled ? "enabled" : "disabled"));
        }
    }

    public boolean isDecodingEnabled() {
        return isDecodingEnabled;
    }

    // [NEW] Метод для немедленной остановки текущего цикла декодирования
    public void requestDecodeStop() {
        decodeThreadShouldStop = true;
        Log.d(TAG, "Decode stop requested");
    }

    // [NEW] Сброс флага остановки для нового цикла
    public void resetDecodeStopFlag() {
        decodeThreadShouldStop = false;
        Log.d(TAG, "Decode stop flag reset");
    }

    // [NEW] Проверка флага остановки
    public boolean shouldStopDecode() {
        return decodeThreadShouldStop || !isDecodingEnabled;
    }

    public void startListen() {
        synchronized (decodeLock) {
            isDecodingEnabled = true;
            decodeThreadShouldStop = false;
        }
        utcTimer.start();
    }

    public void stopListen() {
        synchronized (decodeLock) {
            isDecodingEnabled = false;
            decodeThreadShouldStop = true;
        }
        utcTimer.stop();
    }

    public boolean isListening() {
        return utcTimer.isRunning();
    }

    public int time_Offset() {
        return utcTimer.getTime_sec() + UtcTimer.delay;
    }

    private void runRecorde(long utc) {
        // [NEW] Проверка: если декодирование отключено — не запускаем запись
        if (!isDecodingEnabled) {
            return;
        }

        if (onWaveDataListener != null) {
            onWaveDataListener.getVoiceData(FT8Common.FT8_SLOT_TIME_MILLISECOND, true,
                    new OnGetVoiceDataDone() {
                        @Override
                        public void onGetDone(float[] data) {
                            // [NEW] Проверка: если данные пустые — не декодируем
                            if (data == null || data.length == 0) {
                                Log.w(TAG, "Empty audio data, skipping decode");
                                return;
                            }
                            // [NEW] Проверка флага остановки перед запуском декода
                            if (shouldStopDecode()) {
                                Log.d(TAG, "Decode stopped before start, skipping");
                                return;
                            }
                            decodeFt8(utc, data);
                        }
                    });
        }
    }

    public void decodeFt8(long utc, float[] voiceData) {
        // [DEFENSIVE] Проверка данных перед передачей в нативный код
        if (voiceData == null || voiceData.length == 0) return;
        if (voiceData.length > 12000 * 15) {  // Максимальный разумный размер
            Log.w(TAG, "Voice data too large: " + voiceData.length);
            return;
        }
        // [NEW] Двойная проверка перед запуском потока
        if (shouldStopDecode() || voiceData == null || voiceData.length == 0) {
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                // [NEW] Проверка внутри потока — мог быть остановлен пока поток стартовал
                if (shouldStopDecode()) return;

                long time = System.currentTimeMillis();
                if (onFt8Listen != null) {
                    onFt8Listen.beforeListen(utc);
                }

                long ft8Decoder = 0;
                try {
                    ft8Decoder = InitDecoder(utc, FT8Common.SAMPLE_RATE, voiceData.length, true);
                    // [NEW] Проверка: если декодер не инициализировался — выходим
                    if (ft8Decoder == 0) {
                        Log.e(TAG, "Failed to initialize decoder");
                        return;
                    }

                    DecoderMonitorPressFloat(voiceData, ft8Decoder);

                    ArrayList<Ft8Message> allMsg = new ArrayList<>();

                    // [NEW] Проверка перед каждым вызовом runDecode
                    if (!shouldStopDecode()) {
                        ArrayList<Ft8Message> msgs = runDecode(ft8Decoder, utc, false);
                        addMsgToList(allMsg, msgs);
                    }

                    timeSec = System.currentTimeMillis() - time;
                    decodeTimeSec.postValue(timeSec);

                    if (onFt8Listen != null && !shouldStopDecode()) {
                        onFt8Listen.afterDecode(utc, averageOffset(allMsg), UtcTimer.sequential(utc), allMsg, false);
                    }

                    if (GeneralVariables.deepDecodeMode && !shouldStopDecode()) {
                        ArrayList<Ft8Message> msgs = runDecode(ft8Decoder, utc, true);
                        addMsgToList(allMsg, msgs);
                        timeSec = System.currentTimeMillis() - time;
                        decodeTimeSec.postValue(timeSec);
                        if (onFt8Listen != null && !shouldStopDecode()) {
                            onFt8Listen.afterDecode(utc, averageOffset(allMsg), UtcTimer.sequential(utc), msgs, true);
                        }

                        do {
                            if (timeSec > FT8Common.DEEP_DECODE_TIMEOUT) break;
                            if (shouldStopDecode()) break; // [NEW] Проверка внутри цикла

                            ReBuildSignal.subtractSignal(ft8Decoder, a91List);
                            msgs = runDecode(ft8Decoder, utc, true);
                            addMsgToList(allMsg, msgs);
                            timeSec = System.currentTimeMillis() - time;
                            decodeTimeSec.postValue(timeSec);
                            if (onFt8Listen != null && !shouldStopDecode()) {
                                onFt8Listen.afterDecode(utc, averageOffset(allMsg), UtcTimer.sequential(utc), msgs, true);
                            }
                        } while (msgs.size() > 0 && !shouldStopDecode());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error during decode: " + e.getMessage(), e);
                } finally {
                    // [NEW] Безопасное удаление декодера
                    if (ft8Decoder != 0) {
                        try {
                            DeleteDecoder(ft8Decoder);
                        } catch (Exception e) {
                            Log.e(TAG, "Error deleting decoder: " + e.getMessage());
                        }
                    }
                }
            }
        }).start();
    }

    private ArrayList<Ft8Message> runDecode(long ft8Decoder, long utc, boolean isDeep) {
        ArrayList<Ft8Message> ft8Messages = new ArrayList<>();
        if (ft8Decoder == 0 || shouldStopDecode()) return ft8Messages;

        Ft8Message ft8Message = new Ft8Message(FT8Common.FT8_MODE);
        ft8Message.utcTime = utc;
        ft8Message.band = GeneralVariables.band;
        a91List.clear();

        try {
            setDecodeMode(ft8Decoder, isDeep);
            int num_candidates = DecoderFt8FindSync(ft8Decoder);

            for (int idx = 0; idx < num_candidates && !shouldStopDecode(); ++idx) {
                try {
                    if (DecoderFt8Analysis(idx, ft8Decoder, ft8Message)) {
                        if (ft8Message.isValid && !shouldStopDecode()) {
                            Ft8Message msg = new Ft8Message(ft8Message);
                            byte[] a91 = DecoderGetA91(ft8Decoder);
                            a91List.add(a91, ft8Message.freq_hz, ft8Message.time_sec);
                            if (checkMessageSame(ft8Messages, msg)) continue;
                            msg.isWeakSignal = isDeep;
                            ft8Messages.add(msg);
                        }
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Error in DecoderFt8Analysis idx=" + idx + ": " + t.getMessage());
                    // Продолжаем обработку остальных кандидатов
                }
            }
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Native library error: " + e.getMessage());
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error in runDecode: " + t.getMessage(), t);
        }

        return ft8Messages;
    }

    private float averageOffset(ArrayList<Ft8Message> messages) {
        if (messages == null || messages.size() == 0) return 0f;
        float dt = 0;
        for (Ft8Message msg : messages) {
            dt += msg.time_sec;
        }
        return dt / messages.size();
    }

    private void addMsgToList(ArrayList<Ft8Message> allMsg, ArrayList<Ft8Message> newMsg) {
        if (newMsg == null) return;
        for (int i = newMsg.size() - 1; i >= 0; i--) {
            if (checkMessageSame(allMsg, newMsg.get(i))) {
                newMsg.remove(i);
            } else {
                allMsg.add(newMsg.get(i));
            }
        }
    }

    private boolean checkMessageSame(ArrayList<Ft8Message> ft8Messages, Ft8Message ft8Message) {
        if (ft8Messages == null || ft8Message == null) return false;
        for (Ft8Message msg : ft8Messages) {
            if (msg.getMessageText().equals(ft8Message.getMessageText())) {
                if (msg.snr < ft8Message.snr) {
                    msg.snr = ft8Message.snr;
                }
                return true;
            }
        }
        return false;
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
    }

    public OnWaveDataListener getOnWaveDataListener() { return onWaveDataListener; }
    public void setOnWaveDataListener(OnWaveDataListener onWaveDataListener) {
        this.onWaveDataListener = onWaveDataListener;
    }

    public String getCacheFileName(String fileName) {
        return GeneralVariables.getMainContext().getCacheDir() + "/" + fileName;
    }

    public float[] ints2floats(int data[][]) {
        if (data == null || data.length == 0) return new float[0];
        float temp[] = new float[data[0].length];
        for (int i = 0; i < data[0].length; i++) {
            temp[i] = data[0][i] / 32768.0f;
        }
        return temp;
    }

    public int[] floats2ints(float data[]) {
        if (data == null) return new int[0];
        int temp[] = new int[data.length];
        for (int i = 0; i < data.length; i++) {
            temp[i] = (int) (data[i] * 32767.0f);
        }
        return temp;
    }

    // Native methods
    public native long InitDecoder(long utcTime, int sampleRat, int num_samples, boolean isFt8);
    public native void DecoderMonitorPress(int[] buffer, long decoder);
    public native void DecoderMonitorPressFloat(float[] buffer, long decoder);
    public native int DecoderFt8FindSync(long decoder);
    public native boolean DecoderFt8Analysis(int idx, long decoder, Ft8Message ft8Message);
    public native void DeleteDecoder(long decoder);
    public native void DecoderFt8Reset(long decoder, long utcTime, int num_samples);
    public native byte[] DecoderGetA91(long decoder);
    public native void setDecodeMode(long decoder, boolean isDeep);
}