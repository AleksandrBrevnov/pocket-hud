import sqlite3
import tempfile
import unittest
from pathlib import Path
from d3200_keystore_metadata_probe import keystore_metadata, preferences_metadata, probe

class D3200KeystoreMetadataProbeTest(unittest.TestCase):
    def test_counts_only_and_does_not_leak_aliases(self):
        with tempfile.TemporaryDirectory() as directory:
            db = Path(directory) / "persistent.sqlite"
            with sqlite3.connect(db) as conn:
                conn.execute("CREATE TABLE keyentry (domain INT, namespace INT, alias TEXT, blob BLOB)")
                conn.executemany("INSERT INTO keyentry VALUES (?,?,?,?)", [
                    (0, 20001, "SECRET_ALIAS", b"SECRET_VALUE"),
                    (1, 20001, "ANOTHER_ALIAS", b"SECRET_VALUE"),
                    (0, 20002, "OTHER_USER", b"SECRET_VALUE")])
            out = keystore_metadata(db, 20001)
            self.assertEqual(2, out["app_namespace_count"])
            self.assertEqual(1, out["app_domain_count"])
            self.assertNotIn("SECRET", str(out))

    def test_preferences_report_only_lengths_and_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / "secure_keys.xml"
            private = "a" * 40
            p.write_text('<map><string name="' + private +
                '_authKey_data">AAECAwQFBgcICQoLDA0ODw==</string>'
                '<string name="' + private +
                '_authKey_iv">PRIVATE_IV</string></map>')
            out = preferences_metadata(p)
            self.assertEqual(1, out["auth_entries"])
            self.assertEqual(1, out["iv_entries"])
            self.assertEqual([16], out["decoded_data_lengths"])
            self.assertNotIn(private, str(out))
            self.assertNotIn("PRIVATE_IV", str(out))

    def test_missing_paths_are_handled(self):
        with tempfile.TemporaryDirectory() as directory:
            result = probe(Path(directory) / "absent", Path(directory) / "db")
            self.assertFalse(result["app_uid_resolved"])
            self.assertIsNone(result["keystore"])

if __name__ == "__main__":
    unittest.main()
