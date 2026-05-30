package com.bg7yoz.ft8cn.ui;

import static android.view.MotionEvent.ACTION_UP;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import java.util.ArrayList;
import java.util.List;

public class SpectrumView extends ConstraintLayout {
    private static final String TAG = "SpectrumView";

    private MainViewModel mainViewModel;
    private ColumnarView columnarView;
    private Switch controlDeNoiseSwitch;
    private Switch controlShowMessageSwitch;
    private WaterfallView waterfallView;
    private RulerFrequencyView rulerFrequencyView;
    private Fragment fragment;

    private int frequencyLineTimeOut = 0;

    // Corridor fields
    private final List<RectF> clearCorridors = new ArrayList<>();
    private final Paint corridorPaint;
    private final Paint corridorBorderPaint;

    // === НАСТРОЙКА ЧУВСТВИТЕЛЬНОСТИ ===
    // Коридором считается участок, который тише пикового сигнала на X дБ.
    // Увеличено до 15 для надежного обнаружения тихих зон.
    private static final int CORRIDOR_SENSITIVITY_DB = 15;
    private static final int MIN_BIN_WIDTH = 6; // Минимальная ширина коридора в точках FFT

    static {
        try {
            // Пытаемся загрузить библиотеку в зависимости от настройки
            boolean dxMode = com.bg7yoz.ft8cn.GeneralVariables.acceptDxCalls;
            String libName = dxMode ? "ft8cn_dx" : "ft8cn_std";
            System.loadLibrary(libName);
        } catch (UnsatisfiedLinkError e) {
            // Fallback: пробуем загрузить стандартную
            try {
                System.loadLibrary("ft8cn_std");
            } catch (UnsatisfiedLinkError e2) {
                // Если не удалось, пробуем старое имя для совместимости
                try {
                    System.loadLibrary("ft8cn");
                } catch (UnsatisfiedLinkError e3) {
                    android.util.Log.e("ReBuildSignal", "Failed to load any native library", e3);
                }
            }
        }
    }

    public SpectrumView(@NonNull Context context) {
        super(context);  // <-- ПЕРВЫМ
        corridorPaint = initCorridorPaint();
        corridorBorderPaint = initBorderPaint();
        Log.e(TAG, ">>> SpectrumView created (constructor 1)");
    }

