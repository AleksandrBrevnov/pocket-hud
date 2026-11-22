# Official Soundcore App 5.0.39 → D3200 FW 03.56: verified HCI observations

Captured from the user-supplied Android bugreport ZIP dated 2026-11-22.
This report is privacy-redacted. No HCI binary, account UID, device serial,
Bluetooth address, recording/file ID, encrypted payload, nonce, key, or audio is
included. Original ZIP remains local to the conversation; never commit it.

## Capture validity

- H4 BTSnoop with 38,021 records; 490 checksum-validated Soundcore frames.
- The relevant official-app D3200 command exchange begins at
  2026-11-22 14:44:11 UTC (15:44:11 CET).
- 446 `1A/08` notifications contain one 166-byte audio transfer unit each.
  Their sequence numbers run 0–445 without gaps or duplicates.
- First audio-unit flags `02`, last flags `04`, 444 middle flags `00`.
- 446 units at 20 ms per frame correspond to 8.92 seconds of source audio.
  The complete BLE burst of those units lasts about 1.5 seconds.

## Observed command sequence and shapes

| Order | Direction | Command | Payload bytes | Validated observation |
|---|---|---|---:|---|
| 1 | TX / RX | `01/01` | 0 / 65 | Device-info request/response |
| 2 | TX / RX | `0B/8B` | 62 / 1 | TX layout `[2C][44 encrypted][10][16 nonce]`; RX one result byte `00` with successful frame status |
| 3 | TX / RX | `1A/A7` | 1 / 0 | TX value `01`; successful empty ACK, repeated three times |
| 4 | TX / RX | `1A/0E` | 2 / 66 | TX page `0000`; successful 66-byte list response, repeated four times |
| 5 | RX | `1A/06` | 8 | Recording/rotation notification |
| 6 | TX / RX | `1A/07` | 8 / 87 | Completed-file-style request (no ninth realtime flag byte), 87-byte response header |
| 7 | TX / RX | `18/82` | 2 / 2 | TX control `02 01` (pause), acknowledged |
| 8 | RX | `1A/08` | 166 | 446 contiguous encrypted audio units |
| 9 | RX / TX | `1A/0A` | 9 / 1 | Transfer completion then host response |
| 10 | TX / RX | `1A/10` | 4 / 0 | Official app issued delete; **Pocket HUD must not do this** |

The initial auth reply occurs about 64 ms after the `0B/8B` write.
The first `1A/A7` write follows roughly 10 ms later.
The completed-file header arrives about 30 ms after its request.
The pause is issued about 6.2 seconds after receiving that header.

## What is and is not established

**Established:** The official app can authenticate, obtain file metadata,
and download a paused recording over BLE on this physical firmware 03.56 unit.
`1A/A7` uses a one-byte `01` request and an empty success ACK. The
`0B/8B` request contains 44 encrypted bytes and a 16-byte nonce, each
preceded by a one-byte length.

**Not established:** The plaintext schema of the 44 encrypted bytes; the
persistent binding key used by the official app; the exact semantics of
`1A/A7`; how the official app unwraps the file key without an observed
`2E/01` exchange; and whether Android/fw 03.56 supports *unpaused* live BLE
audio. The decrypted audio waveform is not recoverable from this recording
without the necessary session/key material.

The public `ws/soundcore` builder uses 19 UID bytes + a four-byte timestamp,
and therefore encrypts only 23 bytes; the captured 44-byte field is **not**
equivalent. A 40-byte UID + timestamp is a possible explanation of the length,
**not evidence of the plaintext structure**. Do not assume it or replay
the captured ciphertext and nonce.

## Engineering consequence

- The 1.0.68 Pocket HUD `FF` refusals occur before Opus decoding.
- Plain ECDH `2E/01` is not a substitute for the official `0B/8B` proof
  when the recorder is already bound.
- Both commands are intentionally **observation-only** in Pocket HUD for now.
- Avoid bind, unbind, silent reset, OTA, file deletion, and playback of captured
  authorization packets.
- To support the existing bound device non-destructively, obtain the
  **preexisting** binding credential from a legitimate source, then identify
  the matching plaintext schema and file-key derivation before enabling auth.

The original Android Bug Report ZIP is sensitive and must not be uploaded to
public repositories, commit history, CI artifacts, or issue comments.
