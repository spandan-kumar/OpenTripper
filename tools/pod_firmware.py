#!/usr/bin/env python3
"""Offline S-record inspector and reconstructed Tripper Pod OTA planner.

This tool has no network, Bluetooth, serial, or flash transport. A plan is a
simulation of the Android host format, not proof a Pod accepts an image.
"""
from __future__ import annotations

import argparse
import binascii
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import struct
import sys


class FirmwareError(ValueError):
    """Malformed input or a plan outside the reconstructed compatibility path."""


@dataclass(frozen=True)
class Record:
    kind: int
    address: int
    data: bytes
    line: int


@dataclass(frozen=True)
class Segment:
    address: int
    data: bytes


@dataclass(frozen=True)
class Image:
    sha256: str
    records: tuple[Record, ...]
    segments: tuple[Segment, ...]
    entrypoint: int | None


ADDRESS_BYTES = {0: 2, 1: 2, 2: 3, 3: 4, 5: 2, 6: 3, 7: 4, 8: 3, 9: 2}
DATA_TYPES = {1, 2, 3}
LIMIT = 64 * 1024 * 1024


def parse_srec(raw: bytes) -> Image:
    """Validate S-records while preserving sparse address ranges and file order.

    Reject every overlap (including identical duplicate data), invalid counts,
    unsupported record types, bad checksums and data following termination.
    """
    if len(raw) > LIMIT:
        raise FirmwareError("input exceeds 64 MiB inspection limit")
    try:
        text = raw.decode("ascii")
    except UnicodeDecodeError as exc:
        raise FirmwareError("S-record input must be ASCII text") from exc
    records: list[Record] = []
    data_count = 0
    termination = False
    entrypoint = None
    count_seen = False
    for line_number, line in enumerate(text.splitlines(), 1):
        line = line.strip()
        if not line:
            continue
        if termination:
            raise FirmwareError(f"line {line_number}: record after termination")
        if not re.fullmatch(r"S[0-9][0-9A-Fa-f]+", line):
            raise FirmwareError(f"line {line_number}: invalid S-record syntax")
        kind = int(line[1])
        if kind not in ADDRESS_BYTES:
            raise FirmwareError(f"line {line_number}: unsupported S{kind} record")
        try:
            payload = bytes.fromhex(line[2:])
        except ValueError as exc:
            raise FirmwareError(f"line {line_number}: invalid hexadecimal length") from exc
        width = ADDRESS_BYTES[kind]
        if not payload or payload[0] != len(payload) - 1:
            raise FirmwareError(f"line {line_number}: byte count mismatch")
        if payload[0] < width + 1:
            raise FirmwareError(f"line {line_number}: truncated address/checksum")
        if sum(payload) & 0xFF != 0xFF:
            raise FirmwareError(f"line {line_number}: checksum mismatch")
        address = int.from_bytes(payload[1:1 + width], "big")
        data = payload[1 + width:-1]
        if kind in DATA_TYPES:
            if count_seen:
                raise FirmwareError(f"line {line_number}: data after count record")
            if not data:
                raise FirmwareError(f"line {line_number}: empty data record")
            if address + len(data) > 1 << (width * 8):
                raise FirmwareError(f"line {line_number}: data exceeds address width")
            data_count += 1
        elif kind in (5, 6):
            if data or count_seen:
                raise FirmwareError(f"line {line_number}: invalid/repeated count record")
            if address != data_count:
                raise FirmwareError(f"line {line_number}: data-record count mismatch")
            count_seen = True
        elif kind in (7, 8, 9):
            if data:
                raise FirmwareError(f"line {line_number}: termination carries data")
            entrypoint = address
            termination = True
        records.append(Record(kind, address, data, line_number))
    data_records = sorted((r for r in records if r.kind in DATA_TYPES),
                          key=lambda record: record.address)
    if not data_records:
        raise FirmwareError("no data records")
    segments: list[Segment] = []
    start = data_records[0].address
    contents = bytearray()
    for record in data_records:
        end = start + len(contents)
        if record.address < end:
            raise FirmwareError(f"line {record.line}: overlapping data at 0x{record.address:x}")
        if record.address > end:
            segments.append(Segment(start, bytes(contents)))
            start, contents = record.address, bytearray()
        contents.extend(record.data)
    segments.append(Segment(start, bytes(contents)))
    return Image(hashlib.sha256(raw).hexdigest(), tuple(records), tuple(segments), entrypoint)


