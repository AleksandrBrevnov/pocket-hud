# D3200 key-storage metadata audit — 2026-11-24

## Device-side read-only probe v1

User ran `tools/d3200_keystore_metadata_probe.py` on the rooted Android 16 device.

Observed:
- Soundcore app data directory exists and its Linux UID was resolved.
- `shared_prefs/secure_keys.xml` exists.
- One `*_authKey_data` entry.
- One `*_sessionKey_data` entry.
- Two IV entries.
- The v1 probe reported the set of successfully Base64-decoded DATA lengths as `[64]`.
- Android Keystore `persistent.sqlite` is readable.
- Two `keyentry` rows use the Soundcore app UID as namespace.
- Both rows have `domain=0`.
- No aliases, blobs, or key values were read or printed.

## Device-side read-only probe v2 — actual result

The user ran `tools/d3200_keystore_metadata_probe_v2.py` on the same device.

Per-field Base64-decoded lengths:
- `auth_data`: 64 bytes, one entry.
- `session_data`: 64 bytes, one entry.
- `auth_iv`: 16 bytes, one entry.
- `session_iv`: 16 bytes, one entry.

Keystore:
- two rows in the Soundcore UID namespace;
- both have `domain=0`;
- both have `key_type=0`;
- schema includes `alias`, `domain`, `id`, `key_type`, `km_uuid`, `namespace`, and `state`.

### Correct interpretation from AOSP

AOSP Keystore2 defines:
- `domain=0` as `Domain::APP`; the namespace for APP entries is the calling app UID.
- `key_type=0` as `KeyType::Client`: keys created/imported through the Keystore2 client API. It is **not** the cryptographic algorithm and does not mean AES.

Sources:
- https://android.googlesource.com/platform/system/security/+/refs/heads/master/keystore2/src/database.rs
- https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-xr-arcore-release/android-35/android/system/keystore2/Domain.java

Therefore, the two records are two app-owned client key entries, but they are not yet proven to be the wrapping keys for `authKey_data` and `sessionKey_data`. Their algorithms and authorizations are stored separately in `keyparameter`.

## Probe v3

Added:
- `tools/d3200_keystore_metadata_probe_v3.py`
- `tools/test_d3200_keystore_metadata_probe_v3.py`

The v3 probe:
- reports the individual lengths of the four known preference fields;
- joins app-owned Client key entries to `keyparameter` internally;
- reports only safe key characteristics: algorithm, bit size, allowed purposes, block modes, digests, padding, and security level;
- uses key entry IDs only internally and never prints them;
- never reads aliases, key blobs, certificates, preference values, nonces, or secret key material;
- opens SQLite read-only and sets `PRAGMA query_only=ON`.

AOSP stores key entries and their parameters in separate `keyentry` and `keyparameter` tables. The selected parameters are standard KeyMint metadata (algorithm, purpose, key size, block mode, digest, padding), not key material.

Relevant sources:
- https://android.googlesource.com/platform/system/security/+/refs/heads/master/keystore2/src/database.rs
- https://android.googlesource.com/platform/system/security/+/refs/heads/master/keystore2/src/key_parameter.rs
- https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/security/keymint/aidl/android/hardware/security/keymint/Tag.aidl
- https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/security/keymint/aidl/android/hardware/security/keymint/Algorithm.aidl
- https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/security/keymint/aidl/android/hardware/security/keymint/KeyPurpose.aidl

Synthetic tests are included for filtering, enum mapping, per-field lengths, and redaction of aliases/unknown parameter values. They have not yet been run on the user's phone.

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

Run the v3 probe on the same device. Use only the reported algorithm/key-size/purpose/mode metadata to determine whether either app-owned Keystore key is a plausible wrapper for the encrypted preferences. Do not extract aliases or key blobs, do not alter app storage, do not reset/rebind the D3200, and do not change firmware.
