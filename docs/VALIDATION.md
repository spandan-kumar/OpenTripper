# OpenTripper 0.3 validation — 25 October 2026

Workspace: `/Users/Spandan/github.com/spandan-kumar/OpenTripper`.

## Build and automated checks

`./gradlew :core:test :app:assembleDebug :app:lintDebug --console=plain` passed with JDK 17 and Android SDK 35. Gradle/AGP/Kotlin pins are unchanged. The camera uses pinned CameraX 1.4.2, Activity 1.9.3, Core 1.13.1 and ExifInterface 1.3.7; AndroidX is enabled.

- **54 JVM tests passed**, zero failures/errors/skips. The original 47 tests remain passing.
- Seven new guided-run tests cover exact write confirmation, settling before the photo prompt, repeated screen writes while waiting, photo/step association, stale-result rejection, explicit skips, completion, cancellation, write/photo/global timeouts and experimental/traffic-side plans.
- Android lint: **0 errors, 43 warnings**. Warnings concern English strings, pinned dependency versions, target API and compatibility manifest declarations; no lint baseline suppresses findings.
- Debug APK v2 signature verification passed. Package `io.opentripper`, version `0.3.0-experimental`, version code 3, min API 26, target API 35. Camera and bounded wake-lock permissions are declared. There is no Internet or shared-storage permission.

Final APK SHA-256:

`a81c57c41d55888c181baa4ac29751822ddc2f7e4c1dc8e015424cdff2993384`

## Actual Pixel 8 checks

Phone: Pixel 8, Android 17 / API 37, connected over wireless ADB. The final APK was installed, pulled back from the phone, and its SHA-256 matched the build and delivered APK above. Camera capture and practice-photo deletion were rechecked on that final build. No OpenTripper runtime crash appeared in the inspected AndroidRuntime log. These checks used the actual app UI:

- Opened the guided-test screen through the new main-screen button.
- Opened **Check camera / allow camera permission**. Android displayed the camera permission dialog directly over OpenTripper.
- Denied camera access: capture stayed disabled and an in-app **Allow camera** button was available. Requested again and granted access while using the app.
- Opened the rear-camera preview, captured a JPEG, reached review, retook it and finished the camera check. Verified that the practice JPEG was deleted from app-private cache on leaving.
- Started an automatic test from the main screen. Bluetooth permission was granted during the flow; the scan started without requiring the old manual connection sequence.
- Cancelled during discovery. The app showed a saved partial report, allowed sharing and stopped its foreground service. Android's service listing confirmed no OpenTripper service remained.
- Opened Android's share sheet with a generated ZIP. Dismissed it without sending to anyone. Read the cached ZIP back: valid `report.json`, phase `CANCELLED`, seven planned checkpoints, zero successful writes, zero photos and no fabricated visual observations.
- Ran discovery without a Pod present. It stopped after the bounded scan with **No Pod found**, enabled another run and sharing, and shut down its service.

Camera-check photos were practice captures, not photographs of a connected Pod. No practice photo is included in hardware evidence. Saved test reports contain no PIN or Pod address. ZIPs are exposed only through a non-exported FileProvider with a temporary read grant when the user chooses Share.

## Remaining hardware checks

No physical Pod was connected. Automatic Pod selection, pairing/resume, actual screen refresh during camera capture, physical graphics, reverse GATT replies and accepting a real checkpoint photo into a completed hardware-run ZIP still require the user's Hunter. Successful write callbacks are transmission evidence, not a visual or PIN-acceptance verdict. Older Android versions and multiple-Pod selection have not been exercised on devices.

The guided runner times out individual photo waits after two minutes and the whole run after ten minutes. App-process death preserves a report marked interrupted; the run is not restored. The user explicitly captures/reviews each photo; no automatic image recognition is implemented. Experimental roundabouts and approach emphasis remain opt-in. Live GPS route following is still not implemented.

## Previous milestone

The section below preserves the verified 0.2 evidence. Its APK hash and test counts refer to that earlier version.

# Previous milestone: OpenTripper 0.2 validation — 25 October 2026

Local workspace: `/Users/Spandan/github.com/spandan-kumar/OpenTripper`.

## Build and automated checks

