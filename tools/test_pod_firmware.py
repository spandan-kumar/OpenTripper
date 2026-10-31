"""Tests use synthetic S-record data only; none is stock or recovered firmware."""
import binascii
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pod_firmware as pf


def record(kind, address, data=b""):
    payload = address.to_bytes(pf.ADDRESS_BYTES[kind], "big") + data
    count = len(payload) + 1
    return "S" + str(kind) + (bytes([count]) + payload + bytes([~(count + sum(payload)) & 255])).hex().upper()


def synthetic_image(size=256, base=0x2000):
    data = bytearray((index * 17) & 255 for index in range(size))
    if size >= 80:
        struct.pack_into("<II", data, 0, 0x20008000, base + 0x41)
        data[32:48] = b"SYNTHETIC IMAGE!"
    lines = [record(0, 0, b"SYNTHETIC TEST ONLY")]
    lines += [record(3, base + offset, bytes(data[offset:offset + 32]))
              for offset in range(0, size, 32)]
    lines += [record(5, len(lines) - 1), record(7, base + 0x41)]
    return ("\n".join(lines) + "\n").encode("ascii"), bytes(data)


def mapping_row(first, address, count):
    return f"{first:08X}    {address:08X}    {count:08X}"


class SRecordTests(unittest.TestCase):
    def test_valid_synthetic_vectors_and_source_identity(self):
        raw, data = synthetic_image()
        image = pf.parse_srec(raw)
        result = pf.inspect_image(image)
        self.assertEqual(image.sha256, hashlib.sha256(raw).hexdigest())
        self.assertEqual(image.segments, (pf.Segment(0x2000, data),))
        self.assertTrue(result["vector_candidates"][0]["plausible"])
        self.assertEqual(result["vector_candidates"][0]["evidence_type"], "inference")
        self.assertTrue(any("SYNTHETIC" in item["text"] for item in result["ascii_strings"]))

    def test_checksum_corruption_rejected(self):
        raw, _ = synthetic_image()
        lines = raw.decode().splitlines()
        lines[1] = lines[1][:-2] + ("00" if lines[1][-2:] != "00" else "01")
        with self.assertRaisesRegex(pf.FirmwareError, "checksum"):
            pf.parse_srec("\n".join(lines).encode())

    def test_byte_count_is_independent_of_checksum(self):
        with self.assertRaisesRegex(pf.FirmwareError, "byte count"):
            # Checksum still sums to FF; declared count 09 exceeds actual 08.
            pf.parse_srec(b"S30900002000010203D0\n")

    def test_sparse_ranges_preserved_and_plan_rejected(self):
        raw = (record(3, 0x2000, b"ABCD") + "\n" + record(3, 0x3000, b"EFGH")).encode()
        image = pf.parse_srec(raw)
        self.assertEqual([segment.address for segment in image.segments], [0x2000, 0x3000])
        self.assertEqual(sum(len(s.data) for s in image.segments), 8)
        with self.assertRaisesRegex(pf.FirmwareError, "gaps"):
            pf.plan_update(image, 16)

    def test_any_overlap_rejected_including_identical_duplicate(self):
        for address in (0x2000, 0x2002):
            with self.subTest(address=address):
                raw = (record(3, 0x2000, b"AAAA") + "\n" + record(3, address, b"AAAA")).encode()
                with self.assertRaisesRegex(pf.FirmwareError, "overlap"):
                    pf.parse_srec(raw)

    def test_general_inspector_accepts_s1_s2_but_compatibility_requires_s3(self):
        for kind in (1, 2):
            with self.subTest(kind=kind):
                image = pf.parse_srec(record(kind, 0x2000, b"HELLO").encode())
                self.assertEqual(image.segments[0].data, b"HELLO")
                with self.assertRaisesRegex(pf.FirmwareError, "S3"):
                    pf.plan_update(image, 16)

    def test_all_supported_address_widths_and_s6_count(self):
        for kind, terminator in ((1, 9), (2, 8), (3, 7)):
            with self.subTest(kind=kind):
                raw = "\n".join([record(kind, 0x2000, b"ABCD"),
                                  record(6, 1), record(terminator, 0x2001)]).encode()
                self.assertEqual(pf.parse_srec(raw).entrypoint, 0x2001)

    def test_repeated_count_and_data_after_count_rejected(self):
        beginning = record(3, 0x2000, b"A") + "\n" + record(5, 1) + "\n"
        for suffix in (record(6, 1), record(3, 0x2001, b"B")):
            with self.subTest(suffix=suffix), self.assertRaises(pf.FirmwareError):
                pf.parse_srec((beginning + suffix).encode())

    def test_count_record_mismatch_and_data_after_termination(self):
        for suffix, error in [(record(5, 2), "count mismatch"),
                              (record(7, 0x2001) + "\n" + record(3, 0x2004, b"B"), "after termination")]:
            with self.subTest(error=error):
                with self.assertRaisesRegex(pf.FirmwareError, error):
                    pf.parse_srec((record(3, 0x2000, b"A") + "\n" + suffix).encode())

    def test_out_of_order_data_is_inspectable_but_not_compatible(self):
        image = pf.parse_srec((record(3, 0x2004, b"BBBB") + "\n" + record(3, 0x2000, b"AAAA")).encode())
        self.assertEqual(image.segments[0].data, b"AAAABBBB")
        with self.assertRaisesRegex(pf.FirmwareError, "out-of-order"):
            pf.plan_update(image, 16)

    def test_address_overflow_and_reserved_record_rejected(self):
        with self.assertRaisesRegex(pf.FirmwareError, "address width"):
            pf.parse_srec(record(3, 0xFFFFFFFF, b"XX").encode())
        with self.assertRaisesRegex(pf.FirmwareError, "unsupported S4"):
            pf.parse_srec(b"S4030000FC")


