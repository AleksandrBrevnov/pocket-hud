package com.aleksandr.pockethud

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

data class WavInfo(
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSize: Long,
    val durationSeconds: Double,
    val totalFrames: Long
)

object WavAudioClip {
    /**
     * Parses and validates a PCM WAV file header.
     * Enforces PCM16 mono 16kHz project standard, handles RIFF chunk padding,
     * validates declared RIFF size and chunk boundaries, and rejects corrupted files.
     */
    fun parseHeader(file: File): WavInfo {
        require(file.exists() && file.isFile) { "WAV file does not exist: ${file.absolutePath}" }
        val fileLen = file.length()
        require(fileLen >= 44L) { "File is too short to be a valid WAV ($fileLen bytes)" }

        RandomAccessFile(file, "r").use { raf ->
            val riffTag = readAscii(raf, 4)
            require(riffTag == "RIFF") { "Not a valid RIFF file (expected RIFF, got $riffTag)" }

            val riffSize = Integer.toUnsignedLong(readIntLE(raf))
            require(fileLen >= riffSize + 8L) {
                "WAV file is truncated: declared RIFF size is $riffSize (expected file length at least ${riffSize + 8L}, got $fileLen)"
            }

            val waveTag = readAscii(raf, 4)
            require(waveTag == "WAVE") { "Not a valid WAVE file (expected WAVE, got $waveTag)" }

            val maxRiffOffset = riffSize + 8L
            var hasFmt = false
            var hasData = false
            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var dataOffset = 0L
            var dataSize = 0L

            while (raf.filePointer < fileLen && raf.filePointer < maxRiffOffset) {
                if (raf.filePointer + 8L > fileLen || raf.filePointer + 8L > maxRiffOffset) break
                val chunkId = readAscii(raf, 4)
                val chunkSize = Integer.toUnsignedLong(readIntLE(raf))
                val chunkDataStart = raf.filePointer

                require(chunkDataStart + chunkSize <= fileLen && chunkDataStart + chunkSize <= maxRiffOffset) {
                    "Chunk '$chunkId' extends beyond declared boundaries: start=$chunkDataStart, size=$chunkSize, fileLen=$fileLen, maxRiff=$maxRiffOffset"
                }

                when (chunkId) {
                    "fmt " -> {
                        require(chunkSize >= 16L) { "Invalid fmt chunk size: $chunkSize" }
                        val audioFormat = readShortLE(raf).toInt()
                        channels = readShortLE(raf).toInt()
                        sampleRate = readIntLE(raf)
                        val byteRate = readIntLE(raf)
                        val blockAlign = readShortLE(raf).toInt()
                        bitsPerSample = readShortLE(raf).toInt()

                        require(audioFormat == 1) { "Unsupported audio format: $audioFormat (only uncompressed PCM format 1 is supported)" }
                        require(channels == 1) { "Unsupported channel count: $channels (only mono 1 channel is supported)" }
                        require(sampleRate == 16000) { "Unsupported sample rate: $sampleRate (only 16000 Hz is supported)" }
                        require(bitsPerSample == 16) { "Unsupported bit depth: $bitsPerSample (only 16-bit PCM is supported)" }
                        require(blockAlign == 2) { "Invalid block align: $blockAlign (expected 2 for 16-bit mono)" }
                        require(byteRate == 32000) { "Invalid byte rate: $byteRate (expected 32000 for 16kHz 16-bit mono)" }

                        hasFmt = true
                        raf.seek(chunkDataStart + chunkSize)
                    }
                    "data" -> {
                        dataOffset = chunkDataStart
                        dataSize = chunkSize
                        require(dataSize > 0L) { "WAV data chunk is empty (0 bytes)" }
                        require(dataSize % 2L == 0L) { "WAV data chunk size must be a multiple of 2 bytes for 16-bit PCM" }
                        hasData = true
                        raf.seek(chunkDataStart + chunkSize)
                    }
                    else -> {
                        // Skip unhandled chunk (e.g. LIST, JUNK, INFO)
                        raf.seek(chunkDataStart + chunkSize)
                    }
                }

                // RIFF chunk word-alignment: odd-sized chunks are padded with a null byte
                if (chunkSize % 2L != 0L) {
                    val padPos = chunkDataStart + chunkSize + 1L
                    if (padPos <= fileLen && padPos <= maxRiffOffset) {
                        raf.seek(padPos)
                    }
                }

                if (hasFmt && hasData) {
                    break
                }
            }

            require(hasFmt) { "WAV file is missing mandatory 'fmt ' chunk" }
            require(hasData) { "WAV file is missing mandatory 'data' chunk" }

            val totalFrames = dataSize / 2L
            val durationSeconds = dataSize.toDouble() / (sampleRate * channels * (bitsPerSample / 8.0))
            return WavInfo(
                channels = channels,
                sampleRate = sampleRate,
                bitsPerSample = bitsPerSample,
                dataOffset = dataOffset,
                dataSize = dataSize,
                durationSeconds = durationSeconds,
                totalFrames = totalFrames
            )
        }
    }

