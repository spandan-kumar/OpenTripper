# Original Pod OTA protocol: deeper static reconstruction

1 November 2026. Offline Android SDK/SQLite disassembly analysis, not observed BLE behavior or REA decompiler output. Target `com.royalenfield.reprime` 10.1.23(45), base APK SHA-256 `d08478e8c564624503b440834a40499cd4acdc1c8572e49fe1b4830472ad37a4`. All offsets below are `classes5.dex` file offsets. This protocol reconstruction used no network/app/device execution and recovered no firmware image. Separate live acquisition attempts are documented in [the investigation](POD_FIRMWARE_INVESTIGATION.md). These facts are a protocol reconstruction, not vendor source reproduction.

## Important practical findings

- The updater sends **plain S3 payload bytes**, with a per-block SHA-256, over the existing Pod UART characteristic. This is enough host-side information to build an offline update simulator. It does not prove acceptance of modified images by the device.
- Partial update is a **selection of full-image blocks by a text mapping**, not a byte-patch algorithm in the reviewed path.
- Do not use opcode `02` as an identity probe. Its successful response automatically advances the official host state to `04` and then firmware initialization. Opcode `03` is the isolated software-version query builder; it is not firmware read-back.
- No bootloader read-back/backup command was identified in this reviewed command set. A full OTA application image also need not include the bootloader, calibration, or recovery data.

## Framing and builders

Integer fields are big endian unless explicitly stated; zero-initialized reserved fields remain zero. CRC is CRC-16/CCITT-FALSE: polynomial `1021`, initial `FFFF`, non-reflected, no final XOR; append high byte then low byte. Evidence: `f22.a` at `1c7308`, constructor at `1c74bc`, table update `1c7560`, BE serializer `53752c`. Reply CRC validation at `xwd.g`, `248b7c`, always covers bytes 0–17 and compares bytes 18–19; reviewed replies are therefore 20-byte frames.

| Opcode | Outbound format (before CRC) | Builder/location |
|---|---|---|
| `02` | 18 bytes: opcode then 17 zeros | `te1.g` `1bca20`; send wrapper `t` `1bd6d8` |
| `03` | Same fixed frame; software version query | `te1.i` `1bcb30`; wrapper `u` `1bd6fc` |
| `04` | Same fixed frame; obtain update block/segment parameters | `te1.j` `1bcba8`; wrapper `l` `1bd1a0` |
| `05` | 18 bytes: `[05, start-address BE32, total-blocks BE16, 11 zeros]` | `te1.k` `1bd0d4`; caller `xwd.b` `2495cc` |
| `06` | 18 bytes: `[06, block-index BE16, block-address BE32, segment-count BE16, 9 zeros]` | `te1.o` `1bd3d4` |
| `07` | `10+n` bytes: `[07, block-index BE16, segment-index BE16, n as u8, 4 zeros, n data bytes]`; then CRC, total `n+12` | `te1.n` `1bd1c4` |
| `08` | 39 bytes: `[08, block-index BE16, 4 zeros, 32-byte SHA256(actual block data)]`; then CRC, total 41 | `te1.p` `1bd4e4`; hash `pe1.g` `1bd774`, `te1.h` `1bca98` |
| `09` | Fixed 18-byte body, no additional image hash or signature field | `te1.d` `1bc9a8`; wrapper `q` `1bd5fc` |
| `0A` | 18 bytes: `[0A, action, 16 zeros]`; action 1 after file verification, action 2 on abort paths | `te1.r` `1bd620` |

Queue flag true enqueues; false writes immediately. Both paths use write-without-response type 1. `xwd.h` `249904`; `xwd.f` `249844` polls next queued frame and stores it for retry; `xwd.d` `249770` retransmits that frame. `xwd.c` `249740` requests ATT MTU 247. Negotiation success/failure behavior requires runtime verification.

`te1.n` clears the queue, enqueues all `07` segments then `08` digest, and immediately sends the `06` block header. Thus `06` acknowledgment releases the first queued segment; each successful `07` acknowledgment releases the next item, ultimately the digest. This is stop-and-wait at application-response level despite GATT write-without-response.

## Reply state machine

All transitions below are reconstructed from `xwd.a` entry `248c94`.

