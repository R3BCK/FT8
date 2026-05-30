package com.bg7yoz.ft8cn.icom;
/**
 * Simple UDP stream handler wrapper.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;

import org.checkerframework.checker.units.qual.A;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Timer;
import java.util.TimerTask;

public class IcomUdpBase {
    public enum IcomUdpStyle {
        UdpBase,
        ControlUdp,
        CivUdp,
        AudioUdp
    }

    public static String getUdpStyle(IcomUdpStyle style) {
        switch (style) {
            case ControlUdp:
                return GeneralVariables.getStringFromResource(R.string.control_stream);
            case CivUdp:
                return GeneralVariables.getStringFromResource(R.string.civ_stream);
            case AudioUdp:
                return GeneralVariables.getStringFromResource(R.string.audio_stream);
            default:
                return GeneralVariables.getStringFromResource(R.string.data_stream);
        }
    }

    /**
     * Event interface
     */
    public interface OnStreamEvents {
        void OnReceivedIAmHere(byte[] data);
        void OnReceivedCivData(byte[] data);
        void OnReceivedAudioData(byte[] audioData);
        void OnUdpSendIOException(IcomUdpStyle style, IOException e);
        void OnLoginResponse(boolean authIsOK);
    }

    public IcomUdpStyle udpStyle = IcomUdpStyle.UdpBase;

    private static final String TAG = "IcomUdpBase";
    public int rigPort;          // Port for handshake (usually 50001)
    public int civPort;          // Port for CI-V commands (extracted from 0x50 response)
    public String rigIp;
    public int localPort;
    public int localId = (int) System.currentTimeMillis();
    public int remoteId;
    public boolean authDone = false;
    public boolean rigReadyDone = false;
    public short trackedSeq = 1;
    public short pingSeq = 0;
    public short innerSeq = 0x30;
    public int rigToken;
    public short localToken = (short) System.currentTimeMillis();
    public boolean isPttOn = false;

    public IcomSeqBuffer txSeqBuffer = new IcomSeqBuffer();
    public long lastReceivedTime = System.currentTimeMillis();
    public long lastSentTime = System.currentTimeMillis();

    public IcomUdpClient udpClient;

    public OnStreamEvents onStreamEvents;
    public Timer areYouThereTimer;
    private AreYouThereTimerTask areYouThereTask = null;
    public Timer pingTimer;
    public Timer idleTimer;

    public IcomUdpBase() {
        // Default: civPort = rigPort (will be updated after handshake)
        this.civPort = this.rigPort;
    }

    public void close() {
        onStreamEvents = null;
        sendUntrackedPacket(IComPacketTypes.ControlPacket.toBytes(
                IComPacketTypes.CMD_DISCONNECT, (short) 0, localId, remoteId));
        stopTimer(areYouThereTimer);
        stopTimer(pingTimer);
        stopTimer(idleTimer);
        closeStream();
    }

    public void closeStream() {
        if (udpClient != null) {
            try {
                udpClient.setActivated(false);
            } catch (SocketException e) {
                e.printStackTrace();
                Log.e(TAG, "closeStream: " + e.getMessage());
            }
        }
    }

    public void openStream() {
        if (udpClient == null) {
            udpClient = new IcomUdpClient(-1);
        }
        udpClient.setOnUdpEvents(new IcomUdpClient.OnUdpEvents() {
            @Override
            public void OnReceiveData(DatagramSocket socket, DatagramPacket packet, byte[] data) {
                if (data.length < IComPacketTypes.CONTROL_SIZE) return;
                if (IComPacketTypes.ControlPacket.getRcvdId(data) != localId) return;
                onDataReceived(packet, data);
            }

            @Override
            public void OnUdpSendIOException(IOException e) {
                if (onStreamEvents != null) {
                    onStreamEvents.OnUdpSendIOException(udpStyle, e);
                }
            }
        });

        try {
            if (udpClient.isActivated()) udpClient.setActivated(false);
            udpClient.setActivated(true);
            localPort = udpClient.getLocalPort();
            Log.d(TAG, "IcomUdpBase: Open udp local port:" + localPort);
        } catch (SocketException e) {
            e.printStackTrace();
            Log.e(TAG, "IcomUdpBase: Open udp failed:" + e.getMessage());
        }
    }

    public boolean streamOpened() {
        return udpClient != null && udpClient.isActivated();
    }

    public void onDataReceived(DatagramPacket packet, byte[] data) {
        switch (data.length) {
            case IComPacketTypes.CONTROL_SIZE:
                onReceivedControlPacket(data);
                break;
            case IComPacketTypes.PING_SIZE:
                onReceivedPingPacket(data);
                break;
            case IComPacketTypes.RETRANSMIT_RANGE_SIZE:
                break;
        }

        if (data.length != IComPacketTypes.CONTROL_SIZE
                && IComPacketTypes.ControlPacket.getType(data) == IComPacketTypes.CMD_RETRANSMIT) {
            retransmitMultiPacket(data);
        }
    }

    public void onReceivedControlPacket(byte[] data) {
        switch (IComPacketTypes.ControlPacket.getType(data)) {
            case IComPacketTypes.CMD_I_AM_HERE:
                if (onStreamEvents != null) {
                    onStreamEvents.OnReceivedIAmHere(data);
                }
                remoteId = IComPacketTypes.ControlPacket.getSentId(data);
                stopTimer(areYouThereTimer);
                startPingTimer();
                sendUntrackedPacket(IComPacketTypes.ControlPacket.toBytes(
                        IComPacketTypes.CMD_ARE_YOU_READY, (short) 1, localId, remoteId));
                break;

            case IComPacketTypes.CMD_I_AM_READY:
                break;

            case IComPacketTypes.CMD_RETRANSMIT:
                retransmitPacket(data);
                break;

            // === NEW: Handle 0x50 response (AreYouThere) to extract ports ===
            case 0x50:  // AreYouThere response with ports
                if (data.length >= 9) {
                    // Parse ports: bytes 5-6 = civPort, 7-8 = audioPort (big-endian)
                    int extractedCivPort = ((data[5] & 0xFF) << 8) | (data[6] & 0xFF);
                    int extractedAudioPort = ((data[7] & 0xFF) << 8) | (data[8] & 0xFF);

                    Log.d(TAG, "AreYouThere response: civPort=" + extractedCivPort +
                            ", audioPort=" + extractedAudioPort);

                    // Save CI-V port for subsequent commands
                    if (extractedCivPort > 0 && extractedCivPort <= 65535) {
                        this.civPort = extractedCivPort;
                        Log.d(TAG, "Updated civPort to: " + this.civPort);
                    }

                    // Notify listeners if needed
                    if (onStreamEvents != null) {
                        onStreamEvents.OnReceivedCivData(data);
                    }
                }
                break;
            // ================================================================
        }
    }

    public void retransmitPacket(byte[] data) {
        retransmitPacket(IComPacketTypes.ControlPacket.getSeq(data));
    }

    public void retransmitPacket(short retransmitSeq) {
        byte[] packet = txSeqBuffer.get(retransmitSeq);
        if (packet != null) {
            sendUntrackedPacket(packet);
        } else {
            sendUntrackedPacket(IComPacketTypes.ControlPacket.idlePacketData(
                    retransmitSeq, localId, remoteId));
        }
    }

    public void retransmitMultiPacket(byte[] data) {
        if (data.length <= IComPacketTypes.CONTROL_SIZE) return;
        if (IComPacketTypes.ControlPacket.getType(data) != IComPacketTypes.CMD_RETRANSMIT) return;
        for (int i = 0x10; i < data.length; i = i + 2) {
            if (i + 1 > data.length - 1) break;
            retransmitPacket(IComPacketTypes.readShortBigEndianData(data, i));
        }
    }

    public void startAreYouThereTimer() {
        Log.e(TAG, "startAreYouThereTimer: stop timer:" + this.toString());
        stopTimer(areYouThereTimer);
        areYouThereTimer = new Timer();
        areYouThereTimer.scheduleAtFixedRate(new AreYouThereTimerTask()
                , 0, IComPacketTypes.ARE_YOU_THERE_PERIOD_MS);
    }

    public void startPingTimer() {
        stopTimer(pingTimer);
        Log.d(TAG, String.format("start PingTimer: local port:%d, remote port %d", localPort, rigPort));
        pingTimer = new Timer();
        pingTimer.scheduleAtFixedRate(new PingTimerTask(), 0, IComPacketTypes.PING_PERIOD_MS);
    }

    public void startIdleTimer() {
        stopTimer(idleTimer);
        Log.d(TAG, String.format("start Idle Timer: local port:%d, remote port %d", localPort, rigPort));
        idleTimer = new Timer();
        idleTimer.scheduleAtFixedRate(new IdleTimerTask(), IComPacketTypes.IDLE_PERIOD_MS
                , IComPacketTypes.IDLE_PERIOD_MS);
    }

    public void stopTimer(Timer timer) {
        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }
    }

    public void onReceivedPingPacket(byte[] data) {
        if (IComPacketTypes.ControlPacket.getType(data) == IComPacketTypes.CMD_PING) {
            if (IComPacketTypes.PingPacket.getReply(data) == 0x00) {
                sendReplyPingPacket(data);
            } else {
                if (IComPacketTypes.ControlPacket.getSeq(data) == pingSeq) {
                    pingSeq++;
                }
            }
        }
    }

    public void sendTokenPacket(byte requestType) {
        sendTrackedPacket(IComPacketTypes.TokenPacket.getTokenPacketData((short) 0
                , localId, remoteId, requestType, innerSeq, localToken, rigToken));
        innerSeq++;
    }

    public void sendPingPacket() {
        byte[] data = IComPacketTypes.PingPacket.sendPingData(localId, remoteId, pingSeq);
        sendUntrackedPacket(data);
    }

    public void sendReplyPingPacket(byte[] data) {
        byte[] packet = IComPacketTypes.PingPacket.sendReplayPingData(data, localId, remoteId);
        sendUntrackedPacket(packet);
    }

    /**
     * Send untracked packet using civPort for CI-V commands, rigPort for handshake
     */
    public synchronized void sendUntrackedPacket(byte[] data) {
        try {
            // Use civPort for CI-V commands (type 0x05, 0x06, 0x1C, etc.)
            // Use rigPort for handshake packets (0x90, 0x50, 0x80, etc.)
            int targetPort = isCivCommand(data) ? getCivPort() : rigPort;
            udpClient.sendData(data, rigIp, targetPort);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }

    /**
     * Check if packet is a CI-V command (not handshake/control)
     */
    private boolean isCivCommand(byte[] data) {
        if (data == null || data.length < 5) return false;
        // CI-V commands start with FE FE and have command byte at index 4
        // Handshake packets have type at index 4 in control format
        return data[0] == (byte) 0xFE && data[1] == (byte) 0xFE
                && data.length >= 7; // CI-V frame minimum length
    }

    /**
     * Send tracked packet using civPort for CI-V commands
     */
    public synchronized void sendTrackedPacket(byte[] data) {
        try {
            lastSentTime = System.currentTimeMillis();
            System.arraycopy(IComPacketTypes.shortToBigEndian(trackedSeq), 0
                    , data, 6, 2);
            // Use civPort for CI-V commands
            int targetPort = isCivCommand(data) ? getCivPort() : rigPort;
            udpClient.sendData(data, rigIp, targetPort);
            txSeqBuffer.add(trackedSeq, data);
            trackedSeq++;
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }

    /**
     * Get the port for CI-V commands (extracted from 0x50 response or fallback to rigPort)
     */
    public int getCivPort() {
        return (civPort > 0 && civPort <= 65535) ? civPort : rigPort;
    }

    /**
     * Set the CI-V port manually (for testing or if parsing fails)
     */
    public void setCivPort(int port) {
        if (port > 0 && port <= 65535) {
            Log.d(TAG, "setCivPort: " + port);
            this.civPort = port;
        }
    }

    public int getLocalPort() {
        return localPort;
    }

    public void sendIdlePacket() {
        sendTrackedPacket(IComPacketTypes.ControlPacket.toBytes(
                IComPacketTypes.CMD_NULL, (short) 0, localId, remoteId));
    }

    public OnStreamEvents getOnStreamEvents() {
        return onStreamEvents;
    }

    public void setOnStreamEvents(OnStreamEvents onStreamEvents) {
        this.onStreamEvents = onStreamEvents;
    }

    public class AreYouThereTimerTask extends TimerTask {
        @Override
        public void run() {
            Log.d(TAG, String.format("Task,AreYouThereTimer: local port:%d, remote port %d", localPort, rigPort));
            sendUntrackedPacket(IComPacketTypes.ControlPacket.toBytes(
                    IComPacketTypes.CMD_ARE_YOU_THERE, (short) 0, localId, 0));
        }
    }

    public class PingTimerTask extends TimerTask {
        @Override
        public void run() {
            sendPingPacket();
        }
    }

    public class IdleTimerTask extends TimerTask {
        @Override
        public void run() {
            if (txSeqBuffer.getTimeOut() > 200) {
                sendTrackedPacket(IComPacketTypes.ControlPacket.toBytes(
                        IComPacketTypes.CMD_NULL, (short) 0, localId, remoteId));
            }
        }
    }
}