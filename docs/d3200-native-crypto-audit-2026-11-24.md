# Soundcore D3200 — JNI/Flutter native crypto audit, 2026-11-24

## Input and scope

Evidence: owner's own Termux output on rooted Motorola Edge 50 Pro, firmware 03.56, Soundcore Android 5.0.39. Independently cross-checked using the already uploaded Soundcore 5.0.39 APKM in the analysis environment (only offline, read-only binary inspection).

The published issue https://github.com/tacshi/Soundcore/issues/9 reports that firmware 03.56 responds with FF to file operations after 2E/01 unless separately authorized. A later 2026-11-20 comment documents 0B/8B with a 40-character account ID, 4-byte LE timestamp, AES-256-CTR with a persistent 32-byte binding key, and a 16-byte nonce; the commenter tested firmware 03.66. On the owner's 03.56, the official 0B/8B auth was accepted, with a matching 44-byte ciphertext and 16-byte nonce envelope. The owner already confirmed the account identifier length is 40 characters, matching the authKey preference namespace. This supports a format hypothesis but does not recover the binding key.

## What the binary symbols actually demonstrate

- arm64 libcrypto-security.so contains JNI entries for Cryptography_aesDecrypt, Cryptography_computeEcdh, Cryptography_genEcdhKey and Cryptography_cryptoHkdf, plus RSA helpers.
- arm64 libecc-encryption.so exports EccEncryption_eccEncrypt/eccDecrypt/generateEccKey and AES-128-CBC helpers.
- arm64 libapp.so has Flutter AOT strings d3200NeedCryptoJ, d3200CustomsEncrypt, d3200AudioCtrEncrypt, but an ASCII scan does not show authKey_data, sessionKey_data, secure_keys.xml or AndroidKeyStore identifiers. Their absence from strings is NOT proof of absent functionality, because AOT/Java are obfuscated.
- The base classes.dex is packed/obfuscated and gives no useful plain-text source for the D3200 credential store.
- Independent read-only ARM64 disassembly establishes that JNI Cryptography_aesDecrypt at virtual 0x15c888 calls aes_decrypt; the latter at virtual 0x105218 invokes aes_set_key with 128 bits and AES_ecb_encrypt in decrypt mode. This *particular exported JNI function* implements AES-128-ECB blocks, not the AES-256-CTR used for 0B/8B authorization. It should **not** be mistaken for the auth proof builder. This does not exclude the possibility that some local key storage uses AES-128-ECB.

## What this does NOT establish

- Which app component decrypts the 64-byte authKey_data preference value;
- Whether the preference is wrapped by Android Keystore, another algorithm, or the vendor's encrypted DB;
- Whether a key can be legitimately migrated to PocketHUD without resetting/rebinding;
- Whether continuous *unpaused* BLE stream works on firmware 03.56. The official capture's 446 Opus slices were transferred in a post-pause burst.

The public forum suggests capturing the original 0B/87 -> 0B/88 binding exchange to obtain the persistent key. **Do not follow those steps for this existing recorder:** they require unbinding and rebinding, which are explicitly out of scope and could disrupt stored recordings or account access. The previous official HCI capture contains no fresh 0B/88 binding confirmation.

## Correct next investigation

Identify the actual application-side storage unwrap mechanism (not the generic JNI aesDecrypt symbol) and test only owner-authorized local use of an *existing* binding credential. Avoid new binding, silent reset, OTA, deletion commands, replaying captured 0B/8B frames, and sending raw credentials to chat/CI/GitHub.

The feature branch now treats a D3200 advertisement with bound_bit_set and no authorized credential provider as a fail-closed condition after read-only 2E/01 and 01/01. This does not recover the key; it prevents the repeatedly observed FF recording/file commands on a known-bound recorder. Unknown/unbound advertisement states retain their prior behavior.
