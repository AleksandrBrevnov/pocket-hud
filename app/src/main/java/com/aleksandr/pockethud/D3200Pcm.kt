package com.aleksandr.pockethud

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal class D3200Pcm(private val sampleRate: Int, channelCount: Int, pcm16: Boolean) {
    init {
        require(sampleRate == 16000 || sampleRate == 48000) { "Неподдерживаемая частота PCM ($sampleRate Гц)" }
        require(channelCount == 1 && pcm16) { "Декодер D3200 должен отдавать mono PCM16" }
    }

    private val history = ShortArray(filter.size)
    private var writeAt = 0
    private var phase = 0
    private var frame = ByteArray(640)
    private var frameOffset = 0

    fun append(bytes: ByteArray, onFrame: (ByteArray) -> Unit) {
        require(bytes.size % 2 == 0)
        for (offset in bytes.indices step 2) {
            val input = ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort()
            val sample = if (sampleRate == 16000) input.toInt() else downsample(input) ?: continue
            frame[frameOffset++] = sample.toByte()
            frame[frameOffset++] = (sample shr 8).toByte()
            if (frameOffset == frame.size) {
                onFrame(frame)
                frame = ByteArray(640)
                frameOffset = 0
            }
        }
    }

    private fun downsample(input: Short): Int? {
        history[writeAt] = input
        writeAt = (writeAt + 1) % history.size
        if (++phase < 3) return null
        phase = 0
        var sum = 0.0
        var index = writeAt
        for (coefficient in filter) {
            sum += history[index] * coefficient
            index = (index + 1) % history.size
        }
        return sum.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }

    companion object {
        private val filter = DoubleArray(95) { i ->
            // Blackman-windowed sinc, 6.9 kHz cutoff before decimation from 48 to 16 kHz.
            val offset = i - 47
            val cutoff = 6900.0 / 48000
            val sinc = if (offset == 0) 2 * cutoff else sin(2 * PI * cutoff * offset) / (PI * offset)
            val angle = 2 * PI * i / 94
            sinc * (0.42 - 0.5 * cos(angle) + 0.08 * cos(2 * angle))
        }.let { coefficients ->
            val gain = coefficients.sum()
            coefficients.map { it / gain }.toDoubleArray()
        }

        fun opusPacket(slice: ByteArray): ByteArray {
            require(slice.size == 160)
            // Both protocol references describe fixed transport slots with trailing zero padding.
            val last = slice.indexOfLast { it != 0.toByte() }
            require(last >= 1) { "Пустой пакет Opus D3200" }
            return slice.copyOf(last + 1)
        }
    }
}
