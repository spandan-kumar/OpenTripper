# Hunter Pod hardware result — 2 November 2026

The user completed the OpenTripper 0.5 one-visit kit on the Hunter's original small Tripper Pod. The saved report, outer session and **all 33 original JPEGs** were retrieved from the Pixel 8 and reviewed. This was a stationary BLE display test, not an official RE firmware-update test. Pod firmware version remains unknown.

## Evidence and outcome

- Run `644124e0-1626-4818-966a-ce768f5fd8e7`, app `0.5.0-experimental`, Pixel 8 / Android API 37.
- Started 2 November 2026 at 18:32 IST. Report phase **COMPLETED**, 33/33 accepted photos, zero skips; elapsed **292,308 ms** (4 min 52 s).
- **138 successful Android screen-write callbacks**. Logs record Pod selection, local GATT reply-server registration, BLE connection and a CRC-valid Pod reply; reply meaning is still undocumented.
- All 33 JPEGs decode successfully. Visual conclusions below come from assistant inspection of labeled, orientation-corrected photo contact sheets, not an automatic app verdict. The original report correctly retains `Not automatically evaluated`.
- [Structured evidence](evidence/hunter-2026-11-02.json) records every command, write count, visual observation and original-photo SHA-256. Originals and full reports are retained locally in the downloadable review archive; photos are not committed to the public repository.

| Test | Photograph result | What it establishes |
|---|---|---|
| Roundabout exits 1–9, left-hand traffic (`0x0B`–`0x13`) | Exit numbers 1 through 9 appear at 100 m; the circle and upper-left arrow stay essentially the same | The tested commands select exit numbers, not different exit angles. Do not claim geometrically accurate roundabout diagrams |
| Sharp right, slight right, fork right | Tight hairpin, diagonal arrow and highlighted right fork are visibly distinct | Additional built-in maneuver shapes work |
| Merge left/right | Converging branches | Supported graphics; no strong left/right visual distinction established |
| On-ramp left/right | Converging Y-shaped graphic in both | These two tested commands do not provide a clear directional distinction |
| Off-ramp right; keep left/right | Right exit branch; mirrored left/right branching arrows | These categories are visually distinguishable |
| Straight; destination | Straight arrow; destination pin | Both render at 100 m |
| Baseline left; approach at 95, 70, 45, 20, 10 m | Dark shaft at baseline; increasing red shaft sections; red arrow at 20 m; yellow arrow at 10 m | Existing firmware supports staged emphasis using the supplied command field |
| Distance 999 / 1000 m | `999 M` / `1.0 Km` | Metric boundary encoding works for these two values |
| Night mode | Dark background with light arrow/distance | Existing firmware supports a dark presentation; brightness performance is not measured |
| Three Maps-text replay samples | Sharp left, slight left and left fork draw the expected distinct categories | Synthetic sample parsing plus BLE rendering works; live Maps remains unverified |

Approach emphasis was explicitly selected by the test packets, whose theme/emphasis byte was `0x10`, `0x20`, `0x30`, `0x40`, `0x50` at the five distances. These photos do not show the Pod independently calculating approach progress from distance alone.

## Live Maps and firmware limits

The outer session finished with the notification reader connected and the Pod session `READY`, but its only Maps event is source state **WAITING**. It contains **zero live Maps photos and zero recognized live directions**. It ended with reason `Changed to a standalone mode`. That establishes that live evidence is missing, not why it is missing or that the notification integration failed.

The official RE app's earlier update metadata failure is a separate investigation. Its current app-process logs were no longer present in the retrieved ring buffers, and the current foreground screen was Android settings. This run supplies no new official-app firmware eligibility result, firmware image or installed version. No update or flashing operation was initiated during retrieval.

## Product implications and remaining checks

We can already improve maneuver distinction, approach emphasis and day/night appearance through stock firmware commands. Roundabout numbers work, but the stock glyph does not solve the user's request for an exit-angle diagram in this tested family. Freely moving elements, changing fonts, uploading pixels and replacing firmware remain unimplemented.

Real Maps directions, notification freshness, background operation, long rides, right-hand-traffic roundabouts and firmware version identification still need evidence. No new APK or installation was required for this review; no app source changed. Earlier build/test results remain historical and were not rerun for this evidence-only change.
