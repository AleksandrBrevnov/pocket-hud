# Official Soundcore App 5.0.39 — D3200 BLE capture (firmware 03.56)

Date: 2026-11-22. Source: user-provided Android 16 bugreport ZIP. The raw archive and raw `btsnoop_hci.log` are **not** committed to this repository. This report contains only command metadata and counts. It does not contain Bluetooth addresses, serials, file IDs, UIDs, public keys, authentication ciphertext, nonces, or audio bytes.

## Capture integrity

- Android bugreport contains `FS/data/misc/bluetooth/logs/btsnoop_hci.log`.
- BTSnoop H4, 38,021 records; 14,838 HCI ACL packets; 618 ATT PDUs reconstructed.
- 490 valid D3200 vendor frames found on the GATT write/notification characteristic pair.
- ATT ACL/L2CAP fragmentation reassembled without truncation or overshoot.
- Frame additive checksums validated.
- Device firmware identified by the user's stated hardware configuration as 03.56; device info in the capture also returned the firmware string. The actual serial/MAC fields are deliberately not retained in this report.

## Observed successful official-app sequence

The official Soundcore App sent:

1. `01/01` device info.
2. `0B/8B` connection authentication once.
3. `1A/A7` setup command, repeated three times, each acknowledged.
4. `1A/0E` file-list requests.
5. Device event `1A/06` announcing a recording.
6. `1A/07` file-header request with an **8-byte payload** (no ninth realtime-mode byte); device returned a successful **87-byte header**.
7. `18/82` control payload `02 01`, acknowledged by the device.
8. Device sent **446 `1A/08` slices**, each 166 bytes. Slice sequence numbers are contiguous from 0 through 445. First slice flag is `0x02`; final slice flag is `0x04`. No sequence gaps or duplicates were observed in this batch.
9. Device sent `1A/0A` completion; the app acknowledged with `1A/0A`.
10. The app then sent `1A/10`, which the protocol implementations identify as **delete file**. This appears to be automatic cleanup after successful transfer. Pocket HUD must not copy this behaviour; never send `1A/10` or delete the user's recordings without an explicit future user request.

The 446 × 20 ms units represent 8.92 seconds of audio content by the protocol's documented unit duration. The radio transfer itself took about 1.5 seconds. The payload bytes are encrypted and were not retained or decoded for this report.

## Authentication finding

- The official app's `0B/8B` response contains a one-byte result of **0**, which the `ws/soundcore` protocol implementation defines as successful possession proof.
- The request payload was 62 bytes. Its visible length-prefix structure is: 44-byte encrypted block followed by a 16-byte nonce. **The encrypted bytes and nonce are not recorded here.**
- No `2E/01` ECDH handshake frame was observed in this official-app connection.
- No `0B/87` bind, `0B/8C` silent reset, `01/B8` factory reset, OTA, or unbind command was observed.
- The request shape differs from the public `ws/soundcore` implementation currently inspected: its builder constructs an auth payload from a 19-byte UID plus 4-byte timestamp (23 encrypted bytes), then a 16-byte nonce, i.e. 41 bytes total. The official app capture's 44-byte encrypted block is not explained by that model. Do not assume the public builder is compatible with the official app's firmware-03.56 authentication format.

## What this proves — and what it does not

**Proven on this user's D3200 / firmware 03.56:**
- BLE GATT notification path delivers many valid D3200 frames.
- The official app completes `0B/8B` authentication (result 0).
- The official app downloads a complete audio file over BLE using `1A/07`, `1A/08`, and `1A/0A`.

**Not yet proven:**
- That the captured transfer was realtime streaming. The 8-byte `1A/07` request has no realtime flag and is consistent with a completed-file pull. It must not be described as a live-stream success.
- That Pocket HUD can reuse the official app's authentication. The 62-byte request depends on a secret binding credential and fresh nonce; replaying captured ciphertext is not a valid authentication strategy.
- The source or exact meaning of the 44-byte encrypted block. It may encode a longer UID/identity and timestamp or a different legacy auth structure; the capture alone does not reveal its plaintext.

## Consequences for Pocket HUD

1. Current Pocket HUD performs `2E/01` ECDH and device-info read but does not perform the successful official-app `0B/8B` proof. This is now the leading concrete difference to investigate.
2. Do not send `0B/87`, `0B/8C`, `01/B8`, `1A/10`, OTA, unbind, or factory reset.
3. Do not hardcode or replay the captured 62-byte authentication request. It contains session-specific encrypted material and a nonce.
4. Next research task: establish where Soundcore App stores/derives its existing binding credential and why its 03.56 auth request uses a 44-byte encrypted block. Compare the official app's 01/01 identity fields with the documented UID input without publishing those values.
5. Implement an explicit, opt-in, read-only-after-auth Pocket HUD file-transfer diagnostic only after a valid key source and the firmware-03.56 auth format are understood. Never auto-delete a transferred recording.
6. Only after completed-file pull works should realtime be tested with the correct live-mode request, separately from file playback/decryption.

## Sources for protocol cross-check

- `ws/soundcore`: `crates/soundcore-d3200-protocol/src/session.rs`, `setup.rs`, `files.rs`, `commands.rs`.
- `imraywang/openbean`: `docs/PROTOCOL.md`.
- `tacshi/Soundcore`: `PROTOCOL.md`, `lib/ble/ble_file_pull.dart`.

These sources are cross-checks only. The command sequence and counts above are extracted from the user's own HCI capture.
