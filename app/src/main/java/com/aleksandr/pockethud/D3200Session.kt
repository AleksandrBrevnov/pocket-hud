package com.aleksandr.pockethud

import com.aleksandr.pockethud.D3200Protocol.Command
import com.aleksandr.pockethud.D3200Protocol.u32
import com.aleksandr.pockethud.D3200Protocol.u8
import java.security.KeyPair
import java.security.interfaces.ECPublicKey

internal class D3200Session(
    private val send: (ByteArray) -> Unit,
    private val status: (String) -> Unit,
    private val deadline: (String, Long) -> Unit,
    private val onOpus: (ByteArray) -> Unit,
    private val close: () -> Unit
) {
    private enum class Phase { NEW, HANDSHAKE, INFO, START, FILE_EVENT, LIST, LEGACY_LIST, HEAD, AUDIO, STOP, CLOSED }

    private var phase = Phase.NEW
    private var keyPair: KeyPair? = null
    private var sessionKey: ByteArray? = null
    private var fileKey: ByteArray? = null
    private var nonce: ByteArray? = null
    private var notifiedFileId: Long? = null
    private var startedByPocketHud = false
    private var stopRequested = false
    private var maxSequence = -1L
    private var opusCount = 0L

    fun begin() {
        check(phase == Phase.NEW)
        val pair = D3200Crypto.generate()
        keyPair = pair
        status("Обмен ключами ECDH")
        request(Phase.HANDSHAKE, Command.HANDSHAKE.frame(D3200Crypto.encode(pair.public as ECPublicKey)))
    }

    fun receive(frame: D3200Protocol.Frame) {
        if (phase == Phase.CLOSED) return
        val payload = frame.payload
        when ((frame.type shl 8) or frame.id) {
            0x2e01 -> if (phase == Phase.HANDSHAKE) {
                check(!frame.isRejected) { "D3200 отказал в обмене ключами" }
                sessionKey = D3200Crypto.sessionKey(requireNotNull(keyPair), payload)
                keyPair = null
                status("ECDH подтверждён")
                request(Phase.INFO, Command.INFO.frame())
            }
            0x0101 -> if (phase == Phase.INFO) {
                check(!frame.isRejected && payload.size > 50) { "Состояние записи D3200 недоступно" }
                val firmware = payload.copyOfRange(3, 8).toString(Charsets.US_ASCII)
                if (firmware.matches(Regex("[0-9]{2}\\.[0-9]{2}"))) status("Прошивка $firmware")
                when (u8(payload, 50)) {
                    0 -> request(Phase.START, Command.START.frame())
                    1 -> {
                        status("Запись уже идёт")
                        request(Phase.LIST, Command.LIST.frame())
                    }
                    else -> error("Неизвестное состояние записи D3200")
                }
            }
            0x1882 -> when (phase) {
                Phase.START -> if (payload.isEmpty() || payload[0] != 0.toByte()) {
                    // The leading zero in a multi-byte 18/82 event identifies a device-side stop.
                    check(!frame.isRejected) { "D3200 отказал в запуске записи" }
                    startedByPocketHud = true
                    status("Запись запущена PocketHUD")
                    if (stopRequested) stop()
                    else if (notifiedFileId != null) startLive(requireNotNull(notifiedFileId))
                    else arm(Phase.FILE_EVENT, 6000)
                }
                Phase.STOP -> {
                    status(if (frame.isRejected) "D3200 отказал в остановке записи" else "Остановка D3200 подтверждена")
                    finish()
                }
                Phase.FILE_EVENT, Phase.HEAD, Phase.AUDIO -> if (!frame.isRejected && payload.size >= 10 && payload[0] == 0.toByte()) {
                    startedByPocketHud = false
                    error("Запись остановлена на Soundcore Work")
                }
                else -> Unit
            }
            0x1a06 -> if (!frame.isRejected && payload.size >= 4) {
                val id = u32(payload, 0)
                if (id != 0L) when (phase) {
                    Phase.START, Phase.FILE_EVENT -> {
                        notifiedFileId = id
                        if (phase == Phase.FILE_EVENT) startLive(id)
                    }
                    Phase.HEAD, Phase.AUDIO -> if (startedByPocketHud && id != notifiedFileId) {
                        startedByPocketHud = false
                        error("На D3200 началась другая запись")
                    }
                    else -> Unit
                }
            }
            0x1b0e -> if (phase == Phase.LIST) {
                val id = if (frame.isRejected) null else D3200Protocol.liveFileId(payload, legacy = false)
                if (id != null) startLive(id) else request(Phase.LEGACY_LIST, Command.LEGACY_LIST.frame())
            }
            0x1a0e -> if (phase == Phase.LEGACY_LIST) {
                val id = if (frame.isRejected) null else D3200Protocol.liveFileId(payload, legacy = true)
                startLive(checkNotNull(id) { "Номер активной записи D3200 недоступен" })
            }
            0x1a07 -> if (phase == Phase.HEAD) {
                check(!frame.isRejected) { "Устройство отказало в realtime BLE-запросе" }
                check(payload.size >= 87 && u8(payload, 86) == 0 && u32(payload, 4) == 0xffffffffL) {
                    "Устройство отказало в realtime BLE-запросе (заголовок)"
                }
                val headId = u32(payload, 0)
                check(headId != 0L && (!startedByPocketHud || headId == notifiedFileId)) {
                    "Идентификатор записи D3200 изменился"
                }
                fileKey = D3200Crypto.unwrap(requireNotNull(sessionKey), payload.copyOfRange(70, 86),
                    payload.copyOfRange(24, 70))
                nonce = payload.copyOfRange(8, 24)
                status("Realtime открыт, file ID=$headId. Ожидание Opus")
                arm(Phase.AUDIO, 6000)
            }
            0x1a08, 0x1a12 -> if (phase == Phase.AUDIO && !frame.isRejected &&
                (frame.id == 8 || (payload.isNotEmpty() && payload.size % 166 == 0))) {
                for (slice in D3200Protocol.slices(payload)) {
                    if (slice.sequence <= maxSequence) continue
                    val iv = D3200Crypto.counter(requireNotNull(nonce), slice.sequence)
                    val packet = D3200Crypto.ctr(requireNotNull(fileKey), iv, slice.ciphertext)
                    maxSequence = slice.sequence
                    if (opusCount++ == 0L) status("Получен Opus")
                    onOpus(packet)
                }
                arm(Phase.AUDIO, 6000)
            }
            0x1a0a -> if (phase == Phase.AUDIO && !frame.isRejected) {
                startedByPocketHud = false
                error("D3200 завершил поток")
            }
        }
    }

    fun stop() {
        stopRequested = true
        when {
            phase == Phase.STOP || phase == Phase.CLOSED -> return
            startedByPocketHud -> request(Phase.STOP, Command.STOP.frame(), 3000)
            phase == Phase.START -> arm(Phase.START, 3000)
            else -> finish()
        }
    }

    fun timeout() {
        when (phase) {
            Phase.LIST -> request(Phase.LEGACY_LIST, Command.LEGACY_LIST.frame())
            Phase.START -> {
                status("Подтверждение запуска D3200 не получено")
                finish()
            }
            Phase.STOP -> {
                status("Подтверждение остановки D3200 не получено")
                finish()
            }
            Phase.CLOSED -> Unit
            else -> {
                status(if (phase == Phase.AUDIO) "Нет аудио от D3200" else "Истекло ожидание D3200 ($phase)")
                stop()
            }
        }
    }

    fun discard() {
        phase = Phase.CLOSED
        keyPair = null
        sessionKey?.fill(0)
        sessionKey = null
        fileKey?.fill(0)
        fileKey = null
        nonce = null
    }

    private fun startLive(fileId: Long) {
        status("Запрос realtime, file ID=$fileId")
        request(Phase.HEAD, Command.LIVE.frame(fileId = fileId))
    }

    private fun request(next: Phase, request: ByteArray, ms: Long = 6000) {
        arm(next, ms)
        send(request)
    }

    private fun arm(next: Phase, ms: Long) {
        phase = next
        deadline(next.name, ms)
    }

    private fun finish() {
        discard()
        close()
    }
}
