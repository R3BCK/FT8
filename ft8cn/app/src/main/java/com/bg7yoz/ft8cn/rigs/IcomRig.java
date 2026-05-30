package com.bg7yoz.ft8cn.rigs;

/**
 * IcomRig is a generic Icom radio control class. For wifi mode, actual control is via IComWifiConnector
 * (inheriting from WifiConnector). In IComWifiConnector, IComWifiRig performs specific radio operations.
 */

import android.util.Log;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.connector.ConnectMode;
import com.bg7yoz.ft8cn.database.ControlMode;
import com.bg7yoz.ft8cn.ft8transmit.GenerateFT8;
import com.bg7yoz.ft8cn.icom.IComPacketTypes;
import com.bg7yoz.ft8cn.ui.ToastMessage;

import java.util.Timer;
import java.util.TimerTask;

public class IcomRig extends BaseRig {
    private static final String TAG = "IcomRig";

    private final int ctrAddress = 0xE0; // Receiver address, default 0xE0; radio replies can also be 0x00
    private byte[] dataBuffer = new byte[0]; // Data buffer
    private int alc = 0;
    private int swr = 0;
    private boolean alcMaxAlert = false;
    private boolean swrAlert = false;
    private Timer meterTimer; // Timer for querying meter

    private boolean oldVersion = false; // Old radios may not support SWR query

    @Override
    public void setPTT(boolean on) {
        super.setPTT(on);
        alcMaxAlert = false;
        swrAlert = false;
        if (on) {
            // Fix connection mode: 0x03=WLAN, 0x01=USB, 0x02=USB+mic to ensure audio reaches radio
            if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
                sendCivData(IcomRigConstant.setConnectorDataMode(ctrAddress, getCivAddress(), (byte) 0x03));
            } else if (GeneralVariables.connectMode == ConnectMode.USB_CABLE) {
                sendCivData(IcomRigConstant.setConnectorDataMode(ctrAddress, getCivAddress(), (byte) 0x01));
            } else {
                sendCivData(IcomRigConstant.setConnectorDataMode(ctrAddress, getCivAddress(), (byte) 0x02));
            }
        }

        if (getConnector() != null) {
            if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
                getConnector().setPttOn(on);
                return;
            }

