package com.local.xmomentbridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DeviceProtocol {
    private static final Pattern VIBRATE = Pattern.compile("^Vibrate:(\\d{1,2});$");
    private final String address;

    DeviceProtocol(String address) {
        this.address = address;
    }

    static String addressForAndroidId(String androidId) throws IOException {
        if (androidId == null || androidId.isEmpty()) throw new IOException("Android device ID is unavailable");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(androidId.getBytes(StandardCharsets.UTF_8));
            StringBuilder address = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                address.append("0123456789ABCDEF".charAt((digest[i] & 0xff) >>> 4));
                address.append("0123456789ABCDEF".charAt(digest[i] & 0x0f));
            }
            return address.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    String handshake() {
        return "{\"identifier\":\"Buzzybody\",\"address\":\"" + address + "\",\"version\":0}";
    }

    byte[] response(byte[] data) throws IOException {
        String command = new String(data, StandardCharsets.US_ASCII);
        if (command.equals("DeviceType;")) {
            return ("Z:" + address + ":10").getBytes(StandardCharsets.US_ASCII);
        }
        throw new IOException("Unsupported device command: " + command);
    }

    int speed(byte[] data) throws IOException {
        String command = new String(data, StandardCharsets.US_ASCII);
        Matcher match = VIBRATE.matcher(command);
        if (!match.matches()) throw new IOException("Unsupported device command: " + command);
        int value = Integer.parseInt(match.group(1));
        if (value > 20) throw new IOException("Invalid vibration level: " + value);
        return value * 5;
    }

    static int motorSpeed(int intensity) {
        if (intensity < 0 || intensity > 100) throw new IllegalArgumentException("Invalid intensity: " + intensity);
        if (intensity == 0) return 0;
        // Remap the old 40% setting to 10%, keeping 80% and 100% unchanged.
        double adjusted;
        if (intensity <= 10) adjusted = intensity * 4.0;
        else if (intensity <= 80) adjusted = 40 + (intensity - 10) * 40.0 / 70;
        else adjusted = intensity;
        double exponent = Math.log(0.48) / Math.log(0.8);
        return Math.max(1, (int) Math.round(100 * Math.pow(adjusted / 100.0, exponent)));
    }

    static byte[] payload(int counter, int speed) {
        byte[] payload = new byte[17];
        payload[0] = 0x4a;
        payload[1] = 0x54;
        payload[2] = (byte) 0xaa;
        payload[3] = (byte) 0xff;
        payload[4] = (byte) (counter ^ 0x97);
        payload[5] = (byte) (0x33 ^ 0x97);
        payload[6] = (byte) (speed ^ 0x97);
        payload[7] = (byte) (speed ^ 0x97);
        for (int i = 8; i < 16; i++) payload[i] = (byte) 0x97;
        for (int i = 3; i < 16; i++) payload[16] += payload[i];
        return payload;
    }
}
