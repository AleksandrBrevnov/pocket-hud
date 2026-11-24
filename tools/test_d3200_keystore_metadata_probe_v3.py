import sqlite3
import tempfile
import unittest
from pathlib import Path

from d3200_keystore_metadata_probe_v3 import (
    keystore_metadata,
    preferences_metadata,
)

TAG_ALGORITHM = 0x10000002
TAG_PURPOSE = 0x20000001
TAG_KEY_SIZE = 0x30000003
TAG_BLOCK_MODE = 0x20000004
TAG_PADDING = 0x20000006


class D3200KeystoreMetadataProbeV3Test(unittest.TestCase):
    def test_only_safe_characteristics_are_reported_for_app_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "persistent.sqlite"
            with sqlite3.connect(path) as db:
                db.execute(
                    "CREATE TABLE keyentry "
                    "(id INTEGER, key_type INTEGER, domain INTEGER, namespace INTEGER, "
                    "alias BLOB, state INTEGER, km_uuid BLOB)"
                )
                db.execute(
                    "CREATE TABLE keyparameter "
                    "(keyentryid INTEGER, tag INTEGER, data ANY, security_level INTEGER)"
                )
                db.executemany(
                    "INSERT INTO keyentry VALUES (?, ?, ?, ?, ?, ?, ?)",
                    [
                        (1, 0, 0, 10001, b"PRIVATE_ALIAS_1", 1, b"x" * 16),
                        (2, 0, 0, 10001, b"PRIVATE_ALIAS_2", 1, b"x" * 16),
                        (3, 0, 0, 10002, b"OTHER_UID", 1, b"x" * 16),
                        (4, 1, 0, 10001, b"SUPER_KEY", 1, b"x" * 16),
                    ],
                )
                db.executemany(
                    "INSERT INTO keyparameter VALUES (?, ?, ?, ?)",
                    [
                        (1, TAG_ALGORITHM, 32, 1),   # AES
                        (1, TAG_KEY_SIZE, 256, 1),
                        (1, TAG_PURPOSE, 0, 1),      # ENCRYPT
                        (1, TAG_PURPOSE, 1, 1),      # DECRYPT
                        (1, TAG_BLOCK_MODE, 3, 1),   # CTR
                        (1, TAG_PADDING, 1, 1),      # NONE
                        (2, TAG_ALGORITHM, 3, 0),    # EC
                        (2, TAG_KEY_SIZE, 256, 0),
                        (3, TAG_ALGORITHM, 1, 1),    # Other UID, excluded
                        (4, TAG_ALGORITHM, 32, 1),  # Super key, excluded
                    ],
                )
            result = keystore_metadata(path, 10001)
            self.assertEqual(2, result["app_namespace_count"])
            self.assertEqual(1, len(result["app_client_keys"]))
            key = result["app_client_keys"][0]
            self.assertEqual(["AES"], key["algorithm"])
            self.assertEqual([256], key["key_size_bits"])
            self.assertEqual(["ENCRYPT", "DECRYPT"], key["purposes"])
            self.assertEqual(["CTR"], key["block_modes"])
            self.assertEqual(["NONE"], key["padding"])
            self.assertEqual(["TRUSTED_ENVIRONMENT"], key["security_levels"])
            serialized = str(result)
            for secret in ("PRIVATE_ALIAS", "OTHER_UID", "SUPER_KEY", "keyentryid"):
                self.assertNotIn(secret, serialized)

    def test_unknown_or_non_integer_values_are_redacted(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "persistent.sqlite"
            with sqlite3.connect(path) as db:
                db.execute(
                    "CREATE TABLE keyentry "
                    "(id INTEGER, key_type INTEGER, domain INTEGER, namespace INTEGER, "
                    "alias BLOB, state INTEGER, km_uuid BLOB)"
                )
                db.execute(
                    "CREATE TABLE keyparameter "
                    "(keyentryid INTEGER, tag INTEGER, data ANY, security_level INTEGER)"
                )
                db.execute(
                    "INSERT INTO keyentry VALUES (1, 0, 0, 20001, ?, 1, ?)",
                    (b"SECRET_ALIAS", b"x" * 16),
                )
                db.execute(
                    "INSERT INTO keyparameter VALUES (1, ?, ?, 1)",
                    (TAG_ALGORITHM, b"SECRET_PARAMETER"),
                )
            result = keystore_metadata(path, 20001)
            self.assertEqual(
                ["non_integer_metadata"],
                result["app_client_keys"][0]["algorithm"],
            )
            self.assertNotIn("SECRET", str(result))

    def test_missing_parameter_table_is_handled_without_reading_blobs(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "persistent.sqlite"
            with sqlite3.connect(path) as db:
                db.execute(
                    "CREATE TABLE keyentry "
                    "(id INTEGER, key_type INTEGER, domain INTEGER, namespace INTEGER, "
                    "alias BLOB, state INTEGER, km_uuid BLOB)"
                )
                db.execute("INSERT INTO keyentry VALUES (1, 0, 0, 30001, ?, 1, ?)",
                           (b"ALIAS", b"x" * 16))
            result = keystore_metadata(path, 30001)
            self.assertEqual("unsupported_keyparameter_schema", result["error"])
            self.assertEqual([], result["app_client_keys"])

    def test_preferences_metadata_is_per_field(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "secure_keys.xml"
            path.write_text(
                '<map>'
                '<string name="x_authKey_data">'
                'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY='
                '</string>'
                '<string name="x_sessionKey_data">'
                'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY='
                '</string>'
                '<string name="x_authKey_iv">'
                'MDEyMzQ1Njc4OWFiY2RlZg=='
                '</string>'
                '<string name="x_sessionKey_iv">MDEyMzQ1Njc4OWFiY2RlZg==</string>'
                '</map>',
                encoding="utf-8",
            )
            result = preferences_metadata(path)
            self.assertEqual([32], result["fields"]["auth_data"]["decoded_lengths"])
            self.assertEqual([32], result["fields"]["session_data"]["decoded_lengths"])
            self.assertEqual([16], result["fields"]["auth_iv"]["decoded_lengths"])
            self.assertEqual([16], result["fields"]["session_iv"]["decoded_lengths"])


if __name__ == "__main__":
    unittest.main()