            switch (getControlMode()) {
                case ControlMode.CAT: // Via CIV command
                    getConnector().setPttOn(IcomRigConstant.setPTTState(ctrAddress, getCivAddress()
                            , on ? IcomRigConstant.PTT_ON : IcomRigConstant.PTT_OFF));
                    break;
                case ControlMode.RTS:
                case ControlMode.DTR:
                    getConnector().setPttOn(on);
                    break;
            }
        }
    }

    @Override
    public boolean isConnected() {
        if (getConnector() == null) {
            return false;
        }
        return getConnector().isConnected();
    }

    @Override
    public void setUsbModeToRig() {
        if (getConnector() != null) {
            // For older Icom radios that may not support USB-D: set USB first, then USB-D.
            // If USB-D is unsupported, the command is ignored and radio stays in USB mode.
            getConnector().sendData(IcomRigConstant.setOperationDataMode(ctrAddress
                    , getCivAddress(), IcomRigConstant.USB)); // USB-D
        }
    }

    private void sendCivData(byte[] data) {
        if (getConnector() != null) {
            getConnector().sendData(data);
        }
    }

    @Override
    public void setFreqToRig() {
        if (getConnector() != null) {
            getConnector().sendData(IcomRigConstant.setOperationFrequency(ctrAddress
                    , getCivAddress(), getFreq()));
        }
    }

    /**
     * Find the end of a command (0xFD). Returns -1 if not found.
     * @param data Data buffer
     * @return Position of 0xFD or -1
     */
    private int getCommandEnd(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == (byte) 0xFD) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Find command header (0xFE 0xFE). Returns -1 if not found, otherwise position of first 0xFE.
     * @param data Data buffer
     * @return Position or -1
     */
    private int getCommandHead(byte[] data) {
        if (data.length < 2) return -1;
        for (int i = 0; i < data.length - 1; i++) {
            if (data[i] == (byte) 0xFE && data[i + 1] == (byte) 0xFE) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void sendWaveData(Ft8Message message) {
        if (getConnector() != null) {
            float[] data = GenerateFT8.generateFt8(message, GeneralVariables.getBaseFrequency(), 12000);
            if (data == null) {
                setPTT(false);
                return;
            }
            getConnector().sendWaveData(data);
        }
    }

    private void analysisCommand(byte[] data) {
        int headIndex = getCommandHead(data);
        if (headIndex == -1) return; // No command header

        IcomCommand icomCommand;
        if (headIndex == 0) {
            icomCommand = IcomCommand.getCommand(ctrAddress, getCivAddress(), data);
        } else {
            byte[] temp = new byte[data.length - headIndex];
            System.arraycopy(data, headIndex, temp, 0, temp.length);
            icomCommand = IcomCommand.getCommand(ctrAddress, getCivAddress(), temp);
        }
        if (icomCommand == null) return;

        // React only to frequency and mode messages for now
        switch (icomCommand.getCommandID()) {
            case IcomRigConstant.CMD_SEND_FREQUENCY_DATA:
            case IcomRigConstant.CMD_READ_OPERATING_FREQUENCY:
                setFreq(icomCommand.getFrequency(false));
                break;
            case IcomRigConstant.CMD_SEND_MODE_DATA:
            case IcomRigConstant.CMD_READ_OPERATING_MODE:
                break;
            case IcomRigConstant.CMD_READ_METER:
                if (icomCommand.getSubCommand() == IcomRigConstant.CMD_READ_METER_ALC) {
                    alc = IcomRigConstant.twoByteBcdToInt(icomCommand.getData(true));
                }
                if (icomCommand.getSubCommand() == IcomRigConstant.CMD_READ_METER_SWR) {
                    swr = IcomRigConstant.twoByteBcdToInt(icomCommand.getData(true));
                }
                showAlert();
                break;
            case IcomRigConstant.CMD_CONNECTORS:
                break;
        }
    }

    private void showAlert() {
        if ((swr >= IcomRigConstant.swr_alert_max) && GeneralVariables.swr_switch_on) {
            if (!swrAlert) {
                swrAlert = true;
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.swr_high_alert));
            }
        } else {
            swrAlert = false;
        }
        if ((alc > IcomRigConstant.alc_alert_max) && GeneralVariables.alc_switch_on) {
            if (!alcMaxAlert) {
                alcMaxAlert = true;
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.alc_high_alert));
            }
        } else {
            alcMaxAlert = false;
        }
    }

    @Override
    public void onReceiveData(byte[] data) {
        int commandEnd = getCommandEnd(data);
        if (commandEnd <= -1) {
            // Incomplete command: append to buffer
            byte[] temp = new byte[dataBuffer.length + data.length];
            System.arraycopy(dataBuffer, 0, temp, 0, dataBuffer.length);
            System.arraycopy(data, 0, temp, dataBuffer.length, data.length);
            dataBuffer = temp;
        } else {
            // Complete command found: process it
            byte[] temp = new byte[dataBuffer.length + commandEnd + 1];
            System.arraycopy(dataBuffer, 0, temp, 0, dataBuffer.length);
            dataBuffer = temp;
            System.arraycopy(data, 0, dataBuffer, dataBuffer.length - commandEnd - 1, commandEnd + 1);
        }
        if (commandEnd != -1) {
            analysisCommand(dataBuffer);
        }
        dataBuffer = new byte[0]; // Clear buffer
        if (commandEnd <= -1 || commandEnd < data.length) {
            // Remaining data after command end: save for next parse
            byte[] temp = new byte[data.length - commandEnd - 1];
            System.arraycopy(data, commandEnd + 1, temp, 0, temp.length);
            dataBuffer = temp;
        }
    }

    @Override
    public void readFreqFromRig() {
        if (getConnector() != null) {
            getConnector().sendData(IcomRigConstant.setReadFreq(ctrAddress, getCivAddress()));
        }
    }

    @Override
    public String getName() {
        return "ICOM series";
    }

    public void startMeterTimer() {
        meterTimer = new Timer();
        meterTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (isPttOn() && !oldVersion) {
                    sendCivData(IcomRigConstant.getSWRState(ctrAddress, getCivAddress()));
                    sendCivData(IcomRigConstant.getALCState(ctrAddress, getCivAddress()));
                }
            }
        }, 0, IComPacketTypes.METER_TIMER_PERIOD_MS);
    }

    public String getFrequencyStr() {
        return BaseRigOperation.getFrequencyStr(getFreq());
    }

    public IcomRig(int civAddress, boolean newRig) {
        Log.d(TAG, "IcomRig: Create.");
        this.oldVersion = !newRig;
        setCivAddress(civAddress);
        startMeterTimer();
    }

    @Override
    public void setTune(byte action) {
        if (!isConnected()) {
            ToastMessage.show("Cannot send TUNE: rig not connected");
            Log.w(TAG, "Cannot send TUNE: rig not connected");
            return;
        }

        byte[] tuneCmd = IcomRigConstant.setTuneCommand(
                0xE0,
                GeneralVariables.civAddress,
                action
        );

        try {
            getConnector().sendData(tuneCmd);
            ToastMessage.show("TUNE command sent");
            //Log.d(TAG, "TUNE command sent: 0x" + String.format("%02X", action));
        } catch (Exception e) {
            Log.e(TAG, "Failed to send TUNE command: " + e.getMessage());
            ToastMessage.show("Failed to send TUNE command");
        }
    }

    // === ✅ ДОБАВЛЕНО: Переопределение sendTuneCommand для совместимости с BaseRig ===
    @Override
    public void sendTuneCommand(byte action) {
        setTune(action);
    }
    // ==============================================================================
}