# D3200 key-storage metadata audit — 2026-11-24

## Device-side read-only probe v1

User ran `tools/d3200_keystore_metadata_probe.py` on the rooted Android 16 device.

Observed output (values are metadata only):
- Soundcore app data directory exists and its Linux UID was resolved.
- `shared_prefs/secure_keys.xml` exists.
- One `*_authKey_data` entry.
- One `*_sessionKey_data` entry.
- Two IV entries.
- The probe reported the set of successfully Base64-decoded DATA lengths as `[64]`.
- Android Keystore `persistent.sqlite` is readable.
- Two `keyentry` rows use the Soundcore app UID as namespace.
- Both rows have `domain=0`.
- No aliases, blobs, or key values were read or printed.

Important interpretation:
- The `[64]` result is a set of unique lengths across both DATA entries, not a per-field report. It does not prove that both fields are 64 bytes.
- The two Keystore rows are not yet mapped to auth/session keys; the original probe did not inspect key types or aliases.
- This is evidence that app-scoped Keystore entries exist, not evidence that they are the BLE binding key or session key.
- No key material was recovered.

## Probe v2

Added:
- `tools/d3200_keystore_metadata_probe_v2.py`
- `tools/test_d3200_keystore_metadata_probe_v2.py`

The v2 probe reports each auth/session DATA and IV decoded length separately. It also reports only the `keyentry` column names and aggregate counts by `domain` and, when present, `key_type`.

It deliberately never selects or prints aliases, encrypted blobs, certificates, XML values, or key material. SQLite is opened read-only and `PRAGMA query_only=ON` is set.

Local synthetic tests passed:
- separate DATA/IV lengths;
- aggregation by domain and key type;
- no leakage of fixture aliases or blobs;
- missing-file handling.

## Static inspection of Soundcore 5.0.39 APKM

The supplied APKM contains `base.apk`, `split_soundcoreso.apk`, and `split_lib_asset.apk`.

Observed in Flutter AOT string pool (`libapp.so`):
- `D3200DeviceManager.readBindInfos`
- `D3200EventManager.d3200GetLoginInfo`
- `d3200NeedCryptoJ`
- Dart package strings for `HKDFKeyDerivator` and `ECDHKeyDerivator`

Observed exported native symbols in `libecc-encryption.so`:
- `GenerateKeyForECC`, `EncryptECC`, `DecryptECC`
- `HMAC_SHA_256_256`
- AES-CBC functions

Observed in `libcrypto-security.so`:
- ECDH and HKDF symbols
- AES and mbedTLS crypto primitives, including AES-CTR

These findings confirm that the package includes ECC, HMAC, HKDF, and AES implementations. They do not, by themselves, prove which implementation is used for the D3200 BLE authentication path.

The assets split also contains `D3200_server_cert.crt` and `legacy_iv.bin`. The certificate is a self-signed RSA certificate for 192.168.43.1, with validity 2025-04-21 through 2026-04-21. The legacy IV is 16 bytes with an incrementing 00..0F pattern. Neither asset has yet been tied to the BLE `0B/8B` exchange; do not assume they are part of it.

## Next audit step

Run the v2 probe on the same device. Use its per-field lengths and Keystore key-type aggregates to decide what can be inferred safely. Do not extract aliases or key blobs, do not alter app storage, do not reset/rebind the D3200, and do not change firmware.