class OfflinePlanTests(unittest.TestCase):
    def test_crc_known_vector_and_version_query_frame(self):
        self.assertEqual(pf.crc16(b"123456789").hex(), "29b1")
        self.assertEqual(pf.frame(bytes([3]) + bytes(17)), "03000000000000000000000000000000000045d9")

    def test_exact_multiple_emits_zero_length_tail_and_hashes_actual_data(self):
        raw, data = synthetic_image(4096)
        plan = pf.plan_update(pf.parse_srec(raw), 128, include_packets=True)
        self.assertEqual(plan["blocks"][0]["segment_lengths"], [128] * 32 + [0])
        self.assertEqual(plan["blocks"][0]["sha256"], hashlib.sha256(data).hexdigest())
        headers = [bytes.fromhex(item["hex"]) for item in plan["packets"] if item["stage"] == "block_header"]
        self.assertEqual(int.from_bytes(headers[0][7:9], "big"), 33)
        for item in plan["packets"]:
            packet = bytes.fromhex(item["hex"])
            self.assertEqual(packet[-2:], binascii.crc_hqx(packet[:-2], 0xFFFF).to_bytes(2, "big"))
        self.assertEqual(len(next(bytes.fromhex(p["hex"]) for p in plan["packets"] if p["stage"] == "block_hash")), 41)

    def test_partial_final_block_has_no_guessed_padding(self):
        raw, data = synthetic_image(4101)
        plan = pf.plan_update(pf.parse_srec(raw), 128)
        self.assertEqual([b["data_length"] for b in plan["blocks"]], [4096, 5])
        self.assertEqual(plan["blocks"][1]["segment_lengths"], [5])
        self.assertEqual(plan["blocks"][1]["sha256"], hashlib.sha256(data[4096:]).hexdigest())

    def test_mapping_end_is_exclusive_and_first_field_does_not_remap(self):
        raw, _ = synthetic_image(8193)
        mapping = pf.parse_mapping(("DEADBEEF\n" + mapping_row(0xABC00000, 0x3000, 2)).encode())
        plan = pf.plan_update(pf.parse_srec(raw), 128, mapping=mapping)
        self.assertEqual([b["full_image_index"] for b in plan["blocks"]], [1, 2])
        self.assertEqual([b["address"] for b in plan["blocks"]], [0x3000, 0x4000])
        self.assertEqual([b["index"] for b in plan["blocks"]], [0, 1])
        self.assertEqual(plan["blocks"][-1]["data_length"], 1)

    def test_mapping_overrun_unknown_address_and_duplicate_rejected(self):
        raw, _ = synthetic_image(8193)
        image = pf.parse_srec(raw)
        rows = [(mapping_row(0, 0x4000, 2), "beyond"),
                (mapping_row(0, 0x3001, 1), "not a block start"),
                (mapping_row(0, 0x2000, 1) + "\n" + mapping_row(0, 0x2000, 1), "more than once")]
        for text, error in rows:
            with self.subTest(error=error):
                with self.assertRaisesRegex(pf.FirmwareError, error):
                    pf.plan_update(image, 128, mapping=pf.parse_mapping(("00000000\n" + text).encode()))

    def test_explicit_parameter_constraints(self):
        raw, _ = synthetic_image()
        image = pf.parse_srec(raw)
        for kwargs in ({"segment_size": 0}, {"segment_size": 256},
                       {"segment_size": 16, "block_size": 2048}):
            with self.subTest(kwargs=kwargs), self.assertRaises(pf.FirmwareError):
                pf.plan_update(image, **kwargs)
        for count in (0, 0x80000000):
            with self.assertRaisesRegex(pf.FirmwareError, "block count"):
                pf.parse_mapping(("00000000\n" + mapping_row(0, 0x2000, count)).encode())

    def test_cli_json_and_error_exit_on_local_synthetic_file(self):
        raw, _ = synthetic_image()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "SYNTHETIC_ONLY.srec"
            path.write_bytes(raw)
            command = [sys.executable, str(Path(pf.__file__)), "inspect", str(path), "--json"]
            completed = subprocess.run(command, capture_output=True, text=True, check=True)
            self.assertEqual(json.loads(completed.stdout)["mode"], "offline_inspection")
            path.write_bytes(b"not an S-record")
            failed = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(failed.returncode, 2)
            self.assertIn("invalid S-record", failed.stderr)


if __name__ == "__main__":
    unittest.main()