| Reply | Host interpretation and next action | Anchor |
|---|---|---|
| Bad CRC | callback error 1006, stop processing that reply | `248cf0` |
| `02`, status byte1 = 1 | start callback, immediately send `04` | `248d62`–`248d6c` |
| `02`, byte1 = 2 | error 202 | `248da0` |
| `03` | version bytes1–2 formatted independently with `%02x`, joined by `.`; cache/callback. A global eligibility-check flag can invoke `vh7.d`, not flash transfer itself | `248e62`–`248f04`; `ugd.a` `538b74` |
| `04`, status byte5 = 2 | error 402 | `248f80` |
| `04`, otherwise | block size `BE16(reply[1:3])`; segment size `u8(reply[3])`; set write type/MTU; parse firmware and mapping; send `05` | `248fd0`–`24907a` |
| `05`, status byte5 = 1 | plan/send block 0 (`06` header now; `07`+`08` queued) | `2490ba`–`2490ce` |
| `05`, byte5 = 2 | abort `0A/02`, error 502 | `2490d6`–`2490ec` |
| `06`, byte1 = 1 | begin block timer measurement; send next queue item | `249156`–`249162` |
| `06`, otherwise | abort `0A/02`, error 602 | `24916a`–`24917c` |
| `07`, byte5 = 1 | clear segment retry counter; send next queue item | `2491b2`–`2491e6` |
| `07`, otherwise | resend last item while retries < 5; then abort/error 702 | `2491ee`–`24921e`; default `zdh.c = 5` |
| `08`, status byte3 = 1 | read acknowledged block index BE16 bytes1–2; progress = index/total *100; reset block retries; increment index; send next block or `09` if at end | `2492ca`–`24939a` |
| `08`, otherwise | recreate/retry current full block while retries < 2; then abort/error 802 | `2493a2`–`2493f2`; default `pe1.h = 2` |
| `09`, byte1 = 1 | send `0A/01` | `249458` |
| `09`, byte1 = 2 or 3 | abort `0A/02`; error 902 checksum or 903 file | `249464`–`2494c0` |
| `0A`, byte1 = 1 | completion callback, clear cached version | `2494f8`–`24954c` |
| `0A`, byte1 = 2 or 3 | error 1002 checksum or 1003 reflash | `249552`–`2495c2` |

No device-side behavior is inferred from these host messages alone. The handler uses a reply-supplied block index; it is not a validated persistent resume mechanism. `xwd` declares a 5000ms interval and timeout enum 1005 exists, but an active timeout/reconnect/resume path was not established here. No speculative recovery method should be built from these names.

## S-record and mapping layout

`te1.e` `1bcc58` opens text via `BufferedReader`, recognizes S3 and extracts address characters `[4:12]` and payload `[12:len-2]`. It does not verify S-record byte count/checksum in the inspected method. Full file is parsed before optional mapping (`te1.c`, `1bcc20`).

`pe1` holds a start address, a data buffer and actual-byte count. Its constructor (`1bd938`) allocates negotiated block-size bytes but **hardcodes remaining capacity to 4096** (`1bd96a`). `pe1.a` (`1bd974`) copies payload into a block, carries overflow to the next block through static `te1.i`, and counts actual bytes. At the next block, the parser subtracts carry length from only the low address byte (`1bcdb2`–`1bcdbe`), without borrowing through higher bytes. No S3-address continuity check or hole filling was found. Therefore use strict validation and reject noncontiguous data for an emulator's compatibility plan; do not copy these malformed-input behaviors into an inspector.

`te1.m` (`1bc794`) splits actual data length into negotiated-size segments using an inclusive loop `i <= length/segment_size`; when length is exactly divisible, it includes a **zero-length final segment**. Block hash covers actual data, not padding (`pe1.g`, `1bd7d2`–`1bd7f8`). This quirk matters when estimating the block header's segment count or reproducing offline packet fixtures.

Mapping reader `te1.b` `1bc834`:

```text
line 0: first 8 characters -> 4-byte header, stored in q3g.a
subsequent lines:
    chars [0:8]   -> t3g.a (parsed but not used by the reviewed block selector)
    chars [12:20] -> t3g.b (block-start address match)
    chars [24:32] -> t3g.c (big-endian count of consecutive blocks)
```

`te1.f` `1bcec8` iterates mapping rows in order; for each row, finds a full-image block whose start address equals the row's second field, then appends `blocks[index:index+count]` (end exclusive). It does not remap bytes to the first address field. Header is not consumed by this selector. Unknown delimiters/content in skipped columns remain unknown; synthetic tests must not claim a real format sample. `t3g.c` `263e7c` converts count via BE `ByteBuffer.getInt` in `ugd.c` `538b50`.

## Acceptance checks: what is and is not established

The five reviewed classes `te1`, `pe1`, `xwd`, `vh7`, `zs7` contain SHA-256 (`MessageDigest`) calls but no literal references to `Signature`, `Cipher`, `PublicKey`, `KeyFactory`, `Certificate` or `Mac`. There is no host signature field in the reconstructed packet layouts. This is a bounded static finding, not proof the Pod bootloader lacks signatures/encryption, or that an update contains no signed trailer. `09` file verification and `0A` reflash are device operations whose implementation is still unavailable.

## Offline synthetic test vectors

CRC check vector: `CRC("123456789") = 29b1`.

Version-query frame, constructed offline (not transmitted): `03000000000000000000000000000000000045d9`.

For simulator work, serialize exactly the layouts above, retain a manifest with source file SHA-256 and negotiated parameters, and simulate acknowledgments/errors. A simulator passing its own fixtures establishes its deterministic implementation only; actual BLE interoperability and modified-image acceptance remain untested.
