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

    def test_unknown_vendor_initialization_opcode_is_visible_but_payload_redacted(self):
        unknown = d3200(0, 0x10, 0xA2, b"PRIVATE_ACCOUNT_TOKEN")
        raw = snoop([(0, p) for p in make_hci_packets(0, unknown)])
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "hci.log"
            p.write_bytes(raw)
            result = analyze(p)
        self.assertEqual("10/A2", result["events"][0]["command"])
        self.assertNotIn("PRIVATE_ACCOUNT_TOKEN", json.dumps(result))

    def test_auth_result_and_audio_slice_metadata_are_redacted(self):
        # Official-app-shaped 0B/8B: 44-byte encrypted block + 16-byte nonce.
        auth_payload = bytes([44]) + bytes(range(44)) + bytes([16]) + bytes(range(16))
        slices = []
        for sequence, flags in ((0, 2), (1, 0), (2, 4)):
            slices.append(sequence.to_bytes(4, "little") + bytes([flags]) +
                          bytes([0xA5]) * 160 + bytes([0]))
        packets = [
            (0, d3200(0, 0x0B, 0x8B, auth_payload)),
            (1, d3200(1, 0x0B, 0x8B, bytes([0]))),
            (1, d3200(1, 0x1A, 0x08, b"".join(slices))),
        ]
        raw = snoop([(direction, packet)
                     for direction, vendor in packets
                     for packet in make_hci_packets(direction, vendor)])
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "hci.log"
            p.write_bytes(raw)
            result = analyze(p)
        events = result["events"]
        self.assertEqual(44, events[0]["auth_ciphertext_bytes"])
        self.assertEqual(16, events[0]["auth_nonce_bytes"])
        self.assertEqual(0, events[1]["auth_result"])
        self.assertEqual(3, events[2]["audio_slice_count"])
        self.assertEqual(0, events[2]["sequence_first"])
        self.assertEqual(2, events[2]["sequence_last"])
        self.assertEqual(2, events[2]["first_slice_flags"])
        self.assertEqual(4, events[2]["last_slice_flags"])
        serialized = json.dumps(result)
        self.assertNotIn("165", serialized)  # encrypted audio byte value is not emitted
        self.assertNotIn(bytes(range(44)).hex(), serialized)
        self.assertNotIn(bytes(range(16)).hex(), serialized)
        self.assertNotIn("nonce", serialized.lower().replace("auth_nonce_bytes", ""))

    def test_official_a7_observation_redacts_unknown_payload(self):
        packets = [
            (0, d3200(0, 0x1A, 0xA7, b"\x01")),
            (1, d3200(1, 0x1A, 0xA7)),
            (0, d3200(0, 0x1A, 0xA7, b"PRIVATE")),
        ]
        raw = snoop([(direction, chunk) for direction, frame in packets
                     for chunk in make_hci_packets(direction, frame)])
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "hci.log"
            p.write_bytes(raw)
            events = analyze(p)["events"]
        self.assertEqual("01_observed", events[0]["official_a7_request"])
        self.assertTrue(events[1]["official_a7_ack"])
        self.assertEqual("other_redacted", events[2]["official_a7_request"])
        self.assertNotIn("PRIVATE", json.dumps(events))

    def test_legacy_eight_byte_file_header_request_is_not_mislabeled_realtime(self):
        request = d3200(0, 0x1A, 0x07, bytes(4) + (42).to_bytes(4, "little"))
        raw = snoop([(0, packet) for packet in make_hci_packets(0, request)])
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "hci.log"
            p.write_bytes(raw)
            event = analyze(p)["events"][0]
        self.assertEqual("legacy_file_header_request_without_mode_byte",
                         event["transfer_mode"])
        self.assertNotIn("file_id", json.dumps(event))

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
