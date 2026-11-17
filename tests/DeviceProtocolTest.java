package com.local.xmomentbridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class DeviceProtocolTest {
    public static void main(String[] args) throws Exception {
        checkPayload(1, 0, "4A54AAFF96A4979797979797979797971F");
        checkPayload(2, 3, "4A54AAFF95A49494979797979797979718");
        checkPayload(3, 6, "4A54AAFF94A49191979797979797979711");
        checkPayload(4, 9, "4A54AAFF93A49E9E97979797979797972A");
        checkPayload(5, 100, "4A54AAFF92A4F3F39797979797979797D3");
        DeviceProtocol protocol = new DeviceProtocol("0123456789AB");
        check("9F9F5111F7B2".equals(DeviceProtocol.addressForAndroidId("0123456789abcdef")));
        check(!DeviceProtocol.addressForAndroidId("0123456789abcdef").equals(DeviceProtocol.addressForAndroidId("fedcba9876543210")));
        try {
            DeviceProtocol.addressForAndroidId(null);
            throw new AssertionError("Missing Android ID accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("unavailable"));
        }
        check("{\"identifier\":\"Buzzybody\",\"address\":\"0123456789AB\",\"version\":0}".equals(protocol.handshake()));
        check("Z:0123456789AB:10".equals(new String(protocol.response(bytes("DeviceType;")), StandardCharsets.US_ASCII)));
        check(protocol.speed(bytes("Vibrate:0;")) == 0);
        check(protocol.speed(bytes("Vibrate:20;")) == 100);
        check(DeviceProtocol.motorSpeed(0) == 0);
        check(DeviceProtocol.motorSpeed(1) == 1);
        check(DeviceProtocol.motorSpeed(10) == 5);
        check(DeviceProtocol.motorSpeed(80) == 48);
        check(DeviceProtocol.motorSpeed(100) == 100);
        int previous = 0;
        for (int i = 1; i <= 100; i++) {
            int current = DeviceProtocol.motorSpeed(i);
            check(current >= previous && current <= 100);
            previous = current;
        }
        try {
            protocol.speed(bytes("Vibrate:21;"));
            throw new AssertionError("Invalid vibration level accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("Invalid vibration level"));
        }
        System.out.println("Device protocol tests passed");
    }

    private static void checkPayload(int counter, int speed, String hex) {
        byte[] expected = new byte[hex.length() / 2];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        check(Arrays.equals(expected, DeviceProtocol.payload(counter, speed)));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }
}
