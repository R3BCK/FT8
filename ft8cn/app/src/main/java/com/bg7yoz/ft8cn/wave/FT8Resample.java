package com.bg7yoz.ft8cn.wave;

/**
 * 用于重采样的库。
 * @author bg7yoz
 * @date 2023-09-09
 */
public class FT8Resample {

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

    public static native short[] get16Resample16(short[] inputData, int inputRate
            , int outputRate,int channels);

    public static native float[] get32Resample16(short[] inputData, int inputRate
            , int outputRate,int channels);
    public static native short[] get16Resample32(float[] inputData, int inputRate
            , int outputRate,int channels);
    public static native float[] get32Resample32(float[] inputData, int inputRate
            , int outputRate,int channels);

    public static native byte[] get8Resample16(short[] inputData, int inputRate
            , int outputRate,int channels);

    public static native byte[] get8Resample32(float[] inputData, int inputRate
            , int outputRate,int channels);

}
