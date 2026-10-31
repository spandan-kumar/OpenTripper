# Required hardware and phone checks

A standard seven-checkpoint run was completed on the user's Hunter Pod on 31 October 2026. See [the hardware result](HUNTER_RESULT_2026-10-31.md) for the seven photographed graphics and 46 successful writes. The checklist below remains the broader validation plan; that single run does not complete it. Phone UI checks are recorded separately in VALIDATION.md. Passing JVM tests or building an APK does not complete hardware checks. Test stationary, with an ignition-powered Tripper Pod and an Android BLE-capable phone.

- Identify exact Pod model/firmware; confirm this is a Tripper Pod, not Dash.
- Confirm `RE_DISP` scan window and UART service/characteristic properties. Check phones on Android 8–11 and 12–15, including permission refusal/revocation, Bluetooth off/on and location settings on older versions.
- Verify local GATT service registration, reverse-link peer address, reply frame length/CRC and write-response behavior. Record sanitized command IDs and statuses; never share PINs or raw personal notification text.
- Pair with the displayed PIN. Test wrong PIN, leading zeros, retry and ignition cycling. Establish actual reply semantics before implementing an accepted/rejected decoder.
- Verify remembered-address resume, 200/150/300 ms timing, clock display, and 24-hour values around midnight/noon.
- Confirm write callbacks for both no-response and default writes; test disconnects during handshake, missing callbacks, repeated scan/connect, selecting a different Pod and foreground Stop.
- Keep an idle native-clock session open for at least ten minutes. Confirm two-second pings prevent the firmware watchdog.
- Feed recognized left/right/straight/keep/U-turn instructions while stationary; check icon appearance, distance at 999/1000 m, sustained screen refresh and blank unknown fields. Some icon mappings are only “likely” upstream.
- Exercise real Google Maps notifications for current app versions, languages, units, private lock-screen content, lane prompts and route recalculation. The automated fixtures are synthetic English examples, not captured Maps notifications.
- Verify notification removal, same-key unsupported text, fifteen seconds without updates, and listener access revocation stop instruction refresh. Confirm the Pod's resulting screen watchdog drop, then manually reconnect to the clock.
- Leave navigation active with the screen locked and app backgrounded; check Android/OEM battery policy, foreground service survival, Stop, and process kill. Process death requires manually restarting the connection.

Use the maneuver lab to test left/right, sharp/slight, keep, fork, merge, on-ramp, off-ramp, U-turn and destination separately. Test roundabout exits 1–9 for the actual traffic side only after enabling experimental icon tests. Compare the following-maneuver small arrow independently. Record Matches/Different/Blank with the Pod firmware if known and export the observation report. Check optional approach emphasis at 100, 95, 70, 45, 20 and 10 metres. A simulator preview does not establish what the real firmware draws.

Missing capabilities: automatic reconnection, background process restoration, traffic/ETA/radar, live GPS route following/rerouting, localized parsing and full riding validation. No on-road validation was performed.
