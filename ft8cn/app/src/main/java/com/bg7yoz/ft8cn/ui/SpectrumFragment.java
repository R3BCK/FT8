package com.bg7yoz.ft8cn.ui;
/**
 * Spectrum graph main interface.
 * @author BGY70Z
 * @date 2023-03-20
 */

import static android.view.MotionEvent.ACTION_UP;

import android.annotation.SuppressLint;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.databinding.FragmentSpectrumBinding;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A simple Fragment subclass.
 * Create an instance of this fragment.
 */
public class SpectrumFragment extends Fragment {
    private static final String TAG = "SpectrumFragment";

    // === FT8 Safe Frequency Limits ===
    private static final int MIN_SAFE_FREQ_HZ = 100;
    private static final int MAX_SAFE_FREQ_HZ = 2900;
    // =================================

    private FragmentSpectrumBinding binding;
    private MainViewModel mainViewModel;
    private TextView spectrumLocTimeText;
    private TextView spectrumOffsetText;

    private int frequencyLineTimeOut = 0;

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

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mainViewModel = MainViewModel.getInstance(this);
        binding = FragmentSpectrumBinding.inflate(inflater, container, false);

        //Log.d("ft8cn Spectrum", ">>> SpectrumFragment: binding created");

        binding.columnarView.setShowBlock(true);
        binding.deNoiseSwitch.setChecked(mainViewModel.deNoise);
        binding.waterfallView.setDrawMessage(false);
        setDeNoiseSwitchState();
        setMarkMessageSwitchState();

        // === RESTORE PERSISTENT ZONES FROM VIEWMODEL ===
        binding.columnarView.setOccupiedZones(mainViewModel.getPersistentOccupiedZones());
        // ===============================================

        spectrumLocTimeText = binding.getRoot().findViewById(R.id.spectrumLocTimeText);
        spectrumOffsetText = binding.getRoot().findViewById(R.id.spectrumOffsetText);
        updateSpectrumTimeDisplay();

        binding.rulerFrequencyView.setFreq(Math.round(GeneralVariables.getBaseFrequency()));
        mainViewModel.currentMessages = null;

