# OpenTripper 0.3 — guided Pod tests with photos

Repository: [spandan-kumar/OpenTripper](https://github.com/spandan-kumar/OpenTripper).
Local workspace: `/Users/Spandan/github.com/spandan-kumar/OpenTripper`.
The existing project was moved here on 25 October 2026; this continues the original starter.

A Kotlin Android app for the Royal Enfield **Tripper Pod** advertising as `RE_DISP`.
**Experimental. The standard seven-checkpoint run was completed on a Hunter Pod on 31 October 2026: photos show distinct turn/fork/exit/U-turn graphics and the small following-turn arrow. Roundabouts and live navigation remain unverified. Tripper Dash is unsupported.**

See [the actual Hunter hardware result](docs/HUNTER_RESULT_2026-10-31.md) for the observed graphics and remaining limits.

## What is implemented

- One-button stationary guided test: scan, select a unique or previously verified Pod, connect, transmit seven screen tests and pause for a photo at each checkpoint. First-time PIN entry remains manual.
- In-app camera preview and permission request, photo review/retake, explicit skip, bounded test timeouts, partial reports and ZIP sharing with the photos.
- BLE scan and explicit device selection, Android runtime permissions, foreground connection service and Stop notification action.
- Outbound GATT client and inbound local GATT server using the Pod UART UUIDs; no BLE advertising or notification subscription.
- Exactly 20-byte frames, CRC-16/CCITT-FALSE, PIN entry, user-verified device resume, 24-hour clock sync.
- Callback-serialized writes, connection timeout, write timeout, firmware pings and repeated navigation screens.
- Opt-in Google Maps notification preview/forwarding. Recognizes a limited set of English turn instructions, forks, ramps, merges and explicit roundabout exits with metric or imperial distance. Unsupported text is ignored; same-key unsupported updates, removal, listener disconnection and 15-second expiry clear the instruction.
- Maneuver lab with 22 maneuver categories, traffic-side-aware U-turns and explicit roundabout exits 1–9. Phone previews are schematic, not screenshots of the firmware's graphics.
- Current and following-maneuver encoding, including the Pod's separate small-arrow mapping. Roundabout codes and optional approach emphasis require explicit experimental-test opt-in.
- OSRM JSON route import and manual step rehearsal, including a bundled synthetic junction scenario. This does not provide GPS route following or route calculation.
- Bounded 20-second stationary screen tests. Maps cannot override an active test and stays paused afterward. User-reported icon observations can be saved and exported without PINs, Pod addresses or route coordinates.
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

## Try the new screens without a Pod

Open **Maneuver lab & route preview**. Manual icons lets you compare turn, fork, ramp and exit categories and choose a following maneuver. **Route preview → Load junction demo** provides a synthetic sequence with a fork, flyover ramp, exit, third-exit roundabout and a deliberately unsupported step. Unsupported steps generate no screen packet.

Use **Import OSRM JSON** to open an OSRM route response exported with `steps=true` (maximum 1 MiB, 5,000 steps, first route alternative). The imported step's `driving_side` overrides the fallback traffic setting. Roundabouts without an explicit supported exit stay unsupported. The distance selector is a **test distance before the selected maneuver**; the OSRM step's distance is the length *after* that maneuver and is deliberately not misrepresented as your current distance to it.

The bundled example is not a real road route. A separate real OSRM response from the documented Berlin example coordinates is checked into `core/src/test/resources/osrm-berlin-capture.json` as an importer regression fixture. Routing data attribution: © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright).

## Automatic test with photo checkpoints

Park the Hunter and turn on the ignition. Open **Start automatic Pod test**. Android asks for camera and Bluetooth access directly in the app (and optionally notification permission for its connection notification). Camera access can also be granted and checked through **Guided test, camera & reports → Check camera / allow camera permission**, without a Pod.

The app scans for up to 30 seconds. It automatically uses your previously verified Pod, or waits briefly and connects to the only Pod found. If multiple Pods are found, choose yours. Enter the PIN shown on the Pod when requested. Android write callbacks do **not** prove that the firmware accepted the PIN.

For each screen, the app waits for a successful write and a three-second settling period, then vibrates and asks **Photograph the Pod now**. Open the camera, take a photo, check it is readable and tap **Use photo & next test**. Retake leaves the same maneuver active. **Skip this photo** explicitly records missing evidence. Standard checkpoints cover ordinary left/right, sharp/slight left, fork left, exit left with a following right arrow, and a U-turn. Enable experimental tests before starting to add roundabout exits 1–3 and near-turn emphasis (11 checkpoints total).

