# Android → Windows SMTC Bridge

This project mirrors the active Android media session into Windows System Media
Transport Controls (SMTC). It synchronizes metadata, album art, playback state,
timeline and supported controls. It does **not** forward audio.

## Components

- `android/`: Android 8.0+ application written in Kotlin and Jetpack Compose.
  The interface uses Material 3 dynamic color, asymmetric expressive shapes,
  large status surfaces and animated state transitions. The APK contains no
  native library and therefore runs natively on ARM64/armv8a devices.
- `windows/`: dependency-free Windows 10/11 x64 console client written in C++/WinRT.

## Android setup

1. Install `android-smtc-bridge-arm64-release.apk`.
2. Open **Media Bridge** and grant notification access. Android requires an
   enabled notification-listener component to enumerate other apps' active
   media sessions.
3. Enable Wi-Fi, Bluetooth, or both.
4. Note the displayed IP address and six-digit PIN.

For Bluetooth, pair the phone in Windows Bluetooth settings first, then tap
**Make this phone discoverable** in the Android app before connecting.

## Windows usage

Wi-Fi:

```text
smtc-bridge.exe --wifi 192.168.1.23 --pin 123456
```

Use a non-default Android port when necessary:

```text
smtc-bridge.exe --wifi 192.168.1.23 --port 45832 --pin 123456
```

Bluetooth RFCOMM:

```text
smtc-bridge.exe --bluetooth AA:BB:CC:DD:EE:FF --pin 123456
```

Press `Ctrl+C` to stop. Windows play, pause, previous, next, stop and timeline
seek requests are sent back to the active Android player.

## Security model

The six-digit PIN prevents accidental or casual control by other devices on the
same LAN. The protocol is not encrypted. Use it only on a trusted local network;
Bluetooth relies additionally on normal OS pairing.

## Protocol

Both transports carry UTF-8 JSON objects separated by `LF`. The client first
sends:

```json
{"type":"hello","version":1,"pin":"123456"}
```

The server replies with an acknowledgement and then sends `state` objects.
Commands use the following shape:

```json
{"type":"command","action":"seek","positionMs":42000}
```

Valid actions are `play`, `pause`, `toggle`, `previous`, `next`, `stop`, and
`seek`. Frames are limited to 8 MiB by the Windows client.

## Rebuilding

The checked-in source is complete. Android uses Gradle/AGP and JDK 17 or newer.
Windows uses the Visual C++ Build Tools and Windows 10/11 SDK:

```text
windows\build.cmd AndroidSmtcBuild\windows
```

Build caches and generated files are intentionally excluded from source control.
