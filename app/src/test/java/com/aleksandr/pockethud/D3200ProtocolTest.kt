package com.aleksandr.pockethud

import org.junit.Assert.*
import org.junit.Test

class D3200ProtocolTest {
    @Test fun txLengthAndChecksum() {
        val tx = D3200Protocol.Command.START.frame()
        assertEquals("08ee00000018820b00019c", tx.hex())
        assertEquals(11, D3200Protocol.u16(tx, 7))
    }

    @Test fun splitJoinedAndCorruptNotifications() {
        val parser = D3200Protocol.Parser()
        val rejected = rx(0x1a, 7, byteArrayOf(-1))
        val valid = rx(1, 1, byteArrayOf(0))
        val corrupt = valid.copyOf().apply { this[lastIndex] = 99 }
        assertTrue(parser.append(byteArrayOf(42) + valid.copyOfRange(0, 3)).isEmpty())
        val frames = parser.append(valid.copyOfRange(3, valid.size) + corrupt + rejected)
        assertEquals(listOf(1, 0x1a), frames.map { it.type })
        assertFalse(frames[0].isRejected)
        assertTrue(frames[1].isRejected)
    }

    @Test fun parsesMultipleSlicesAndIgnoresTransportPadding() {
        val bytes = ByteArray(332).apply {
            this[0] = 7
            this[4] = 3
            this[5] = 42
            this[165] = 99
            this[166] = 8
            this[170] = 1
        }
        val slices = D3200Protocol.slices(bytes)
        assertEquals(listOf(7L, 8L), slices.map { it.sequence })
        assertEquals(listOf(3, 1), slices.map { it.flags })
        assertEquals(160, slices.first().ciphertext.size)
        assertEquals(42, slices.first().ciphertext.first().toInt())
        assertEquals(0, slices.first().ciphertext.last().toInt())
    }

    @Test fun outboundCommandsAreRestricted() {
        val expected = setOf(0x2e01, 0x0101, 0x1882, 0x1b0e, 0x1a0e, 0x1a07)
        assertEquals(expected, D3200Protocol.Command.entries.map { (it.type shl 8) or it.id }.toSet())
        assertEquals(0, D3200Protocol.Command.STOP.frame()[9].toInt())
        assertThrows(IllegalArgumentException::class.java) {
            D3200Protocol.Command.INFO.frame(byteArrayOf(1))
        }
    }


    @Test fun parsesLegacyAndCurrentRealtimeRotationEvents() {
        val legacy = D3200Protocol.recordingRotation(byteArrayOf(7, 0, 0, 0, 1))!!
        assertEquals(7L, legacy.fileId)
        assertNull(legacy.durationMs)
        assertTrue(legacy.appendPreAudio)

        val current = D3200Protocol.recordingRotation(
            byteArrayOf(8, 0, 0, 0, 0xB8.toByte(), 0x0B, 0, 0, 1)
        )!!
        assertEquals(8L, current.fileId)
        assertEquals(3000L, current.durationMs)
        assertTrue(current.appendPreAudio)

        assertFalse(D3200Protocol.recordingRotation(byteArrayOf(9, 0, 0, 0, 0))!!.appendPreAudio)
        assertNull(D3200Protocol.recordingRotation(byteArrayOf(1, 2, 3)))
        assertNull(D3200Protocol.recordingRotation(byteArrayOf(0, 0, 0, 0)))
    }

    @Test fun parsesTargetedAndLegacyRealtimeCompletionEvents() {
        val complete = D3200Protocol.fileCompletion(
            byteArrayOf(0, 0xE8.toByte(), 0x03, 0, 0, 42, 0, 0, 0)
        )
        assertEquals(0, complete.result)
        assertEquals(1000L, complete.durationMs)
        assertEquals(42L, complete.fileId)

        val legacy = D3200Protocol.fileCompletion(byteArrayOf(1))
        assertEquals(1, legacy.result)
        assertEquals(0L, legacy.durationMs)
        assertEquals(0L, legacy.fileId)

        val empty = D3200Protocol.fileCompletion(byteArrayOf())
        assertEquals(0, empty.result)
        assertEquals(0L, empty.fileId)
    }

    companion object {
        fun rx(type: Int, id: Int, payload: ByteArray, status: Int = 1): ByteArray {
            val size = 10 + payload.size
            val data = byteArrayOf(9, -1, 0, 0, status.toByte(), type.toByte(), id.toByte(),
                size.toByte(), (size shr 8).toByte()) + payload
            return data + (data.sumOf { it.toInt() and 255 } and 255).toByte()
        }
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
