package com.bg7yoz.ft8cn.ft8listener;

import android.util.Log;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.ft8transmit.GenerateFT8;
import com.bg7yoz.ft8cn.wave.WaveFileWriter;

import java.util.ArrayList;

public class ReBuildSignal {
    private static String TAG = "ReBuildSignal";
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


    public static void subtractSignal(long decoder,A91List a91List){
        for (A91List.A91 a91 : a91List.list) {
            doSubtractSignal(decoder,a91.a91,FT8Common.SAMPLE_RATE,a91.freq_hz,a91.time_sec);
        }
    }

    private static native void doSubtractSignal(long decoder,byte[] payload,int sample_rate
            ,float frequency,float time_sec);

}