        // === OBSERVE DECODE COMPLETION TO UPDATE OCCUPIED ZONES ===
        mainViewModel.mutableIsDecoding.observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean isDecoding) {
                if (!isDecoding) {
                    updateOccupiedZones();
                }
            }
        });
        // =========================================================

        binding.deNoiseSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                mainViewModel.deNoise = b;
                setDeNoiseSwitchState();
                mainViewModel.currentMessages = null;
            }
        });

        binding.showMessageSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                mainViewModel.markMessage = b;
                setMarkMessageSwitchState();
            }
        });

        mainViewModel.spectrumListener.mutableDataBuffer.observe(getViewLifecycleOwner(), new Observer<float[]>() {
            @Override
            public void onChanged(float[] floats) {
                drawSpectrum(floats);
            }
        });

        mainViewModel.ft8SignalListener.decodeTimeSec.observe(getViewLifecycleOwner(), new Observer<Long>() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onChanged(Long aLong) {
                binding.decodeDurationTextView.setText(String.format(
                        GeneralVariables.getStringFromResource(R.string.decoding_takes_milliseconds), aLong));
            }
        });

        mainViewModel.mutableIsDecoding.observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                binding.waterfallView.setDrawMessage(!aBoolean);
            }
        });

        mainViewModel.timerSec.observe(getViewLifecycleOwner(), new Observer<Long>() {
            @Override
            public void onChanged(Long utcMillis) {
                binding.timersTextView.setText(UtcTimer.getTimeStr(utcMillis));
                binding.freqBandTextView.setText(GeneralVariables.getBandString());
                updateSpectrumTimeDisplay();
            }
        });

        // === Clear zones on band change ===
        GeneralVariables.mutableBandChange.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer bandIndex) {
                if (mainViewModel != null) {
                    mainViewModel.clearPersistentOccupiedZones();
                }
                if (binding.columnarView != null) {
                    binding.columnarView.setOccupiedZones(new ArrayList<>());
                }
            }
        });
        // =================================

        View.OnTouchListener touchListener = new View.OnTouchListener() {
            @SuppressLint("DefaultLocale")
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                frequencyLineTimeOut = 60;
                binding.waterfallView.setTouch_x(Math.round(motionEvent.getX()));
                binding.columnarView.setTouch_x(Math.round(motionEvent.getX()));

                // Handle tap on spectrum to set TX frequency
                if (motionEvent.getAction() == ACTION_UP) {
                    float x = motionEvent.getX();
                    float freqHz = (x / binding.columnarView.getWidth()) * 3000f;
                    selectFrequency(freqHz);
                    return true;
                }

                if (!mainViewModel.ft8TransmitSignal.isSynFrequency()
                        && (binding.waterfallView.getFreq_hz() > 0)
                        && (motionEvent.getAction() == ACTION_UP)) {
                    mainViewModel.databaseOpr.writeConfig("freq",
                            String.valueOf(binding.waterfallView.getFreq_hz()),
                            null);
                    mainViewModel.ft8TransmitSignal.setBaseFrequency(
                            (float) binding.waterfallView.getFreq_hz());
                    binding.rulerFrequencyView.setFreq(binding.waterfallView.getFreq_hz());

                    requireActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            ToastMessage.show(String.format(
                                    GeneralVariables.getStringFromResource(R.string.sound_frequency_is_set_to),
                                    binding.waterfallView.getFreq_hz()), true);
                        }
                    });
                }
                return false;
            }
        };

        binding.waterfallView.setOnTouchListener(touchListener);
        binding.columnarView.setOnTouchListener(touchListener);

        return binding.getRoot();
    }

    /**
     * Update occupied zones from ViewModel.
     */
    private void updateOccupiedZones() {
        if (mainViewModel.currentMessages != null && binding.columnarView.getWidth() > 0) {
            mainViewModel.updatePersistentOccupiedZones(
                    mainViewModel.currentMessages,
                    binding.columnarView.getWidth(),
                    binding.columnarView.getHeight()
            );
        }

        // Always display current persistent zones from ViewModel
        binding.columnarView.setOccupiedZones(mainViewModel.getPersistentOccupiedZones());
    }

    /**
     * Set transmit frequency with strict safety clamping (100-2900 Hz).
     */
    private void selectFrequency(float freq) {
        float clampedFreq = Math.max(MIN_SAFE_FREQ_HZ, Math.min(MAX_SAFE_FREQ_HZ, freq));
        float roundedFreq = Math.round(clampedFreq / 10) * 10;

        Log.d("ft8cn Spectrum", "selectFrequency: raw=" + freq +
                ", clamped=" + clampedFreq + ", final=" + roundedFreq);

        mainViewModel.ft8TransmitSignal.setBaseFrequency(roundedFreq);
        binding.rulerFrequencyView.setFreq((int) roundedFreq);
        ToastMessage.show("TX: " + roundedFreq + " Hz", true);
    }

    public void drawSpectrum(float[] buffer) {
        if (buffer.length <= 0) return;

        int[] fft = new int[buffer.length / 2];
        if (mainViewModel.deNoise) {
            getFFTDataFloat(buffer, fft);
        } else {
            getFFTDataRawFloat(buffer, fft);
        }

        frequencyLineTimeOut--;
        if (frequencyLineTimeOut < 0) frequencyLineTimeOut = 0;
        if (frequencyLineTimeOut == 0) {
            binding.waterfallView.setTouch_x(-1);
            binding.columnarView.setTouch_x(-1);
        }
        binding.columnarView.setWaveData(fft);
        if (mainViewModel.markMessage) {
            binding.waterfallView.setWaveData(fft, UtcTimer.getNowSequential(), mainViewModel.currentMessages);
        } else {
            binding.waterfallView.setWaveData(fft, UtcTimer.getNowSequential(), null);
        }
    }

    private void setDeNoiseSwitchState() {
        if (mainViewModel.deNoise) {
            binding.deNoiseSwitch.setText(getString(R.string.de_noise));
        } else {
            binding.deNoiseSwitch.setText(getString(R.string.raw_spectrum_data));
        }
    }

    private void setMarkMessageSwitchState() {
        if (mainViewModel.markMessage) {
            binding.showMessageSwitch.setText(getString(R.string.markMessage));
        } else {
            binding.showMessageSwitch.setText(getString(R.string.unMarkMessage));
        }
    }

    public native void getFFTData(int[] data, int fftData[]);
    public native void getFFTDataFloat(float[] data, int fftData[]);
    public native void getFFTDataRaw(int[] data, int fftData[]);
    public native void getFFTDataRawFloat(float[] data, int fftData[]);

    private void updateSpectrumTimeDisplay() {
        if (spectrumLocTimeText == null || spectrumOffsetText == null) return;

        String loc = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        spectrumLocTimeText.setText("Loc: " + loc);

        long offset = UtcTimer.delay;
        spectrumOffsetText.setText(String.format("%+d ms", offset));

        int color = Math.abs(offset) <= 100
                ? requireContext().getColor(R.color.spectrum_text_color)
                : requireContext().getColor(R.color.text_view_error_color);
        spectrumOffsetText.setTextColor(color);
    }
}