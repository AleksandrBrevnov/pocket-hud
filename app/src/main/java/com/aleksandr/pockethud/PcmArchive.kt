package com.aleksandr.pockethud

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Bounded, asynchronous PCM16/16kHz/mono archive. Does not open a microphone. */
class PcmArchive(
    private val directory: File,
    private val sessionId: String,
    capacity: Int = 200,
    private val maxDataBytes: Int = 16000 * 2 * 600,
    private val beforeWrite: () -> Unit = {},
    private val onFailure: (String) -> Unit = {}
) {
    private val queue = LinkedBlockingQueue<ByteArray>(capacity)
    private val stateLock = Any()
    @Volatile private var accepting = true
    @Volatile var failure: String? = null
        private set
    @Volatile var bytesWritten = 0L
        private set
    @Volatile var currentFile: File? = null
        private set

    init {
        require(maxDataBytes > 0 && maxDataBytes % 2 == 0)
        require(sessionId.matches(Regex("[A-Za-z0-9_-]+")))
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create audio directory" }
    }

    private val worker = Thread({ writeLoop() }, "PocketHUD-archive").apply { start() }

    fun offer(pcm: ByteArray): Boolean {
        require(pcm.size % 2 == 0) { "PCM16 requires complete samples" }
        if (pcm.isEmpty()) return true
        var overflow = false
        synchronized(stateLock) {
            if (!accepting) return false
            if (queue.offer(pcm.copyOf())) return true
            accepting = false
            failure = "Archive queue full; recording is incomplete"
            overflow = true
        }
        if (overflow) onFailure(failure!!)
        return false
    }

    /** Returns false if storage is still blocked; caller must not claim finalization. */
    fun close(): Boolean {
        synchronized(stateLock) { accepting = false }
        worker.join(2000)
        return !worker.isAlive
    }

    private fun writeLoop() {
        var output: RandomAccessFile? = null
        var part: File? = null
        var size = 0
        var index = 0
        fun finalizePart() {
            val out = output ?: return
            writeHeader(out, size)
            out.fd.sync()
            out.close()
            output = null
            val source = part!!
            val suffix = if (failure == null) ".wav" else ".wav.incomplete"
            val target = File(directory, source.name.removeSuffix(".wav.part") + suffix)
            if (!source.renameTo(target)) throw IOException("Cannot finalize ${source.name}")
            currentFile = target
        }
        try {
            while (accepting || queue.isNotEmpty()) {
                val pcm = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                beforeWrite()
                var offset = 0
                while (offset < pcm.size) {
                    if (output == null) {
                        if (directory.usableSpace < 16L * 1024 * 1024) throw IOException("Not enough space for audio")
                        index++
                        part = File(directory, "record_${sessionId}_${index.toString().padStart(4, '0')}.wav.part")
                        if (!part!!.createNewFile()) throw IOException("Recording already exists")
                        currentFile = part
                        output = RandomAccessFile(part, "rw")
                        size = 0
                        writeHeader(output!!, 0)
                    }
                    val amount = minOf(pcm.size - offset, maxDataBytes - size)
                    output!!.seek(44L + size)
                    output!!.write(pcm, offset, amount)
                    size += amount
                    bytesWritten += amount
                    offset += amount
                    // Keep a recoverable header even if the process is killed.
                    writeHeader(output!!, size)
                    if (size == maxDataBytes) finalizePart()
                }
            }
            finalizePart()
        } catch (e: Exception) {
            synchronized(stateLock) {
                accepting = false
                failure = e.message ?: e.javaClass.simpleName
            }
            onFailure(failure!!)
        } finally {
            try { output?.close() } catch (e: IOException) {
                failure = failure ?: e.message
            }
            queue.clear()
        }
    }

    private fun writeHeader(out: RandomAccessFile, size: Int) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + size)
        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        header.putShort(1).putShort(1).putInt(16000).putInt(32000)
        header.putShort(2).putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII)).putInt(size)
        out.seek(0)
        out.write(header.array())
    }
}