    /**
     * Extracts a frame-accurate PCM clip into a valid temporary WAV file without altering the original.
     */
    fun extractClip(
        sourceFile: File,
        startSeconds: Double,
        endSeconds: Double,
        destinationFile: File
    ): File {
        require(!startSeconds.isNaN() && !startSeconds.isInfinite()) { "startSeconds must be a finite number: $startSeconds" }
        require(!endSeconds.isNaN() && !endSeconds.isInfinite()) { "endSeconds must be a finite number: $endSeconds" }
        require(sourceFile.canonicalFile != destinationFile.canonicalFile) {
            "Source and destination files must be distinct: ${sourceFile.absolutePath}"
        }

        val info = parseHeader(sourceFile)
        require(startSeconds >= 0.0) { "Start time cannot be negative: $startSeconds" }
        require(endSeconds > startSeconds) { "End time ($endSeconds) must be greater than start time ($startSeconds)" }

        val epsilon = 0.001
        if (endSeconds > info.durationSeconds + epsilon) {
            throw IllegalArgumentException("End time ${String.format(Locale.US, "%.2f", endSeconds)}s exceeds duration ${String.format(Locale.US, "%.2f", info.durationSeconds)}s")
        }
        val effectiveEnd = minOf(endSeconds, info.durationSeconds)

        val totalFrames = info.totalFrames
        val startFrame = Math.round(startSeconds * info.sampleRate).toLong().coerceIn(0L, totalFrames)
        val endFrame = if (Math.abs(effectiveEnd - info.durationSeconds) < 1e-4) {
            totalFrames
        } else {
            Math.round(effectiveEnd * info.sampleRate).toLong().coerceIn(startFrame + 1L, totalFrames)
        }

        val frameCount = endFrame - startFrame
        require(frameCount > 0L) { "Extracted clip duration is 0 frames" }
        val slicePcmBytes = frameCount * 2L
        require(slicePcmBytes <= Int.MAX_VALUE) { "Slice size exceeds maximum allowable buffer" }

        val destParent = destinationFile.parentFile
        if (destParent != null && !destParent.exists()) {
            destParent.mkdirs()
        }

        // Build 44-byte standard RIFF header
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36L + slicePcmBytes).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort()) // PCM
        header.putShort(1.toShort()) // mono
        header.putInt(16000)
        header.putInt(32000)
        header.putShort(2.toShort()) // block align
        header.putShort(16.toShort()) // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(slicePcmBytes.toInt())

        FileOutputStream(destinationFile).use { fos ->
            fos.write(header.array())
            RandomAccessFile(sourceFile, "r").use { raf ->
                val sourceOffset = info.dataOffset + (startFrame * 2L)
                raf.seek(sourceOffset)
                val buffer = ByteArray(16384)
                var remaining = slicePcmBytes
                while (remaining > 0L) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = raf.read(buffer, 0, toRead)
                    if (read <= 0) {
                        throw IOException("Unexpected end of file while reading PCM data: expected $slicePcmBytes bytes, but source file ended with $remaining bytes remaining")
                    }
                    fos.write(buffer, 0, read)
                    remaining -= read
                }
            }
            fos.flush()
        }

        return destinationFile
    }

    /**
     * Parses time string into seconds.
     * Supports "MM:SS", "MM:SS.s", "HH:MM:SS", "SS.s", "SS".
     */
    fun parseTimeToSeconds(input: String): Double {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Time string cannot be empty" }

        return if (trimmed.contains(":")) {
            val parts = trimmed.split(":")
            when (parts.size) {
                2 -> {
                    val minutes = parts[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid minutes: ${parts[0]}")
                    val seconds = parts[1].toDoubleOrNull() ?: throw IllegalArgumentException("Invalid seconds: ${parts[1]}")
                    require(minutes >= 0) { "Minutes cannot be negative" }
                    require(seconds >= 0.0 && seconds < 60.0) { "Seconds must be between 0 and 59.9" }
                    minutes * 60.0 + seconds
                }
                3 -> {
                    val hours = parts[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid hours: ${parts[0]}")
                    val minutes = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid minutes: ${parts[1]}")
                    val seconds = parts[2].toDoubleOrNull() ?: throw IllegalArgumentException("Invalid seconds: ${parts[2]}")
                    require(hours >= 0) { "Hours cannot be negative" }
                    require(minutes in 0..59) { "Minutes must be between 0 and 59" }
                    require(seconds >= 0.0 && seconds < 60.0) { "Seconds must be between 0 and 59.9" }
                    hours * 3600.0 + minutes * 60.0 + seconds
                }
                else -> throw IllegalArgumentException("Invalid time format: $input")
            }
        } else {
            val seconds = trimmed.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid numeric seconds: $input")
            require(seconds >= 0.0) { "Seconds cannot be negative: $seconds" }
            seconds
        }
    }

    /**
     * Formats seconds into "MM:SS.s" (e.g. 70.2 -> "01:10.2").
     * Properly rounds tenths of a second to prevent invalid "MM:60.0" output (e.g. 59.96s -> "01:00.0").
     */
    fun formatSecondsToTime(seconds: Double): String {
        val nonNeg = maxOf(0.0, seconds)
        val roundedTotalTenths = Math.round(nonNeg * 10.0)
        val totalSeconds = roundedTotalTenths / 10L
        val tenths = roundedTotalTenths % 10L
        val minutes = totalSeconds / 60L
        val remSec = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d.%d", minutes, remSec, tenths)
    }

    /**
     * Computes lowercase hex SHA-256 hash of a file.
     */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(32768)
            var n = fis.read(buf)
            while (n > 0) {
                digest.update(buf, 0, n)
                n = fis.read(buf)
            }
        }
        val hashBytes = digest.digest()
        val sb = java.lang.StringBuilder(hashBytes.size * 2)
        for (b in hashBytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun readAscii(raf: RandomAccessFile, length: Int): String {
        val bytes = ByteArray(length)
        raf.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readIntLE(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        if ((b0 or b1 or b2 or b3) < 0) throw java.io.EOFException("Unexpected end of WAV file")
        return (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
    }

    private fun readShortLE(raf: RandomAccessFile): Short {
        val b0 = raf.read()
        val b1 = raf.read()
        if ((b0 or b1) < 0) throw java.io.EOFException("Unexpected end of WAV file")
        return ((b0 and 0xFF) or ((b1 and 0xFF) shl 8)).toShort()
    }
}
