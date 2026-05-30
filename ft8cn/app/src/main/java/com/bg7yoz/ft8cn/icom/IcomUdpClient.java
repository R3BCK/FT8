package com.bg7yoz.ft8cn.icom;
/**
 * Simple UDP protocol handler wrapper.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IcomUdpClient {
    private static final String TAG = "RadioUdpSocket";

    private final int MAX_BUFFER_SIZE = 1024 * 2;
    private DatagramSocket sendSocket;
    private int localPort = -1;
    private boolean activated = false;
    private OnUdpEvents onUdpEvents = null;
    private final ExecutorService doReceiveThreadPool = Executors.newCachedThreadPool();
    private final DoReceiveRunnable doReceiveRunnable = new DoReceiveRunnable(this);
    private final ExecutorService sendDataThreadPool = Executors.newCachedThreadPool();
    private final SendDataRunnable sendDataRunnable = new SendDataRunnable(this);

    public IcomUdpClient() {
        localPort = -1;
    }

    public IcomUdpClient(int localPort) {
        this.localPort = localPort;
    }

    public void sendData(byte[] data, String ip, int port) throws UnknownHostException {
        //       Log.d(TAG, "sendData called: data.len=" + (data != null ? data.length : 0) +
        //               ", ip=" + ip + ", port=" + port + ", activated=" + activated);

        if (!activated) {
            Log.w(TAG, "sendData: not activated, skipping");
            return;
        }

        // === НОВАЯ ПРОВЕРКА ===
        if (port <= 0 || port > 65535) {
            Log.e(TAG, "sendData: INVALID PORT " + port + " (must be 1-65535), skipping");
            return;
        }

        if (data == null || data.length == 0) {
            Log.e(TAG, "sendData: data is null or empty, skipping");
            return;
        }
        if (ip == null || ip.isEmpty()) {
            Log.e(TAG, "sendData: ip is null or empty, skipping");
            return;
        }
        if (port < 0 || port > 65535) {
            Log.e(TAG, "sendData: invalid port " + port + ", skipping");
            return;
        }

        InetAddress address = InetAddress.getByName(ip);
        //       Log.d(TAG, "sendData: resolved address=" + address);

        sendDataRunnable.address = address;
        sendDataRunnable.data = data;
        sendDataRunnable.port = port;
        sendDataThreadPool.execute(sendDataRunnable);
    }

    private static class SendDataRunnable implements Runnable {
        byte[] data;
        int port;
        InetAddress address;
        final IcomUdpClient client;

        public SendDataRunnable(IcomUdpClient client) {
            this.client = client;
        }

        @Override
        public void run() {
            // === ВАЛИДАЦИЯ ПЕРЕД ОТПРАВКОЙ ===
            if (client == null) {
                Log.e(TAG, "SendDataRunnable: client is null");
                return;
            }
            if (data == null || data.length == 0) {
                Log.e(TAG, "SendDataRunnable: data is null or empty");
                return;
            }
            if (address == null) {
                Log.e(TAG, "SendDataRunnable: address is null");
                return;
            }
            if (port < 0 || port > 65535) {
                Log.e(TAG, "SendDataRunnable: invalid port " + port);
                return;
            }

            DatagramSocket socket = client.sendSocket;
            if (socket == null) {
                Log.e(TAG, "SendDataRunnable: sendSocket is null");
                if (client.onUdpEvents != null) {
                    client.onUdpEvents.OnUdpSendIOException(new SocketException("Socket not initialized"));
                }
                return;
            }
            if (socket.isClosed()) {
                Log.e(TAG, "SendDataRunnable: sendSocket is closed");
                if (client.onUdpEvents != null) {
                    client.onUdpEvents.OnUdpSendIOException(new SocketException("Socket closed"));
                }
                return;
            }
            // ================================

            try {
// СТАЛО (только для отладки команд):
// Логируем только если это CI-V команда (начинается с FE FE)
                if (data != null && data.length >= 2 &&
                        data[0] == (byte) 0xFE && data[1] == (byte) 0xFE) {

                    // Показываем только первые 8 байт команды
                    StringBuilder hex = new StringBuilder();
                    for (int i = 0; i < Math.min(data.length, 8); i++) {
                        hex.append(String.format("%02X ", data[i] & 0xFF));
                    }
                    Log.d(TAG, ">>> CIV_CMD to port " + port + ": " + hex.toString().trim());
                }
// Все остальные пакеты (пинги, контрольные) не логируем

                DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
                socket.send(packet);



            } catch (SocketException e) {
                Log.e(TAG, "SendDataRunnable: SocketException: " + e.getMessage(), e);
                // Специальная обработка EINVAL
                if (e.getMessage() != null && e.getMessage().contains("EINVAL")) {
                    Log.e(TAG, "SendDataRunnable: EINVAL detected - check socket initialization and address validity!");
                }
                if (client.onUdpEvents != null) {
                    client.onUdpEvents.OnUdpSendIOException(e);
                }
            } catch (IOException e) {
                Log.e(TAG, "SendDataRunnable: IOException: " + e.getMessage(), e);
                if (client.onUdpEvents != null) {
                    client.onUdpEvents.OnUdpSendIOException(e);
                }
            } catch (Exception e) {
                Log.e(TAG, "SendDataRunnable: Unexpected exception: " + e.getMessage(), e);
                if (client.onUdpEvents != null) {
                    client.onUdpEvents.OnUdpSendIOException(new IOException(e));
                }
            }
        }
    }

    public boolean isActivated() {
        return activated;
    }

    public synchronized void setActivated(boolean activated) throws SocketException {
        Log.d(TAG, "setActivated: " + activated + ", current=" + this.activated);

        if (this.activated == activated) {
            Log.d(TAG, "setActivated: no change, skipping");
            return;
        }

        this.activated = activated;

        if (activated) {
            Log.d(TAG, "setActivated: initializing socket, localPort=" + localPort);

            try {
                if (localPort != -1) {
                    sendSocket = new DatagramSocket(new InetSocketAddress(localPort));
                } else {
                    sendSocket = new DatagramSocket();
                }
                sendSocket.setReuseAddress(true);
                sendSocket.setSoTimeout(5000);

                localPort = sendSocket.getLocalPort();
                Log.d(TAG, "setActivated: socket created, localPort=" + localPort +
                        ", localAddr=" + sendSocket.getLocalAddress());

                receiveData();

            } catch (SocketException e) {
                Log.e(TAG, "setActivated: failed to create socket: " + e.getMessage(), e);
                sendSocket = null;
                this.activated = false;
                throw e;
            }

        } else {
            Log.d(TAG, "setActivated: deactivating, closing socket");
            if (sendSocket != null) {
                try {
                    sendSocket.close();
                    Log.d(TAG, "setActivated: socket closed");
                } catch (Exception e) {
                    Log.e(TAG, "setActivated: error closing socket: " + e.getMessage(), e);
                }
                sendSocket = null;
            }
            localPort = -1;
        }
    }

    private void receiveData() {
        Log.d(TAG, "receiveData: starting receive thread");
        doReceiveThreadPool.execute(doReceiveRunnable);
    }

    public void setOnUdpEvents(OnUdpEvents onUdpEvents) {
        Log.d(TAG, "setOnUdpEvents: listener=" + onUdpEvents);
        this.onUdpEvents = onUdpEvents;
    }

    public interface OnUdpEvents {
        void OnReceiveData(DatagramSocket socket, DatagramPacket packet, byte[] data);
        void OnUdpSendIOException(IOException e);
    }

    public int getLocalPort() {
        DatagramSocket socket = sendSocket;
        if (socket != null && !socket.isClosed()) {
            return socket.getLocalPort();
        }
        return 0;
    }

    public String getLocalIp() {
        DatagramSocket socket = sendSocket;
        if (socket != null && !socket.isClosed() && socket.getLocalAddress() != null) {
            return socket.getLocalAddress().getHostAddress();
        }
        return "127.0.0.1";
    }

    public DatagramSocket getSendSocket() {
        return sendSocket;
    }

    public static String byteToStr(byte[] data) {
        if (data == null) return "null";
        StringBuilder s = new StringBuilder();
        for (byte b : data) s.append(String.format("%02x ", b & 0xff));
        return s.toString().trim();
    }

    private static class DoReceiveRunnable implements Runnable {
        final IcomUdpClient icomUdpClient;

        public DoReceiveRunnable(IcomUdpClient icomUdpClient) {
            this.icomUdpClient = icomUdpClient;
        }

        @Override
        public void run() {
            Log.d(TAG, "DoReceiveRunnable: started, activated=" + icomUdpClient.activated);

            while (icomUdpClient.activated) {
                byte[] data = new byte[icomUdpClient.MAX_BUFFER_SIZE];
                DatagramPacket packet = new DatagramPacket(data, data.length);

                try {
                    DatagramSocket socket = icomUdpClient.sendSocket;
                    if (socket == null || socket.isClosed()) {
                        Log.w(TAG, "DoReceiveRunnable: socket null or closed, exiting loop");
                        break;
                    }

                    socket.receive(packet);

                    // === НОВЫЙ БЛОК: Логирование входящих пакетов ===
                    // Логируем ВСЕ входящие пакеты для отладки ответов рига
                    if (packet.getLength() > 0) {
                        byte[] received = Arrays.copyOf(packet.getData(), packet.getLength());
                        String hexDump = byteToStr(received);
                        int remotePort = packet.getPort();
                        String remoteAddr = packet.getAddress() != null ? packet.getAddress().getHostAddress() : "unknown";

                        Log.d(TAG, "<<< INCOMING UDP from " + remoteAddr + ":" + remotePort +
                                " localPort=" + icomUdpClient.localPort + ": " + hexDump);

                        // Если пакет похож на CI-V ответ (начинается с FE FE и адрес рига E0)
                        if (received.length >= 4 &&
                                received[0] == (byte)0xFE && received[1] == (byte)0xFE &&
                                received[2] == (byte)0xE0) {

                            Log.d(TAG, "<<< CIV RESPONSE detected, length=" + received.length);

                            // Проверяем тип ответа по 4-му байту (после заголовка)
                            if (received.length >= 5) {
                                byte responseType = received[4];
                                if (responseType == (byte)0xFB) {
                                    Log.d(TAG, "<<< CIV: ACK - команда принята");
                                } else if (responseType == (byte)0xFA) {
                                    Log.e(TAG, "<<< CIV: NAK - ошибка в команде!");
                                } else if (responseType == (byte)0x19) {
                                    Log.d(TAG, "<<< CIV: Version response");
                                } else {
                                    Log.d(TAG, "<<< CIV: Unknown response type 0x" +
                                            String.format("%02X", responseType));
                                }
                            }
                        }
                    }
                    // === КОНЕЦ НОВОГО БЛОКА ===

                    if (packet.getLength() > 0 && icomUdpClient.onUdpEvents != null) {
                        byte[] temp = Arrays.copyOf(packet.getData(), packet.getLength());
                        icomUdpClient.onUdpEvents.OnReceiveData(socket, packet, temp);
                    }

                } catch (SocketException e) {
                    if (icomUdpClient.activated) {
                        Log.e(TAG, "DoReceiveRunnable: SocketException while active: " + e.getMessage(), e);
                    } else {
                        Log.d(TAG, "DoReceiveRunnable: SocketException after deactivation (expected): " + e.getMessage());
                    }
                    break;
                } catch (IOException e) {
                    Log.e(TAG, "DoReceiveRunnable: IOException: " + e.getMessage(), e);
                    if (icomUdpClient.onUdpEvents != null) {
                        icomUdpClient.onUdpEvents.OnUdpSendIOException(e);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "DoReceiveRunnable: Unexpected exception: " + e.getMessage(), e);
                }
            }

            Log.d(TAG, "DoReceiveRunnable: exiting loop, activated=" + icomUdpClient.activated);

            // Безопасное закрытие сокета
            DatagramSocket socket = icomUdpClient.sendSocket;
            if (socket != null && !socket.isClosed()) {
                try {
                    Log.d(TAG, "DoReceiveRunnable: closing socket");
                    socket.close();
                } catch (Exception e) {
                    Log.e(TAG, "DoReceiveRunnable: error closing socket: " + e.getMessage(), e);
                }
            }
            Log.e(TAG, "udpClient: is exit!");
        }
    }
}