    public SpectrumView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);  // <-- ОБЯЗАТЕЛЬНО ПЕРВЫМ, иначе ошибка компиляции
        View view = (View) View.inflate(context, R.layout.spectrum_layout, this);
        corridorPaint = initCorridorPaint();
        corridorBorderPaint = initBorderPaint();
        Log.e(TAG, ">>> SpectrumView created (constructor 2)");
    }

    private Paint initCorridorPaint() {
        Paint p = new Paint();
        p.setColor(Color.argb(150, 0, 255, 0)); // Полупрозрачный зеленый
        p.setStyle(Paint.Style.FILL);
        return p;
    }

    private Paint initBorderPaint() {
        Paint p = new Paint();
        p.setColor(Color.argb(200, 0, 220, 0)); // Яркая граница
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        return p;
    }

    @SuppressLint("ClickableViewAccessibility")
    public void run(MainViewModel mainViewModel, Fragment fragment) {
        Log.e(TAG, ">>> run() CALLED! ViewModel: " + (mainViewModel != null));

        this.mainViewModel = MainViewModel.getInstance(null);
        this.fragment = fragment;
        columnarView = findViewById(R.id.controlColumnarView);
        controlDeNoiseSwitch = findViewById(R.id.controlDeNoiseSwitch);
        waterfallView = findViewById(R.id.controlWaterfallView);
        rulerFrequencyView = findViewById(R.id.controlRulerFrequencyView);
        controlShowMessageSwitch = findViewById(R.id.controlShowMessageSwitch);

        setDeNoiseSwitchState();
        setMarkMessageSwitchState();

        rulerFrequencyView.setFreq(Math.round(GeneralVariables.getBaseFrequency()));
        mainViewModel.currentMessages = null;

        controlDeNoiseSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                mainViewModel.deNoise = b;
                setDeNoiseSwitchState();
                mainViewModel.currentMessages = null;
            }
        });

        controlShowMessageSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                mainViewModel.markMessage = b;
                setMarkMessageSwitchState();
            }
        });

        if (mainViewModel.spectrumListener != null) {
            mainViewModel.spectrumListener.mutableDataBuffer.observe(fragment.getViewLifecycleOwner(), new Observer<float[]>() {
                @Override
                public void onChanged(float[] ints) {
                    Log.d(TAG, "Data buffer changed, length: " + (ints != null ? ints.length : 0));
                    drawSpectrum(ints);
                }
            });
        } else {
            Log.e(TAG, "ERROR: spectrumListener is NULL!");
        }

        mainViewModel.mutableIsDecoding.observe(fragment.getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                waterfallView.setDrawMessage(!aBoolean);
            }
        });

        View.OnTouchListener touchListener = new View.OnTouchListener() {
            @SuppressLint("DefaultLocale")
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                frequencyLineTimeOut = 60;
                waterfallView.setTouch_x(Math.round(motionEvent.getX()));
                columnarView.setTouch_x(Math.round(motionEvent.getX()));

                if (motionEvent.getAction() == ACTION_UP && !clearCorridors.isEmpty()) {
                    float x = motionEvent.getX();
                    for (RectF rect : clearCorridors) {
                        if (rect.contains(x, 0)) {
                            float freqHz = (x / getWidth()) * 3000f;
                            selectFrequency(freqHz);
                            return true;
                        }
                    }
                }

                if (!mainViewModel.ft8TransmitSignal.isSynFrequency()
                        && (waterfallView.getFreq_hz() > 0)
                        && (motionEvent.getAction() == ACTION_UP)) {
                    mainViewModel.databaseOpr.writeConfig("freq",
                            String.valueOf(waterfallView.getFreq_hz()), null);
                    mainViewModel.ft8TransmitSignal.setBaseFrequency((float) waterfallView.getFreq_hz());
                    rulerFrequencyView.setFreq(waterfallView.getFreq_hz());

                    fragment.requireActivity().runOnUiThread(() -> ToastMessage.show(String.format(
                            GeneralVariables.getStringFromResource(R.string.sound_frequency_is_set_to),
                            waterfallView.getFreq_hz()), true));
                }
                return false;
            }
        };

        waterfallView.setOnTouchListener(touchListener);
        columnarView.setOnTouchListener(touchListener);
    }

    private void selectFrequency(float freq) {
        float roundedFreq = Math.round(freq / 10) * 10;
        mainViewModel.ft8TransmitSignal.setBaseFrequency(roundedFreq);
        rulerFrequencyView.setFreq((int) roundedFreq);
        ToastMessage.show("TX: " + roundedFreq + " Hz", true);
    }

    private void setDeNoiseSwitchState() {
        if (mainViewModel == null) return;
        controlDeNoiseSwitch.setChecked(mainViewModel.deNoise);
        controlDeNoiseSwitch.setText(mainViewModel.deNoise ?
                GeneralVariables.getStringFromResource(R.string.de_noise) :
                GeneralVariables.getStringFromResource(R.string.raw_spectrum_data));
    }

    private void setMarkMessageSwitchState() {
        controlShowMessageSwitch.setText(mainViewModel.markMessage ?
                GeneralVariables.getStringFromResource(R.string.markMessage) :
                GeneralVariables.getStringFromResource(R.string.unMarkMessage));
    }

    public void drawSpectrum(float[] buffer) {
        Log.d(TAG, "drawSpectrum called. Buffer: " + (buffer != null ? buffer.length : "null"));

        if (buffer == null || buffer.length <= 0) return;

        int[] fft = new int[buffer.length / 2];
        if (mainViewModel.deNoise) {
            getFFTDataFloat(buffer, fft);
        } else {
            getFFTDataRawFloat(buffer, fft);
        }

        analyzeCorridors(fft);

        frequencyLineTimeOut--;
        if (frequencyLineTimeOut < 0) frequencyLineTimeOut = 0;
        if (frequencyLineTimeOut == 0) {
            waterfallView.setTouch_x(-1);
            columnarView.setTouch_x(-1);
        }

        columnarView.setWaveData(fft);
        if (mainViewModel.markMessage) {
            waterfallView.setWaveData(fft, UtcTimer.getNowSequential(), mainViewModel.currentMessages);
        } else {
            waterfallView.setWaveData(fft, UtcTimer.getNowSequential(), null);
        }

        invalidate();
    }

    private void analyzeCorridors(int[] fftData) {
        clearCorridors.clear();
        if (fftData == null || fftData.length == 0) {
            Log.d(TAG, "analyzeCorridors: fftData is empty");
            return;
        }
        if (getWidth() == 0) {
            Log.d(TAG, "analyzeCorridors: view width is 0");
            return;
        }

        int maxVal = Integer.MIN_VALUE;
        int minVal = Integer.MAX_VALUE;
        for (int val : fftData) {
            if (val > maxVal) maxVal = val;
            if (val < minVal) minVal = val;
        }

        int threshold = maxVal - CORRIDOR_SENSITIVITY_DB;
        Log.d(TAG, "FFT stats: min=" + minVal + " max=" + maxVal + " threshold=" + threshold);

        boolean inCorridor = false;
        int startBin = 0;
        int fftLen = fftData.length;
        int foundCount = 0;

        for (int i = 0; i < fftLen; i++) {
            if (fftData[i] < threshold) {
                if (!inCorridor) {
                    inCorridor = true;
                    startBin = i;
                }
            } else {
                if (inCorridor) {
                    inCorridor = false;
                    int width = i - startBin;
                    if (width >= MIN_BIN_WIDTH) {
                        addCorridor(startBin, i, fftLen);
                        foundCount++;
                    }
                }
            }
        }
        if (inCorridor) {
            int width = fftLen - startBin;
            if (width >= MIN_BIN_WIDTH) {
                addCorridor(startBin, fftLen, fftLen);
                foundCount++;
            }
        }
        Log.d(TAG, "Corridors found: " + foundCount);
    }

    private void addCorridor(int startBin, int endBin, int totalBins) {
        if (getWidth() == 0) return;
        float left = ((float) startBin / totalBins) * getWidth();
        float right = ((float) endBin / totalBins) * getWidth();
        clearCorridors.add(new RectF(left, 0, right, getHeight() * 0.35f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Log.d(TAG, "onDraw called! W=" + getWidth() + " H=" + getHeight() + " rects=" + clearCorridors.size());

        // ТЕСТОВЫЙ КРАСНЫЙ ПРЯМОУГОЛЬНИК
        Paint debugPaint = new Paint();
        debugPaint.setColor(Color.RED);
        debugPaint.setAlpha(100);
        canvas.drawRect(0, 0, getWidth() / 2, getHeight() * 0.35f, debugPaint);

        for (RectF rect : clearCorridors) {
            canvas.drawRect(rect, corridorPaint);
            canvas.drawRect(rect, corridorBorderPaint);
        }
    }

    public native void getFFTData(int[] data, int fftData[]);
    public native void getFFTDataFloat(float[] data, int fftData[]);
    public native void getFFTDataRaw(int[] data, int fftData[]);
    public native void getFFTDataRawFloat(float[] data, int fftData[]);
}