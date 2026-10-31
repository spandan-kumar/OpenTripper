# Offline Pod firmware workbench

Python 3.10 or newer, standard library only. These tools inspect local files; the Android app is unchanged.

```sh
python3 tools/pod_firmware.py inspect IMAGE.srec --json
python3 tools/pod_firmware.py plan IMAGE.srec --segment-size 128 --json
python3 tools/pod_firmware.py plan IMAGE.srec --segment-size 128 --mapping MAPPING.txt --include-packets --json
python3 -m unittest discover -s tools -p 'test_*.py' -v
```

`IMAGE.srec` and `MAPPING.txt` must be supplied separately. No stock firmware is bundled. The segment size of 128 above is an illustrative input, not a measured Hunter parameter; the real updater obtains it from the Pod. The tool neither probes for parameters nor connects to Bluetooth, a serial interface, a backend or a motorcycle.

`inspect` validates S-record byte counts/checksums, address widths, record counts, termination and overlapping data. It preserves sparse ranges and reports file/data hashes, address ranges, ASCII strings and explicitly inferred Cortex-M vector plausibility. It does not authenticate firmware or identify a compatible Pod.

`plan` models the [reconstructed official host format](../docs/POD_OTA_PROTOCOL.md). It requires 4096-byte blocks, contiguous S3 records in ascending order and an explicit segment size. It rejects unsupported input rather than copying the official parser's unchecked concatenation/address behavior. Optional mapping selects ranges of full-image blocks; its first field does not remap their addresses. It rejects unknown block starts, overruns and duplicate selections.

`--include-packets` emits a deterministic packet transcript only. Actual transfers are gated by application acknowledgements; the JSON must not be treated as a burst of frames to transmit. The transcript includes update-start and commit bytes but has no means to send them. The planner reproduces the host's observed zero-length last segment on exact multiples and hashes actual block bytes without invented padding. It is not a complete simulator of the Pod bootloader or every host parser quirk.

Tests use synthetic bytes only, including a plausible Cortex-M vector table. Passing them verifies the inspector/planner against those fixtures, not successful firmware extraction, BLE interoperability or custom-firmware installation. A real OTA application image may omit the bootloader, calibration and recovery data; it is not automatically a complete backup.
