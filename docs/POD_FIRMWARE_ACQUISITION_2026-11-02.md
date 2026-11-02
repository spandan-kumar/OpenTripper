# Original Pod firmware acquisition — 2 November 2026

**A matching stock firmware image remains missing. No redesigned Pod UI or modified-firmware installation is claimed.** Three parallel investigations covered public firmware sources, the official app metadata path, and image acceptance/recovery checks. This pass produced additional historical acquisition routes and tighter evidence about the current failure. It did not produce a firmware file.

Target: the original small Hunter Tripper Pod (`RE_DISP`), not Tripper Dash. Continues [the prior firmware investigation](POD_FIRMWARE_INVESTIGATION.md) and [OTA reconstruction](POD_OTA_PROTOCOL.md). [Structured evidence](evidence/pod-acquisition-2026-11-02.json) records artifact identities, method offsets, request results and limits.

## What was actually attempted

| Acquisition path | Result | Bound |
|---|---|---|
| Installed official India app 10.1.23(45), normal existing signed-in session → Navigation → Manage Devices | Fresh `RE_FOTA_TRIPPER` query returns `PERMISSION_DENIED`; firmware/mapping URLs and versions all null | Pod was disconnected. Connected version/eligibility flow is still untested; no universal outage claim |
| Official app external files, depth 3 | Empty | Does not inspect private app storage |
| Official app external cache, depth 4 | Only three map-cache database files | Map database was not copied or searched for user locations |
| DownloadManager history, narrowly filtered to official app package | Android shell denied `ACCESS_ALL_DOWNLOADS` | No history recovered and no permission workaround attempted |
| APAC 2.3 (2021), exact recovered `apac-re-prime-prod / RE_FOTA_FULL` collection | Ordinary unauthenticated read: 403 `PERMISSION_DENIED` | Valid APAC-account flow untested |
| APAC 3.0 (2023), exact recovered `apac-re-prime-prod / RE_FOTA_V2_1` collection | Same denial | Valid APAC-account flow untested |
| Current India's retained `re-prime-prod / RE_FOTA_FULL` path | Same denial | Generic full-update model; no external static caller found, so not an active Pod fallback |
| Older India app 7.9.6 (2023), exact recovered `re-prime-prod / RE_FOTA_V2_1` | Same denial | This archive was inspected offline, not installed |
| Public GitHub/web firmware searches and four firmware-name archive patterns | No matching Pod image/URL recovered | A bounded negative search; different filenames or unindexed material may exist |

No account region was changed, user credential extracted/reused, vendor access restriction bypassed, extra APK installed, Pod update packet sent or Pod casing opened. The user said the bike was unavailable, so no connected test was represented as completed.

## New historical artifacts and routes

All vendor packages remain outside the repository. APK signature verification checks artifact integrity under a key; it is not independent proof of the manufacturer's ownership of that key.

