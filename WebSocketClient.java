package com.local.xmomentbridge;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

final class WebSocketClient {
    interface Listener {
        void onOpen(WebSocketClient client) throws IOException;
        void onText(WebSocketClient client, String message) throws IOException;
        void onBinary(WebSocketClient client, byte[] message) throws IOException;
        void onClosed(WebSocketClient client, String reason);
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_MESSAGE_SIZE = 1024 * 1024;
    private final String host;
    private final int port;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private final Object lock = new Object();
    private Socket socket;
    private OutputStream output;
    private boolean closed;

    WebSocketClient(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    void start() {
        new Thread(this::run, "intiface-websocket").start();
    }

    private void run() {
        String reason = "Disconnected";
        try {
            Socket connection = new Socket();
            synchronized (lock) {
                if (closed) return;
                socket = connection;
            }
            connection.connect(new InetSocketAddress(host, port), 10000);
            connection.setSoTimeout(10000);
            InputStream input = connection.getInputStream();
            OutputStream stream = connection.getOutputStream();
            handshake(input, stream);
            connection.setSoTimeout(0);
            synchronized (lock) {
                if (closed) return;
                output = stream;
            }
            listener.onOpen(this);
            reason = readFrames(input);
        } catch (IOException | RuntimeException e) {
            reason = "Connection error: " + e.getMessage();
        } finally {
            close();
            listener.onClosed(this, reason);
        }
    }

    private void handshake(InputStream input, OutputStream stream) throws IOException {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String request = "GET / HTTP/1.1\r\nHost: " + host + ":" + port
                + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "
                + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        stream.write(request.getBytes(StandardCharsets.US_ASCII));
        stream.flush();

        String status = readLine(input);
        if (!status.matches("HTTP/1\\.[01] 101(?: .*)?")) {
            throw new IOException("WebSocket upgrade rejected: " + status);
        }
        String expected;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
            expected = Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 is unavailable", e);
        }
        boolean upgrade = false;
        boolean connection = false;
        boolean accept = false;
        int headerBytes = status.length();
        while (true) {
            String line = readLine(input);
            headerBytes += line.length() + 2;
            if (headerBytes > 16384) throw new IOException("WebSocket headers too large");
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) throw new IOException("Invalid WebSocket header");
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            if (name.equals("upgrade")) upgrade |= hasToken(value, "websocket");
            if (name.equals("connection")) connection |= hasToken(value, "upgrade");
            if (name.equals("sec-websocket-accept")) {
                if (accept || !expected.equals(value)) throw new IOException("Invalid WebSocket accept");
                accept = true;
            }
            if (name.equals("sec-websocket-extensions") || name.equals("sec-websocket-protocol")) {
                throw new IOException("Unexpected WebSocket negotiation");
            }
        }
        if (!upgrade || !connection || !accept) throw new IOException("Incomplete WebSocket upgrade");
    }

