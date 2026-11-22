package com.aleksandr.pockethud

internal object D3200Protocol {
    private val rxHeader = byteArrayOf(9, -1, 0, 0)
    private const val MIN_FRAME = 10
    private const val MAX_FRAME = 65535
    private const val SLICE_SIZE = 166

    enum class Command(val type: Int, val id: Int) {
        HANDSHAKE(0x2e, 1), INFO(1, 1), START(0x18, 0x82), STOP(0x18, 0x82),
        LIST(0x1b, 0x0e), LEGACY_LIST(0x1a, 0x0e), LIVE(0x1a, 7);

        fun frame(
            publicKey: ByteArray = byteArrayOf(),
            fileId: Long = 0,
            realtimeFlag: Int = 1
        ): ByteArray {
            require(this == HANDSHAKE || publicKey.isEmpty())
            require(this == LIVE || fileId == 0L)
            require(this == LIVE || realtimeFlag == 1)
            val payload = when (this) {
                HANDSHAKE -> publicKey.also { require(it.size == 65 && it[0] == 4.toByte()) }
                INFO -> byteArrayOf()
                START -> byteArrayOf(1)
                STOP -> byteArrayOf(0)
                LIST, LEGACY_LIST -> ByteArray(2)
                LIVE -> {
                    require(fileId in 1..0xffffffffL)
                    require(realtimeFlag in 0..1)
                    ByteArray(4) + ByteArray(4) { (fileId shr (it * 8)).toByte() } + byteArrayOf(realtimeFlag.toByte())
                }
            }
            val length = MIN_FRAME + payload.size
            val bytes = byteArrayOf(8, 0xee.toByte(), 0, 0, 0, type.toByte(), id.toByte(),
                length.toByte(), (length shr 8).toByte()) + payload
            return bytes + checksum(bytes, 0, bytes.size)
        }
    }

    enum class RecordingSignal { ACK, STOPPED, RECORDING, PAUSED, REFUSED, UNKNOWN }

    data class Frame(val status: Int, val type: Int, val id: Int, val payload: ByteArray) {
        val successStatus: Boolean get() = status and 15 == 1
        val explicitRefusal: Boolean get() = payload.size == 1 && u8(payload, 0) == 255
        val isRejected: Boolean get() = !successStatus || explicitRefusal

        fun recordingSignal(): RecordingSignal {
            require(type == 0x18 && id == 0x82)
            if (explicitRefusal) return RecordingSignal.REFUSED
            return when (payload.firstOrNull()?.toInt()) {
                null -> if (successStatus) RecordingSignal.ACK else RecordingSignal.UNKNOWN
                0 -> RecordingSignal.STOPPED
                1 -> RecordingSignal.RECORDING
                2 -> RecordingSignal.PAUSED
                else -> RecordingSignal.UNKNOWN
            }
        }
    }

    data class Slice(val sequence: Long, val flags: Int, val ciphertext: ByteArray)

    data class RecordingRotation(
        val fileId: Long,
        val durationMs: Long?,
        val appendPreAudio: Boolean
    )

    data class FileCompletion(
        val result: Int,
        val durationMs: Long,
        val fileId: Long
    )

    class Parser {
        private var tail = byteArrayOf()

        fun append(fragment: ByteArray): List<Frame> {
            val data = tail + fragment
            val frames = mutableListOf<Frame>()
            var offset = 0
            while (offset <= data.size - rxHeader.size) {
                if (!rxHeader.indices.all { data[offset + it] == rxHeader[it] }) {
                    offset++
                    continue
                }
                if (data.size - offset < MIN_FRAME) break
                val length = u16(data, offset + 7)
                if (length !in MIN_FRAME..MAX_FRAME) {
                    offset++
                    continue
                }
                val end = offset + length
                if (end > data.size) break
                if (checksum(data, offset, end - 1) != data[end - 1]) {
                    offset++
                    continue
                }
                frames.add(Frame(u8(data, offset + 4), u8(data, offset + 5), u8(data, offset + 6),
                    data.copyOfRange(offset + 9, end - 1)))
                offset = end
            }
            tail = data.copyOfRange(offset, data.size)
            return frames
        }
    }

    fun slices(payload: ByteArray): List<Slice> {
        require(payload.isNotEmpty() && payload.size % SLICE_SIZE == 0) { "Размер BLE-среза" }
        return (payload.indices step SLICE_SIZE).map {
            Slice(u32(payload, it), u8(payload, it + 4), payload.copyOfRange(it + 5, it + 165))
        }
    }

    /**
     * Parse the 1A/06 new-file event.
     *
     * Legacy payload: fileId:u32le + appendPreAudio:u8.
     * Current payload: fileId:u32le + durationMs:u32le + appendPreAudio:u8.
     */
    fun recordingRotation(payload: ByteArray): RecordingRotation? {
        if (payload.size < 4) return null
        val fileId = u32(payload, 0)
        if (fileId == 0L) return null
        val durationMs = if (payload.size >= 8) u32(payload, 4) else null
        val appendIndex = when {
            payload.size >= 9 -> 8
            payload.size == 5 -> 4
            else -> -1
        }
        return RecordingRotation(
            fileId = fileId,
            durationMs = durationMs,
            appendPreAudio = appendIndex >= 0 && u8(payload, appendIndex) == 1
        )
    }

    /**
     * Parse 1A/0A: result:u8 + durationMs:u32le + fileId:u32le.
     * Older devices may send only the result byte.
     */
    fun fileCompletion(payload: ByteArray): FileCompletion {
        val result = payload.firstOrNull()?.let { it.toInt() and 255 } ?: 0
        if (payload.size < 9) return FileCompletion(result, 0L, 0L)
        return FileCompletion(result, u32(payload, 1), u32(payload, 5))
    }

    fun liveFileId(payload: ByteArray, legacy: Boolean): Long? {
        if (payload.size < 2) return null
        val stride = if (legacy) 8 else 12
        val count = u16(payload, 0)
        val end = 2 + count * stride
        if (end > payload.size) return null
        if (payload.size >= end + 8) {
            val active = u32(payload, end)
            if (active != 0L) return active
        }
        return (0 until count).maxOfOrNull { u32(payload, 2 + it * stride) }?.takeIf { it != 0L }
    }

    fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 255
    fun u16(bytes: ByteArray, offset: Int): Int = u8(bytes, offset) or (u8(bytes, offset + 1) shl 8)
    fun u32(bytes: ByteArray, offset: Int): Long =
        (0..3).sumOf { u8(bytes, offset + it).toLong() shl (it * 8) }

    private fun checksum(bytes: ByteArray, start: Int, end: Int): Byte {
        var sum = 0
        for (index in start until end) sum += u8(bytes, index)
        return sum.toByte()
    }
}
