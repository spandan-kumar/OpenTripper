# Protocol implementation notes

Source snapshot: [WazeTripper PROTOCOL.md at 7c4235d5c4de5a1a60aa74ea6ece0c06e9ffab07](https://github.com/jnsdias/wazetripper/blob/7c4235d5c4de5a1a60aa74ea6ece0c06e9ffab07/docs/PROTOCOL.md), consulted 24 October 2026. It is a reverse-engineered, hardware-observed protocol, not a Royal Enfield specification. This project contains an original Kotlin implementation of its documented fields, not WazeTripper application source.

`TripperProtocol` implements CRC over the first 18 bytes and stores the checksum big-endian. Tests compare all three fixed packets to the published byte vectors and also check CRC against the independent `123456789` check value.

`BleTransport` registers a write/write-without-response local UART characteristic before connecting as a client to the same UART UUIDs on the Pod. It accepts only matching-peer, non-prepared, zero-offset, valid 20-byte inbound frames and responds when requested. Reply command semantics are undocumented; receiving a valid reply is diagnostic only. Verify the same Bluetooth address is used for the reverse link on real firmware.

The discovery delay belongs to the Android transport; the remaining resume timing belongs to `PodSession`: resume write, 200 ms, phone time, 150 ms, ping twice (each waiting for its callback), 300 ms, READY. Delays start after successful callbacks, which may be more conservative than upstream's enqueue-based timing. Keepalive/screen scheduling checks at one-second intervals during READY or PIN entry; handshake delays check every 50 ms. A missing write callback fails at three seconds; the initial connection fails at twenty seconds.

New pairing sends SHOW_PIN and keeps pinging while the user enters 1–6 ASCII digits. The PIN packet is serialized with other traffic. READY after its successful write is **provisional**, not proof of PIN acceptance. The app stores a resume address only after the user explicitly verifies the Pod display. It never starts Android OS Bluetooth bonding (`createBond`); this is the Pod's application-level PIN handshake.

Navigation uses only a known maneuver icon, distance and day flag. Next maneuver, radar and bottom fields stay blank. Unknown instructions produce no packet. No speculative ETA, screen reset, radar fields or ACK decoder is included. The day flag is used; automatic night mode is not implemented.

The same screen repeats every two seconds while its instruction is fresh. On expiry/removal the screen is cleared from the scheduler, while pings continue. The Pod may then close its own connection due to its independent screen watchdog. Returning to the native clock and reconnection need the manual hardware procedure in README.
