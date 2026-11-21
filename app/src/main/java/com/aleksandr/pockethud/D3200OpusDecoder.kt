package com.aleksandr.pockethud

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class D3200OpusDecoder(private val onFrame: (ByteArray) -> Unit) : AutoCloseable {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var pcm: D3200Pcm? = null
    private var packetIndex = 0L
    private var closed = false

    init {
        try {
            val header = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("OpusHead".toByteArray(Charsets.US_ASCII))
                put(1)
                put(1)
                putShort(0)
                putInt(48000)
                putShort(0)
                put(0)
                flip()
            }
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 1).apply {
                setByteBuffer("csd-0", header)
                setByteBuffer("csd-1", ByteBuffer.allocate(8))
                setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply {
                    putLong(80_000_000)
                    flip()
                })
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            }
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (exception: Exception) {
            codec.release()
            throw exception
        }
    }

    fun decode(slice: ByteArray) {
        val packet = D3200Pcm.opusPacket(slice)
        drain()
        val index = codec.dequeueInputBuffer(2000)
        check(index >= 0) { "Декодер Opus перегружен" }
        checkNotNull(codec.getInputBuffer(index)).apply { clear(); put(packet) }
        codec.queueInputBuffer(index, 0, packet.size, packetIndex++ * 20000, 0)
        drain()
    }

    fun drain() {
        while (true) {
            when (val index = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> updatePcm(codec.outputFormat)
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> {
                    check(index >= 0) { "Неизвестный статус декодера Opus" }
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            if (pcm == null) updatePcm(codec.getOutputFormat(index))
                            val buffer = checkNotNull(codec.getOutputBuffer(index))
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            checkNotNull(pcm).append(bytes, onFrame)
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        }
    }

    private fun updatePcm(format: MediaFormat) {
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else AudioFormat.ENCODING_PCM_16BIT
        pcm = D3200Pcm(format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), encoding == AudioFormat.ENCODING_PCM_16BIT)
    }

    override fun close() {
        if (closed) return
        closed = true
        codec.release()
    }
}
