"""Synthetic tests for privacy-safe D3200 HCI snoop analyzer."""
import json
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from d3200_hci_audit import analyze


def d3200(direction, command_type, command_id, payload=b"", status=1):
    first = bytes([8, 238, 0, 0, 0]) if direction == 0 else bytes([9, 255, 0, 0, status])
    size = 10 + len(payload)
    prefix = first + bytes([command_type, command_id]) + struct.pack("<H", size) + payload
    return prefix + bytes([sum(prefix) & 255])


def make_hci_packets(direction, vendor, chunk=None, handle=0x0040):
    att = bytes([0x52 if direction == 0 else 0x1B]) + struct.pack("<H", 0x0035) + vendor
    l2cap = struct.pack("<HH", len(att), 4) + att
    first = l2cap if chunk is None else l2cap[:chunk]
    rest = b"" if chunk is None else l2cap[chunk:]
    hci = lambda pb, body: bytes([2]) + struct.pack("<HH", handle | (pb << 12), len(body)) + body
    return [hci(2, first)] + ([hci(1, rest)] if rest else [])


def snoop(records):
    out = bytearray(b"btsnoop\0" + struct.pack(">II", 1, 1002))
    for index, (direction, packet) in enumerate(records):
        out += struct.pack(">IIIIQ", len(packet), len(packet), direction, 0, 1000000 + index * 10000)
        out += packet
    return bytes(out)


class AuditTest(unittest.TestCase):
    def test_decodes_realtime_ff_and_redacts_secret_material(self):
        records = []
        packets = [
            (0, d3200(0, 0x2E, 1, b"DO_NOT_REVEAL_PUBLIC_KEY")),
            (1, d3200(1, 0x2E, 1, b"SHARED_SECRET_DO_NOT_REVEAL")),
            (0, d3200(0, 0x1A, 7, b"\0\0\0\0" + b"secret"[:4] + b"\x01")),
            (1, d3200(1, 0x1A, 7, b"\xff")),
            (0, d3200(0, 0x0B, 0x8B, b"UIDSECRET_ENCRYPTED_TOKEN")),
            (1, d3200(1, 0x1A, 0x08, b"AUDIO_OPUS_SECRET")),
        ]
        for direction, vendor in packets:
            for raw in make_hci_packets(direction, vendor, chunk=11):
                records.append((direction, raw))
        with tempfile.TemporaryDirectory() as tmp:
            capture = Path(tmp) / "btsnoop_hci.log"
            capture.write_bytes(snoop(records))
            result = analyze(capture)
        data = json.dumps(result)
        self.assertEqual(6, result["soundcore_frames"])
        self.assertEqual("realtime", result["events"][2]["transfer_mode"])
        self.assertEqual("FF", result["events"][3]["response"])
        for token in ("UIDSECRET", "AUDIO_OPUS", "PUBLIC_KEY", "SHARED_SECRET", "secret"):
            self.assertNotIn(token, data)
        self.assertIn("0B/8B", data)

    def test_android_bugreport_zip_and_invalid_input(self):
        raw = snoop([(1, packet) for packet in make_hci_packets(
            1, d3200(1, 0x1A, 0x0E, b"\xff"))])
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "bugreport.zip"
            with zipfile.ZipFile(p, "w") as z:
                z.writestr("FS/data/misc/bluetooth/logs/btsnoop_hci.log", raw)
            result = analyze(p)
            self.assertEqual(1, result["soundcore_frames"])
            self.assertEqual("FF", result["events"][0]["response"])
            bad = Path(tmp) / "bad.log"
            bad.write_bytes(b"bad")
            with self.assertRaises(ValueError):
                analyze(bad)

    def test_ignores_non_soundcore_and_truncated_records(self):
        raw = snoop([(0, packet) for packet in make_hci_packets(
            0, b"hello_secret_audio")])
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "none.log"
            p.write_bytes(raw)
            self.assertEqual(0, analyze(p)["soundcore_frames"])
            p.write_bytes(raw[:-2])
            with self.assertRaises(ValueError):
                analyze(p)

    def test_completed_mode_and_dangerous_commands_are_only_observed(self):
        packets = [
            d3200(0, 0x1A, 7, b"\0\0\0\0" + b"\x01\x02\x03\x04" + b"\x00"),
            d3200(0, 0x0B, 0x87, b"\x01"),
            d3200(0, 0x18, 0x82, b"\x02"),
        ]
        raw = snoop([(0, chunk) for packet in packets for chunk in make_hci_packets(0, packet)])
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "hci.log"
            p.write_bytes(raw)
            result = analyze(p)
        self.assertEqual("completed", result["events"][0]["transfer_mode"])
        self.assertEqual(["0B/87"], result["state_changing_commands_observed"])
        self.assertEqual("pause", result["events"][2]["record_control"])


if __name__ == "__main__":
    unittest.main()