def inspect_image(image: Image, min_string: int = 5, max_strings: int = 100) -> dict:
    result = {
        "mode": "offline_inspection", "source_sha256": image.sha256,
        "data_bytes": sum(len(s.data) for s in image.segments),
        "data_record_types": sorted({r.kind for r in image.records if r.kind in DATA_TYPES}),
        "entrypoint": image.entrypoint,
        "segments": [{"address": s.address, "length": len(s.data),
                      "end_exclusive": s.address + len(s.data),
                      "sha256": hashlib.sha256(s.data).hexdigest()} for s in image.segments],
        "vector_candidates": [], "ascii_strings": [],
        "limitations": ["No firmware authenticity, device compatibility or signature validation.",
                        "Vector-table plausibility is an inference, not MCU identification.",
                        "Sparse gaps remain absent; no guessed fill bytes are inserted."],
    }
    if min_string < 1 or max_strings < 0:
        raise FirmwareError("string length must be positive and string limit nonnegative")
    expression = re.compile(rb"[\x20-\x7e]{%d,}" % min_string)
    for segment in image.segments:
        if len(segment.data) >= 8:
            sp, reset = struct.unpack_from("<II", segment.data)
            thumb = bool(reset & 1)
            reset_present = any(s.address <= (reset & ~1) < s.address + len(s.data)
                                for s in image.segments)
            # Includes common Kinetis SRAM below 0x20000000. It is deliberately
            # broad and cannot establish the actual target's RAM or boot address.
            ram_candidate = 0x1FFF0000 <= sp < 0x40000000 and sp % 8 == 0
            result["vector_candidates"].append({
                "address": segment.address, "initial_sp": sp, "reset_word": reset,
                "thumb_bit": thumb, "reset_inside_image": reset_present,
                "common_ram_range_and_alignment": ram_candidate,
                "plausible": ram_candidate and thumb and reset_present,
                "evidence_type": "inference",
            })
        for match in expression.finditer(segment.data):
            if len(result["ascii_strings"]) >= max_strings:
                break
            result["ascii_strings"].append({"address": segment.address + match.start(),
                                            "text": match.group().decode("ascii")})
    return result


def crc16(data: bytes) -> bytes:
    """CCITT-FALSE, initial FFFF, polynomial 1021, high byte first."""
    return binascii.crc_hqx(data, 0xFFFF).to_bytes(2, "big")


def frame(body: bytes) -> str:
    return (body + crc16(body)).hex()


def parse_mapping(raw: bytes) -> dict:
    """Parse the fixed columns observed in te1.b, without guessing delimiters."""
    try:
        lines = raw.decode("ascii").splitlines()
    except UnicodeDecodeError as exc:
        raise FirmwareError("mapping must be ASCII") from exc
    if not lines or not re.fullmatch(r"[0-9a-fA-F]{8}", lines[0][:8]):
        raise FirmwareError("mapping header must begin with eight hex characters")
    rows = []
    for number, line in enumerate(lines[1:], 2):
        if len(line) < 32:
            raise FirmwareError(f"mapping line {number}: expected at least 32 columns")
        fields = [line[0:8], line[12:20], line[24:32]]
        if any(not re.fullmatch(r"[0-9a-fA-F]{8}", value) for value in fields):
            raise FirmwareError(f"mapping line {number}: invalid hexadecimal field")
        first, address, count = [int(value, 16) for value in fields]
        if not 0 < count <= 0x7FFFFFFF:
            raise FirmwareError(f"mapping line {number}: invalid block count")
        rows.append({"first_field": first, "address": address, "block_count": count})
    if not rows:
        raise FirmwareError("mapping has no selection rows")
    return {"source_sha256": hashlib.sha256(raw).hexdigest(),
            "header": lines[0][:8], "rows": rows,
            "note": "First field/header meaning unverified; selection uses second field and count."}


