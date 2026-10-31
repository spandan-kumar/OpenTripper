# Stock Pod firmware and hardware investigation

31 October 2026. Continues [the UI research](POD_UI_RESEARCH.md). Target: original small Tripper Pod, not Tripper Dash. No replacement firmware or changed Pod layout has been built, installed or demonstrated.

## What the supplied internal photographs add

The user supplied the public Visteon JDCP internal-photo exhibit after automated downloads could not provide readable images. Its identity was verified locally:

```text
FCC attachments: 5227608 / 5227630
PDF: 91,273 bytes, two pages, no encryption or JavaScript
SHA-256: bef169a53b96d7b05b2bee3f3c04af206dc31fa010fa8b4650135c9d4a245f4f
Embedded photographs: two JPEGs, each 1240 x 930 pixels
```

Both complete pages were rendered and reviewed. The original JPEGs were extracted, then cropped, rotated and enlarged for inspection. No generative enhancement was used; enlargement cannot recover missing lettering. Original PDF/photos remain local and are not redistributed in this repository. The [public exhibit metadata](https://fccid.io/NT8-JDCP/Internal-Photos/Internal-Photos-5227630) reports the same SHA-256 but a size of 91,274 bytes; the supplied file measures 91,273 bytes. The digest matches, and the size discrepancy is retained rather than silently reconciled.

| Observation | Evidence and confidence | What it does not establish |
|---|---|---|
| PCB reference `PWB25068` | Readable on page 2; observed | The user's Hunter has the same PCB revision |
| A rear pad group labelled `MICRO` with `3.3V`, `GND`, `CLK`, `DIO` nearby | Page 1, rotated for reading; labels observed by the primary reviewer. Other labels are ambiguous | Actual voltage, pad-to-pin continuity, pad numbering or a verified wiring diagram |
| A likely MCU programming/debug connection | `MICRO` plus clock/data/power labels support an SWD interpretation; inference | Debug access is enabled, firmware can be read, or changed firmware can be installed |
| NXP/Freescale-style chip logo and partial `36A`-like marking | Page 2 main chip. An independent reviewer tentatively read `36A` before candidate searching | Complete chip identity or a programming-tool target selection |
| **KW36A-family candidate** | Moderate-confidence inference: partial marking/layout and a possible `1N41U` mask line fit NXP's documentation | Exact part/package: VHT4, VFP4 and VFT4 remain unresolved; the mask spans multiple related products |
| A second sizeable IC, reference `U2`, and a display connector, `J1` | Page 2, observed | U2's identity/capacity, an external flash pinout, or the LCD controller/model |

The candidate comparison used the [NXP MKW36A/35A/34A datasheet](https://www.nxp.com/docs/en/data-sheet/MKW36A512.pdf), orderable-part marking table on page 2; [AN12181](https://www.nxp.com/docs/en/application-note/AN12181.pdf), marking examples on pages 7 and 10; and [mask 1N41U errata](https://www.nxp.com/docs/en/errata/KINETIS_W_1N41U.pdf), page 1. None uniquely identifies this photographed part from the mask alone. Initial comparisons to KW41 documentation were exploratory, not a chip identification; the stronger current candidate is KW36A.

## Requested filing page

The exact requested URL, [device.report/fccid/nt8-jdcp](https://device.report/fccid/nt8-jdcp), failed direct opening in both available web tools. Its accessible [nonhyphenated index](https://device.report/fccid/nt8jdcp) identifies the same Visteon JDCP and links label and test exhibits. No evidence that the spelling difference denotes a hardware revision was found.

The [FCC index](https://fccid.io/NT8-JDCP) explicitly marks **Block Diagram**, **Schematics**, **Operational Description** and the antenna evaluation document as **metadata only**. Public photos and reports are available; those circuit documents do not have public download links in the reviewed index. The index records long-term confidentiality. The body of the separate confidentiality request was not retrieved, so its precise scope is not inferred from its title.

The manufacturer-submitted [DTS report, attachment 5227624](https://fccid.io/NT8-JDCP/Test-Report/Test-Report-DTS-5227624), describes a 1.49-inch TFT and FOTA on pages 9-10, with tested HW `PWB25068` and SW `V07.05`. Its statement about software updates or image changes via FOTA is ambiguous: "image" could mean a firmware image. It does not establish a standalone bitmap-upload API. These filing samples do not determine the user's firmware revision.

## The official app updater is now traced further

Same locally pulled official APK as the earlier report: `com.royalenfield.reprime`, version `10.1.23(45)`, base APK SHA-256 `d08478e8c564624503b440834a40499cd4acdc1c8572e49fe1b4830472ad37a4`.

Because REA's Android provider still lacks its required JADX JAR, the deeper work used Android SDK `dexdump`, a local DEX index and ordinary shell analysis. All ten DEX files were indexed, containing 87,717 class definitions. This broadens static lookup coverage; it does not recover reflection or prove every runtime path. The disassembly/APKs and local index remain outside this repository.

The static chain is connected beyond the previous metadata-only lead:

```text
Firestore RE_FOTA_TRIPPER
  -> tw6.e0 -> uw6$y -> bi7/mkf
  -> ccb$b$a: firmware URL, mapping URL, version, full/partial type
  -> RECloudInfo -> navigation fragment f
  -> DeviceSettingsActivity -> vh7 eligibility/downloader
  -> xwd / te1 S-record block handling and BLE transfer
```

Exact anchors in this APK:

| Finding | DEX/class/location | Limit |
|---|---|---|
| Metadata converted into full/partial `RECloudInfo`, with firmware and mapping URLs | `classes8.dex`, `ccb$b$a`, `0x29953a`-`0x299684` | Does not supply an actual URL when the lookup fails |
| Device settings obtains `RECloudInfo` and invokes eligibility checks | `classes5.dex`, `DeviceSettingsActivity.onCreate`, `0x52c09e`-`0x52c0d0` | Static behavior; eligibility was not tested with the Hunter connected |
| Downloader writes app external files and distinguishes full/partial updates | `classes5.dex`, `vh7.k`, `0x1f9b58`; `vh7.i/j/h` | No matching image downloaded |
| Full file parsed as S-record data; partial mode also applies a mapping file | `classes5.dex`, `xwd.b` at `0x2495cc`; `te1.c/e/f` at `0x1bcc20`, `0x1bcc58`, `0x1bcec8` | Reviewed parser explicitly handles S3 records; do not assume arbitrary binary support |
| UART service/characteristic UUIDs match the original Pod path | `classes5.dex`, `zs7` initialization at `0x1faf94` | Does not justify substituting NXP's different stock OTAP service |
| Block data is hashed with unkeyed SHA-256 | `classes5.dex`, `pe1.g` at `0x1bd774`, `te1.h` at `0x1bca98` | A hash is not a signature; device-side checks remain unknown |
| Reviewed update commands use CRC-bearing packets, including a 41-byte command carrying a 32-byte hash | `classes5.dex`, `te1.p` at `0x1bd4e4`, `f22.a` at `0x1c7308` | Static protocol evidence only; no update packet was transmitted |

The 20-byte navigation format is therefore not evidence that the Pod cannot receive larger firmware transfers. Neither these host hashes nor a completed transfer would establish that its bootloader accepts modified firmware.

## Actual firmware acquisition attempt and blocker

This follow-up performed normal read-only acquisition attempts:

- An unauthenticated read of the exact `RE_FOTA_TRIPPER` collection in the app's default project returned HTTP 403, `PERMISSION_DENIED`.
- The installed official app was opened normally on the Pixel using its existing signed-in state. Its Manage Devices flow made the Tripper lookup. A focused app log confirmed the same Firestore permission denial and an `REFotaTripper` result whose firmware/mapping URLs and versions were null. This is runtime evidence, beyond our external request. The cause could be backend rules, account/region configuration or app configuration; it has not been diagnosed as a universal RE outage.
- The app's external-files directory search, depth two, found zero cached files. A content check of non-media APK assets/raw resources up to 5 MB per entry found no S-record text. No firmware URL literal was found in the ten DEX string tables.

No access restriction was bypassed, account credential extracted, firmware installed, new APK installed or Pod update command sent. The ordinary app lookup is an evidenced acquisition failure; turning on the bike is not presently shown to fix that server denial.

## Concrete route to a redesigned UI

1. **Identify the actual part and board revision.** The public photo narrows the MCU family and exposes candidate debug access. A readable marking and electrical verification must precede a probe connection. The photograph is not a wiring guide.
2. **Obtain an original firmware backup.** A matching legitimate update file remains useful. If that path stays unavailable, a verified debug connection on a matching unit could check identification/security and read firmware if permitted. Actual read protection is unknown.
3. **Preserve the original firmware.** Debug tools can offer destructive unlock behavior. For example, [pyOCD documents automatic unlock/mass erase](https://pyocd.io/docs/options.html). A preservation attempt must explicitly disable such behavior and stop if readout is protected; erasing is not a way to obtain a backup. No probe commands or target configuration are supplied here because the exact part and pad wiring remain unresolved.
4. **Locate UI code/assets offline.** Inspect a recovered image for the display driver, icon/font tables, layout code and integrity checks. U2 might be relevant storage, but its function is currently unidentified.
5. **Only then establish a recoverable modification path.** Create an offline patch and a backup/restore procedure before considering installation. Device-side validation, bootloader protections and display-controller details remain open.

This investigation now has a plausible hardware acquisition route despite the failed cloud lookup. It has not yet demonstrated changing stock layouts, fonts, colours or arbitrary graphics. No additional app build is required for the findings above.
