# Local validation — 25 October 2026

The existing project was moved to `/Users/Spandan/github.com/spandan-kumar/OpenTripper`. The connected integration still returns HTTP 403, `Resource not accessible by integration`; the independently authenticated local GitHub client is used to publish. See [GitHub access](GITHUB_ACCESS.md) for the permission diagnosis.

## Successful local checks

Command, run from the new workspace: `./gradlew :core:test :app:assembleDebug :app:lintDebug --console=plain`, with JDK 17 and `ANDROID_HOME` set to the installed SDK. The project wrapper downloaded and checksum-verified Gradle 8.10.2. Result: **BUILD SUCCESSFUL**, 48 tasks executed; no tasks were reused from the old project location.

Environment: macOS arm64; OpenJDK 17.0.17; Gradle 8.10.2; Android Gradle Plugin 8.7.3; Kotlin 2.0.21; Android SDK platform 35 and Build Tools 34.0.0.

- Android Kotlin sources compiled and debug APK assembled.
- 26 JVM tests passed; zero failures, errors or skipped tests.
- Android lint completed: zero errors, seven warnings (API 35 target, a newer manifest attribute ignored on old Android versions, legacy backup declaration guidance, and four English UI/localization warnings). These are retained explicitly, not hidden with a lint baseline.
- Gradle wrapper JAR SHA-256 matched Gradle's published checksum: `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`.
- Gradle distribution SHA-256 is pinned in wrapper properties: `31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26`.
- Debug APK signature verification passed. Manifest minimum SDK is 26, target is 35; Internet permission is absent.

## Test suites

| Suite | Tests | Failures | Errors |
|---|---:|---:|---:|
| MapsParserTest | 9 | 0 | 0 |
| PodSessionTest | 9 | 0 | 0 |
| TripperProtocolTest | 8 | 0 | 0 |

## What was not validated

`adb devices -l` showed no attached devices. No Android phone, emulator or Tripper hardware runtime test was run. Automated notification fixtures are synthetic examples, not captures from an installed Google Maps version. Initial PIN acceptance and reverse GATT replies remain unverified; the app reports READY provisionally and requires manual visual verification before saving a Pod address. The published packet vectors and CRC were validated in software only.

The service discovery delay, local GATT service registration and actual Android write callback behavior compile but require device testing. Background operation, OS permission flows, OEM battery management and visual UI layout have not been exercised on a device.

See [the hardware checklist](HARDWARE_VALIDATION.md). Source and APK are an experimental starter, not a hardware-validated navigation product.

## Verified publication

The starter was committed and pushed to the existing public repository on 25 October 2026:
[1aba541a55cdd803de13e80bd860c5f6cd414c8e](https://github.com/spandan-kumar/OpenTripper/commit/1aba541a55cdd803de13e80bd860c5f6cd414c8e).
`git ls-remote origin refs/heads/main` returned that exact SHA after the push, matching local
`HEAD`; the working tree was clean. A subsequent documentation commit records this evidence.

Rebuilt debug APK SHA-256: `43c4f55174950464c344c4b7e2e4470ef96e0a6d99b605b5d52525c3a072f0b4`.
