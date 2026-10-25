# OpenTripper Android starter

Repository: [spandan-kumar/OpenTripper](https://github.com/spandan-kumar/OpenTripper).
Local workspace: `/Users/Spandan/github.com/spandan-kumar/OpenTripper`.
The existing project was moved here on 25 October 2026; this continues the original starter.

A Kotlin Android app for the Royal Enfield **Tripper Pod** advertising as `RE_DISP`.
**Experimental; no physical Pod or Android phone was available for validation. Tripper Dash is unsupported.**

## What is implemented

- BLE scan and explicit device selection, Android runtime permissions, foreground connection service and Stop notification action.
- Outbound GATT client and inbound local GATT server using the Pod UART UUIDs; no BLE advertising or notification subscription.
- Exactly 20-byte frames, CRC-16/CCITT-FALSE, PIN entry, user-verified device resume, 24-hour clock sync.
- Callback-serialized writes, connection timeout, write timeout, firmware pings and repeated navigation screens.
- Opt-in Google Maps notification preview/forwarding. Recognizes a limited set of English turn instructions with metric or imperial distance. Unsupported text is ignored; same-key unsupported updates, removal, listener disconnection and 15-second expiry clear the instruction.
- JVM unit tests for packet vectors, CRC corruption, wire fields, session timing, queue failures, parsing and stale route handling.

The protocol is based on [WazeTripper's observed Pod protocol](https://github.com/jnsdias/wazetripper/blob/7c4235d5c4de5a1a60aa74ea6ece0c06e9ffab07/docs/PROTOCOL.md).
Read [protocol notes](docs/PROTOCOL_NOTES.md) and [hardware checks](docs/HARDWARE_VALIDATION.md) before interpreting software tests as device compatibility.

## Build

Use JDK 17, Android SDK platform 35 and Android SDK Build Tools 34.0.0. The pinned Gradle wrapper downloads Gradle 8.10.2 and verifies its SHA-256.
Android Gradle Plugin is 8.7.3 and Kotlin is 2.0.21; these are pinned reproducible versions, not a claim of latest versions.

Set `ANDROID_HOME` to your Android SDK directory, or create an untracked `local.properties` containing `sdk.dir=/absolute/path/to/android/sdk`.
Then run:

```sh
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

Windows: use `gradlew.bat`. Android Studio can open this directory and sync the project. The app targets Android 15 (API 35) and runs on Android 8 (API 26) or later. APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Try on hardware

1. Install the debug APK, grant Bluetooth permissions and enable Bluetooth. On Android 8–11 also grant location and enable the phone's location setting for BLE scanning. On Android 13+ allow notifications to see the foreground control notification.
2. Turn the motorcycle ignition on and scan promptly. Upstream observes a short advertising window; cycle ignition and rescan if no `RE_DISP` appears.
3. Select your Pod. A local GATT service is registered before connecting, and the first handshake waits 200 ms after remote service discovery.
4. Enter the PIN displayed on the Pod and send it. A successful Android write callback only means the write completed; the app cannot authenticate PIN acceptance from an undocumented reply.
5. Verify the Pod responds, then choose **Remember Pod — I verified its display**. Only the device address is saved; PINs are not stored. Future connections use the resume handshake. If resume fails, forget the device and pair again. **Sync phone time** sends the current 24-hour phone time.
6. For Maps: enable notification access in Android settings, start Maps navigation, then tap **Start Maps preview**. Check a recognized instruction before turning on forwarding. If no instruction appears, that notification format/language is unsupported. This is not a Google Maps routing SDK or an official navigation integration.
7. Stop connection when finished. Rescan manually after a disconnection. Automatic reconnect and process-death restoration are not implemented.

## Limits and data handling

Google Maps does not provide a stable public notification schema for this integration. No icon-image interpretation, roundabout exit inference, ETA inference, localized instruction parsing, radar, GPS compass or Tripper Dash support is implemented. Multiple navigation notifications, OEM background restrictions and notification text privacy/redaction still need phone testing.

Notification access is granted broadly by Android. The listener immediately filters for `com.google.android.apps.maps`. It retains only parsed maneuver/distance while the service is active, and a small in-memory status log. Nothing is uploaded; the manifest has no Internet permission. Raw PIN/reply payloads and notification text are not logged. Android preferences store the manually verified Pod address and forwarding choice. App backup is disabled.

If screen forwarding stops, generic pings do not preserve the Pod's screen watchdog. Upstream reports the Pod may drop its link about five seconds after the last screen. The app intentionally does not invent a return-to-clock command; reconnect manually after the Pod drops, or cycle ignition if needed.

## Source layout

- `core`: Android-free protocol encoder, session state machine, experimental parser, and unit tests.
- `app`: platform Android UI, notification listener, foreground service, GATT adapter.
- `docs/VALIDATION.md`: actual local checks and remaining validation gaps.
- `docs/GITHUB_ACCESS.md`: GitHub integration permission diagnosis and local Git authentication route.

No project license has been selected; choose one before redistribution. Gradle wrapper files retain their own upstream notices.
