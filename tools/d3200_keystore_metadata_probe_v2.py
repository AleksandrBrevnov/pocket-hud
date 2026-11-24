#!/usr/bin/env python3
"""Read-only D3200 storage metadata probe v2.

Reports only field labels, decoded byte lengths, SQLite schema column names,
and row counts grouped by non-secret key metadata. Never prints values,
aliases, blobs, certificates, or cryptographic material.
"""
import argparse
import base64
import binascii
import json
from pathlib import Path
import sqlite3
import xml.etree.ElementTree as ET

DEFAULT_APP = Path("/data/user/0/com.oceanwing.soundcore")
DEFAULT_DB = Path("/data/misc/keystore/persistent.sqlite")
SUFFIXES = {
    "_authKey_data": "auth_data",
    "_sessionKey_data": "session_data",
    "_authKey_iv": "auth_iv",
    "_sessionKey_iv": "session_iv",
}


def decoded_length(value):
    try:
        return len(base64.b64decode((value or "").strip(), validate=True))
    except (ValueError, binascii.Error):
        return None


def preferences_metadata(path):
    out = {
        "found": path.is_file(),
        "fields": {label: {"entries": 0, "decoded_lengths": []}
                   for label in SUFFIXES.values()},
        "error": None,
    }
    if not out["found"]:
        return out
    try:
        for node in ET.parse(path).getroot().iter():
            name = node.attrib.get("name", "")
            label = next((v for suffix, v in SUFFIXES.items()
                          if name.endswith(suffix)), None)
            if label is None:
                continue
            item = out["fields"][label]
            item["entries"] += 1
            value = node.text or node.attrib.get("value", "")
            length = decoded_length(value)
            if length is not None and length not in item["decoded_lengths"]:
                item["decoded_lengths"].append(length)
        for item in out["fields"].values():
            item["decoded_lengths"].sort()
    except (ET.ParseError, OSError):
        out["error"] = "not_readable_or_invalid"
    return out


def keystore_metadata(path, app_uid):
    out = {
        "found": path.is_file(),
        "readable": False,
        "keyentry_columns": [],
        "app_namespace_count": None,
        "app_rows_by_domain": [],
        "app_rows_by_domain_and_key_type": [],
        "error": None,
    }
    if not out["found"]:
        return out
    try:
        uri = path.resolve().as_uri() + "?mode=ro"
        with sqlite3.connect(uri, uri=True, timeout=2) as conn:
            conn.execute("PRAGMA query_only=ON")
            columns = {row[1] for row in conn.execute("PRAGMA table_info(keyentry)")}
            if not columns:
                out["error"] = "keyentry_table_missing"
                return out
            out["keyentry_columns"] = sorted(columns)
            if not {"namespace", "domain"}.issubset(columns):
                out["error"] = "unsupported_schema"
                return out
            out["readable"] = True
            out["app_namespace_count"] = int(conn.execute(
                "SELECT count(*) FROM keyentry WHERE namespace=?", (app_uid,)
            ).fetchone()[0])
            rows = conn.execute(
                "SELECT domain, count(*) FROM keyentry WHERE namespace=? "
                "GROUP BY domain ORDER BY domain", (app_uid,)
            ).fetchall()
            out["app_rows_by_domain"] = [
                {"domain": row[0], "count": row[1]} for row in rows
            ]
            if "key_type" in columns:
                rows = conn.execute(
                    "SELECT domain, key_type, count(*) FROM keyentry "
                    "WHERE namespace=? GROUP BY domain, key_type "
                    "ORDER BY domain, key_type", (app_uid,)
                ).fetchall()
                out["app_rows_by_domain_and_key_type"] = [
                    {"domain": row[0], "key_type": row[1], "count": row[2]}
                    for row in rows
                ]
    except (sqlite3.Error, OSError):
        out["error"] = "not_readable"
    return out


def probe(app=DEFAULT_APP, db=DEFAULT_DB):
    out = {
        "app_data_found": app.is_dir(),
        "app_uid_resolved": False,
        "prefs": preferences_metadata(app / "shared_prefs/secure_keys.xml"),
        "keystore": None,
        "conclusion": "metadata_only_no_key_recovered",
    }
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
