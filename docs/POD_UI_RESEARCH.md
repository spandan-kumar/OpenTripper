# Changing the original Tripper Pod UI

Investigated 31 October 2026. Target: the original small `RE_DISP` Pod on the user's Hunter, not the Wi-Fi Tripper Dash. This records a bounded investigation, not a completed firmware replacement.

## Result

OpenTripper currently selects graphics and supplies values to the Pod's existing firmware over BLE. It does not upload a phone-rendered screen. Our seven actual Hunter photos demonstrate distinct ordinary, sharp, slight, fork, exit and U-turn graphics, including a separate following-turn arrow: see [hardware evidence](HUNTER_RESULT_2026-10-31.md).

| Desired change | Current evidence | Status for this Hunter |
|---|---|---|
| Use more informative turn shapes | Photographs show distinct built-in graphics responding to our commands | Confirmed for the seven photographed checkpoints |
| Following-turn arrow | Separate small right arrow visible beside an exit | Confirmed for that combination |
| Day/night appearance and approach emphasis | Documented command fields; bundled field kit exercises both | Not yet verified in this Hunter's photos |
| Bottom-line distance/time/ETA, clock or compass presentation | Documented predefined fields/screens | Upstream evidence only; not an arbitrary widget system |
| Move/resize elements, new fonts, freely chosen colours, custom bitmaps or map | No drawing/upload command identified in the reviewed protocol or focused official-app navigation path | No demonstrated implementation on stock firmware |
| Replace stock UI through modified firmware | Tripper-specific firmware metadata code exists in the official app; no matching firmware image or verified modified-image installation obtained | Research lead, not a working capability |
| Fully custom screen with replacement electronics | Published ESP32/AMOLED project replaces Tripper internals | Concrete separate hardware project; does not retain the original electronics |

## What the public projects establish

