package com.bg7yoz.ft8cn.connector;
/**
 * ICom network connector.
 * Note: ICom network audio packets are Int type, need to convert to Float type
 *
 * @author BGY70Z
 * @date 2023-08-19
 */

import android.util.Log;
import com.bg7yoz.ft8cn.icom.WifiRig;

public class IComWifiConnector extends WifiConnector {
    private static final String TAG = "IComWifiConnector";

    public IComWifiConnector(int controlMode, WifiRig wifiRig) {
        super(controlMode, wifiRig);
        Log.d(TAG, "Constructor: controlMode=" + controlMode + ", wifiRig=" + wifiRig);

        this.wifiRig.setOnDataEvents(new WifiRig.OnDataEvents() {
            @Override
            public void onReceivedCivData(byte[] data) {
                Log.d(TAG, "onReceivedCivData: len=" + (data != null ? data.length : 0));
                if (data != null && data.length > 0) {
                    StringBuilder hex = new StringBuilder(data.length * 3);
                    for (byte b : data) {
                        hex.append(String.format("%02X ", b));
                    }
                    Log.d(TAG, "onReceivedCivData: hex=" + hex.toString().trim());
                }

                if (getOnConnectReceiveData() != null) {
                    Log.d(TAG, "onReceivedCivData: forwarding to onData listener");
                    getOnConnectReceiveData().onData(data);
                } else {
                    Log.w(TAG, "onReceivedCivData: onData listener is NULL");
                }

                if (onWifiDataReceived != null) {
                    Log.d(TAG, "onReceivedCivData: calling OnCivReceived");
                    onWifiDataReceived.OnCivReceived(data);
                } else {
                    Log.w(TAG, "onReceivedCivData: onWifiDataReceived listener is NULL");
                }
            }

            @Override
            public void onReceivedWaveData(byte[] data) {
                if (onWifiDataReceived != null && data != null && data.length >= 2) {
                    float[] waveFloat = new float[data.length / 2];
                    for (int i = 0; i < waveFloat.length; i++) {
                        waveFloat[i] = readShortBigEndianData(data, i * 2) / 32768.0f;
                    }
                    onWifiDataReceived.OnWaveReceived(waveFloat.length, waveFloat);
                }
            }
        });
        Log.d(TAG, "Constructor: OnDataEvents listener registered");
    }

    @Override
    public void connect() {
        Log.d(TAG, "connect() called");
        super.connect();
        Log.d(TAG, "connect() returned, isConnected=" + isConnected());
    }

    @Override
    public void disconnect() {
        Log.d(TAG, "disconnect() called");
        super.disconnect();
        Log.d(TAG, "disconnect() returned");
    }

    @Override
    public synchronized void sendData(byte[] data) {
        if (data != null && data.length > 0) {
            StringBuilder hex = new StringBuilder(data.length * 3);
            for (byte b : data) {
                hex.append(String.format("%02X ", b));
            }
            Log.d(TAG, "sendData: len=" + data.length + " hex=" + hex.toString().trim());
        } else {
            Log.w(TAG, "sendData: called with null or empty data");
        }
        super.sendData(data);
    }


}