| Artifact | Size / SHA-256 | New route |
|---|---|---|
| [APAC 2.3 XAPK archive](https://apkpure.net/royal-enfield-app-apac/com.apac.royalenfield.reprime/download/2.3) | 46,194,812 bytes; `98fd6878c193ae65fc151217dacd99eff86ec1ffea6f14c9140a2c934afbaf3b` | `classes2.dex`, `com.royalenfield.firebase.a.a.a`, entry `0x3600d4`, `RE_FOTA_FULL` literal `0x3600ec` |
| [APAC 3.0 APK archive](https://apkpure.net/royal-enfield-app-apac/com.apac.royalenfield.reprime/download/3.0) | 120,932,512 bytes; `445479174a9228ab4e3a40fa93765d963703b075f470086f4855d63bf787e705` | `classes2.dex`, `gg.g0.a0`, entry `0x400048`, `RE_FOTA_V2_1` literal `0x400060` |
| [India 7.9.6 APK archive](https://apkpure.net/royal-enfield-app/com.royalenfield.reprime/download/7.9.6) | 127,934,206 bytes; SHA-256 `3d1b033b13fb4f47b19a3a66d84d76de957fd7a29c390d16b772985cab9cd04a` | `classes2.dex`, `eh.v0.s0`, entry `0x3d6c24`, `RE_FOTA_V2_1` literal `0x3d6c3c` |

APAC 2.3 and 3.0 share signer SHA-256 `8a042e446539f51997f87dc42ba358025f59c9eecb7e242cbd19a92d3c5b2f0c`; signatures v2/v3 verify. India's older package verifies v2/v3 and SourceStamp. [The manufacturer's APAC Play listing](https://play.google.com/store/apps/details?id=com.apac.royalenfield.reprime) supplies independent package identity. Legacy archive inspection found no firmware candidate in the reviewed member paths or bounded non-media asset/S3 scans; it does not exclude arbitrary obfuscated containers.

Pre-2023 Indian APK archive history was also sought. The indexed oldest concrete acquisition was 7.9.6; an older-history page returned 502 and no 2020–2022 Indian artifact was obtained. This is not a statement that none exists.

## Current authentication failure: what is ruled out, and what is not

The new static trace places the log marker `signInWithCustomToken() executed.` **after** an awaited Firebase custom-token sign-in task:

- `classes8.dex`, `tw6.m` entry `0x20a158` calls `rx6.r` before that marker.
- `rx6.r` entry `0x215834` invokes the sign-in task at `0x2158ac` and awaits it at `0x2158c4`.
- `tw6.e0` awaits its authentication check at `0x20d418` before selecting Firestore and constructing `RE_FOTA_TRIPPER` at `0x20d432`–`0x20d43a`.

The new normal-app runtime capture again contains successful-await markers followed by a metadata permission denial. Thus “sign-in was never called” and an obvious sign-in/query timing race are poor explanations for the observed flow. Authentication success does not grant authorization to a collection.

`a07.a` and `a07.f` use the same logged-in region key. Initialization passes the same FirebaseApp wrapper into Firestore and Auth, then stores both under that same key (`0x218a9a`–`0x218b04`); their unwrappers call the SDKs with that app. Missing-region paths both fall back to defaults. No obvious static project mismatch was identified. Actual runtime Firebase project selection and backend rules remain unobserved, so account claims, configuration, region handling or a retired/disabled collection remain possible.

Base AppCheck SDK classes are packaged, but the ten-DEX direct-call/literal review found no explicit app-level provider installation. Server-side AppCheck enforcement remains unknown; the SDK's presence alone does not diagnose the denial.

The current metadata query has no Pod address/version filter and does not require a BLE connection in the inspected method. This separates metadata acquisition from the later installed-version/eligibility check; it does not substitute for testing the connected flow.

## Do not trust a lone “12.10” value as measured firmware

`DeviceListFragment$i.onReceive` reads the `currentSoftwareVersion` preference. If missing or empty, it assigns **`12.10`** to its `DeviceInfo` software-version field (`classes5.dex`, method `0x52e5dc`, preference read `0x52e654`, fallback `0x52e696`–`0x52e69a`).

This is a bookkeeping fallback, not a Pod response. It does not mean every app screen or owner-reported 12.10 value is fabricated. It means a matching-image decision needs an actual CRC-valid opcode-03 reply or another trustworthy device-version observation. The original OpenTripper photo report does not supply that reply or an installed firmware version.

## Exact URL is the key remaining software artifact

The host download method `vh7.h` (`classes5.dex`, `0x1f952c`) builds a bare Android `DownloadManager.Request` from the supplied URI, chooses an external-files destination and enqueues it. The reviewed method adds no app-session request headers or separate signing exchange. A recovered metadata URL would allow the corresponding ordinary download, subject to URL authorization, expiry and server restrictions.

The metadata field `checkSum` is **not passed into `RECloudInfo`** by the observed Pod mapping. The model receives update type plus six strings, including firmware/mapping URL and base/version values. Full-index direct-reference review found no separate consumer enforcing this checksum in the traced host path. This is not proof that a device accepts arbitrary data: block hashes, whole-image checks, encrypted content or a signed trailer can still be inside the payload/device implementation.

## False positives and recovery limits

- Both direct callers of the current `otap/otap/firmwaredownload` endpoint explicitly pass `KDASH` (`classes8.dex`, `0x475dcc` and `0x47e002`). No current original-Pod fallback caller was found. Server-wide exclusivity is not established.
- Nordic Secure DFU in the APK belongs to a traced BluArmor STM/NRF subsystem, not the original Pod S3 updater (`ded$c` / `lde`, `classes4.dex`). It supplies no Pod signature policy.
- Public companion repositories reviewed contain phone/app integrations, not Pod firmware. Motolog's [publisher](https://motolog.satrujit.in/) identifies package `in.satrujit.tripper_bridge`. Its public 1.3.0 XAPK was downloaded and signature-verified, not installed: 81,190,254 bytes, SHA-256 `7d1dfcbce4e5a9e2bbaf13cf11fdaa80890c26943268d0cf34b94e43e320aa11`. All four APK members, 34 assets/raw resources and 17 native libraries were inspected within the recorded scan bounds. No firmware image or acquisition URL was found. Flutter `libapp.so` contains firmware-success/init-response vocabulary, which does not establish a reachable flasher or dump facility. The author's alleged separate dump remains unverified. No outreach was sent.
- The J1AB comparator internal-photo attachment `5335948` was located in public indexes but could not be retrieved. No new visual chip identification follows. The previously proposed `5335899` is unrelated and must not be used.
- For the still-unconfirmed KW36 MCU candidate, NXP's [flashloader guide, page 4](https://www.nxp.com/docs/en/user-guide/MBOOTFLASHGS.pdf), describes a one-time programming aid that runs in RAM and is no longer available after application programming; the [KW36 support discussion](https://community.nxp.com/t5/Kinetis-Microcontrollers/MCUBOOT-for-KW36-MCU/td-p/805073) distinguishes this from a retained flash-resident bootloader. UART capability is not evidence of an immutable recovery path in this Visteon product.
- [NXP AN12560](https://www.nxp.com/docs/en/application-note/AN12560.pdf) documents candidate-family UART/SWD pins. It is not a verified Pod pad-to-pin wiring diagram. [NXP AN4507](https://www.nxp.com/docs/en/application-note/AN4507.pdf) distinguishes debug security from flash protection and explains why mass erase destroys the firmware sought for backup.

The available static engine remains Android SDK dexdump/SQLite, because REA's separately supplied JADX provider prerequisite is absent. No decompiler-runtime result is claimed. Public GitHub connector calls returned “Unknown tool”; ordinary read-only `gh` searches were used instead.

## Dependency to proceed to a custom UI

At least one of these is still needed:

1. A successful legitimate original-Pod metadata/download result, including firmware URL and matching hardware/base-version information.
2. A matching original firmware file or trustworthy backup supplied by an owner/vendor.
3. Physical access to an identified matching board and a verified preservation-oriented readout path, if debug readout is allowed.

A connected software-version query is a useful identification step when the bike becomes available; it is not a firmware backup. Physical extraction requires actual chip/pad identification and tools, and cannot be performed remotely from the charging socket. The working Hunter Pod is the user's only unit; no physical work or speculative firmware image was attempted.

Once an image exists, inspect address coverage, vectors/architecture, version/board strings, image-validation data, bootloader/config coverage and LCD/icon/layout code. Only after that can a concrete UI patch and restoration plan be assessed. The tested offline inspector/planner already exists, but synthetic packets are not evidence of device acceptance.

No app-source change, new build or new installation was required for this acquisition pass. The research remains incomplete at the firmware-image dependency.
