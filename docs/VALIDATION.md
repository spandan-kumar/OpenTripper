# OpenTripper 0.2 validation — 25 October 2026

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
