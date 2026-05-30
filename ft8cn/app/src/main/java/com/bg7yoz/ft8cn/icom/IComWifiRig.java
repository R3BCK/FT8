package com.bg7yoz.ft8cn.icom;
/**
 * WIFI mode ICom radio operation.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.media.AudioTrack;
import android.util.Log;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.icom.IcomUdpBase.IcomUdpStyle;
import com.bg7yoz.ft8cn.ui.ToastMessage;

import java.io.IOException;

public class IComWifiRig extends WifiRig {
    private static final String TAG = "IComWifiRig";

    // Port for CI-V commands (extracted from 0x50 AreYouThere response)
    // Default fallback: 50002 (standard for many Icom models)
    private int civPort = 50001;


    public IComWifiRig(String ip, int port, String userName, String password) {
        super(ip, port, userName, password);
        // Initialize civPort from settings (fallback to 50002)
        this.civPort = GeneralVariables.getNetworkCivPort();
        Log.d(TAG, "Constructor: ip=" + ip + ", handshakePort=" + port + ", civPort=" + civPort);
    }

    /**
     * Get the port for CI-V commands
     * Примечание: для некоторых моделей (включая IC-705) все команды,
     * включая сырые CI-V, должны идти на порт управления 50001,
     * а не на отдельный serial порт 50002.
     */
    public int getCivPort() {
        return 50001;
        //return (civPort > 0 && civPort <= 65535) ? civPort : 50002; для проверки
    }

    @Override
    public void sendWaveData(float[] data) {
    }

    /**
     * Set the CI-V port (called after parsing 0x50 response)
     */
    public void setCivPort(int port) {
        if (port > 0 && port <= 65535) {
            Log.d(TAG, "setCivPort: " + port);
            this.civPort = port;
            // Update the controlUdp if already initialized
            if (controlUdp != null && controlUdp.civUdp != null) {
                controlUdp.civUdp.rigPort = port;
                Log.d(TAG, "Updated controlUdp.civUdp.rigPort to: " + port);
            }
        }
    }

    @Override
    public void start() {
        Log.d(TAG, "start() called");
        opened = true;
        openAudio(); // Open audio track

        // Create control UDP with handshake port (50001)
        controlUdp = new IcomControlUdp(userName, password, ip, port);

        // Set event handlers
        controlUdp.setOnStreamEvents(new IcomUdpBase.OnStreamEvents() {
            @Override
            public void OnReceivedIAmHere(byte[] data) {
                Log.d(TAG, "OnReceivedIAmHere");
            }

            @Override
            public void OnReceivedCivData(byte[] data) {
                // === Parse 0x50 response to extract CI-V port ===
                if (data != null && data.length >= 9 && data[4] == (byte) 0x50) {
                    int extractedCivPort = ((data[5] & 0xFF) << 8) | (data[6] & 0xFF);
                    int extractedAudioPort = ((data[7] & 0xFF) << 8) | (data[8] & 0xFF);
                    Log.d(TAG, "AreYouThere: civPort=" + extractedCivPort +
                            ", audioPort=" + extractedAudioPort);

                    if (extractedCivPort > 0 && extractedCivPort <= 65535) {
                        setCivPort(extractedCivPort);
                    }
                }
                // ================================================

                if (onDataEvents != null) {
                    onDataEvents.onReceivedCivData(data);
                }
            }

            @Override
            public void OnReceivedAudioData(byte[] audioData) {
                if (onDataEvents != null) {
                    onDataEvents.onReceivedWaveData(audioData);
                }
                if (audioTrack != null) {
                    audioTrack.write(audioData, 0, audioData.length,
                            AudioTrack.WRITE_NON_BLOCKING);
                }
            }

            @Override
            public void OnUdpSendIOException(IcomUdpStyle style, IOException e) {
                ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                        R.string.network_exception), IcomUdpBase.getUdpStyle(style), e.getMessage()));
                close();
            }

            @Override
            public void OnLoginResponse(boolean authIsOK) {
                if (authIsOK) {
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.login_succeed));

                    // === ТЕСТОВЫЙ ВЫЗОВ: раскомментируйте для автопроверки ===
                    // Отправляет запрос версии прошивки сразу после успешного логина
                    sendTestVersionCommand();
                    // =========================================================

                } else {
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.loging_failed));
                    controlUdp.closeAll();
                }
            }
        });

        controlUdp.openStream(); // Open UDP ports
        controlUdp.startAreYouThereTimer(); // Start handshake
        Log.d(TAG, "start() done, opened=" + opened);
    }

    @Override
    public void setPttOn(boolean on) { // Toggle PTT
        Log.d(TAG, "setPttOn: " + on);
        isPttOn = on;
        if (controlUdp != null && controlUdp.civUdp != null) {
            controlUdp.civUdp.sendPttAction(on);
        }
        if (controlUdp != null && controlUdp.audioUdp != null) {
            controlUdp.audioUdp.isPttOn = on;
        }
    }

    @Override
    public void sendCivData(byte[] data) {
        if (data == null || data.length == 0) {
            Log.w(TAG, "sendCivData: empty data, skipping");
            return;
        }
        if (!opened) {
            Log.w(TAG, "sendCivData: not opened, skipping");
            return;
        }

        // Log only key CI-V commands for debugging (reduce spam)
        if (data.length >= 5 && data[0] == (byte) 0xFE && data[1] == (byte) 0xFE) {
            byte cmd = data[4]; // Command byte at index 4
            // Log only frequency (0x05), mode (0x06), tuner (0x1C) commands
            if (cmd == 0x05 || cmd == 0x06 || cmd == 0x1C) {
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < Math.min(data.length, 12); i++) {
                    hex.append(String.format("%02X ", data[i] & 0xFF));
                }
                Log.d(TAG, ">>> SEND CIV: port=" + getCivPort() + " cmd=0x" +
                        String.format("%02X", cmd) + " data=" + hex.toString().trim());
            }
        }

        // === FIX: Send "raw" CI-V frame directly to civUdp (port 50002) ===
        // WITHOUT control packet wrapper (no seq, no localId, no remoteId)
        if (controlUdp != null && controlUdp.civUdp != null && controlUdp.civUdp.streamOpened()) {
            // Update port before sending
            controlUdp.civUdp.rigPort = getCivPort();
            // Send raw CI-V frame
            controlUdp.civUdp.sendUntrackedPacket(data);
            Log.d(TAG, ">>> SENT RAW CIV: len=" + data.length + " to port " + controlUdp.civUdp.rigPort);
        } else {
            Log.e(TAG, "sendCivData: civUdp not available");
        }
        // =================================================================
    }

    /**
     * Отправляет тестовую команду запроса версии прошивки (0x19)
     * Используется для проверки двусторонней связи по CI-V
     */
    public void sendTestVersionCommand() {
        // Команда 0x19: запрос версии, формат: FE FE A4 E0 19 FD
        byte[] getVersion = new byte[] {
                (byte) 0xFE, (byte) 0xFE,  // preamble
                (byte) 0xA4,               // адрес контроллера (приложение)
                (byte) 0xE0,               // адрес рига (IC-705)
                (byte) 0x19,               // команда: запрос версии
                (byte) 0xFD                // end marker
        };

        Log.d(TAG, "sendTestVersionCommand: sending 0x19 to port " + getCivPort());
        sendCivData(getVersion);
    }

    /**
     * Close all connections and audio
     */
    @Override
    public void close() {
        Log.d(TAG, "close() called");
        opened = false;
        if (controlUdp != null) {
            controlUdp.closeAll();
            controlUdp = null;
        }
        closeAudio();
        Log.d(TAG, "close() done");
    }
}