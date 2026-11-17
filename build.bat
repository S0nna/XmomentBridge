@echo off
setlocal
cd /d "%~dp0"

set "STEP=checking Java tools"
where java >nul 2>nul || goto :error
where javac >nul 2>nul || goto :error
where jar >nul 2>nul || goto :error
where keytool >nul 2>nul || goto :error

if not exist tools mkdir tools || goto :error

if not exist tools\android.jar (
    set "STEP=downloading Android 10 platform"
    if not exist tools\platform-29.zip (
        echo Downloading Android 10 platform...
        curl.exe -L -f --retry 2 -o tools\platform-29.zip.tmp "https://dl.google.com/android/repository/platform-29_r05.zip" || goto :error
        move /y tools\platform-29.zip.tmp tools\platform-29.zip >nul || goto :error
    )
    set "STEP=extracting Android platform jar"
    powershell -NoProfile -Command "$ErrorActionPreference = 'Stop'; Add-Type -AssemblyName System.IO.Compression.FileSystem; $zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path 'tools\platform-29.zip')); try { $entry = $zip.GetEntry('android-10/android.jar'); if (-not $entry) { throw 'android.jar not found in platform archive' }; [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path (Get-Location) 'tools\android.jar.tmp'), $true) } finally { $zip.Dispose() }" || goto :error
    move /y tools\android.jar.tmp tools\android.jar >nul || goto :error
)

set "BT=tools\android-16"
if not exist "%BT%\aapt.exe" (
    set "STEP=downloading Android build tools"
    if not exist tools\build-tools-36.zip (
        echo Downloading Android build tools...
        curl.exe -L -f --retry 2 -o tools\build-tools-36.zip.tmp "https://dl.google.com/android/repository/build-tools_r36_windows.zip" || goto :error
        move /y tools\build-tools-36.zip.tmp tools\build-tools-36.zip >nul || goto :error
    )
    set "STEP=extracting Android build tools"
    powershell -NoProfile -Command "$ErrorActionPreference = 'Stop'; Expand-Archive -Path 'tools\build-tools-36.zip' -DestinationPath 'tools' -Force" || goto :error
)
if not exist "%BT%\aapt.exe" goto :error
if not exist "%BT%\lib\d8.jar" goto :error
if not exist "%BT%\lib\apksigner.jar" goto :error

set "STEP=preparing build directory"
if exist build rmdir /s /q build
if exist build goto :error
mkdir build\gen build\obj build\apk || goto :error

set "STEP=packaging resources"
echo 1. Packaging resources...
"%BT%\aapt.exe" package -f -m -J build\gen -M AndroidManifest.xml -I tools\android.jar || goto :error

set "STEP=compiling Java"
echo 2. Compiling Java...
javac --release 11 -cp tools\android.jar -d build\obj MainActivity.java WebSocketClient.java DeviceProtocol.java || goto :error
jar cf build\classes.jar -C build\obj . || goto :error

set "STEP=converting to DEX"
echo 3. Converting to DEX...
java -cp "%BT%\lib\d8.jar" com.android.tools.r8.D8 --min-api 26 --lib tools\android.jar --output build\apk build\classes.jar || goto :error

set "STEP=building APK"
echo 4. Building APK...
"%BT%\aapt.exe" package -f -M AndroidManifest.xml -I tools\android.jar -F build\unsigned.apk build\apk || goto :error

set "STEP=signing APK"
echo 5. Signing APK...
if not exist debug.keystore (
    keytool -genkeypair -v -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" || goto :error
)
java -jar "%BT%\lib\apksigner.jar" sign --ks debug.keystore --ks-pass pass:android --out build\signed.apk build\unsigned.apk || goto :error
move /y build\signed.apk XmomentBridge.apk >nul || goto :error

echo Done! Created XmomentBridge.apk
exit /b 0

:error
echo Build failed while %STEP%. >&2
exit /b 1