A photo wait expires after two minutes; the whole run is capped at ten minutes. Disconnects, write failures and cancellation stop the run and preserve its partial report. Keep the app open for the guided prompts. After process death, the saved run is marked interrupted rather than silently resumed. The service keeps refreshing the current screen while the camera is open. Maps forwarding stays paused after testing.

At the end, tap **Share test report and photos** to choose where to send a ZIP. Its `report.json` links each checkpoint to a JPEG at the ZIP root, with intended maneuver, traffic side, packet bytes, successful-write count, skipped photos and failure reason. Photos/report stay in app-private storage until you choose Share; no gallery permission or upload is used. Practice camera-check photos are deleted when the camera screen closes. Only the latest saved run is exposed by the current UI. Clearing app data/uninstalling deletes unshared evidence.

This run collects evidence; it does not automatically recognize or grade the Pod's arrows. Physical Pod tests are still required before treating any experimental icon as supported.

## Try on hardware

1. Install the debug APK, grant Bluetooth permissions and enable Bluetooth. On Android 8–11 also grant location and enable the phone's location setting for BLE scanning. On Android 13+ allow notifications to see the foreground control notification.
2. Turn the motorcycle ignition on and scan promptly. Upstream observes a short advertising window; cycle ignition and rescan if no `RE_DISP` appears.
3. Select your Pod. A local GATT service is registered before connecting, and the first handshake waits 200 ms after remote service discovery.
4. Enter the PIN displayed on the Pod and send it. A successful Android write callback only means the write completed; the app cannot authenticate PIN acceptance from an undocumented reply.
5. Verify the Pod responds, then choose **Remember Pod — I verified its display**. Only the device address is saved; PINs are not stored. Future connections use the resume handshake. If resume fails, forget the device and pair again. **Sync phone time** sends the current 24-hour phone time.
6. For Maps: enable notification access in Android settings, start Maps navigation, then tap **Start Maps preview**. Check a recognized instruction before turning on forwarding. If no instruction appears, that notification format/language is unsupported. This is not a Google Maps routing SDK or an official navigation integration.
7. Stop connection when finished. Rescan manually after a disconnection. Automatic reconnect and process-death restoration are not implemented.

## Verify the icons on your Pod

After connecting and completing PIN entry, open the lab. Choose the maneuver, traffic side, following maneuver and test distance, then **Send to Pod for 20 seconds**. The send button is disabled while disconnected. Experimental roundabout codes and approach emphasis also require **Enable experimental icon tests**.

After a successful Android write callback, observation buttons become available for that exact test. Look at the physical Pod and record **Matches**, **Different** or **Blank**, with firmware version and notes if known. This is a human observation, not a firmware acknowledgement. Export the JSON report through Android's document picker. No test is sent merely by selecting a preview. The lab pauses Maps forwarding until you explicitly enable it again on the connection screen.

See [research and gap decisions](docs/RESEARCH.md) for why these are the first improvements, and [hardware checks](docs/HARDWARE_VALIDATION.md) for what still needs your Hunter.

## Limits and data handling

Google Maps does not provide a stable public notification schema for this integration. No icon-image interpretation, guessed roundabout exits, ETA inference, localized instruction parsing, live GPS route following, route calculation, radar, GPS compass or Tripper Dash support is implemented. Multiple navigation notifications, OEM background restrictions and notification text privacy/redaction still need phone testing.

Notification access is granted broadly by Android. The listener immediately filters for `com.google.android.apps.maps`. It retains only parsed maneuver/distance while the service is active, and a small in-memory status log. Nothing is uploaded; the manifest has no Internet permission. Raw PIN/reply payloads and notification text are not logged. Android preferences store the manually verified Pod address, forwarding choice, fallback traffic side, optional firmware label and user-entered lab observations. Imported routes stay in memory; the app does not retain a route history. An exported observation report includes phone model, Android version, navigation test packets and your notes, but no PIN, Pod address, notification contents or route coordinates. Guided runs also save app-private photos and structured test evidence; shared ZIPs are cached for Android sharing. Camera photos have no app-supplied GPS location. App backup is disabled.

If screen forwarding stops, generic pings do not preserve the Pod's screen watchdog. Upstream reports the Pod may drop its link about five seconds after the last screen. The app intentionally does not invent a return-to-clock command; reconnect manually after the Pod drops, or cycle ignition if needed.

## Source layout

- `core`: Android-free protocol encoder, session state machine, experimental parser, and unit tests.
- `app`: platform Android UI, notification listener, foreground service, GATT adapter.
- `docs/VALIDATION.md`: actual local checks and remaining validation gaps.
- `docs/GITHUB_ACCESS.md`: GitHub integration permission diagnosis and local Git authentication route.

No project license has been selected; choose one before redistribution. Gradle wrapper files retain their own upstream notices.
