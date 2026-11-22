# Pocket HUD — D3200 firmware 03.56 audit

Date: 2026-11-22  
Working branch: `feature/soundcore-d3200-ble-input`  
Baseline: Pocket HUD 1.0.67, commit `08fcb832b39334ab0b597c049ad505394cbbcc61`

This document records the current protocol and architecture findings. It contains no Bluetooth addresses, file IDs, UIDs, keys, nonces, ciphertext, audio, or raw HCI data.

## 1. Evidence from Pocket HUD 1.0.67 on the user's device

Hardware: Soundcore Work D3200, firmware 03.56; Motorola Edge 50 Pro, Android 16.

- Advertisement is detected and reports the bound bit.
- GATT connection, service discovery, MTU negotiation, and notifications succeed.
- The app's `2E/01` ECDH exchange succeeds.
- `01/01` returns device information and firmware 03.56.
- The app's `18/82` start request with a one-byte start value is rejected with `FF`.
- Starting recording with the physical button produces `1A/06`.
- The nine-byte `1A/07` realtime request (realtime flag 1) is rejected with `FF`.
- After the user stops recording, both `1B/0E` and `1A/0E` list requests are rejected with `FF`.
- The eight-byte `1A/07` completed-file request is rejected twice, five seconds apart.
- No `1A/08` slices, `1A/0A` completion, Opus frames, decoded PCM, or queued PCM are received.

The 1.0.67 completed-file request length is therefore correct, but the device still refuses it. The failure is not explained by the old nine-byte request format alone.

## 2. Official Soundcore App 5.0.39 capture on the same firmware

The redacted BTSnoop audit documented the following successful sequence:

1. `01/01` device information.
2. `0B/8B` connection authentication; device result byte is `00`.
3. `1A/A7` setup command, sent three times and acknowledged each time.
4. `1A/0E` file-list request.
5. `1A/06` recording event.
6. `1A/07` header request with an eight-byte payload (no realtime-mode byte); device returns an 87-byte header.
7. `18/82` pause/control request with payload `02 01`, acknowledged.
8. 446 `1A/08` slices, 166 bytes each, sequence 0–445, no gaps or duplicates in that batch. First slice flag `0x02`, last slice flag `0x04`.
9. `1A/0A` transfer completion.
10. The app then sends `1A/10` (delete). Pocket HUD must never copy this cleanup behaviour.

The transfer contains 8.92 seconds of audio content. It proves that the official app can retrieve audio from an active recording over BLE after pausing it. It does **not** prove an uninterrupted, unpaused realtime stream. The capture did not establish a resume command, repeated transfer cycles, or the realtime flag value for the official app's request.

The official `0B/8B` request has a 62-byte payload: a 44-byte encrypted block and a 16-byte nonce, with length fields. No ciphertext or nonce is retained in this repository.

The official-app capture contains no `2E/01`, `0B/03`, or `01/A6` command. This is a different connection path from the current Pocket HUD implementation and from several public clients.

## 3. Public implementation comparison

