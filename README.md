# Xmoment Bridge

An Android bridge between Intiface Central and an Xmoment AP69 device. The
phone sends Bluetooth LE manufacturer advertisements and presents a virtual
Lovense Hush to Intiface's Device WebSocket Server.

## Requirements

- Android 8.0 or later with Bluetooth LE advertising support. Tested on a Moto e
  running Android 10.
- Intiface Central with the Device WebSocket Server enabled.
- A computer with Android Platform Tools (`adb`) and a USB-debugging-enabled
  phone, for the USB setup below. The phone does not need internet access.
- An Intiface user device configuration mapping the WebSocket name
  `Buzzybody` to the `lovense` protocol. See the
  [WebSocket Device Manager documentation](https://buttplug.io/docs/dev-guide/inflating-buttplug/devices/websocket-device-manager/).
  Preserve any existing user device configuration when adding the mapping.

## Connect over USB

1. Enable USB debugging, connect the phone, and approve its debugging prompt.
   Run `adb devices` to confirm that the phone is available.
2. In Intiface Central, enable **Device Websocket Server**, configure the
   `Buzzybody` mapping, and start the engine. The device server uses port
   `54817` by default; this is separate from the normal client server on
   port `12345`.
3. Forward the device server over USB:

   ```cmd
   adb reverse tcp:54817 tcp:54817
   ```

4. Install `XmomentBridge.apk`, enable Bluetooth, and open the app. Connect to
   `127.0.0.1` port `54817`. Intiface should list the bridge as a Lovense Hush.
   If using a different device-server port, change both the forwarding command
   and the port in the app.

USB forwarding may need to be re-established after disconnecting or restarting
ADB. For other network setups, enter the reachable server address in the app.
The WebSocket connection is unencrypted; use only a trusted network and avoid
exposing the device server to the internet.

The slider uses a calibrated nonlinear intensity curve; the displayed Bluetooth
output is the actual motor command. The nRF test button sends the known
intensity-9 packet without that curve. Start at low intensity and verify the
zero setting works with your device. A lost connection, app termination, or
Bluetooth failure does not guarantee that the device stops; turn it off
manually if needed.

## Build on Windows

Install a JDK with Java 11 or later, ensure `java`, `javac`, `jar`, and `keytool`
are on `PATH`, and run:

```cmd
build.bat
```

The script downloads the Android 10 platform and Android build tools from
Google when needed, then creates `XmomentBridge.apk`. It signs the APK with a
local debug keystore. Keep that keystore private: replacing it changes the
signing identity and may prevent upgrades over an existing installation.
For a public release, use a securely managed release signing key and verify the
APK and signing certificate before publishing.

## License

The bridge's original source is available under the [0BSD license](LICENSE).
This does not grant rights to third-party apps, SDKs, or decompiled source.
