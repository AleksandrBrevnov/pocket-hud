# D3200 credential storage: safe local reconnaissance

Date: 2026-11-24.

The user's official Soundcore app 5.0.39 on Motorola Android 16 stores encrypted authKey_data and sessionKey_data values in secure_keys.xml, alongside IV fields. A local read-only check found a 40-character hexadecimal account UID directly matching one authKey_data preference namespace.

This does not reveal a persistent 32-byte D3200 binding key. It also does not prove that Android Keystore wraps this encrypted value. The vendor app can use a Keystore key in its own app context; PocketHUD has no direct authority to access another app's Keystore keys.

## Safe metadata probe

tools/d3200_keystore_metadata_probe.py only counts encrypted preference entries and their decoded byte lengths, resolves the local app UID without printing it, and counts keyentry metadata records in the app namespace. It queries SQLite using mode=ro, PRAGMA query_only and aggregate count queries. It never retrieves aliases, key material, account IDs, IV content, ciphertext, BLE packets or audio. It never modifies binding, recordings or firmware. If the path or schema is inaccessible, it returns a safe error code.

A nonzero app namespace count is NOT proof of binding-key storage or ability to decrypt it. Results should be analyzed as leads only; the app's concrete wrapping mechanism and credential derivation still need independent verification.

Run tests using synthetic data:
python3 -m unittest discover -s tools -p 'test_d3200_keystore_metadata_probe.py' -v

Do not export secret values or commit raw Soundcore app files to GitHub. Do not re-bind, reset, OTA, replay an old 0B/8B packet or send 1A/10 file deletion.

Open issue still to solve: establish an owner-authorized on-device use of existing credentials without pulling plaintext key material into chat, GitHub, or CI. Offline authentication packet format tests do not prove device acceptance or unpaused BLE audio.