def plan_update(image: Image, segment_size: int, block_size: int = 4096,
                mapping: dict | None = None, include_packets: bool = False) -> dict:
    """Construct an offline host-format plan, never a transport operation.

    The reviewed host hardcodes 4096 bytes in its block accumulator. Reject
    other sizes and discontinuous/out-of-order/non-S3 data instead of silently
    reproducing its unchecked concatenation/address arithmetic.
    """
    if block_size != 4096:
        raise FirmwareError("compatibility planner requires exactly 4096-byte blocks")
    if not 1 <= segment_size <= 255:
        raise FirmwareError("segment size must fit the observed one-byte field (1..255)")
    ordered = [r for r in image.records if r.kind in DATA_TYPES]
    if any(record.kind != 3 for record in ordered):
        raise FirmwareError("compatibility planner accepts S3 data records only")
    for previous, current in zip(ordered, ordered[1:]):
        if current.address != previous.address + len(previous.data):
            raise FirmwareError("compatibility planner rejects gaps or out-of-order addresses")
    if len(image.segments) != 1:
        raise FirmwareError("compatibility planner requires contiguous data")
    segment = image.segments[0]
    all_blocks = [(segment.address + offset, segment.data[offset:offset + block_size], index)
                  for index, offset in enumerate(range(0, len(segment.data), block_size))]
    blocks = all_blocks
    if mapping is not None:
        blocks = []
        selected = set()
        address_to_index = {address: index for index, (address, _, _) in enumerate(all_blocks)}
        for row in mapping["rows"]:
            if row["address"] not in address_to_index:
                raise FirmwareError(f"mapping address 0x{row['address']:x} is not a block start")
            start = address_to_index[row["address"]]
            stop = start + row["block_count"]
            if stop > len(all_blocks):
                raise FirmwareError("mapping selection extends beyond full-image block list")
            for block in all_blocks[start:stop]:
                if block[2] in selected:
                    raise FirmwareError("mapping selects a block more than once")
                selected.add(block[2])
                blocks.append(block)
    if not blocks or len(blocks) > 0xFFFF:
        raise FirmwareError("selected block count must fit observed unsigned 16-bit field")
    if sum(len(data) // segment_size + 1 for _, data, _ in blocks) > 100000:
        raise FirmwareError("plan exceeds 100,000-segment offline inspection limit")
    result = {
        "mode": "offline_simulation_only", "source_sha256": image.sha256,
        "block_size": block_size, "segment_size": segment_size,
        "minimum_att_mtu_for_largest_frame": 44,
        "full_image_block_count": len(all_blocks), "selected_block_count": len(blocks),
        "mapping": mapping, "blocks": [],
        "limitations": [
            "Reconstructed Android host behavior; no hardware interoperability demonstrated.",
            "Exact-multiple blocks include the host's zero-length final segment quirk.",
            "No device transport, read-back, recovery or flash capability is provided.",
            "No evidence that a modified image is accepted by the Pod bootloader.",
        ],
    }
    packets = []
    if include_packets:
        packets.append({"stage": "start", "hex": frame(bytes([2]) + bytes(17))})
        packets.append({"stage": "parameters", "hex": frame(bytes([4]) + bytes(17))})
        packets.append({"stage": "initialize", "hex": frame(
            bytes([5]) + blocks[0][0].to_bytes(4, "big") + len(blocks).to_bytes(2, "big") + bytes(11))})
    for index, (address, data, full_index) in enumerate(blocks):
        # Inclusive loop mirrors te1.m's observed exact-multiple behavior.
        pieces = [data[offset:offset + segment_size]
                  for offset in range(0, len(data) + 1, segment_size)]
        if len(pieces) > 0xFFFF:
            raise FirmwareError("segment count exceeds observed field width")
        digest = hashlib.sha256(data).digest()
        block_info = {"index": index, "full_image_index": full_index,
                      "address": address, "data_length": len(data),
                      "sha256": digest.hex(), "segment_lengths": [len(p) for p in pieces]}
        result["blocks"].append(block_info)
        result["minimum_att_mtu_for_largest_frame"] = max(
            result["minimum_att_mtu_for_largest_frame"], max(map(len, pieces)) + 15)
        if include_packets:
            block_index = index.to_bytes(2, "big")
            packets.append({"stage": "block_header", "block": index, "hex": frame(
                bytes([6]) + block_index + address.to_bytes(4, "big") +
                len(pieces).to_bytes(2, "big") + bytes(9))})
            for piece_index, piece in enumerate(pieces):
                packets.append({"stage": "segment", "block": index, "segment": piece_index,
                                "hex": frame(bytes([7]) + block_index + piece_index.to_bytes(2, "big") +
                                             bytes([len(piece)]) + bytes(4) + piece)})
            packets.append({"stage": "block_hash", "block": index,
                            "hex": frame(bytes([8]) + block_index + bytes(4) + digest)})
    if include_packets:
        packets.extend([
            {"stage": "file_verify", "hex": frame(bytes([9]) + bytes(17))},
            {"stage": "commit", "hex": frame(bytes([10, 1]) + bytes(16))},
        ])
        result["packets"] = packets
        result["packet_order_note"] = "ACK-gated simulated order; never transmit this JSON automatically."
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    for name in ("inspect", "plan"):
        sub = subparsers.add_parser(name)
        sub.add_argument("image", type=Path, help="local S-record file")
        sub.add_argument("--json", action="store_true", help="emit deterministic JSON")
    inspect_parser = subparsers.choices["inspect"]
    inspect_parser.add_argument("--min-string", type=int, default=5)
    inspect_parser.add_argument("--max-strings", type=int, default=100)
    plan_parser = subparsers.choices["plan"]
    plan_parser.add_argument("--segment-size", type=int, required=True,
                             help="explicit assumed/observed parameter, never auto-probed")
    plan_parser.add_argument("--block-size", type=int, default=4096)
    plan_parser.add_argument("--mapping", type=Path)
    plan_parser.add_argument("--include-packets", action="store_true",
                             help="include offline ACK-gated packet simulation; no transport exists")
    args = parser.parse_args(argv)
    try:
        if args.image.stat().st_size > LIMIT:
            raise FirmwareError("input exceeds 64 MiB inspection limit")
        image = parse_srec(args.image.read_bytes())
        if args.command == "inspect":
            result = inspect_image(image, args.min_string, args.max_strings)
        else:
            mapping = parse_mapping(args.mapping.read_bytes()) if args.mapping else None
            result = plan_update(image, args.segment_size, args.block_size, mapping, args.include_packets)
    except (OSError, FirmwareError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps(result, indent=2, sort_keys=True))
    else:
        print(f"{result['mode']}\nSource SHA-256: {result['source_sha256']}")
        if args.command == "inspect":
            print(f"Data: {result['data_bytes']} bytes in {len(result['segments'])} address ranges")
            for segment in result["segments"]:
                print(f"  0x{segment['address']:08x}..0x{segment['end_exclusive']:08x} (end exclusive)")
            for candidate in result["vector_candidates"]:
                print(f"Vector inference at 0x{candidate['address']:08x}: plausible={candidate['plausible']}")
            for match in result["ascii_strings"]:
                print(f"  string 0x{match['address']:08x}: {match['text']}")
        else:
            print(f"Blocks: {result['selected_block_count']} of {result['full_image_block_count']}; "
                  f"segment size: {result['segment_size']}")
            for block in result["blocks"]:
                print(f"  block {block['index']} at 0x{block['address']:08x}: "
                      f"{block['data_length']} bytes, {len(block['segment_lengths'])} segments")
            if args.include_packets:
                print(json.dumps({"packets": result["packets"]}, indent=2))
        for limitation in result["limitations"]:
            print(f"Limit: {limitation}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