Command: `./gradlew :core:test :app:assembleDebug :app:lintDebug --console=plain`, with JDK 17 and ANDROID_HOME set. A clean build was also performed during this change to remove incremental metadata referencing the project's old location. The final build completed successfully.

Environment: macOS arm64, OpenJDK 17.0.17, Gradle wrapper 8.10.2, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Android SDK platform 35 and Build Tools 34.0.0. Dependencies are pinned; these are not claims of latest versions.

- 47 JVM tests passed; no failures, errors or skipped tests.
- Android lint: 0 errors, 25 warnings. Warnings cover the older target API, backward-compatible manifest declarations and English/localization strings. No lint baseline suppresses them.
- Debug APK signature verification passed (v2 signature).
- Manifest: package `io.opentripper`, version `0.2.0-experimental`, version code 2, minimum SDK 26, target SDK 35. No Internet permission.
- Gradle wrapper JAR SHA-256: `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`.
- Gradle distribution checksum remains pinned in wrapper properties.

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| ManeuverCueTest | 7 | 0 | 0 | 0 |
| MapsParserTest | 13 | 0 | 0 | 0 |
| OsrmRouteTest | 9 | 0 | 0 | 0 |
| PodSessionTest | 10 | 0 | 0 | 0 |
| TripperProtocolTest | 8 | 0 | 0 | 0 |

Coverage includes fixed protocol vectors and CRC corruption, angle/fork/ramp distinctions, small following-maneuver icons, traffic-side U-turns, roundabout exit families and invalid-exit rejection, opt-in approach intensity, session timing/failures, successful-write reporting, notification freshness, OSRM validation and a captured real OSRM response.

## Actual phone checks

A connected **Pixel 8 running Android 17 / API 37** was available for this milestone. The app was installed successfully. The final installed base APK's SHA-256 matched the build artifact exactly:

`c036b9013b8cf2352378c7638fa280af0d6e06cba57257a838988b1dd60a686f`

The following were exercised on the phone using the actual app UI:

- Main screen and maneuver lab launch without crashes; preview does not require granting Bluetooth permission.
- Bundled synthetic route shows **Roundabout · exit 3**, left-hand traffic, and following **Sharp left**; the phone schematic is explicitly labeled as requiring a physical Pod check.
- The deliberately unsupported ninth route step remains unsupported. The send button is disabled while disconnected.
- The actual captured OSRM response was copied into Downloads and selected through Android's document picker. The app imported four steps and preserved the response's right-hand traffic.
- The observation buttons were disabled before any completed Bluetooth test write.
- The report was exported through Android's document picker and read back as valid schema-version-1 JSON. It contained **zero observations**; no hardware results were fabricated.
- The final APK was reinstalled and the roundabout preview rechecked after the last UI/theme changes. A screenshot is provided with the deliverables.

## What still requires the user's Hunter

No Pod was connected during this validation. Actual pairing, PIN acceptance, reverse GATT traffic, displayed graphics, distance/fill behavior, following-maneuver visibility and the test's real firmware effect remain unverified. The 20-second test's hardware behavior and successful visual-observation recording require a connected Pod. A write callback confirms Android's transmission result, not the correctness of what the Pod drew.

The new OSRM feature is **manual route rehearsal/import**, not live GPS navigation. The app does not calculate routes, track route progress or reroute. Maps notification fixtures remain synthetic English examples; notification forwarding and background reliability still need real app/device/Pod checks.

Use [the hardware checklist](HARDWARE_VALIDATION.md) and the lab's Matches/Different/Blank observations to establish which icons the user's firmware actually supports. Experimental roundabout codes, especially the left-hand-traffic range, must not be treated as established hardware behavior.

## Repository history

The recovered 0.1 starter was published as [1aba541](https://github.com/spandan-kumar/OpenTripper/commit/1aba541a55cdd803de13e80bd860c5f6cd414c8e), followed by [c0e4060](https://github.com/spandan-kumar/OpenTripper/commit/c0e406057e728a39665bf97a82707500ed190ee8) recording publication. This document describes the subsequent 0.2 upgrade; its commit is identified in Git history and the delivery message. Publication is checked by comparing local HEAD against remote main before reporting success.
