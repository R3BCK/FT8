package com.bg7yoz.ft8cn.connector;

import android.content.Context;
import android.util.Log;

import com.bg7yoz.ft8cn.database.ControlMode;
import com.bg7yoz.ft8cn.rigs.BaseRig;
import com.bg7yoz.ft8cn.serialport.util.SerialInputOutputManager;

/**
 * Connector for wired (USB cable) connections to radios.
 * Extends BaseRigConnector.
 *
 * @author BG7YOZ
 * @date 2023-03-20
 */
public class CableConnector extends BaseRigConnector {
    private static final String TAG = "CableConnector";

    /**
     * Callback interface for receiving waveform data from rig over CAT.
     * Added 2023-08-16 by DS1UFX for (tr)uSDX audio-over-CAT support.
     */
    public interface OnCableDataReceived {
        void OnWaveReceived(int bufferLen, float[] buffer);
    }

    /**
     * Callback interface for USB error notifications.
     * Allows notifying MainActivity about USB disconnects for RFI protection.
     */
    public interface OnUsbErrorListener {
        void onUsbConnectionError(String errorMessage);
    }

    private final CableSerialPort cableSerialPort;
    private final BaseRig cableConnectedRig;
    private OnCableDataReceived onCableDataReceived;
    private OnUsbErrorListener onUsbErrorListener;

    public CableConnector(Context context,
                          CableSerialPort.SerialPort serialPort,
                          int baudRate,
                          int controlMode,
                          BaseRig cableConnectedRig) {
        super(controlMode);
        this.cableConnectedRig = cableConnectedRig;
        cableSerialPort = new CableSerialPort(context, serialPort, baudRate, getOnConnectorStateChanged());

        cableSerialPort.ioListener = new SerialInputOutputManager.Listener() {
            @Override
            public void onNewData(byte[] data) {
                if (getOnConnectReceiveData() != null) {
                    getOnConnectReceiveData().onData(data);
                }
            }

            @Override
            public void onRunError(Exception e) {
                String errorMsg = e.getMessage();
                Log.e(TAG, "CableConnector error: " + errorMsg);

                // Detect USB-specific errors (RFI-induced disconnects)
                if (errorMsg != null && (
                        errorMsg.contains("USB get_status request failed") ||
                                errorMsg.contains("USB device not found") ||
                                errorMsg.contains("device 0x") ||
                                errorMsg.contains("connection lost"))) {

                    Log.w(TAG, "USB RFI disconnect detected: " + errorMsg);

                    // Notify listener to increment disconnect counter
                    if (onUsbErrorListener != null) {
                        onUsbErrorListener.onUsbConnectionError(errorMsg);
                    }
                }

                if (getOnConnectorStateChanged() != null) {
                    getOnConnectorStateChanged().onRunError("Serial connection lost: " + errorMsg);
                }
            }
        };
    }

    /**
     * Set listener for USB error notifications.
     * @param listener Listener to receive USB error callbacks
     */
    public void setOnUsbErrorListener(OnUsbErrorListener listener) {
        this.onUsbErrorListener = listener;
    }

    @Override
    public synchronized void sendData(byte[] data) {
        if (cableSerialPort != null) {
            cableSerialPort.sendData(data);
        }
    }

    @Override
    public void setPttOn(boolean on) {
        switch (getControlMode()) {
            case ControlMode.DTR:
                if (cableSerialPort != null) {
                    cableSerialPort.setDTR_On(on);
                }
                break;
            case ControlMode.RTS:
                if (cableSerialPort != null) {
                    cableSerialPort.setRTS_On(on);
                }
                break;
        }
    }

    @Override
    public void setPttOn(byte[] command) {
        sendData(command);
    }

    @Override
    public void sendWaveData(byte[] data) {
        sendData(data);
    }

    @Override
    public void receiveWaveData(float[] data) {
        if (onCableDataReceived != null) {
            onCableDataReceived.OnWaveReceived(data.length, data);
        }
    }

    public void setOnCableDataReceived(OnCableDataReceived onCableDataReceived) {
        this.onCableDataReceived = onCableDataReceived;
    }

    @Override
    public void connect() {
        super.connect();
        if (cableSerialPort != null) {
            cableSerialPort.connect();
        }
    }

    @Override
    public void disconnect() {
        if (cableConnectedRig != null) {
            cableConnectedRig.onDisconnecting();
        }
        super.disconnect();
        if (cableSerialPort != null) {
            cableSerialPort.disconnect();
        }
    }

    /**
     * Configure smart polling for the connected rig.
     * Call this after connection to enable 1-second interval and Transceive support.
     * @param rig The connected BaseRig instance
     */
    public void configureSmartPolling(BaseRig rig) {
        if (rig != null) {
            rig.setPollIntervalMs(1000);

            if (rig instanceof com.bg7yoz.ft8cn.rigs.IcomRig) {
                rig.setTransceiveEnabled(true);
                rig.enableTransceiveMode();
                Log.d(TAG, "Smart polling: Transceive enabled for Icom rig");
            } else {
                rig.setTransceiveEnabled(false);
                Log.d(TAG, "Smart polling: Transceive not supported for this rig");
            }
        }
    }
}