package com.local.xmomentbridge;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.*;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.widget.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback callback;
    private AdvertiseSettings settings;
    private TextView statusText;
    private TextView bluetoothStatus;
    private SeekBar manualSlider;
    private int arg = 0;
    private int desiredSpeed = -1;
    private boolean startingAdvertisement;
    private boolean advertising;
    private boolean destroyed;
    private long lastAdvertisementTime;
    private int advertisedSpeed;
    private final Handler handler = new Handler();
    private final Runnable updatePendingAdvertisement = this::updateAdvertisement;
    private WebSocketClient ws;
    private Button btnConnect;
    private EditText serverHost;
    private EditText serverPort;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 60, 40, 40);

        statusText = new TextView(this);
        statusText.setText("Status: Ready");
        statusText.setTextSize(16);
        layout.addView(statusText);

        bluetoothStatus = new TextView(this);
        bluetoothStatus.setText("Bluetooth: Ready");
        layout.addView(bluetoothStatus);

        serverHost = new EditText(this);
        serverHost.setSingleLine(true);
        serverHost.setHint("Intiface server address");
        serverHost.setText("127.0.0.1");
        layout.addView(serverHost);

        serverPort = new EditText(this);
        serverPort.setSingleLine(true);
        serverPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        serverPort.setHint("Port");
        serverPort.setText("54817");
        layout.addView(serverPort);

        btnConnect = new Button(this);
        btnConnect.setText("Connect as Intiface device");
        btnConnect.setOnClickListener(v -> connectIntiface());
        layout.addView(btnConnect);

        manualSlider = new SeekBar(this);
        manualSlider.setMax(100);
        manualSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    statusText.setText("Manual intensity: " + p + "%");
                    sendAP69(p);
                }
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        layout.addView(manualSlider);

        Button btnTest = new Button(this);
        btnTest.setText("Send nRF test packet (intensity 9)");
        btnTest.setOnClickListener(v -> {
            arg = 3;
            manualSlider.setProgress(9);
            statusText.setText("nRF test packet: raw motor intensity 9");
            sendRawAP69(9);
        });
        layout.addView(btnTest);
        setContentView(layout);

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) advertiser = adapter.getBluetoothLeAdvertiser();
        settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build();
        callback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                startingAdvertisement = false;
                advertising = true;
                if (destroyed) {
                    advertiser.stopAdvertising(this);
                    return;
                }
                bluetoothStatus.setText("Bluetooth: Motor output " + advertisedSpeed + "%");
                if (desiredSpeed >= 0) updateAdvertisement();
            }

            @Override
            public void onStartFailure(int errorCode) {
                startingAdvertisement = false;
                advertising = false;
                desiredSpeed = -1;
                bluetoothStatus.setText("Bluetooth advertising failed: " + errorCode);
            }
        };
        if (advertiser == null) bluetoothStatus.setText("Bluetooth advertising unavailable. Enable Bluetooth and reopen the app.");
    }

    private void connectIntiface() {
        String host = serverHost.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(serverPort.getText().toString());
        } catch (NumberFormatException e) {
            statusText.setText("Enter a valid server port (1-65535).");
            return;
        }
        if (host.isEmpty() || host.contains("://") || host.contains("/") || host.contains(":") || host.contains(" ")) {
            statusText.setText("Enter a server hostname or IPv4 address without a port.");
            return;
        }
        if (port < 1 || port > 65535) {
            statusText.setText("Enter a valid server port (1-65535).");
            return;
        }
        statusText.setText("Connecting to " + host + ":" + port + "...");
        btnConnect.setEnabled(false);
        String address;
        try {
            String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            address = DeviceProtocol.addressForAndroidId(androidId);
        } catch (IOException e) {
            statusText.setText("Could not create device identity: " + e.getMessage());
            btnConnect.setEnabled(true);
            return;
        }
        DeviceProtocol protocol = new DeviceProtocol(address);
        ws = new WebSocketClient(host, port, new WebSocketClient.Listener() {
                @Override
                public void onOpen(WebSocketClient client) throws IOException {
                    client.sendText(protocol.handshake());
                    runOnUiThread(() -> {
                        if (ws == client) statusText.setText("Connected; waiting for Intiface to initialize device...");
                    });
                }

                @Override
                public void onText(WebSocketClient client, String msg) throws IOException {
                    throw new IOException("Unexpected text from device server: " + msg);
                }

                @Override
                public void onBinary(WebSocketClient client, byte[] data) throws IOException {
                    String command = new String(data, StandardCharsets.US_ASCII);
                    if (command.equals("DeviceType;")) {
                        client.sendBinary(protocol.response(data));
                        runOnUiThread(() -> {
                            if (ws == client) statusText.setText("Intiface device initialized.");
                        });
                        return;
                    }
                    int speed = protocol.speed(data);
                    runOnUiThread(() -> {
                        if (ws != client) return;
                        manualSlider.setProgress(speed);
                        statusText.setText("Intiface speed: " + speed + "%");
                        sendAP69(speed);
                    });
                }

                @Override
                public void onClosed(WebSocketClient client, String reason) {
                    runOnUiThread(() -> {
                        if (ws != client) return;
                        ws = null;
                        statusText.setText(reason);
                        btnConnect.setEnabled(true);
                        if (manualSlider.getProgress() != 0) {
                            manualSlider.setProgress(0);
                            sendAP69(0);
                        }
                    });
                }
            });
        ws.start();
    }

    @Override
    protected void onDestroy() {
        WebSocketClient client = ws;
        ws = null;
        destroyed = true;
        handler.removeCallbacks(updatePendingAdvertisement);
        if (client != null) client.close();
        if (advertiser != null && advertising) advertiser.stopAdvertising(callback);
        super.onDestroy();
    }

    private void sendAP69(int speed) {
        sendRawAP69(DeviceProtocol.motorSpeed(speed));
    }

    private void sendRawAP69(int speed) {
        if (advertiser == null) {
            bluetoothStatus.setText("Bluetooth advertising unavailable. Enable Bluetooth and reopen the app.");
            return;
        }
        desiredSpeed = speed;
        if (!startingAdvertisement) updateAdvertisement();
    }

    private void updateAdvertisement() {
        if (destroyed || desiredSpeed < 0 || startingAdvertisement) return;
        long delay = 100 - (SystemClock.elapsedRealtime() - lastAdvertisementTime);
        if (advertising && delay > 0) {
            handler.removeCallbacks(updatePendingAdvertisement);
            handler.postDelayed(updatePendingAdvertisement, delay);
            return;
        }
        handler.removeCallbacks(updatePendingAdvertisement);
        if (advertising) {
            advertiser.stopAdvertising(callback);
            advertising = false;
        }
        int speed = desiredSpeed;
        desiredSpeed = -1;
        advertisedSpeed = speed;
        arg = (arg + 1) & 0xff;
        AdvertiseData data = new AdvertiseData.Builder()
                .addManufacturerData(0x5a53, DeviceProtocol.payload(arg, speed))
                .build();
        startingAdvertisement = true;
        lastAdvertisementTime = SystemClock.elapsedRealtime();
        try {
            advertiser.startAdvertising(settings, data, callback);
        } catch (IllegalStateException | SecurityException e) {
            startingAdvertisement = false;
            bluetoothStatus.setText("Bluetooth advertising error: " + e.getMessage());
        }
    }
}
