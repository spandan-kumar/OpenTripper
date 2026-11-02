# Mobile UI validation — 3 November 2026

Version **0.6.0-experimental**, versionCode 6. This release redesigns the Android app UI. It does not change the Pod firmware or implement arbitrary Pod graphics.

## Build and artifact

- JDK 17; Android SDK 35; pinned existing AGP/Kotlin/Gradle versions.
- `:core:test --rerun-tasks :app:assembleDebug :app:lintDebug` completed successfully. **66 core tests, zero failures/errors/skips**. Subsequent UI fixes were rebuilt and linted; core stayed unchanged.
- Final lint: **0 errors, 61 warnings**. Remaining categories concern English strings, pinned dependency/target age, compatibility and existing platform metadata. No lint baseline added.
- APK v2 signature verifies. Signer SHA-256 `8f7a1d1830d401866985253d480eb7f4748c4c1144a8eb77f0e11448c28f9b68`, same as the installed 0.5 build's artifact.
- Final APK: **4,612,314 bytes**, SHA-256 **`52b62fa1544fc158c2e43f80b210e16264b006dc7deacca8980f13269a7aabfb`**.
- Installed the final APK in the emulator and read its installed base APK back; SHA-256 matches the deliverable.
- `git diff --quiet HEAD -- core PodService.kt BleTransport.kt MapsObserver.kt` (with full source paths) confirms no change to the core protocol, BLE transport, notification reader or connection service in this release. Report writers only replace the hardcoded app-version value with generated BuildConfig.

## Actual emulator checks

Android 15 / API 35, ARM64, 1080 × 2400 at 420 dpi, with compact 840 × 1890 and landscape configurations also exercised. These are software UI checks, not a new Hunter hardware test.

- Launched Ride, Pod, Tools, Maps, check kit, quick guided check, maneuver lab and camera check through the UI.
- Verified the selected bottom destination and Back returning to Ride.
- Inspected actual light/dark screenshots. The Pod drawing is labelled as an illustration; waiting screens do not fabricate navigation.
- At 200% font scale, text wraps and bottom navigation remains reachable. On the compact viewport, an initial review found the main navigation action below the illustration; the final layout moves it above decorative content on short displays or at large text sizes. Final captures show the action visible.
- Inspected landscape centered content and scrolling. This is not a full tablet/foldable validation.
- Kit starts with **33 photos / four groups**. Clearing all groups disables Start; selecting the baseline group re-enables it. Disclosure state and accessible state descriptions update. A helper cleanup was interrupted by overlapping automation; subsequent UI operations were run sequentially. The related uiautomator crash was not an app crash.
- Camera: exercised the actual in-app Android permission request, live emulated preview, capture, review, retake, second capture and Finish. Confirmed the practice JPEG was deleted after Activity teardown. Its test pattern is an emulator image, not a Pod photograph.
- App process exit history contains only force-stop/package-update reasons from QA; no app crash was recorded. A complete screen-reader audit and real pairing/PIN keyboard check were not performed.

## Existing report upgrade and sharing

An isolated emulator was downgraded to the signed 0.5 APK. A 33-checkpoint session was started, cancelled during discovery and explicitly finished. It contains **zero hardware writes and no photos**, not a passed display run.

After in-place upgrade to 0.6, both files retained their exact SHA-256:

- Child report: `c4a0f6bc37f466c34227f2e96d966a58c7941d0794cc315ae744f6eebbafedc2` (`CANCELLED`, version 0.5).
- Outer session: `a513f38849965e893856957ed92e02bd3be716ec7d412b1817e3dbe44b5a8b10` (`FINISHED`, version 0.5).

The redesigned saved-evidence section recognized the old session and enabled sharing. The generated ZIP passed integrity checks and contained `suite.json` plus its referenced child `report.json`. The system sharing/device picker was dismissed without selecting a recipient or sending anything. Source files remained byte-for-byte unchanged after export.

The Pixel was not reachable during this UI pass. **No installation on the user's phone or new physical Pod test is claimed.** Existing user reports/photos on the phone were not accessed, overwritten or deleted by this pass. The old real Hunter evidence remains separately documented.

## Scope and remaining limits

[Design decisions and installed skills](UI_DESIGN.md) describe the shared native components. Palette body-text pairs tested exceed 5.8:1; control outlines exceed 3:1 against their input surfaces. This is a palette check, not a complete accessibility certification.

Actual Maps notification cadence, riding/background behavior, pairing states and physical camera quality remain hardware/phone checks. The redesigned UI preserves their existing behavior. Public release signing, localization, automatic reconnection and firmware replacement remain outside this release.
