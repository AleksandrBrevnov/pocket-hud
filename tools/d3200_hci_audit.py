#!/usr/bin/env python3
"""Privacy-preserving, read-only Soundcore D3200 protocol audit from Android BTSnoop.

Only emits command codes, directions, relative times, payload lengths and a small
allowlist of non-secret status/flag values. Never prints raw packet data, UUIDs,
Bluetooth addresses, file IDs, ECDH public keys, nonces, UID, or Opus audio.

Use with a raw BTSnoop H4 capture or an Android bugreport ZIP. Python 3.9+.
"""
from __future__ import annotations

import argparse
import json
import struct
import sys
import zipfile
from collections import Counter, defaultdict
from contextlib import contextmanager
from pathlib import Path
from typing import BinaryIO, Iterator

BTSNOOP_MAGIC = b"btsnoop\x00"
BTSNOOP_H4 = 1002
RX_MAGIC = b"\x09\xff\x00\x00"
TX_MAGIC = b"\x08\xee\x00\x00\x00"
MAX_RECORD = 1024 * 1024
MAX_FRAME = 65535
D3200_TYPES = {0x01, 0x0B, 0x18, 0x1A, 0x1B, 0x2E}


@contextmanager
def open_capture(path: Path) -> Iterator[BinaryIO]:
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            names = [name for name in archive.namelist()
                     if name.lower().endswith((".log", ".btsnoop"))
                     and "btsnoop" in name.lower()
                     and not name.endswith("/")]
            if not names:
                raise ValueError("Bugreport ZIP has no btsnoop_hci.log; check Developer options")
            names.sort(key=lambda x: ("/fs/" not in x.lower(), -archive.getinfo(x).file_size))
            with archive.open(names[0]) as stream:
                yield stream
    else:
        with path.open("rb") as stream:
            yield stream


def read_records(stream: BinaryIO):
    header = stream.read(16)
    if len(header) != 16 or header[:8] != BTSNOOP_MAGIC:
        raise ValueError("Not a BTSnoop H4 file (embedded btsnooz needs separate decoding)")
    version, datalink = struct.unpack(">II", header[8:])
    if version != 1 or datalink != BTSNOOP_H4:
        raise ValueError(f"Unsupported BTSnoop format: version={version}, datalink={datalink}")
    while True:
        hdr = stream.read(24)
        if not hdr:
            break
        if len(hdr) != 24:
            raise ValueError("Truncated BTSnoop record header")
        original_len, included_len, flags, drops, timestamp = struct.unpack(">IIIIQ", hdr)
        if included_len > MAX_RECORD:
            raise ValueError("Oversized BTSnoop record (capture corrupted?)")
        data = stream.read(included_len)
        if len(data) != included_len:
            raise ValueError("Truncated BTSnoop packet")
        if included_len != original_len:
            continue
        yield flags & 1, timestamp, data


