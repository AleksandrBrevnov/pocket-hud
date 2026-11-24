#!/usr/bin/env python3
"""Read-only Soundcore/Android Keystore metadata probe v3.

Only reads public key characteristics for the Soundcore app's own Keystore
entries: algorithm, size, allowed purposes, block modes, digests, padding,
and security level. It never reads aliases, key blobs, certificates, key IDs,
preference values, or cryptographic material.
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

# KeyMint Tag values from AOSP Tag.aidl (tag type in high 4 bits).
TAGS = {
    0x10000002: "algorithm",
    0x20000001: "purpose",
    0x30000003: "key_size_bits",
    0x20000004: "block_mode",
    0x20000005: "digest",
    0x20000006: "padding",
}
ENUMS = {
    "algorithm": {1: "RSA", 3: "EC", 32: "AES", 33: "TRIPLE_DES", 128: "HMAC"},
    "purpose": {
        0: "ENCRYPT", 1: "DECRYPT", 2: "SIGN", 3: "VERIFY",
        5: "WRAP_KEY", 6: "AGREE_KEY", 7: "ATTEST_KEY",
    },
    "block_mode": {1: "ECB", 2: "CBC", 3: "CTR", 32: "GCM"},
    "digest": {
        0: "NONE", 1: "MD5", 2: "SHA1", 3: "SHA224",
        4: "SHA256", 5: "SHA384", 6: "SHA512",
    },
    "padding": {
        1: "NONE", 2: "RSA_OAEP", 3: "RSA_PSS",
        4: "RSA_PKCS1_1_5_ENCRYPT", 5: "RSA_PKCS1_1_5_SIGN",
        64: "PKCS7",
    },
}
SECURITY_LEVELS = {
    0: "SOFTWARE",
    1: "TRUSTED_ENVIRONMENT",
    2: "STRONGBOX",
    100: "KEYMINT",
}
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
            length = decoded_length(node.text or node.attrib.get("value", ""))
            if length is not None and length not in item["decoded_lengths"]:
                item["decoded_lengths"].append(length)
        for item in out["fields"].values():
            item["decoded_lengths"].sort()
    except (ET.ParseError, OSError):
        out["error"] = "not_readable_or_invalid"
    return out


def _safe_parameter_value(name, value):
    if not isinstance(value, int):
        return "non_integer_metadata"
    if name == "key_size_bits":
        return value if 0 < value <= 16384 else "out_of_range"
    return ENUMS.get(name, {}).get(value, "OTHER_OR_UNKNOWN")


def keystore_metadata(path, app_uid):
    out = {
        "found": path.is_file(),
        "readable": False,
        "keyentry_columns": [],
        "keyparameter_columns": [],
        "app_namespace_count": None,
        "app_rows_by_domain": [],
        "app_rows_by_domain_and_key_type": [],
        "app_client_keys": [],
        "error": None,
    }
    if not out["found"]:
        return out
    try:
        uri = path.resolve().as_uri() + "?mode=ro"
        with sqlite3.connect(uri, uri=True, timeout=2) as conn:
            conn.execute("PRAGMA query_only=ON")
            key_cols = {r[1] for r in conn.execute("PRAGMA table_info(keyentry)")}
            param_cols = {r[1] for r in conn.execute("PRAGMA table_info(keyparameter)")}
            out["keyentry_columns"] = sorted(key_cols)
            out["keyparameter_columns"] = sorted(param_cols)
            if not {"id", "namespace", "domain", "key_type"}.issubset(key_cols):
                out["error"] = "unsupported_keyentry_schema"
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
            rows = conn.execute(
                "SELECT domain, key_type, count(*) FROM keyentry "
                "WHERE namespace=? GROUP BY domain, key_type ORDER BY domain, key_type",
                (app_uid,),
            ).fetchall()
            out["app_rows_by_domain_and_key_type"] = [
                {"domain": row[0], "key_type": row[1], "count": row[2]}
                for row in rows
            ]

            required = {"keyentryid", "tag", "data", "security_level"}
            if not required.issubset(param_cols):
                out["error"] = "unsupported_keyparameter_schema"
                return out

            # Use key IDs only as an internal join/grouping field. Never output them.
            key_rows = conn.execute(
                "SELECT id FROM keyentry WHERE namespace=? AND domain=0 AND key_type=0 "
                "ORDER BY id",
                (app_uid,),
            ).fetchall()
            for index, (key_id,) in enumerate(key_rows, start=1):
                props = {name: [] for name in TAGS.values()}
                security_levels = set()
                query = (
                    "SELECT tag, data, security_level FROM keyparameter "
                    "WHERE keyentryid=? AND tag IN ("
                    + ",".join(str(tag) for tag in TAGS)
                    + ") ORDER BY tag, data"
                )
                for tag, data, security_level in conn.execute(query, (key_id,)):
                    name = TAGS.get(tag)
                    if name is None:
                        continue
                    safe_value = _safe_parameter_value(name, data)
                    if safe_value not in props[name]:
                        props[name].append(safe_value)
                    if isinstance(security_level, int):
                        security_levels.add(
                            SECURITY_LEVELS.get(security_level, "OTHER_OR_UNKNOWN")
                        )
                out["app_client_keys"].append({
                    "entry": f"app_client_key_{index}",
                    "algorithm": props["algorithm"],
                    "key_size_bits": props["key_size_bits"],
                    "purposes": props["purpose"],
                    "block_modes": props["block_mode"],
                    "digests": props["digest"],
                    "padding": props["padding"],
                    "security_levels": sorted(security_levels),
                })
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
