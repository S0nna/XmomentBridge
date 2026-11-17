package com.local.xmomentbridge;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class WebSocketClientTest {
    public static void main(String[] args) throws Exception {
        testFrames();
        testRejectedHandshake();
        System.out.println("WebSocket tests passed");
    }

    private static void testFrames() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> peer = executor.submit(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(5000);
                        InputStream in = socket.getInputStream();
                        OutputStream out = socket.getOutputStream();
                        String request = readHeaders(in);
                        check(request.startsWith("GET / HTTP/1.1\r\n"), "request path");
                        String key = header(request, "Sec-WebSocket-Key");
                        check(Base64.getDecoder().decode(key).length == 16, "nonce length");
                        String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                                + "Connection: keep-alive, Upgrade\r\nSec-WebSocket-Accept: "
                                + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        frame(out, 0x01, new byte[] { 'h', (byte) 0xc3 });
                        frame(out, 0x89, "p".getBytes(StandardCharsets.UTF_8));
                        frame(out, 0x80, new byte[] { (byte) 0xa9 });
                        frame(out, 0x81, "z".repeat(130).getBytes(StandardCharsets.UTF_8));
                        frame(out, 0x02, new byte[] { 1, 2 });
                        frame(out, 0x80, new byte[] { 3 });
                        out.flush();
                        check(Arrays.equals(readClientFrame(in, 1), "a".repeat(130).getBytes(StandardCharsets.UTF_8)), "16-bit client payload");
                        check(Arrays.equals(readClientFrame(in, 1), "b".repeat(66000).getBytes(StandardCharsets.UTF_8)), "64-bit client payload");
                        check(Arrays.equals(readClientFrame(in, 2), new byte[] { 4, 5 }), "binary client payload");
                        check(Arrays.equals(readClientFrame(in, 10), new byte[] { 'p' }), "pong payload");
                        frame(out, 0x88, new byte[] { 3, (byte) 232 });
                        out.flush();
                        check(Arrays.equals(readClientFrame(in, 8), new byte[] { 3, (byte) 232 }), "close reply");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                CountDownLatch closed = new CountDownLatch(1);
                List<String> texts = new CopyOnWriteArrayList<>();
                AtomicReference<byte[]> binary = new AtomicReference<>();
                AtomicReference<String> reason = new AtomicReference<>();
                WebSocketClient client = new WebSocketClient("127.0.0.1", server.getLocalPort(), new WebSocketClient.Listener() {
                    public void onOpen(WebSocketClient c) throws java.io.IOException {
                        c.sendText("a".repeat(130));
                        c.sendText("b".repeat(66000));
                        c.sendBinary(new byte[] { 4, 5 });
                    }
                    public void onText(WebSocketClient c, String message) { texts.add(message); }
                    public void onBinary(WebSocketClient c, byte[] message) { binary.set(message); }
                    public void onClosed(WebSocketClient c, String why) { reason.set(why); closed.countDown(); }
                });
                client.start();
                check(closed.await(10, TimeUnit.SECONDS), "client closed");
                peer.get(10, TimeUnit.SECONDS);
                check(texts.equals(Arrays.asList("h\u00e9", "z".repeat(130))), "fragmented UTF-8 and extended incoming text");
                check(Arrays.equals(binary.get(), new byte[] { 1, 2, 3 }), "fragmented binary");
                check("Disconnected".equals(reason.get()), "clean close");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static void testRejectedHandshake() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> peer = executor.submit(() -> {
                    try (Socket socket = server.accept()) {
                        readHeaders(socket.getInputStream());
                        socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\n"
                                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                                + "Sec-WebSocket-Accept: invalid\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                CountDownLatch closed = new CountDownLatch(1);
                AtomicReference<String> reason = new AtomicReference<>();
                WebSocketClient client = new WebSocketClient("127.0.0.1", server.getLocalPort(), new WebSocketClient.Listener() {
                    public void onOpen(WebSocketClient c) { throw new AssertionError("invalid handshake accepted"); }
                    public void onText(WebSocketClient c, String message) { throw new AssertionError("unexpected text"); }
                    public void onBinary(WebSocketClient c, byte[] message) { throw new AssertionError("unexpected binary"); }
                    public void onClosed(WebSocketClient c, String why) { reason.set(why); closed.countDown(); }
                });
                client.start();
                check(closed.await(10, TimeUnit.SECONDS), "rejected connection closed");
                peer.get(10, TimeUnit.SECONDS);
                check(reason.get().contains("Invalid WebSocket accept"), "handshake error reported");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static String readHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (!bytes.toString("US-ASCII").endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0 || bytes.size() > 16384) throw new AssertionError("incomplete headers");
            bytes.write(b);
        }
        return bytes.toString("US-ASCII");
    }

    private static String header(String request, String name) {
        for (String line : request.split("\r\n")) {
            if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) {
                return line.substring(name.length() + 1).trim();
            }
        }
        throw new AssertionError("missing header " + name);
    }

    private static void frame(OutputStream out, int first, byte[] payload) throws Exception {
        out.write(first);
        if (payload.length < 126) {
            out.write(payload.length);
        } else {
            out.write(126);
            out.write(payload.length >>> 8);
            out.write(payload.length);
        }
        out.write(payload);
    }

    private static byte[] readClientFrame(InputStream in, int opcode) throws Exception {
        check(in.read() == (0x80 | opcode), "client opcode");
        int second = in.read();
        check((second & 0x80) != 0, "client frame must be masked");
        long length = second & 0x7f;
        if (length == 126) length = (in.read() << 8) | in.read();
        else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) length = (length << 8) | in.read();
        }
        check(length <= 100000, "client length");
        byte[] mask = in.readNBytes(4);
        byte[] payload = in.readNBytes((int) length);
        check(mask.length == 4 && payload.length == length, "complete client frame");
        for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
        return payload;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