class Audit:
    def __init__(self):
        self.fragments = {}
        self.frames = defaultdict(bytearray)
        self.entries = []
        self.counts = Counter()
        self.att_packets = 0
        self.btsnoop_start = None

    def accept_packet(self, direction: int, ts: int, data: bytes):
        if len(data) < 9 or data[0] != 0x02:
            return
        handle_flags, hci_length = struct.unpack_from("<HH", data, 1)
        if len(data) < 5 + hci_length:
            return
        connection = handle_flags & 0x0FFF
        boundary = (handle_flags >> 12) & 3
        payload = data[5:5 + hci_length]
        key = (connection, direction)
        if boundary == 1:
            old = self.fragments.get(key)
            if old is None:
                return
            expected, buf, cid, started_at = old
            if len(buf) + len(payload) > expected:
                self.fragments.pop(key, None)
                return
            buf.extend(payload)
            if len(buf) == expected:
                self.fragments.pop(key, None)
                self.parse_l2cap(connection, direction, cid, bytes(buf), started_at)
            return
        if len(payload) < 4:
            return
        expected, cid = struct.unpack_from("<HH", payload, 0)
        body = payload[4:]
        if len(body) == expected:
            self.fragments.pop(key, None)
            self.parse_l2cap(connection, direction, cid, body, ts)
        elif len(body) < expected:
            self.fragments[key] = (expected, bytearray(body), cid, ts)
        else:
            self.fragments.pop(key, None)

    def parse_l2cap(self, connection: int, direction: int, cid: int, att: bytes, ts: int):
        if cid != 4 or len(att) < 3:
            return
        opcode = att[0]
        if opcode not in (0x12, 0x52, 0x1B, 0x1D):
            return
        handle = int.from_bytes(att[1:3], "little")
        value = att[3:]
        self.att_packets += 1
        if not value:
            return
        key = (connection, direction, handle)
        buf = self.frames[key]
        buf.extend(value)
        if len(buf) > 2 * MAX_FRAME:
            buf.clear()
            return
        self.parse_vendor_frames(buf, direction, ts)

    def parse_vendor_frames(self, buffer: bytearray, direction: int, ts: int):
        magic = TX_MAGIC if direction == 0 else RX_MAGIC
        while True:
            index = buffer.find(magic)
            if index < 0:
                if len(buffer) > 8:
                    del buffer[:-8]
                return
            if index:
                del buffer[:index]
            if len(buffer) < 10:
                return
            length = int.from_bytes(buffer[7:9], "little")
            if not 10 <= length <= MAX_FRAME:
                del buffer[0]
                continue
            if len(buffer) < length:
                return
            frame = bytes(buffer[:length])
            if sum(frame[:-1]) & 255 != frame[-1]:
                del buffer[0]
                continue
            del buffer[:length]
            if frame[5] not in D3200_TYPES:
                continue
            self.add_entry(ts, direction, frame[4] if direction else None,
                           frame[5], frame[6], frame[9:-1])

    def add_entry(self, ts: int, direction: int, status: int | None,
                  command_type: int, command_id: int, payload: bytes):
        if self.btsnoop_start is None:
            self.btsnoop_start = ts
        entry = {
            "ms": max(0, round((ts - self.btsnoop_start) / 1000)),
            "direction": "device_to_phone" if direction else "phone_to_device",
            "command": f"{command_type:02X}/{command_id:02X}",
            "payload_bytes": len(payload),
        }
        if status is not None:
            entry["status_low_nibble"] = status & 15
            if len(payload) == 1 and payload[0] == 255:
                entry["response"] = "FF"
        if direction == 0 and (command_type, command_id) == (0x1A, 0x07) and len(payload) == 9:
            entry["transfer_mode"] = "realtime" if payload[8] == 1 else (
                "completed" if payload[8] == 0 else "unknown")
        if direction == 0 and (command_type, command_id) == (0x18, 0x82) and len(payload) == 1:
            entry["record_control"] = {0: "stop", 1: "start", 2: "pause"}.get(payload[0], "other")
        if (command_type, command_id) in ((0x0B, 0x87), (0x0B, 0x8C), (0x01, 0xB8), (0x1A, 0x10)):
            entry["caution"] = "state_changing_command_observed"
        self.entries.append(entry)
        self.counts[(entry["direction"], entry["command"])] += 1

    def result(self):
        sensitive_commands = [x["command"] for x in self.entries if x["command"] in
                              ("0B/87", "0B/8C", "01/B8", "1A/10")]
        return {
            "format": "D3200_HCI_AUDIT_V1_REDACTED",
            "privacy": "NO_MAC_NO_UID_NO_KEYS_NO_AUDIO_NO_FILE_IDS_NO_RAW_HCI",
            "att_packets_examined": self.att_packets,
            "soundcore_frames": len(self.entries),
            "commands": [dict(direction=d, command=c, count=n)
                         for (d, c), n in sorted(self.counts.items())],
            "state_changing_commands_observed": sorted(set(sensitive_commands)),
            "events": self.entries,
        }


def analyze(path: Path) -> dict:
    audit = Audit()
    with open_capture(path) as stream:
        for direction, ts, data in read_records(stream):
            audit.accept_packet(direction, ts, data)
    return audit.result()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path, help="Raw BTSnoop H4 log or Android bugreport ZIP")
    parser.add_argument("-o", "--output", type=Path, help="Write redacted JSON (default stdout)")
    args = parser.parse_args(argv)
    try:
        result = analyze(args.capture)
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        parser.error(str(exc))
    output = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(output + "\n", encoding="utf-8")
    else:
        print(output)
    if not result["soundcore_frames"]:
        print("No D3200 frames found; confirm HCI snoop logging was FULL, not FILTERED.", file=sys.stderr)


if __name__ == "__main__":
    main()