| Project | Evidence / firmware | Relevant finding | Compatibility with 03.56 |
|---|---|---|---|
| [tacshi/Soundcore](https://github.com/tacshi/Soundcore) | Protocol notes reverse-engineered from the Feishu Android SDK; example firmware 4.92.3.06 | BLE GATT, file transfer, record control, and a separate live-transfer path | Useful protocol reference, not proof for 03.56 |
| [ws/soundcore](https://github.com/ws/soundcore) | Real D3200, firmware 03.44 | Offline BLE download; its README says BLE realtime does not send audio during recording and requires the iPhone-only MFi channel. Its secure-auth builders are test-vector-backed but explicitly not hardware-tested | Important older-firmware comparison; not a verified 03.56 implementation |
| [imraywang/openbean](https://github.com/imraywang/openbean) | Real D3200, firmware 04.82 | BLE file transfer and live audio are supported on that tested firmware | Shows newer firmware can support BLE live audio; not evidence for 03.56 |
| [andrewseago/d3200-ble-note-downloader](https://github.com/andrewseago/d3200-ble-note-downloader) | Real-recording download/decryption validation; firmware version not stated in its README | ECDH/AES offline file download; uses a different pre-handshake auth/time-sync sequence | Useful for crypto/file-format cross-checks, not a match for the 03.56 official capture |

The [FoloToy AI Passport D3200 emulator](https://github.com/lewangdev/ai-passport-soundcore) is an emulator, not a real D3200 hardware test. Its successful emulated realtime stream cannot establish what firmware 03.56 supports.

### Authentication-format mismatch

The inspected `ws/soundcore` builder encrypts `UID[19] || timestamp:u32le` (23 bytes) and appends a 16-byte nonce, producing a 41-byte payload. The official 03.56 capture has a 44-byte encrypted block and a 16-byte nonce (62 bytes including the length fields). The public builder therefore does not explain the official request shape.

Do not guess a longer UID, replay captured ciphertext, reuse a nonce, or send bind/reset commands. The existing binding-key source and the exact 03.56 UID/auth structure remain unresolved.

## 4. Current Pocket HUD architecture audit

- `D3200BleInput`: BLE scan, GATT connect, service/characteristic discovery, CCCD notification subscription, serialized writes, frame parsing, and session lifecycle. The latest device log confirms these transport steps work.
- `D3200Protocol`: RCSP-style frame parser, additive checksum, `1A/06` event parsing, `1A/07` request builders, and 166-byte slice parsing. The completed-file request is eight payload bytes.
- `D3200Session`: current state machine starts with `2E/01` ECDH, then `01/01`, attempts recording control, and requests `1A/07`. It does not implement the successful official `0B/8B` authentication or `1A/A7` setup sequence. It also does not reproduce the official pause-before-slice-transfer step.
- `D3200Crypto`: assumes the ECDH-derived session key unwraps the file key. The official-app capture has no `2E/01`; its header-key derivation path must be established before using this crypto path on 03.56.
- `D3200OpusDecoder` and `D3200Pcm`: the intended path is encrypted 160-byte Opus slot → AES-CTR decrypt → Android Opus decoder → mono PCM16 → 16 kHz resampling/framing. This has unit-test coverage but is not yet validated against actual 03.56 audio because no slices have reached Pocket HUD.
- `PocketService`: D3200 PCM is offered to the existing 16 kHz mono Gemini Live queue. Gemini Live setup and playback are separate from the observed BLE refusal; no evidence currently points to the Gemini pipeline as the blocker.
- BLE reconnect is not automatic in the current D3200 client. This must be tracked separately from Gemini WebSocket reconnects.

## 5. Current conclusions

1. BLE transport is operational; protocol authorization/initialization is the leading unresolved difference.
2. The official 03.56 app's `0B/8B` and `1A/A7` sequence must be understood before changing the transfer state machine.
3. The `0B/8B` payload is not compatible with the 23-byte-UID builder currently published in `ws/soundcore`.
4. The existing binding key must be obtained through a legitimate, non-destructive source. No bind, unbind, silent reset, factory reset, or OTA is acceptable.
5. The official capture proves paused active-file retrieval, not continuous live BLE streaming.
6. The exact semantics of `1A/A7`, the second byte in the official `18/82` pause payload, the post-transfer recording state, and the official file-key unwrap path remain open questions.

## 6. Next steps

1. Recover a sanitized command timeline from the original BTSnoop (or create a fresh official-app capture). Keep raw HCI and all secret fields local; share only the redacted audit JSON.
2. Inspect the official app 5.0.39 implementation or a second capture to identify the `1A/A7` payload/semantics and the exact `0B/8B` field layout without publishing ciphertext or nonces.
3. Establish a safe source for the already-existing binding credential. Do not rotate it.
4. Implement a firmware-specific 03.56 profile only after the authentication and file-key derivation are supported by evidence.
5. Test paused-file BLE retrieval first. Then separately test whether repeated pause/pull/resume cycles exist and whether a true unpaused realtime path is available.
6. Preserve the Gemini Live and PCM pipeline unless real audio frames reveal a downstream defect.