[WazeTripper's protocol snapshot](https://github.com/jnsdias/wazetripper/blob/7c4235d5c4de5a1a60aa74ea6ece0c06e9ffab07/docs/PROTOCOL.md) describes 20-byte packets selecting a large maneuver, small following maneuver, distance, theme/emphasis and predefined bottom-line values. It also documents compass, clock, route-start, recalculation and call-overlay behavior with varying confidence. These are firmware-rendered screens. No pixel, font, coordinate or arbitrary-text interface is documented there. That is a limit of the known interface, not proof that an undiscovered command cannot exist. The NXP UART UUID identifies a transport lineage; it does not establish the precise MCU, debug access or firmware protections.

Motolog's [product page](https://motolog.satrujit.in/) advertises additional built-in presentation controls. Its author [claims to have extracted a raw Pod firmware image and examined it in Ghidra](https://www.reddit.com/r/RoyalEnfieldHunter350/comments/1v9wmtu/motolog_update_full_tripper_pod_display_control/), but withholds the extraction details. This is an attributed claim: no public dump or repeatable extraction method was located in the reviewed material. It does not demonstrate installing a redesigned UI.

[HimalayanGauge at 8948326](https://github.com/ViktorSvirobovic/HimalayanGauge/tree/89483263005561014caf352f4d8f394869fabbae) explicitly replaces the internals with a Waveshare ESP32-S3 1.75-inch round AMOLED and obtains motorcycle data through an ELM327 adapter. Its UI draws inspiration from [Garage Tinkering's minimal-speedo](https://github.com/garagetinkering/minimal-speedo). It is a useful custom-dashboard example, not a stock Pod display driver or firmware patch. No license was identified for HimalayanGauge during this review; no source was copied.

[better-dash](https://github.com/OpenMotoDash/better-dash) uses the different Tripper Dash's Wi-Fi/video transport. Its screen streaming cannot be assumed to work on the BLE Pod.

## Read-only inspection of the installed official app

The installed Android package `com.royalenfield.reprime`, version `10.1.23(45)`, was pulled from the connected Pixel for local static inspection. The four APKs were not changed or installed. No firmware update, backend request, flashing or Pod command was performed for this investigation.

Base APK SHA-256:

```text
d08478e8c564624503b440834a40499cd4acdc1c8572e49fe1b4830472ad37a4
```

REA's package pull and archive inventory supplied artifact identity and member coverage. Its Android decompiler provider was unavailable because the required caller-supplied JADX MCP JAR was not configured. Android SDK `apkanalyzer`, `dexdump` and a small local DEX table reader were therefore used for focused bytecode inspection. The observations below are ordinary SDK/shell static analysis, not REA decompiler results or observed execution. Obfuscated names and offsets apply only to this APK.

| Static observation | Exact anchor | Interpretation and limit |
|---|---|---|
| A Tripper-specific metadata lookup passes `RE_FOTA_TRIPPER` to a data accessor and deserializes a Firestore document into `bluconnect.mkf` | `classes8.dex`, `bluconnect.tw6.e0`, string instruction at file offset `0x20d43a`; model deserialization at `0x20d488` | More specific than a generic OTA library, but no live document or usable image was retrieved |
| A coroutine calls that lookup, casts its result to `mkf`, and emits it in a state object | `classes8.dex`, `bluconnect.uw6$y.invokeSuspend`, `0x21170e` through `0x21177e` | Static metadata-to-state path; does not prove update availability for this particular Pod |
| Another `REFotaTripper` model retains checksum, base version, device type, region, vehicle variant, firmware URL and mapping URL fields | `classes8.dex`, `bluconnect.nkf`, constructor starting `0x30ef38`; named fields/getters | A schema, not actual firmware contents, download authorization or a verified signature policy |
| A navigation view-model builds a firmware-download request using device type, region, vehicle variant and current FOTA version | `classes8.dex`, `NavigationRootFragmentViewModel`, endpoint string at `0x475a0e` | Shared update infrastructure exists; its attribution to the user's Pod remains unresolved |
| The Tripper navigation handler delegates to a byte-array command builder | `classes8.dex`, `NavigationFragment.w7` at `0x42d44c`; `classes5.dex`, `bluconnect.ae1.q` at `0x1bc4ec`, calling `bluconnect.luc.d` at `0x1bc67c` | Structured numeric fields become packet bytes; this path supplies no font, bitmap or layout upload |
| The builder writes command byte `0x10`, packs fields into fixed positions and appends two check bytes | `classes5.dex`, `bluconnect.luc.d` at `0x232b34` | Consistent with a fixed-field navigation protocol; not a complete search of every app or firmware command |

The package also contains an `RECloudInfo` update/download helper with partial S-record download handling (`classes5.dex`, `bluconnect.vh7`). That helper has not been connected end to end to the specific original Pod. Likewise, generic DFU classes are not evidence of this Pod's chip or bootloader.

The four APK member-name inventories contained no obvious Pod firmware file named with `.bin`, `.hex`, `.dfu` or `.img`. This is only a filename search: compressed, differently named or remotely fetched images remain possible. No matching firmware binary was recovered in this pass.

### Evidence retained locally

REA evidence identifiers:

- Package pull: `ev_7249e8755a6fe84e1395a7fc8ba3bb4d353d14313b3a461eb297281debebfc06`.
- Base archive inspection: `ev_2200ff42ad29a39d6d79d4a93c150fb4898237c8e190967eff9b60cc4bada253`.
- Nested inventory: `ev_fd8c414dfbf6c010f89c99211a9f4a3f657e38237966af75ce76d4d151a7f976`.

The local chat workspace retains `work/pod-ui-research/`: APKs, hashes/inventory summaries, DEX inspection scripts, candidate bytecode and exact class/offset anchors. These proprietary artifacts and disassembly are not committed or redistributed. REA archive coverage does not amount to APK signature verification. The active target was unavailable at the final close attempt; no saved REA session snapshot was produced.

## What would make a stock-firmware redesign concrete

1. Identify a firmware image and metadata explicitly matching the original Pod's hardware and version. Trace the app's Tripper state into its actual downloader/updater, rather than assuming shared OTA code applies.
2. Inspect that image offline for command handlers, graphics/font storage, display driver and image integrity/signature checks. Establish whether unused existing commands solve the desired changes before proposing replacement firmware.
3. Establish the update format, acceptance rules and a recoverable backup/restore procedure. A checksum field alone does not establish whether firmware is signed, encrypted or downgrade-protected.
4. If software acquisition cannot provide an image, hardware identification/readout would require a separate physical investigation, preferably on a spare Pod. Chip, debug interface and read protection are currently unknown.

For the current app, continue using the demonstrated maneuver distinctions and the already-installed one-visit field kit. Its remaining photos can establish roundabout shapes, night mode and approach emphasis. No additional app build/install was needed for this research. Custom screen layout, arbitrary graphics and stock firmware replacement remain unresolved; they should not be advertised as implemented.
