# Hunter Pod hardware result — 31 October 2026

The user completed the standard OpenTripper 0.3 stationary guided run on their Hunter's original small Tripper Pod. The saved app report and all seven associated photos were retrieved from the connected Pixel 8 and reviewed. Firmware version is unknown. Original photos and the full report are retained locally; this repository records only the findings.

## Run evidence

- App report phase: **COMPLETED**, seven checkpoints, seven JPEGs, zero skips.
- Duration: **114,489 ms** (about 1 minute 54 seconds), including discovery/connection.
- **46 successful Android screen-write callbacks** across the seven checkpoints.
- Event log records automatic Pod selection, local GATT registration, BLE connection, service discovery and one CRC-valid Pod reply. Reply meaning remains undocumented.
- Each checkpoint photo shows the intended category at **100 m**. Visual review was performed by the assistant after capture, not by an automatic app grader.

| Checkpoint | Wire icon | Successful writes | Visible in photograph |
|---|---|---:|---|
| Ordinary left | `0x14` | 8 | Left arrow with a roughly right-angle bend |
| Ordinary right | `0x15` | 11 | Right arrow with a roughly right-angle bend |
| Sharp left | `0x16` | 6 | Tight hairpin-like left bend; distinct from ordinary left |
| Slight left | `0x18` | 5 | Diagonal upper-left arrow; distinct from ordinary/sharp left |
| Fork left | `0x06` | 5 | Two-branch junction with left branch highlighted |
| Exit left, next right | `0x2E`, next `0x15` | 6 | Left exit branch from a straight stem, plus a separate smaller right-turn arrow |
| Left-hand-traffic U-turn | `0x1A` | 5 | U-shaped return arrow, distinct from sharp left |

## What this establishes

This Pod can draw different built-in graphics for the tested maneuver categories when OpenTripper sends the corresponding commands. Its firmware does not force these commands into one universal turn arrow. The following-maneuver field can produce a separate small right-turn arrow. Display photos establish that the real device responded, beyond Android's write callbacks alone.

This does not establish why another navigation app may show generic arrows: the maneuver source, interpretation and command selection need separate testing. It also does not demonstrate arbitrary pixel drawing, replacement firmware or redesigned layouts.

## Still unverified

This was the standard seven-checkpoint run (`experimental=false`). Roundabouts, approach emphasis, other distances, night mode, right-side variants beyond ordinary right, remembered-device resume, wrong-PIN behavior, reply semantics, long sessions, riding conditions and actual Google Maps notification forwarding remain unverified. The screenshots show the app maintained enough communication to complete the run, but there was no separate instrumented timing capture of every display refresh.

No app code or APK changed for this evidence update. The 25 October build/test results in VALIDATION.md remain historical build evidence; no new build was required for these documentation changes.
