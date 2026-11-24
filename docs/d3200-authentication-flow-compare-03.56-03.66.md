# D3200 BLE authentication flow comparison — firmware 03.56 vs 03.66

Date: 2026-11-24
Scope: compare the user's verified official Soundcore 5.0.39 capture on physical firmware 03.56 with the later public report for firmware 03.66 in tacshi/Soundcore issue #9.

## 1. Direct evidence: user's firmware 03.56

Source: `docs/d3200-official-app-capture-2026-11-22-verified.md`, derived from the user's original Android BTSnoop.

Observed sequence:
1. `01/01` — device info.
2. `0B/8B` — 62-byte request: `2C` + 44-byte ciphertext + `10` + 16-byte nonce. Response result `00`.
3. `1A/A7 01` — one-byte request, empty successful ACK, repeated three times.
4. `1A/0E` — page 0, file list.
5. `1A/06` — recording/rotation event.
6. `1A/07` — eight-byte completed-file-style request, 87-byte response head.
7. `18/82 02 01` — pause/control request.
8. 446 `1A/08` encrypted units, 166 bytes each, sequence 0–445 with no gaps.
9. `1A/0A` — transfer completion.
10. Official app then sends `1A/10` delete. Pocket HUD must never send this.

The capture contains **no** `2E/01`, `0B/03`, or `01/A6` commands. The transfer is a post-pause burst of 8.92 seconds of audio, not proof of unpaused live streaming.

## 2. Public report: firmware 03.66

Source: https://github.com/tacshi/Soundcore/issues/9#issuecomment-6868992977

The report describes a different flow:
1. `01/01` — device info.
2. `05/01` — TLV info.
3. `0B/8B` — 62-byte authorization request, result `00`.
4. `1A/A7 01` — empty successful ACK.
5. `01/A6`, `05/01`, `05/81` — accepted after auth.
6. `1A/0E` file list.
7. `1A/0F 00` start BT transport.
8. `1A/07` with eight-byte offset + file ID, then `1A/08` slices.

The report explicitly says this flow does **not** send `2E/01`. It also notes that replaying a captured `0B/8B` request fails.

A later comment in the same issue gives a candidate authorization plaintext for 03.66:
- 40-character hexadecimal account ID (40 ASCII bytes);
- Unix timestamp in 4-byte little-endian seconds;
- AES-256-CTR with a persistent 32-byte binding key and fresh 16-byte nonce;
- envelope: 1-byte ciphertext length (44), 44-byte ciphertext, 1-byte nonce length (16), 16-byte nonce.

The candidate matches the user's 03.56 observed envelope dimensions and their local finding of a 40-hex account identifier. This makes the construction structurally plausible for 03.56, but does not independently prove the exact plaintext or key for 03.56.

## 3. Important distinction: two ECDH purposes

Do not conflate:
- `0B/87 → 0B/88`: initial persistent binding exchange described in the public report. Its ECDH result is used to derive a persistent binding key for later `0B/8B` proofs.
- `2E/01`: per-session/file-transfer ECDH exchange used by the older public file-decryption path to derive a session key for file-key unwrapping.

The user's official 03.56 capture includes neither exchange during the recorded session. Its `0B/8B` proof is accepted without a visible `2E/01` first. The file-head key unwrap therefore cannot be assumed to use the current Pocket HUD `2E/01` session key without further evidence.

The public issue's proposed method for obtaining the persistent binding key requires unbinding/rebinding and capturing `0B/88`. That is explicitly out of scope for this user's already-bound device. Do not perform it.

## 4. Consequence for current Pocket HUD branch

The current generic `D3200Session.begin()` sends `2E/01` before `01/01`, then optionally attempts `0B/8B`. It does not send `1A/A7`. This does not reproduce the verified official 03.56 sequence.

Do not patch this by blindly adding more commands. First:
1. identify the existing local Keystore key characteristics via the read-only v3 probe;
2. determine whether the two app-owned Keystore Client keys are AES/EC/RSA and what purposes/modes they allow;
3. understand how the two 64-byte encrypted preference values are wrapped and whether they correspond to the auth/session records;
4. establish the exact 03.56 file-key unwrap source.

## 5. Safety and implementation boundaries

- No unbind, rebind, reset, OTA, file delete, or destructive pairing action.
- Never replay captured `0B/8B` ciphertext/nonces.
- Never upload account IDs, key aliases, preference values, key blobs, raw BTSnoop, or decrypted secrets to GitHub.
- `1A/10` must remain forbidden in Pocket HUD.
- Treat 03.56 and 03.66 as separate firmware profiles until a cross-version equivalence is proven.
- Do not claim true live streaming from the current capture; only paused active-file retrieval is proven.

## 6. Next step

Run `tools/d3200_keystore_metadata_probe_v3.py` on the user's device. Its output is limited to public KeyMint key characteristics and does not disclose aliases or key material.