    private static boolean hasToken(String value, String expected) {
        for (String token : value.split(",")) {
            if (token.trim().equalsIgnoreCase(expected)) return true;
        }
        return false;
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() <= 8192) {
            int value = input.read();
            if (value == -1) throw new EOFException("Unexpected end of WebSocket headers");
            if (value == '\r') {
                if (input.read() != '\n') throw new IOException("Invalid HTTP line ending");
                return new String(line.toByteArray(), StandardCharsets.US_ASCII);
            }
            if (value == '\n') throw new IOException("Invalid HTTP line ending");
            line.write(value);
        }
        throw new IOException("WebSocket header line too large");
    }

    private String readFrames(InputStream input) throws IOException {
        ByteArrayOutputStream message = null;
        int messageOpcode = 0;
        while (true) {
            int first = readByte(input);
            int second = readByte(input);
            boolean fin = (first & 0x80) != 0;
            int opcode = first & 0x0f;
            if ((first & 0x70) != 0 || (second & 0x80) != 0) {
                throw new IOException("Invalid WebSocket frame flags");
            }
            long length = second & 0x7f;
            if (length == 126) {
                length = ((long) readByte(input) << 8) | readByte(input);
                if (length < 126) throw new IOException("Invalid WebSocket frame length");
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    int b = readByte(input);
                    if (i == 0 && (b & 0x80) != 0) throw new IOException("Invalid WebSocket frame length");
                    length = (length << 8) | b;
                }
                if (length < 65536) throw new IOException("Invalid WebSocket frame length");
            }
            boolean control = (opcode & 0x08) != 0;
            if (control && (!fin || length > 125)) throw new IOException("Invalid WebSocket control frame");
            if (length > MAX_MESSAGE_SIZE) throw new IOException("WebSocket message too large");
            byte[] payload = new byte[(int) length];
            readFully(input, payload);
            if (opcode == 8) {
                if (payload.length == 1) throw new IOException("Invalid WebSocket close frame");
                String closeReason = payload.length > 2 ? decodeText(payload, 2, payload.length - 2) : "";
                sendFrame(8, payload);
                return closeReason.isEmpty() ? "Disconnected" : "Disconnected: " + closeReason;
            } else if (opcode == 9) {
                sendFrame(10, payload);
            } else if (opcode == 10) {
                continue;
            } else if (opcode == 1 || opcode == 2 || opcode == 0) {
                if (opcode == 1 || opcode == 2) {
                    if (message != null) throw new IOException("Unexpected WebSocket data frame");
                    message = new ByteArrayOutputStream();
                    messageOpcode = opcode;
                } else if (message == null) {
                    throw new IOException("Unexpected WebSocket continuation frame");
                }
                if (message.size() + payload.length > MAX_MESSAGE_SIZE) {
                    throw new IOException("WebSocket message too large");
                }
                message.write(payload);
                if (fin) {
                    byte[] data = message.toByteArray();
                    if (messageOpcode == 1) listener.onText(this, decodeText(data, 0, data.length));
                    else listener.onBinary(this, data);
                    message = null;
                }
            } else {
                throw new IOException("Unsupported WebSocket opcode: " + opcode);
            }
        }
    }

    private static String decodeText(byte[] data, int offset, int length) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, length)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Invalid UTF-8 in WebSocket frame", e);
        }
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException("WebSocket connection closed unexpectedly");
        return value;
    }

    private static void readFully(InputStream input, byte[] payload) throws IOException {
        int offset = 0;
        while (offset < payload.length) {
            int count = input.read(payload, offset, payload.length - offset);
            if (count < 0) throw new EOFException("Incomplete WebSocket frame");
            offset += count;
        }
    }

    void sendText(String message) throws IOException {
        sendFrame(1, message.getBytes(StandardCharsets.UTF_8));
    }

    void sendBinary(byte[] data) throws IOException {
        sendFrame(2, data);
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        synchronized (lock) {
            if (closed || output == null) throw new IOException("WebSocket is not connected");
            output.write(0x80 | opcode);
            if (payload.length < 126) {
                output.write(0x80 | payload.length);
            } else if (payload.length <= 65535) {
                output.write(0x80 | 126);
                output.write(payload.length >>> 8);
                output.write(payload.length);
            } else {
                output.write(0x80 | 127);
                long length = payload.length;
                for (int shift = 56; shift >= 0; shift -= 8) output.write((int) (length >>> shift));
            }
            byte[] mask = new byte[4];
            random.nextBytes(mask);
            output.write(mask);
            byte[] masked = new byte[payload.length];
            for (int i = 0; i < payload.length; i++) masked[i] = (byte) (payload[i] ^ mask[i % 4]);
            output.write(masked);
            output.flush();
        }
    }

    void close() {
        synchronized (lock) {
            closed = true;
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    System.err.println("Failed to close WebSocket: " + e.getMessage());
                }
            }
        }
    }
}
