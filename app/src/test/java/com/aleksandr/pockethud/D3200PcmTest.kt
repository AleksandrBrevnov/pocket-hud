package com.aleksandr.pockethud

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class D3200PcmTest {
    @Test fun trimsZeroPaddedTransportSlot() {
        val slice = ByteArray(160)
        val packet = byteArrayOf(0xbc.toByte(), 42, 0, 99)
        packet.copyInto(slice)
        assertArrayEquals(packet, D3200Pcm.opusPacket(slice))
        assertThrows(IllegalArgumentException::class.java) { D3200Pcm.opusPacket(ByteArray(160)) }
    }

    @Test fun splitsAndReassembles16kPcmInto640ByteFrames() {
        val format = D3200Pcm(16000, 1, pcm16 = true)
        val sample = ByteArray(1280) { (it * 7).toByte() }
        val frames = mutableListOf<ByteArray>()
        format.append(sample.copyOfRange(0, 480), frames::add)
        assertTrue(frames.isEmpty())
        format.append(sample.copyOfRange(480, sample.size), frames::add)
        assertEquals(listOf(640, 640), frames.map { it.size })
        assertArrayEquals(sample, frames[0] + frames[1])
    }

    @Test fun decimates48kAndAttenuatesFrequencyThatWouldAlias() {
        fun amplitude(frequency: Int): Int {
            val pcm = D3200Pcm(48000, 1, pcm16 = true)
            val frames = mutableListOf<ByteArray>()
            val bytes = ByteArray(3840)
            for (i in 0 until 1920) {
                val sample = (12000 * sin(2 * PI * frequency * i / 48000)).toInt()
                bytes[2 * i] = sample.toByte()
                bytes[2 * i + 1] = (sample shr 8).toByte()
            }
            pcm.append(bytes.copyOfRange(0, 100), frames::add)
            pcm.append(bytes.copyOfRange(100, bytes.size), frames::add)
            assertEquals(listOf(640, 640), frames.map { it.size })
            val stable = frames.last()
            return (stable.indices step 2).maxOf { i ->
                abs((stable[i].toInt() and 255) or (stable[i + 1].toInt() shl 8))
            }
        }
        assertTrue(amplitude(1000) > 11000)
        assertTrue(amplitude(12000) < 150)
    }

    @Test fun rejectsWrongCodecOutputFormat() {
        assertThrows(IllegalArgumentException::class.java) { D3200Pcm(48000, 2, pcm16 = true) }
        assertThrows(IllegalArgumentException::class.java) { D3200Pcm(48000, 1, pcm16 = false) }
        assertThrows(IllegalArgumentException::class.java) { D3200Pcm(24000, 1, pcm16 = true) }
    }
}
