package com.bg7yoz.ft8cn.connector;
/**
 * Network connector base class.
 * Note: Compatible with ICom network mode, but audio packets are Int type, need conversion to Float.
 *
 * @author BGY70Z
 * @date 2023-08-19
 */

import android.util.Log;
import com.bg7yoz.ft8cn.icom.WifiRig;

public class WifiConnector extends BaseRigConnector {
    private static final String TAG = "WifiConnector";

    public interface OnWifiDataReceived {
        void OnWaveReceived(int bufferLen, float[] buffer);
        void OnCivReceived(byte[] data);
    }

    public WifiRig wifiRig;
    public OnWifiDataReceived onWifiDataReceived;

    public WifiConnector(int controlMode, WifiRig wifiRig) {
        super(controlMode);
        Log.d(TAG, "Constructor START: controlMode=" + controlMode);

        this.wifiRig = wifiRig;

        if (wifiRig != null) {
            Log.d(TAG, "Constructor: registering OnDataEvents listener");
            this.wifiRig.setOnDataEvents(new WifiRig.OnDataEvents() {
                @Override
                public void onReceivedCivData(byte[] data) {
                    Log.d(TAG, "onReceivedCivData: len=" + (data != null ? data.length : 0));
                    if (data != null && data.length > 0) {
                        StringBuilder hex = new StringBuilder();
                        for (byte b : data) hex.append(String.format("%02X ", b));
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
                    Log.d(TAG, "onReceivedWaveData: len=" + (data != null ? data.length : 0));

                    if (onWifiDataReceived != null && data != null && data.length >= 2) {
                        Log.d(TAG, "onReceivedWaveData: converting " + data.length + " bytes to float[]");
                        float[] waveFloat = new float[data.length / 2];
                        for (int i = 0; i < waveFloat.length; i++) {
                            waveFloat[i] = readShortBigEndianData(data, i * 2) / 32768.0f;
                        }
                        Log.d(TAG, "onReceivedWaveData: calling OnWaveReceived with " + waveFloat.length + " samples");
                        onWifiDataReceived.OnWaveReceived(waveFloat.length, waveFloat);
                    } else {
                        Log.w(TAG, "onReceivedWaveData: skipped (listener null or data too short)");
                    }
                }
            });
            Log.d(TAG, "Constructor: OnDataEvents listener registered");
        } else {
            Log.e(TAG, "Constructor: wifiRig is NULL!");
        }
        Log.d(TAG, "Constructor END");
    }

    @Override
    public void sendWaveData(float[] data) {
        Log.d(TAG, "sendWaveData: len=" + (data != null ? data.length : 0));
        if (wifiRig != null) {
            Log.d(TAG, "sendWaveData: wifiRig.opened=" + wifiRig.opened);
            if (wifiRig.opened) {
                Log.d(TAG, "sendWaveData: calling wifiRig.sendWaveData()");
                wifiRig.sendWaveData(data);
                Log.d(TAG, "sendWaveData: done");
            } else {
                Log.w(TAG, "sendWaveData: skipped, wifiRig not opened");
            }
        } else {
            Log.e(TAG, "sendWaveData: wifiRig is NULL");
        }
    }

    @Override
    public void connect() {
        Log.d(TAG, "connect() START");
        Log.d(TAG, "connect: wifiRig");

        super.connect();

        if (wifiRig != null) {
            try {
                Log.d(TAG, "connect: calling wifiRig.start()");
                long startTime = System.currentTimeMillis();
                wifiRig.start();
                long elapsed = System.currentTimeMillis() - startTime;
                Log.d(TAG, "connect: wifiRig.start() returned in " + elapsed + "ms");
                Log.d(TAG, "connect: wifiRig.opened=" + wifiRig.opened);
            } catch (Exception e) {
                Log.e(TAG, "connect: Exception in wifiRig.start(): " + e.getMessage(), e);
                if (getOnConnectorStateChanged() != null) {
                    getOnConnectorStateChanged().onRunError("Start failed: " + e.getMessage());
                }
            }
        } else {
            Log.e(TAG, "connect: wifiRig is NULL, cannot start");
            if (getOnConnectorStateChanged() != null) {
                getOnConnectorStateChanged().onRunError("wifiRig is NULL");
            }
        }
        Log.d(TAG, "connect() END, isConnected=" + isConnected());
    }

    @Override
    public void disconnect() {
        Log.d(TAG, "disconnect() START");
        super.disconnect();
        if (wifiRig != null) {
            Log.d(TAG, "disconnect: calling wifiRig.close()");
            try {
                wifiRig.close();
                Log.d(TAG, "disconnect: wifiRig.close() done, opened=" + wifiRig.opened);
            } catch (Exception e) {
                Log.e(TAG, "disconnect: Exception in wifiRig.close(): " + e.getMessage(), e);
            }
        } else {
            Log.w(TAG, "disconnect: wifiRig is NULL");
        }
        Log.d(TAG, "disconnect() END");
    }

    @Override
    public void sendData(byte[] data) {
        if (data != null && data.length > 0) {
            StringBuilder hex = new StringBuilder();
            for (byte b : data) hex.append(String.format("%02X ", b));
            Log.d(TAG, "sendData: len=" + data.length + " hex=" + hex.toString().trim());
        } else {
            Log.w(TAG, "sendData: called with null or empty data");
            return;
        }

        if (!isConnected()) {
            Log.w(TAG, "sendData: not connected (isConnected=" + isConnected() + "), skipping");
            return;
        }

        if (wifiRig != null) {
            try {
                Log.d(TAG, "sendData: calling wifiRig.sendCivData()");
                wifiRig.sendCivData(data);
                Log.d(TAG, "sendData: done");
            } catch (Exception e) {
                Log.e(TAG, "sendData: Exception in wifiRig.sendCivData(): " + e.getMessage(), e);
                if (getOnConnectorStateChanged() != null) {
                    getOnConnectorStateChanged().onRunError("Send failed: " + e.getMessage());
                }
            }
        } else {
            Log.e(TAG, "sendData: wifiRig is NULL");
        }
    }

    @Override
    public void setPttOn(byte[] command) {
        Log.d(TAG, "setPttOn(byte[]): len=" + (command != null ? command.length : 0));
        if (command != null && command.length > 0) {
            StringBuilder hex = new StringBuilder();
            for (byte b : command) hex.append(String.format("%02X ", b));
            Log.d(TAG, "setPttOn(byte[]): hex=" + hex.toString().trim());
        }

        if (wifiRig != null && isConnected()) {
            Log.d(TAG, "setPttOn(byte[]): calling wifiRig.sendCivData()");
            wifiRig.sendCivData(command);
        } else {
            Log.w(TAG, "setPttOn(byte[]): skipped, not connected or wifiRig null");
        }
    }

    @Override
    public void setPttOn(boolean on) {
        Log.d(TAG, "setPttOn(boolean): on=" + on);
        if (wifiRig != null) {
            Log.d(TAG, "setPttOn(boolean): wifiRig.opened=" + wifiRig.opened);
            if (wifiRig.opened) {
                Log.d(TAG, "setPttOn(boolean): calling wifiRig.setPttOn(" + on + ")");
                wifiRig.setPttOn(on);
                Log.d(TAG, "setPttOn(boolean): done");
            } else {
                Log.w(TAG, "setPttOn(boolean): skipped, wifiRig not opened");
            }
        } else {
            Log.e(TAG, "setPttOn(boolean): wifiRig is NULL");
        }
    }

    public OnWifiDataReceived getOnWifiDataReceived() {
        return onWifiDataReceived;
    }

    @Override
    public boolean isConnected() {
        boolean connected = (wifiRig != null && wifiRig.opened);
        // Log only when state changes to avoid spam
        // Log.d(TAG, "isConnected: returning " + connected);
        return connected;
    }

    public void setOnWifiDataReceived(OnWifiDataReceived onDataReceived) {
        Log.d(TAG, "setOnWifiDataReceived: listener=" + onDataReceived);
        this.onWifiDataReceived = onDataReceived;
    }

    /**
     * Read little-endian Short from byte stream
     *
     * @param data  byte stream
     * @param start start position
     * @return Int16
     */
    public static short readShortBigEndianData(byte[] data, int start) {
        if (data.length - start < 2) return 0;
        return (short) ((short) data[start] & 0xff
                | ((short) data[start + 1] & 0xff) << 8);
    }
}