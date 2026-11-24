#!/usr/bin/env python3
"""Read-only D3200 on-device storage metadata probe. Never prints secret values."""
import argparse
import base64
import json
from pathlib import Path
import sqlite3
import xml.etree.ElementTree as ET

DEFAULT_APP = Path("/data/user/0/com.oceanwing.soundcore")
DEFAULT_DB = Path("/data/misc/keystore/persistent.sqlite")

def preferences_metadata(path):
    r = dict(found=path.is_file(), auth_entries=0, session_entries=0,
             iv_entries=0, decoded_data_lengths=[], error=None)
    if not r["found"]:
        return r
    lengths = set()
    try:
        for x in ET.parse(path).getroot():
            key = x.attrib.get("name", "")
            if key.endswith("_authKey_data"):
                r["auth_entries"] += 1
            elif key.endswith("_sessionKey_data"):
                r["session_entries"] += 1
            elif key.endswith(("_authKey_iv", "_sessionKey_iv")):
                r["iv_entries"] += 1
            else:
                continue
            if key.endswith("_data"):
                try:
                    value = (x.text or x.attrib.get("value", "")).strip()
                    lengths.add(len(base64.b64decode(value, validate=True)))
                except (ValueError, base64.binascii.Error):
                    pass
    except (ET.ParseError, OSError):
        r["error"] = "not_readable_or_invalid"
    r["decoded_data_lengths"] = sorted(lengths)
    return r

def keystore_metadata(path, app_uid):
    r = dict(found=path.is_file(), readable=False,
             app_namespace_count=None, app_domain_count=None, error=None)
    if not r["found"]:
        return r
    try:
        with sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True, timeout=2) as conn:
            conn.execute("PRAGMA query_only=ON")
            columns = {x[1] for x in conn.execute("PRAGMA table_info(keyentry)")}
            if not {"namespace", "domain"}.issubset(columns):
                r["error"] = "unsupported_schema"
                return r
            r["readable"] = True
            # Counts only: do NOT query aliases or encrypted key blobs.
            r["app_namespace_count"] = int(conn.execute(
                "SELECT count(*) FROM keyentry WHERE namespace=?", (app_uid,)).fetchone()[0])
            r["app_domain_count"] = int(conn.execute(
                "SELECT count(*) FROM keyentry WHERE namespace=? AND domain=0",
                (app_uid,)).fetchone()[0])
    except (sqlite3.Error, OSError):
        r["error"] = "not_readable"
    return r

def probe(app=DEFAULT_APP, db=DEFAULT_DB):
    out = dict(app_data_found=app.is_dir(), app_uid_resolved=False,
               prefs=preferences_metadata(app / "shared_prefs/secure_keys.xml"),
               keystore=None, conclusion="metadata_only_no_key_recovered")
    try:
        uid = app.stat().st_uid
    except OSError:
        return out
    out["app_uid_resolved"] = True
    out["keystore"] = keystore_metadata(db, uid)
    return out

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--app-dir", type=Path, default=DEFAULT_APP)
    parser.add_argument("--keystore-db", type=Path, default=DEFAULT_DB)
    args = parser.parse_args()
    print(json.dumps(probe(args.app_dir, args.keystore_db), sort_keys=True))
