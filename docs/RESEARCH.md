# Maneuver clarity: research to implementation

Reviewed 25 October 2026. The hardware target is the original small BLE Tripper Pod on the user's Hunter, not Tripper Dash. Reports below establish recurring usability complaints, not their root cause or prevalence.

## Rider problems

- [Same symbol for left versus straight at a roundabout; Y-junctions and close turns](https://www.reddit.com/r/royalenfield/comments/1tqovup/alternative_app_for_tripperpod_super_tripperpod/). The Super TripperPod author describes exit numbers, while users also report incorrect exits. It is a product benchmark; no reusable source/license was established.
- [Hunter riders report overbridge-versus-surface-road ambiguity and difficult roundabout exits](https://www.reddit.com/r/RoyalEnfieldHunter350/comments/1x244xw/hunter_350_navigation/).
- [Earlier requests for clearer arrows](https://www.reddit.com/r/royalenfield/comments/1eqg1az/tripper_navigation_uisoftware/).

An app can preserve a maneuver correctly while firmware still draws an unclear icon. Conversely, a distinct firmware icon cannot help if our input source loses the maneuver type. Both sides need evidence.

## Projects evaluated

| Project | Fit and evidence | Decision |
|---|---|---|
| [WazeTripper](https://github.com/jnsdias/wazetripper) | Original Pod; Apache-2.0. Author reports a working Meteor/S23 setup, rich maneuvers, following maneuver, reconnection and diagnostic logs. Its patched Waze is version-specific. | Main wire-protocol reference. Implement semantic distinctions and explicit evidence gathering without adopting Waze APK patching. |
| [Tripper Bridge](https://github.com/ananthakrishnan754/tripper-app) | Original-Pod Kotlin/Compose app. No explicit project license found. Its [parser](https://github.com/ananthakrishnan754/tripper-app/blob/master/app/src/main/java/com/tripper/app/nav/TurnParser.kt) reduces roundabouts to a generic code and falls back to straight for unknown text. | Interface inspiration only; do not copy unlicensed code or its unsafe parser fallback. |
| [OpenDash](https://github.com/subtlesayak/open-dash) | Apache-2.0 Android navigation app with routing and structured maneuver models; different Wi-Fi/H.264 Tripper Dash transport. | Navigation architecture reference. Its streamed map cannot be sent to the original Pod. |
| [OSRM](https://github.com/Project-OSRM/osrm-backend) | BSD-2-Clause engine; [route schema](https://project-osrm.org/docs/v5.24.0/api/#stepmaneuver-object) carries maneuver type/modifier, exit number and driving side. | Add a structured route adapter and manual rehearsal before implementing GPS tracking and rerouting. This milestone does not contact a routing server from the app. |
| [BikeNavMobile](https://github.com/rabbihossain/BikeNavMobile) / [ESP32](https://github.com/rabbihossain/BikeNavESP32) | Custom hardware; SDK-based structured guidance. READMEs claim MIT, but standalone project license files were not found. | Study structured guidance and hardware separation; no source copied. A Google SDK integration would need separate API/account setup and terms review. |
| [TripperDash++](https://github.com/kolaCZek/TripperDashPlusPlus) | Different Dash hardware; PolyForm Noncommercial source-available license. | Not selected as a Pod base. |
| [NorthStar](https://github.com/adityadasika21/NorthStar) | Archived; README says development stopped and source was removed. | Not an available implementation base. |

This release is an original Kotlin implementation based on documented interfaces. No third-party application source has been copied. The existing project's license selection remains open; public visibility alone is not a reuse license.

## Concrete gap-to-feature decisions

| Gap | Implemented | Remaining evidence |
|---|---|---|
| Fork/ramp/merge collapsed into ordinary turn | Separate semantic categories, explicit notification patterns and OSRM mappings | What each code actually draws on this Hunter's firmware |
| Roundabout direction/exit ambiguity | Explicit exit 1–9 and traffic-side-aware encoding; no guessed exit | WazeTripper marks exit-code ranges untested, especially left-hand traffic; gated as experimental |
| Closely spaced junctions | Following-maneuver field with small-arrow mapping in lab/structured route rehearsal | Visual legibility of the secondary icon; Maps notifications usually do not supply it |
| Unknown instruction becomes a misleading arrow | Null/unsupported cue retained in route order; no generated screen packet | Broader captured notification fixtures and route examples |
| No way to validate a claimed icon | Manual 20-second test and per-test visual observation report, enabled after successful write callback | User's observations; software tests do not prove firmware behavior |
| Notification text loses route structure | OSRM JSON import independent of notification parsing | Live GPS progress, off-route detection, route calculation and rerouting are not implemented |

## Reference snapshots and sample data

Wire reference: [WazeTripper PROTOCOL.md at 7c4235d5c4de5a1a60aa74ea6ece0c06e9ffab07](https://github.com/jnsdias/wazetripper/blob/7c4235d5c4de5a1a60aa74ea6ece0c06e9ffab07/docs/PROTOCOL.md). Primary/following keep and exit icons differ; U-turn orientation and roundabout exit families depend on driving side. Approach intensity is documented but not independently verified; the lab gates it as experimental.

`junction-demo.osrm.json` is authored synthetic test data, including one intentionally unknown maneuver. `osrm-berlin-capture.json` is a successful response fetched on 25 October 2026 from:

```
https://router.project-osrm.org/route/v1/driving/13.388860,52.517037;13.397634,52.529407?steps=true&overview=false
```

These are the public example coordinates from OSRM documentation, not the user's location or trip. Routing data: © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright). Capturing a response is an importer check, not a road-navigation accuracy test.
