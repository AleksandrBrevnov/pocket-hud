import base64
import sqlite3
import tempfile
import unittest
from pathlib import Path

from d3200_keystore_metadata_probe_v2 import (
    keystore_metadata,
    preferences_metadata,
)


class D3200KeystoreMetadataProbeV2Test(unittest.TestCase):
    def test_reports_each_pref_field_length_without_values(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "secure_keys.xml"
            def b64(size):
                return base64.b64encode(bytes(range(size))).decode("ascii")
            path.write_text(
                '<map>'
                f'<string name="private_authKey_data">{b64(64)}</string>'
                f'<string name="private_sessionKey_data">{b64(48)}</string>'
                f'<string name="private_authKey_iv">{b64(16)}</string>'
                f'<string name="private_sessionKey_iv">{b64(16)}</string>'
                '</map>',
                encoding="utf-8",
            )
            result = preferences_metadata(path)
            self.assertEqual([64], result["fields"]["auth_data"]["decoded_lengths"])
            self.assertEqual([48], result["fields"]["session_data"]["decoded_lengths"])
            self.assertEqual([16], result["fields"]["auth_iv"]["decoded_lengths"])
            self.assertEqual([16], result["fields"]["session_iv"]["decoded_lengths"])
            self.assertNotIn("private_", str(result))
            self.assertNotIn(b64(64), str(result))

    def test_keystore_aggregates_only_domain_and_key_type_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "persistent.sqlite"
            with sqlite3.connect(path) as connection:
                connection.execute(
                    "CREATE TABLE keyentry "
                    "(domain INT, namespace INT, key_type INT, alias TEXT, km_blob BLOB)"
                )
                connection.executemany(
                    "INSERT INTO keyentry VALUES (?, ?, ?, ?, ?)",
                    [
                        (0, 12345, 1, "PRIVATE_ALIAS_A", b"PRIVATE_BLOB_A"),
                        (0, 12345, 2, "PRIVATE_ALIAS_B", b"PRIVATE_BLOB_B"),
                        (1, 12345, 1, "PRIVATE_ALIAS_C", b"PRIVATE_BLOB_C"),
                        (0, 12346, 1, "OTHER_UID", b"OTHER_BLOB"),
                    ],
                )
            result = keystore_metadata(path, 12345)
            self.assertEqual(3, result["app_namespace_count"])
            self.assertEqual(
                [
                    {"domain": 0, "key_type": 1, "count": 1},
                    {"domain": 0, "key_type": 2, "count": 1},
                    {"domain": 1, "key_type": 1, "count": 1},
                ],
                result["app_rows_by_domain_and_key_type"],
            )
            serialized = str(result)
            for secret in ("PRIVATE_ALIAS", "PRIVATE_BLOB", "OTHER_UID", "OTHER_BLOB"):
                self.assertNotIn(secret, serialized)

    def test_missing_preferences_and_database_are_handled(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "missing"
            self.assertFalse(preferences_metadata(missing)["found"])
            result = keystore_metadata(missing, 123)
            self.assertFalse(result["found"])
            self.assertIsNone(result["app_namespace_count"])


if __name__ == "__main__":
    unittest.main()
