# Pocket HUD 1.0.68 — Soundcore Work D3200 device log summary

Date: 2026-11-22  
Device: Soundcore Work D3200, firmware 03.56  
Host: Motorola Edge 50 Pro, Android 16  
App: Pocket HUD 1.0.68 (versionCode 72)

This is a redacted summary of the user-provided diagnostic log. No Bluetooth address, recording/file ID, UID, key, nonce, ciphertext, raw payload, or audio data is included.

## Latest connected run

| Check | Result |
|---|---|
| BLE scan | First attempt missed device; second attempt found it |
| Advertisement bound bit | Set |
| GATT / service discovery | Pass |
| MTU | 512 |
| Notifications | Active |
| ECDH 2E/01 | Pass |
| Device info 01/01 | Pass |
| Firmware | 03.56 |
| Pocket HUD auth 0B/8B | Not attempted |
| Remote recording start 18/82 | Refused (FF) |
| Physical-button recording event 1A/06 | Received |
| Realtime 1A/07 | Refused (FF); payload 9 bytes |
| File-list 1B/0E | Refused (FF) |
| Legacy file-list 1A/0E | Refused (FF) |
| Completed-file 1A/07 | Refused (FF); payload 8 bytes; repeated after 5 seconds |
| Audio slices 1A/08 | None |
| Transfer completion 1A/0A | None |
| Opus / PCM / PCM queue | 0 / 0 / 0 |
| PCM sent to Gemini | 0 |

The full TX frame lengths are 19 bytes for realtime (9-byte command payload) and 18 bytes for completed-file pull (8-byte command payload). These lengths agree with the observed official-app completed-file request and Pocket HUD's current realtime request format.

## Interpretation

The BLE transport path is working through GATT, notifications, ECDH, and device-info read. The device rejects recording control and both file-transfer modes before any audio data arrives. This log does not test or validate the Gemini Live, Opus decoder, or PCM stages because they receive no audio.

The advertisement's bound bit is set. This is a fact from the log, not proof that the bound state causes the refusal.

The next investigation is the official app's `0B/8B` and `1A/A7` sequence. The current Pocket HUD build explicitly reports that `0B/8B` authentication has not been attempted.
