package com.aleksandr.pockethud

import com.aleksandr.pockethud.D3200Protocol.Command
import com.aleksandr.pockethud.D3200Protocol.RecordingSignal
import com.aleksandr.pockethud.D3200Protocol.u32
import com.aleksandr.pockethud.D3200Protocol.u8
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.util.ArrayDeque

internal class D3200Session(
    private val send: (ByteArray) -> Unit,
    private val status: (String) -> Unit,
    private val deadline: (String, Long) -> Unit,
    private val onOpus: (ByteArray) -> Unit,
    private val close: () -> Unit,
    private val trace: (String) -> Unit = {}
) {
    private enum class Phase { NEW, HANDSHAKE, INFO, START, FILE_EVENT, VERIFY_START, LIST, LEGACY_LIST, MANUAL_START, HEAD, AUDIO, WAIT_ROTATION, STOP, CLOSED }

    private var phase = Phase.NEW
    private var keyPair: KeyPair? = null
    private var sessionKey: ByteArray? = null
    private var fileKey: ByteArray? = null
    private var nonce: ByteArray? = null
    private var notifiedFileId: Long? = null
    private val pendingFileIds = ArrayDeque<Long>()
    private var startedByPocketHud = false
    private var stopRequested = false
    private var startProbeReason = ""
    private var sawUnconfirmedRecording = false
    private var maxSequence = -1L
    private var opusCount = 0L
    private var fileOpusCount = 0L

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
            0x0101 -> if (phase == Phase.INFO || phase == Phase.VERIFY_START) handleInfo(frame)
            0x1882 -> handleRecordingSignal(frame)
            0x1a06 -> if (!frame.explicitRefusal) {
                val event = D3200Protocol.recordingRotation(payload)
                if (event != null) {
                    val id = event.fileId
                    trace("recording_rotation fileId=$id durationMs=${event.durationMs} appendPreAudio=${event.appendPreAudio} phase=$phase")
                    when (phase) {
                        Phase.HANDSHAKE, Phase.INFO, Phase.VERIFY_START -> notifiedFileId = id
                        Phase.START -> {
                            notifiedFileId = id
                            if (!stopRequested && sawUnconfirmedRecording) probeStart("file_event_without_confirmed_ack")
                        }
                        Phase.FILE_EVENT, Phase.LIST, Phase.LEGACY_LIST, Phase.MANUAL_START -> {
                            pendingFileIds.clear()
                            startLive(id)
                        }
                        Phase.HEAD -> queueRotation(id)
                        Phase.AUDIO, Phase.WAIT_ROTATION -> {
                            if (id != notifiedFileId) startLive(id)
                            else trace("duplicate_rotation_ignored fileId=$id")
                        }
                        else -> Unit
                    }
                }
            }
            0x1b0e -> if (phase == Phase.LIST) {
                val id = if (frame.isRejected) null else D3200Protocol.liveFileId(payload, legacy = false)
                if (id != null) startLive(id) else request(Phase.LEGACY_LIST, Command.LEGACY_LIST.frame())
            }
            0x1a0e -> if (phase == Phase.LEGACY_LIST) {
                val id = if (frame.isRejected) null else D3200Protocol.liveFileId(payload, legacy = true)
                if (id != null) startLive(id) else awaitManualStart(alreadyRecording = true)
            }
            0x1a07 -> if (phase == Phase.HEAD) {
                trace("realtime result=${if (frame.isRejected) "rejected" else "response"} status=${frame.status}")
                check(!frame.isRejected) { "Устройство отказало в realtime BLE-запросе" }
                check(payload.size >= 87 && u8(payload, 86) == 0 && u32(payload, 4) == 0xffffffffL) {
                    "Устройство отказало в realtime BLE-запросе (заголовок)"
                }
                val headId = u32(payload, 0)
                check(headId != 0L && headId == notifiedFileId) {
                    "Идентификатор realtime-записи не совпадает с запрошенным"
                }
                fileKey?.fill(0)
                fileKey = D3200Crypto.unwrap(requireNotNull(sessionKey), payload.copyOfRange(70, 86),
                    payload.copyOfRange(24, 70))
                nonce?.fill(0)
                nonce = payload.copyOfRange(8, 24)
                maxSequence = -1L
                fileOpusCount = 0L
                trace("realtime result=accepted fileId=$headId")
                if (!startNextLive()) {
                    status("Realtime открыт, file ID=$headId. Ожидание Opus")
                    arm(Phase.AUDIO, 6000)
                } else {
                    trace("realtime_header_followed_by_queued_rotation fileId=$headId")
                }
            }
            0x1a08, 0x1a12 -> if (phase == Phase.AUDIO && !frame.isRejected &&
                (frame.id == 8 || (payload.isNotEmpty() && payload.size % 166 == 0))) {
                for (slice in D3200Protocol.slices(payload)) {
                    if (slice.sequence <= maxSequence) continue
                    val iv = D3200Crypto.counter(requireNotNull(nonce), slice.sequence)
                    val packet = D3200Crypto.ctr(requireNotNull(fileKey), iv, slice.ciphertext)
                    maxSequence = slice.sequence
                    fileOpusCount++
                    if (opusCount++ == 0L) status("Получен Opus")
                    onOpus(packet)
                }
                arm(Phase.AUDIO, 6000)
            }
            0x1a0a -> if (phase == Phase.AUDIO && !frame.isRejected) {
                val completion = D3200Protocol.fileCompletion(payload)
                val currentFileId = notifiedFileId
                val matchesCurrent = completion.fileId != 0L && completion.fileId == currentFileId
                val untargetedCurrent = completion.fileId == 0L && fileOpusCount > 0L
                if (matchesCurrent || untargetedCurrent) {
                    trace("realtime_complete result=${completion.result} durationMs=${completion.durationMs} fileId=${completion.fileId} currentFileId=$currentFileId")
                    check(completion.result == 0) {
                        "D3200 завершил realtime с ошибкой ${completion.result}"
                    }
                    fileKey?.fill(0)
                    fileKey = null
                    nonce?.fill(0)
                    nonce = null
                    maxSequence = -1L
                    fileOpusCount = 0L
                    if (!startNextLive()) {
                        status("Realtime-сегмент завершён, ожидание следующего файла")
                        arm(Phase.WAIT_ROTATION, 60_000)
                    }
                } else {
                    trace("stale_or_empty_realtime_complete ignored fileId=${completion.fileId} currentFileId=$currentFileId opus=$fileOpusCount")
                }
            }
        }
    }

    private fun handleInfo(frame: D3200Protocol.Frame) {
        val payload = frame.payload
        check(!frame.isRejected && payload.size > 50) { "Состояние записи D3200 недоступно" }
        val firmware = payload.copyOfRange(3, 8).toString(Charsets.US_ASCII)
        if (firmware.matches(Regex("[0-9]{2}[.][0-9]{2}"))) status("Прошивка $firmware")
        val state = u8(payload, 50)
        trace("recording_info state=$state phase=$phase")
        if (phase == Phase.VERIFY_START) {
            if (state != 1) startedByPocketHud = false
            if (state == 0) {
                trace("remote_start_not_confirmed reason=$startProbeReason")
                awaitManualStart(alreadyRecording = false)
                return
            }
            check(state == 1) { "Запуск записи D3200 не подтверждён (состояние $state)" }
            if (notifiedFileId == null) startedByPocketHud = false
            followActiveRecording()
            return
        }
        when (state) {
            0 -> if (notifiedFileId == null) request(Phase.START, Command.START.frame())
                 else probeStart("file_event_before_idle_info")
            1 -> followActiveRecording()
            else -> error("D3200 не готов к запуску записи (состояние $state)")
        }
    }

    private fun handleRecordingSignal(frame: D3200Protocol.Frame) {
        val signal = frame.recordingSignal()
        trace("recording_signal phase=$phase signal=$signal successStatus=${frame.successStatus}")
        when (phase) {
            Phase.START -> when {
                signal == RecordingSignal.REFUSED -> {
                    if (stopRequested) finish() else probeStart("explicit_refusal")
                }
                signal == RecordingSignal.ACK ||
                    (signal == RecordingSignal.RECORDING && frame.successStatus && frame.payload.size == 1) -> {
                    startedByPocketHud = true
                    status("Запуск записи D3200 подтверждён")
                    val id = notifiedFileId
                    if (stopRequested) stop()
                    else if (id != null) startLive(id)
                    else arm(Phase.FILE_EVENT, 4500)
                }
                signal == RecordingSignal.RECORDING -> {
                    sawUnconfirmedRecording = true
                    if (notifiedFileId != null && !stopRequested) probeStart("file_event_without_confirmed_ack")
                }
                signal == RecordingSignal.PAUSED || signal == RecordingSignal.STOPPED ->
                    trace("awaiting_start_confirmation signal=$signal")
                else -> trace("awaiting_start_confirmation status=${frame.status}")
            }
            Phase.VERIFY_START -> if (signal == RecordingSignal.ACK ||
                (signal == RecordingSignal.RECORDING && frame.successStatus && frame.payload.size == 1)) {
                if (startProbeReason == "file_event_without_confirmed_ack" || startProbeReason == "confirmation_timeout") {
                    startedByPocketHud = true
                    status("Запуск записи D3200 подтверждён")
                }
            }
            Phase.STOP -> when (signal) {
                RecordingSignal.REFUSED -> {
                    status("D3200 отказал в остановке записи")
                    finish()
                }
                RecordingSignal.ACK, RecordingSignal.STOPPED -> {
                    status("Остановка D3200 подтверждена")
                    finish()
                }
                else -> Unit
            }
            Phase.FILE_EVENT, Phase.HEAD, Phase.AUDIO, Phase.WAIT_ROTATION -> if (
                signal == RecordingSignal.STOPPED && (frame.successStatus || frame.payload.size >= 10)
            ) {
                startedByPocketHud = false
                status("Запись остановлена на Soundcore Work")
                finish()
            }
            else -> Unit
        }
    }

    private fun probeStart(reason: String) {
        startProbeReason = reason
        trace("verify_start reason=$reason")
        request(Phase.VERIFY_START, Command.INFO.frame())
    }

    private fun awaitManualStart(alreadyRecording: Boolean) {
        startedByPocketHud = false
        notifiedFileId = null
        val instruction = if (alreadyRecording) {
            "ID текущей записи недоступен. Останови её и начни новую кнопкой D3200"
        } else {
            "Автозапуск не подтверждён. Начни запись кнопкой D3200"
        }
        arm(Phase.MANUAL_START, 90_000)
        status(instruction)
    }

    private fun followActiveRecording() {
        if (stopRequested) {
            stop()
            return
        }
        status(if (startedByPocketHud) "Запись запущена PocketHUD" else "На Soundcore Work уже идёт запись")
        val id = notifiedFileId
        if (id != null) startLive(id)
        else request(Phase.LIST, Command.LIST.frame())
    }

    fun disconnected() {
        if (phase == Phase.CLOSED) return
        trace("link_lost phase=$phase ownRecording=$startedByPocketHud")
        finish()
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
            Phase.LEGACY_LIST -> awaitManualStart(alreadyRecording = true)
            Phase.MANUAL_START -> {
                status("За 90 секунд D3200 не сообщил ID новой записи")
                finish()
            }
            Phase.START, Phase.FILE_EVENT -> {
                if (stopRequested) finish()
                else probeStart("confirmation_timeout")
            }
            Phase.STOP -> {
                status("Подтверждение остановки D3200 не получено")
                finish()
            }
            Phase.WAIT_ROTATION, Phase.CLOSED -> Unit
            else -> {
                if ((phase == Phase.HEAD || phase == Phase.AUDIO) && startNextLive()) {
                    trace("rotation_recovered_after_timeout phase=$phase")
                } else {
                    status(if (phase == Phase.AUDIO) "Нет аудио от D3200" else "Истекло ожидание D3200 ($phase)")
                    stop()
                }
            }
        }
    }

    fun discard() {
        if (phase != Phase.CLOSED) trace("transition from=$phase to=CLOSED ownership=$startedByPocketHud")
        phase = Phase.CLOSED
        keyPair = null
        sessionKey?.fill(0)
        sessionKey = null
        fileKey?.fill(0)
        fileKey = null
        nonce?.fill(0)
        nonce = null
        pendingFileIds.clear()
        fileOpusCount = 0L
    }

    private fun queueRotation(fileId: Long) {
        if (fileId == notifiedFileId || pendingFileIds.contains(fileId)) {
            trace("duplicate_rotation_ignored fileId=$fileId")
            return
        }
        if (pendingFileIds.size >= 8) {
            val dropped = pendingFileIds.removeFirst()
            trace("rotation_queue_overflow droppedFileId=$dropped")
        }
        pendingFileIds.addLast(fileId)
        trace("rotation_queued fileId=$fileId pending=${pendingFileIds.size}")
    }

    private fun startNextLive(): Boolean {
        val nextFileId = pendingFileIds.pollFirst() ?: return false
        startLive(nextFileId)
        return true
    }

    private fun startLive(fileId: Long) {
        require(fileId in 1..0xffffffffL) { "Некорректный ID realtime-файла D3200" }
        notifiedFileId = fileId
        fileKey?.fill(0)
        fileKey = null
        nonce?.fill(0)
        nonce = null
        maxSequence = -1L
        fileOpusCount = 0L
        status("Запрос realtime, file ID=$fileId")
        trace("realtime_request fileId=$fileId")
        request(Phase.HEAD, Command.LIVE.frame(fileId = fileId))
    }

    private fun request(next: Phase, request: ByteArray, ms: Long = 6000) {
        arm(next, ms)
        send(request)
    }

    private fun arm(next: Phase, ms: Long) {
        if (phase != next) trace("transition from=$phase to=$next ownership=$startedByPocketHud")
        phase = next
        deadline(next.name, ms)
    }

    private fun finish() {
        discard()
        close()
    }
}
