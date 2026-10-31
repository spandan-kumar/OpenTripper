# Stock Pod firmware and hardware investigation

31 October–1 November 2026. Continues [the UI research](POD_UI_RESEARCH.md). Target: original small Tripper Pod, not Tripper Dash. No replacement firmware or changed Pod layout has been built, installed or demonstrated. The latest [dealer-tool inspection](#public-dealer-tool-found-and-inspected) identifies DOL software and Visteon cluster components, but does not establish small-Pod coverage.

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

## Service-centre updates and the Hunter USB-C socket

Follow-up, 1 November 2026 (research began 31 October). The user clarified that the USB-C port is the **separate phone-charging socket near the handlebar**, not a socket on the Pod casing.

### The separate charger is not an established Pod data connection

Royal Enfield's [Hunter rider guide](https://www.royalenfield.com/in/en/support/digital-quickstart/hunter-350/rider/) describes the charger/fast charger and states that no data transfer is enabled. The [earlier Hunter dual-channel owner's manual](https://www.royalenfield.com/content/dam/royal-enfield/hunter-350/owners-manual/hunter-350-dual-channel.pdf), printed pages 43-44 (PDF pages 45-46), locates the socket under the left switch and makes the same charging-only statement. That older manual describes Type-A; the newer [Hunter specification](https://www.royalenfield.com/content/dam/open-pdf/royal-enfield-hunter-350-technical-specifications-new.pdf) identifies Type-C fast charging. Do not transfer the old manual's charging rate or connector type to the user's Type-C unit. The current guide combines charging descriptions from different revisions; the exact physical charger circuit is uninspected.

Having physical D+/D- contacts does not establish USB data communication. [TI's USB-C charging application note SLVA768](https://www.ti.com/lit/an/slva768/slva768.pdf), page 2, explains that dedicated charging ports can use these lines for electrical charging signatures without a USB data connection. This is a general explanation, not an identification of TI silicon inside this Hunter. Charge negotiation also does not establish a link to the Tripper processor.

No wiring, USB enumeration, VID/PID or data trace from this user's socket has been collected. It is therefore unsupported to describe it as an ADB, DFU, UART or Pod service port. Based on the manufacturer's declared function, it is a low-priority firmware entry point.

The [manufacturer's P4A/B OBD2B schematic](https://www.royalenfield.com/node/assets/WiringManual/code48/P4A_Schematic_Diagram_EuroV.pdf) was downloaded and visually inspected as a comparator. Its navigation module has battery positive, ground and ignition input. The USB chargers, main instrument cluster and diagnostic connector are separate; the CAN pair belongs to the main cluster rather than the navigation module. This is a different original-Pod-equipped model, not a verified Hunter loom or a pinout for the user's bike. Local source SHA-256: `77758f51fcd107dba862c09e136cd96b29be8b7306e6aac0d004dc04e908a9c2`, 197,677 bytes. It supports keeping power sockets, main-cluster diagnostics and Pod programming distinct.

### What the official app actually says about a service-centre update

The inspected app contains more than a generic service-centre string. Its Tripper updater has this static decision path:

```text
if available_firmware_version > installed_version:
    if firmware_base_version > installed_version:
        report ineligible; direct user to service centre
    else:
        report eligible for phone update
else:
    report no update available
```

Evidence: `classes5.dex`, `bluconnect.vh7.e`, entry `0x1f947c`, service message at `0x1f9502`; `vh7.w` at `0x1f8fd0`, `vh7.t` at `0x1f8f24`, shared numeric comparator `vh7.f/g` at `0x53e8cc`. The `RECloudInfo` constructor/getters and the `ccb$b$a` metadata mapping connect those values to `firmwareVersion` and `firmwareBaseVersion`. The comparator parses numeric doubles; the sketch above describes the successful comparison path, not malformed/null-input handling. This branch was not exercised with the Hunter connected.

This establishes a minimum-base-version gate and an intended service-centre fallback. It does **not** reveal whether a technician uses a dealer app, a different BLE bootloader, an external connector, internal pads, replacement hardware or some combination. No original-Pod dealer update procedure, confirmed flashing tool or dealer connector pinout was recovered in the reviewed sources. Earlier rider comments merely suggested asking a service centre and are not evidence of a completed cable update.

The public [NACS-II ROYAL user manual (2019)](https://royalenfield-service.com/RoyalEnfield/Home/downloads/5d955fe6-6cc4-4da7-af4b-17d05abf7ff2) has a relevant-looking firmware-upgrade chapter on printed pages 25-28. The steps target the NACS interface itself: PC USB connection, interface icon, and the interface's hardware button. Its diagnosis examples cover engine/ABS systems. This predates the Pod and does not document its flashing; absence from this old manual also cannot exclude later dealer support. A located [DOL cluster country-selection procedure](https://www.manualslib.com/manual/4078448/Royal-Enfield-Himalayan-Euro-V-2023.html?page=574) explicitly names Himalayan Tripper Dash, so it is not evidence for the small Hunter Pod.

### USB and alternative interfaces checked

- In the ten-DEX official APK, `UsbManager` appears in one generic Android system-service lookup table (`bluconnect.qu3$h`, `classes3.dex`, `0x2e4914`). No `UsbDevice`, `UsbDeviceConnection`, common USB-serial library or FTDI-package references were found. The six reviewed original-Pod connection/updater classes contain no Android USB API references. This bounded static result supports BLE as the identified phone-update transport; it does not inventory unpublished dealer software, reflection or every native library.
- The likely KW36A-family candidate exposes SWD, LPUART, SPI and I2C in [NXP's datasheet](https://www.nxp.com/docs/en/data-sheet/MKW36A512.pdf). An [NXP employee's KW36 bootloader explanation](https://community.nxp.com/t5/Kinetis-Microcontrollers/MCUBOOT-for-KW36-MCU/td-p/805073) lists LPUART/I2C/SPI/CAN for NXP's flash-resident implementation. That does not establish that Visteon installed this implementation or enabled any of those transports. No native USB controller is listed for this candidate family; an external USB bridge would be a separate component and is not identified here.
- The FCC test report's USB-to-TTL auxiliary equipment is a UART investigation lead. Its appearance in test equipment does not establish a customer-accessible UART, service flashing workflow or pinout.
- The photographed `MICRO` clock/data pad group remains a more concrete physical lead than the charging socket, subject to part/revision identification, continuity checks and read-protection status.

### What dealer evidence would resolve next

The useful missing evidence is a demonstrated update of the **small `RE_DISP` Pod**: the actual tool/app name, firmware version before/after, and whether the technician uses Bluetooth or connects a cable directly to the Pod. If a cable is used, photographs of both ends and the connector it enters would distinguish it from the motorcycle diagnostic socket. A USB cable from a laptop into a diagnostic interface is not itself evidence of USB reaching the Pod.

For UI work, prefer an original firmware file/backup and verified access to the Pod itself. Installing an official update alone would not establish acceptance of a modified image or provide a firmware read-back command. No port test, enumeration, wiring change or flashing was performed in this follow-up.

## Public dealer tool found and inspected

Further search, 1 November 2026, found a concrete manufacturer procedure and downloadable dealer package. This advances the dealer-tool question without resolving which tool, if any, services the original Pod.

### Confirmed service workflow: RE DOL

[Royal Enfield SOP 38, hosted by NHTSA](https://static.nhtsa.gov/odi/tsbs/2026/MC-11028676-0001.pdf), released 9 May 2024 and circulated 24 May, names **ROYAL ENFIELD DOL V0_17_BETA26**. Its target is the throttle-body ECU for P0106/P0196 faults on Meteor/Classic/Hunter/Bullet 350. It describes laptop USB to the DOL interface, DOL connection to the motorcycle, distributor activation of the interface serial number for online flashing, and model selection followed by EMS/FLASH. This establishes an actual dealer ECU workflow; it supplies no Pod connector pinout or Pod update procedure.

The document links a publicly downloadable [DOL installer](https://drive.google.com/file/d/1PNaCvEJga2l7_1fFxWIat_HI56vW-rp2/view). It was retrieved and inspected offline:

```text
ROYAL_ENFIELD_DOL-V0_17_Beta26_install.exe
32,399,696 bytes
SHA-256 e35d1fba078be20e56c2141d12b881ea29dd5dc1353e0a54ee660c030dbc7ae9
Inner ROYAL_ENFIELD_DOL_setup.exe: 14,515,345 bytes
SHA-256 19920016b7fef04a25dc31c4aed18e4389084a58a23164ebe47a1782b32591be
```

The outer GEA package contains the application setup, USB-driver installer, LabVIEW runtime installer, MSXML installer and a batch file. Its application setup contains **950 members**. All 950 were extracted with their stored member CRCs verified; SHA-256 identities were retained and rechecked. CRC agreement checks extraction consistency, not authenticity or safety. No vendor executable, DLL, batch file or LabVIEW VI was executed or installed. The archive parser/decoder ran locally against file bytes, using the [upstream Gentee format/source](https://github.com/gentee/c-gentee/tree/f231d698ef7ed54406888619427db7432dccec0e) with a local 64-bit adaptation. REA separately inventoried the outer PE resources; that result is not the evidence for application behavior.

### DOL contains Visteon cluster programming, but Pod support is unproven

| Packaged observation | Bound on the conclusion |
|---|---|
| `RE_VISTEON_Programming.vi` contains a Visteon cluster-programming title, `VISTEON_P3K`/`VISTEON_P4D` selectors and a `VISTEON_FLASH_Procedure.vi` reference | DOL includes cluster-related components beyond the ECU SOP. A name/reference is not a demonstrated executable path or compatible Pod target |
| The Visteon files contain `CANID` fields; the vehicle database contains P3K/P4D I-cluster UDS identifiers | Consistent with instrument-cluster diagnostics; does not establish a CAN connection to the separate navigation Pod |
| Generic Bluetooth libraries and parameter fields also exist | Does not establish use of the Pod's BLE service or an original-Pod updater |
| All 950 member payloads were searched for `Tripper`, `JDCP`, `RE_DISP` and the original Pod UART-service UUID as ASCII, UTF-16LE and UTF-16BE; zero matches | A bounded string search in this package/version, not proof that DOL or later/private dealer tools can never support the Pod |
| No member filename ends in `.bin`, `.hex`, `.mot`, `.s19`, `.s28`, `.s37` or `.srec` | No obvious standalone firmware image in this package; embedded, compressed or server-delivered images remain possible |

The selected file hashes, exact string offsets, extraction counts, source links and limitations are recorded in [the evidence summary](evidence/dealer-dol-2026-11-01.json). Vendor installers and extracted application files remain local; they are not committed. No dealer activation, backend authentication or firmware request was attempted through DOL.

### Owner reports do not fill the transport gap

- [A 2024 original-Pod report](https://www.reddit.com/r/royalenfield/comments/1dmizel/cannot_update_tripper_pod/) shows `RE_DISP` settings whose update controls do nothing. A proposed service visit has no documented successful update; a later reply reports replacing the Pod.
- [A V07.05-to-V12.10 question](https://www.reddit.com/r/royalenfield/comments/1tlhzjr/its_possible_to_update_the_tripper_pod_firmware/) receives speculative service-centre advice, without a completed update or tool/cable identification.
- [The Meteor “Software Update” thread](https://forum.classicmotorworks.com/index.php?topic=33329.0) describes a completed **ECU** update for the engine warning light. A later comment mentions Tripper V07.05, which makes snippets misleading; it does not document a Pod update. Direct fetch returned 403; indexed thread text was reviewed.
- [RE's original-Pod FAQ](https://www.royalenfield.com/in/en/motorcycles/tripper/) recommends regular updates but supplies no dealer-tool identity or physical update procedure.

**Status:** the dealer software is identified and its package inspected. The original Pod's actual dealer flashing method remains unresolved. The manufacturer app's BLE updater is still the identified phone-side firmware transport; its intended service-centre fallback must not be promoted into a verified cable procedure. The separate Hunter charging socket remains unsupported as a data entry point. For a redesigned stock UI, a matching Pod firmware image/backup and a recoverable write path are still missing.
