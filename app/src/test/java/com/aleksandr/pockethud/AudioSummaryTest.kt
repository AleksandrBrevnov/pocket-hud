package com.aleksandr.pockethud

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AudioSummaryTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        tempDir = Files.createTempDirectory("audio-summary-test").toFile()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // 1. WAV Slicing, Validation & Deterministic Synthetic WAV (R2, R8, R10)
    // =========================================================================

    @Test
    fun contractWavVerificationAndSampleAccurateSlicing() {
        // [R2] Deterministic synthetic WAV 70.2s for CI (16kHz mono16 -> 1,123,200 frames -> 2,246,400 PCM bytes)
        val synthetic702 = File(tempDir, "contract_synthetic_70_2s.wav")
        val sampleCount = 1123200
        val pcmBytes = ByteArray(sampleCount * 2)
        val pcmBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            pcmBuf.putShort((i % 1000).toShort())
        }
        writeWavFile(synthetic702, audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcm = pcmBytes)

        val info = WavAudioClip.parseHeader(synthetic702)
        assertEquals(1, info.channels)
        assertEquals(16000, info.sampleRate)
        assertEquals(16, info.bitsPerSample)
        assertEquals(70.2, info.durationSeconds, 0.001)
        assertEquals(2246400L, info.dataSize)
        assertEquals(1123200L, info.totalFrames)

        // 1. Slice range 20.0s - 40.0s -> strictly 640000 PCM bytes
        val slice2040 = File(tempDir, "slice_20_40.wav")
        WavAudioClip.extractClip(synthetic702, 20.0, 40.0, slice2040)
        assertTrue(slice2040.exists())
        assertEquals(640044L, slice2040.length()) // 44 bytes header + 640000 bytes PCM

        val sliceInfo = WavAudioClip.parseHeader(slice2040)
        assertEquals(1, sliceInfo.channels)
        assertEquals(16000, sliceInfo.sampleRate)
        assertEquals(16, sliceInfo.bitsPerSample)
        assertEquals(640000L, sliceInfo.dataSize)
        assertEquals(20.0, sliceInfo.durationSeconds, 0.001)

        val origBytes = synthetic702.readBytes()
        val sliceBytes = slice2040.readBytes()
        // Offset 44 + 20.0 * 32000 = 44 + 640000 = 640044
        val expectedPcm = origBytes.copyOfRange(44 + 640000, 44 + 640000 + 640000)
        val actualPcm = sliceBytes.copyOfRange(44, sliceBytes.size)
        assertArrayEquals("Sliced PCM bytes must match original recording samples exactly", expectedPcm, actualPcm)

        // 2. Slice full range 0.0s - 70.2s -> all 2246400 PCM bytes
        val sliceFull = File(tempDir, "slice_full.wav")
        WavAudioClip.extractClip(synthetic702, 0.0, 70.2, sliceFull)
        assertEquals(2246444L, sliceFull.length())
        val fullInfo = WavAudioClip.parseHeader(sliceFull)
        assertEquals(2246400L, fullInfo.dataSize)
        assertEquals(70.2, fullInfo.durationSeconds, 0.001)

        val fullSliceBytes = sliceFull.readBytes()
        val expectedFullPcm = origBytes.copyOfRange(44, origBytes.size)
        val actualFullPcm = fullSliceBytes.copyOfRange(44, fullSliceBytes.size)
        assertArrayEquals("Full sliced PCM bytes must match original completely", expectedFullPcm, actualFullPcm)

        // 3. Local optional contract check (only when user untracked file is present locally)
        val localContract = locateContractWav()
        if (localContract != null && localContract.exists()) {
            val sha256 = WavAudioClip.computeSha256(localContract)
            assertEquals("91de6a9d393f18819770d0f546e0ad9fedd4dc6c6686bc41838d43ea45447763", sha256)
        }
    }

    @Test
    fun wavWholeFileExtractPreservesExactLastSamples() {
        // [R8] Test whole file 70.2345s: 16000 * 70.2345 = 1,123,752 frames -> 2,247,504 PCM bytes
        val frameCount = Math.round(70.2345 * 16000).toInt()
        assertEquals(1123752, frameCount)
        val pcmBytes = ByteArray(frameCount * 2)
        // Mark the very last two bytes uniquely
        pcmBytes[pcmBytes.size - 2] = 0x42.toByte()
        pcmBytes[pcmBytes.size - 1] = 0x24.toByte()

        val wavFile = File(tempDir, "whole_70_2345s.wav")
        writeWavFile(wavFile, audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcm = pcmBytes)

        val info = WavAudioClip.parseHeader(wavFile)
        val sliceOut = File(tempDir, "slice_whole_out.wav")
        WavAudioClip.extractClip(wavFile, 0.0, info.durationSeconds, sliceOut)

        assertEquals(44L + pcmBytes.size, sliceOut.length())
        val slicedBytes = sliceOut.readBytes()
        assertEquals(0x42.toByte(), slicedBytes[slicedBytes.size - 2])
        assertEquals(0x24.toByte(), slicedBytes[slicedBytes.size - 1])
    }

    @Test
    fun wavParserRejectsCorruptedAndInvalidWavFiles() {
        // Not a RIFF
        val notRiff = File(tempDir, "not_riff.wav").apply { writeBytes("RIFX1234WAVEfmt ".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { WavAudioClip.parseHeader(notRiff) }

        // Not WAVE
        val notWave = File(tempDir, "not_wave.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 100, waveTag = "AVI "))
        }
        assertFailsWith<IllegalArgumentException> { WavAudioClip.parseHeader(notWave) }

        // Stereo (2 channels)
        val stereo = File(tempDir, "stereo.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 2, sampleRate = 16000, bitsPerSample = 16, pcmSize = 100))
        }
        assertFailsWith<IllegalArgumentException> { WavAudioClip.parseHeader(stereo) }

        // 44.1 kHz sample rate
        val sampleRate44k = File(tempDir, "sample44k.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 44100, bitsPerSample = 16, pcmSize = 100))
        }
        assertFailsWith<IllegalArgumentException> { WavAudioClip.parseHeader(sampleRate44k) }

        // 8-bit PCM
        val bits8 = File(tempDir, "bits8.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 8, pcmSize = 100))
        }
        assertFailsWith<IllegalArgumentException> { WavAudioClip.parseHeader(bits8) }

        // Truncated data chunk (file ends prematurely)
        val truncated = File(tempDir, "truncated.wav").apply {
            val bytes = createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 1000)
            writeBytes(bytes.copyOfRange(0, bytes.size - 200)) // cut off 200 bytes
        }
        assertFailsWith<IllegalArgumentException> { WavAudioClip.parseHeader(truncated) }

        // Empty data chunk
        val emptyData = File(tempDir, "empty.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 0))
        }
        assertFailsWith<IllegalArgumentException> { WavAudioClip.parseHeader(emptyData) }
    }

    @Test
    fun wavValidationChecksDeclaredRiffSizeAndMalformedFmt() {
        // [R10] Declared RIFF size larger than file length
        val malformedRiff = File(tempDir, "malformed_riff.wav")
        val baseWav = createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 320)
        // Overwrite declared RIFF length to huge value
        val bb = ByteBuffer.wrap(baseWav).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(4, 999999) // riff size field
        malformedRiff.writeBytes(baseWav)
        assertFailsWith<IllegalArgumentException> { WavAudioClip.parseHeader(malformedRiff) }

        // [R10] Declared fmt chunkSize extends beyond file
        val malformedFmt = File(tempDir, "malformed_fmt.wav")
        val baseWav2 = createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 320)
        val bb2 = ByteBuffer.wrap(baseWav2).order(ByteOrder.LITTLE_ENDIAN)
        bb2.putInt(16, 5000) // fmt chunk size
        malformedFmt.writeBytes(baseWav2)
        assertFailsWith<IllegalArgumentException> { WavAudioClip.parseHeader(malformedFmt) }
    }

    @Test
    fun wavValidationRejectsNonFiniteTimesAndInvalidBounds() {
        // [R10] Non-finite times
        val sampleWav = File(tempDir, "bounds.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 32000))
        }
        val out = File(tempDir, "out_bounds.wav")

        assertFailsWith<IllegalArgumentException> { WavAudioClip.extractClip(sampleWav, Double.NaN, 1.0, out) }
        assertFailsWith<IllegalArgumentException> { WavAudioClip.extractClip(sampleWav, 0.0, Double.POSITIVE_INFINITY, out) }
        assertFailsWith<IllegalArgumentException> { WavAudioClip.extractClip(sampleWav, -0.1, 0.5, out) }
        assertFailsWith<IllegalArgumentException> { WavAudioClip.extractClip(sampleWav, 0.5, 0.5, out) }
        assertFailsWith<IllegalArgumentException> { WavAudioClip.extractClip(sampleWav, 0.8, 0.5, out) }
        assertFailsWith<IllegalArgumentException> { WavAudioClip.extractClip(sampleWav, 0.0, 5.0, out) }
    }

    @Test
    fun wavExtractClipRejectsSameSourceAndDestination() {
        // [R10] source == destination
        val sampleWav = File(tempDir, "same.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 32000))
        }
        assertFailsWith<IllegalArgumentException> { WavAudioClip.extractClip(sampleWav, 0.0, 0.5, sampleWav) }
    }

    @Test
    fun wavExtractClipThrowsOnTruncatedSourceDuringRead() {
        // [R10] Source file truncated while reading
        val sampleWav = File(tempDir, "truncated_during_read.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 32000))
        }
        val out = File(tempDir, "out_trunc.wav")
        // Truncate original file after header parsing
        val raf = RandomAccessFile(sampleWav, "rw")
        raf.setLength(200L) // shorter than 32000 bytes
        raf.close()

        assertFailsWith<IllegalArgumentException> { WavAudioClip.extractClip(sampleWav, 0.0, 1.0, out) }
    }

    @Test
    fun wavParserHandlesExtraChunksAndRiffPadding() {
        // [R2] Buffer sized properly (512 bytes) to accommodate header, JUNK, fmt and data
        val file = File(tempDir, "with_junk_chunk.wav")
        val junkData = byteArrayOf(1, 2, 3) // 3 bytes -> odd -> 1 pad byte
        val pcm = ByteArray(320) { it.toByte() }

        val buf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        val riffSizePos = buf.position()
        buf.putInt(0) // placeholder
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))

        // JUNK chunk
        buf.put("JUNK".toByteArray(Charsets.US_ASCII))
        buf.putInt(junkData.size)
        buf.put(junkData)
        buf.put(0.toByte()) // pad byte for odd chunk size

        // fmt chunk
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(1) // Mono
        buf.putInt(16000)
        buf.putInt(32000)
        buf.putShort(2)
        buf.putShort(16)

        // data chunk
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(pcm.size)
        buf.put(pcm)

        val totalSize = buf.position()
        buf.putInt(riffSizePos, totalSize - 8)
        file.writeBytes(buf.array().copyOfRange(0, totalSize))

        val info = WavAudioClip.parseHeader(file)
        assertEquals(1, info.channels)
        assertEquals(16000, info.sampleRate)
        assertEquals(16, info.bitsPerSample)
        assertEquals(320L, info.dataSize)
        assertEquals(0.01, info.durationSeconds, 0.001)

        val slice = File(tempDir, "slice_padded.wav")
        WavAudioClip.extractClip(file, 0.0, 0.01, slice)
        val slicePcm = slice.readBytes().copyOfRange(44, slice.readBytes().size)
        assertArrayEquals(pcm, slicePcm)
    }

    @Test
    fun timeParsingAndFormattingValidation() {
        assertEquals(20.0, WavAudioClip.parseTimeToSeconds("00:20"), 0.001)
        assertEquals(20.0, WavAudioClip.parseTimeToSeconds("00:20.0"), 0.001)
        assertEquals(40.0, WavAudioClip.parseTimeToSeconds("00:40.0"), 0.001)
        assertEquals(70.2, WavAudioClip.parseTimeToSeconds("01:10.2"), 0.001)
        assertEquals(70.2, WavAudioClip.parseTimeToSeconds("70.2"), 0.001)
        assertEquals(0.0, WavAudioClip.parseTimeToSeconds("00:00.0"), 0.001)
        assertEquals(0.0, WavAudioClip.parseTimeToSeconds("0"), 0.001)

        // [R8] Check rounding edge case: 59.96s -> "01:00.0", never "00:60.0"
        assertEquals("01:00.0", WavAudioClip.formatSecondsToTime(59.96))
        assertEquals("01:10.2", WavAudioClip.formatSecondsToTime(70.2))
        assertEquals("00:20.0", WavAudioClip.formatSecondsToTime(20.0))
        assertEquals("00:00.0", WavAudioClip.formatSecondsToTime(0.0))

        assertFailsWith<IllegalArgumentException> { WavAudioClip.parseTimeToSeconds("") }
        assertFailsWith<IllegalArgumentException> { WavAudioClip.parseTimeToSeconds("abc") }
        assertFailsWith<IllegalArgumentException> { WavAudioClip.parseTimeToSeconds("-5.0") }
        assertFailsWith<IllegalArgumentException> { WavAudioClip.parseTimeToSeconds("10:65") }
    }

    // =========================================================================
    // 2. Gemini API: Schema, Hosts, Finish Reasons & Cleanup (R3, R4)
    // =========================================================================

    @Test
    fun geminiApiFullPipelineSuccessWithWhitelistSchemaAndCleanup() {
        val baseUrl = mockWebServer.url("").toString().removeSuffix("/")
        val api = AudioSummaryApi(baseUrl = baseUrl, uploadBaseUrl = baseUrl, pollIntervalMs = 10)
        val wavFile = File(tempDir, "input.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 64000))
        }

        val uploadUrl = "$baseUrl/upload-session-endpoint"
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setHeader("X-Goog-Upload-URL", uploadUrl))
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                JSONObject().put(
                    "file", JSONObject()
                        .put("name", "files/test-file-abc")
                        .put("uri", "$baseUrl/v1beta/files/test-file-abc")
                        .put("state", "PROCESSING")
                ).toString()
            )
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                JSONObject().put(
                    "file", JSONObject()
                        .put("name", "files/test-file-abc")
                        .put("uri", "$baseUrl/v1beta/files/test-file-abc")
                        .put("state", "ACTIVE")
                ).toString()
            )
        )

        val expectedMarkdown = "### О чём разговор\nРабочая встреча.\n### Главное\nПроект готов к релизу."
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                JSONObject().put(
                    "candidates", JSONArray().put(
                        JSONObject()
                            .put("finishReason", "STOP")
                            .put(
                                "content", JSONObject().put(
                                    "parts", JSONArray().put(
                                        JSONObject().put("text", expectedMarkdown)
                                    )
                                )
                            )
                    )
                ).toString()
            )
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = api.generateSummary(wavFile, apiKey = "test-gemini-key")
        assertTrue("Summary result must be Success", result is AudioSummaryResult.Success)
        val success = result as AudioSummaryResult.Success
        assertEquals(expectedMarkdown, success.summaryMarkdown)
        assertNull(success.cleanupWarning)

        mockWebServer.takeRequest() // Req 1: upload start
        mockWebServer.takeRequest() // Req 2: upload bytes
        mockWebServer.takeRequest() // Req 3: poll GET

        // [R4] Inspect Req 4: strict key-set whitelist schema validation
        val req4 = mockWebServer.takeRequest()
        assertEquals("POST", req4.method)
        assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", req4.path)
        val genJson = JSONObject(req4.body.readUtf8())

        // Verify root keys
        assertEquals(setOf("contents"), genJson.keySet())
        val contents = genJson.getJSONArray("contents")
        assertEquals(1, contents.length())

        // Verify contents[0] keys
        val content0 = contents.getJSONObject(0)
        assertEquals(setOf("parts"), content0.keySet())
        val parts = content0.getJSONArray("parts")
        assertEquals(2, parts.length())

        // Verify parts[0] fileData keys
        val part0 = parts.getJSONObject(0)
        assertEquals(setOf("fileData"), part0.keySet())
        val fileData = part0.getJSONObject("fileData")
        assertEquals(setOf("mimeType", "fileUri"), fileData.keySet())
        assertEquals("audio/wav", fileData.getString("mimeType"))
        assertEquals("$baseUrl/v1beta/files/test-file-abc", fileData.getString("fileUri"))

        // Verify parts[1] text keys
        val part1 = parts.getJSONObject(1)
        assertEquals(setOf("text"), part1.keySet())
        assertTrue(part1.getString("text").contains("конспект"))

        // Req 5: DELETE cleanup
        val req5 = mockWebServer.takeRequest()
        assertEquals("DELETE", req5.method)
        assertEquals("/v1beta/files/test-file-abc", req5.path)
    }

    @Test
    fun geminiApiRejectsNonStopFinishReasons() {
        // [R4] Reject missing, OTHER, BLOCKLIST, PROHIBITED_CONTENT
        val baseUrl = mockWebServer.url("").toString().removeSuffix("/")
        val api = AudioSummaryApi(baseUrl = baseUrl, uploadBaseUrl = baseUrl)
        val wavFile = File(tempDir, "input.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 640))
        }

        val testReasons = listOf("OTHER", "BLOCKLIST", "PROHIBITED_CONTENT", "")
        for (reason in testReasons) {
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setHeader("X-Goog-Upload-URL", "$baseUrl/upload"))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"file":{"name":"files/f1","uri":"$baseUrl/v1beta/files/f1","state":"ACTIVE"}}"""))
            val candidateObj = JSONObject().put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "text"))))
            if (reason.isNotEmpty()) {
                candidateObj.put("finishReason", reason)
            }
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(JSONObject().put("candidates", JSONArray().put(candidateObj)).toString()))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}")) // DELETE

            val res = api.generateSummary(wavFile, apiKey = "key")
            assertTrue("finishReason '$reason' must be rejected as Error", res is AudioSummaryResult.Error)
        }
    }

    @Test
    fun geminiApiValidatesFileNameFormatAndRequiresFileUri() {
        // [R4] Validate files/[a-z0-9-]+ format and require non-blank fileUri
        val baseUrl = mockWebServer.url("").toString().removeSuffix("/")
        val api = AudioSummaryApi(baseUrl = baseUrl, uploadBaseUrl = baseUrl)
        val wavFile = File(tempDir, "input.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 640))
        }

        // 1. Invalid file name format (underscore not allowed by files/[a-z0-9-]+)
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setHeader("X-Goog-Upload-URL", "$baseUrl/upload"))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"file":{"name":"files/f_bad_name","uri":"$baseUrl/v1beta/files/f_bad_name","state":"ACTIVE"}}"""))
        val res1 = api.generateSummary(wavFile, apiKey = "key")
        assertTrue("Invalid file name format must be rejected", res1 is AudioSummaryResult.Error)

        // 2. Missing/blank fileUri
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setHeader("X-Goog-Upload-URL", "$baseUrl/upload"))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"file":{"name":"files/valid-name-1","uri":"","state":"ACTIVE"}}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}")) // DELETE cleanup
        val res2 = api.generateSummary(wavFile, apiKey = "key")
        assertTrue("Missing fileUri must be rejected", res2 is AudioSummaryResult.Error)
    }

    @Test
    fun geminiApiProductionRejectsLoopbackUrls() {
        // [R5] Production baseUrl rejects http://127.0.0.1 and localhost without network calls
        val requestCount = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val count = requestCount.incrementAndGet()
                if (count > 1) {
                    throw AssertionError("Any second request must fail: attempted request #$count")
                }
                val request = chain.request()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("X-Goog-Upload-URL", "http://127.0.0.1/upload-session")
                    .body("{}".toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()

        val api = AudioSummaryApi(
            client = client,
            baseUrl = "https://generativelanguage.googleapis.com",
            uploadBaseUrl = "https://generativelanguage.googleapis.com"
        )
        val wavFile = File(tempDir, "input.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 640))
        }

        val res = api.generateSummary(wavFile, apiKey = "test-key")
        assertTrue("Result must be Error", res is AudioSummaryResult.Error)
        val err = res as AudioSummaryResult.Error
        assertTrue("Error must describe unsafe upload URL", err.userMessage.contains("Небезопасный URL для загрузки аудио"))
        assertTrue("Error must mention 127.0.0.1 host", err.userMessage.contains("127.0.0.1"))
        assertEquals("Exactly one request must have been intercepted", 1, requestCount.get())
    }

    @Test
    fun geminiApiDisconnectDuringPollingCleansUpRemoteFile() {
        // [R1] Network disconnect (IOException) during polling after upload still calls DELETE cleanup
        val baseUrl = mockWebServer.url("").toString().removeSuffix("/")
        val api = AudioSummaryApi(
            client = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
            baseUrl = baseUrl, uploadBaseUrl = baseUrl, pollIntervalMs = 1
        )
        val wavFile = File(tempDir, "input.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 640))
        }

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setHeader("X-Goog-Upload-URL", "$baseUrl/upload"))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"file":{"name":"files/poll-ioe","uri":"$baseUrl/v1beta/files/poll-ioe","state":"PROCESSING"}}"""))
        // Poll request throws IOException via DISCONNECT_AFTER_REQUEST
        mockWebServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        // Cleanup DELETE response
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = api.generateSummary(wavFile, apiKey = "test-key")
        assertTrue("Result must be Error", result is AudioSummaryResult.Error)

        assertEquals("Total 4 requests expected: start, upload, poll, delete", 4, mockWebServer.requestCount)
        mockWebServer.takeRequest(2, TimeUnit.SECONDS) // start
        mockWebServer.takeRequest(2, TimeUnit.SECONDS) // upload
        mockWebServer.takeRequest(2, TimeUnit.SECONDS) // poll
        val deleteReq = requireNotNull(mockWebServer.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("DELETE", deleteReq.method)
        assertEquals("/v1beta/files/poll-ioe", deleteReq.path)
    }

    @Test
    fun geminiApiInvalidJsonInGenerateContentCleansUpRemoteFile() {
        // [R1] Malformed JSON in generateContent still preserves uploadedFileName and calls DELETE cleanup
        val baseUrl = mockWebServer.url("").toString().removeSuffix("/")
        val api = AudioSummaryApi(baseUrl = baseUrl, uploadBaseUrl = baseUrl)
        val wavFile = File(tempDir, "input.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 640))
        }

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setHeader("X-Goog-Upload-URL", "$baseUrl/upload"))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"file":{"name":"files/gen-badjson","uri":"$baseUrl/v1beta/files/gen-badjson","state":"ACTIVE"}}"""))
        // generateContent returns 200 with invalid JSON
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("<html>502 Bad Gateway</html>"))
        // Cleanup DELETE response
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = api.generateSummary(wavFile, apiKey = "test-key")
        assertTrue("Result must be Error", result is AudioSummaryResult.Error)
        val err = result as AudioSummaryResult.Error
        assertTrue("Error should mention invalid JSON", err.userMessage.contains("невалидный JSON"))

        assertEquals("Total 4 requests expected: start, upload, generateContent, delete", 4, mockWebServer.requestCount)
        mockWebServer.takeRequest(2, TimeUnit.SECONDS) // start
        mockWebServer.takeRequest(2, TimeUnit.SECONDS) // upload
        mockWebServer.takeRequest(2, TimeUnit.SECONDS) // gen
        val deleteReq = requireNotNull(mockWebServer.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("DELETE", deleteReq.method)
        assertEquals("/v1beta/files/gen-badjson", deleteReq.path)
    }

    @Test
    fun geminiApiInvalidJsonInPollCleansUpRemoteFile() {
        // [R1] Malformed JSON in polling still preserves handle and calls DELETE cleanup
        val baseUrl = mockWebServer.url("").toString().removeSuffix("/")
        val api = AudioSummaryApi(baseUrl = baseUrl, uploadBaseUrl = baseUrl, pollIntervalMs = 1)
        val wavFile = File(tempDir, "input.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 640))
        }

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setHeader("X-Goog-Upload-URL", "$baseUrl/upload"))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"file":{"name":"files/poll-badjson","uri":"$baseUrl/v1beta/files/poll-badjson","state":"PROCESSING"}}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("<html>Server Error</html>"))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = api.generateSummary(wavFile, apiKey = "test-key")
        assertTrue("Result must be Error", result is AudioSummaryResult.Error)

        assertEquals("Total 4 requests expected", 4, mockWebServer.requestCount)
        mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        val deleteReq = requireNotNull(mockWebServer.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("DELETE", deleteReq.method)
        assertEquals("/v1beta/files/poll-badjson", deleteReq.path)
    }

    @Test
    fun geminiApiDelete500AfterSuccessReturnsWarning() {
        // [R3] DELETE 500 after generateContent success
        val baseUrl = mockWebServer.url("").toString().removeSuffix("/")
        val api = AudioSummaryApi(baseUrl = baseUrl, uploadBaseUrl = baseUrl)
        val wavFile = File(tempDir, "input.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 640))
        }

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setHeader("X-Goog-Upload-URL", "$baseUrl/upload"))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"file":{"name":"files/f-del-warn","uri":"$baseUrl/v1beta/files/f-del-warn","state":"ACTIVE"}}"""))
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                JSONObject().put("candidates", JSONArray().put(
                    JSONObject().put("finishReason", "STOP").put("content", JSONObject().put("parts", JSONArray().put(
                        JSONObject().put("text", "Итоговый конспект встречи")
                    )))
                )).toString()
            )
        )
        // DELETE returns 500
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"message":"Internal delete failure"}}"""))

        val result = api.generateSummary(wavFile, apiKey = "key")
        assertTrue("Result must be Success", result is AudioSummaryResult.Success)
        val success = result as AudioSummaryResult.Success
        assertEquals("Итоговый конспект встречи", success.summaryMarkdown)
        assertNotNull("cleanupWarning must be present after DELETE 500", success.cleanupWarning)
        assertTrue(success.cleanupWarning!!.contains("500") || success.cleanupWarning!!.contains("Не удалось удалить"))
    }

    @Test
    fun geminiApiDelete500AfterFailureReturnsWarning() {
        // [R3] DELETE 500 after generateContent failure
        val baseUrl = mockWebServer.url("").toString().removeSuffix("/")
        val api = AudioSummaryApi(baseUrl = baseUrl, uploadBaseUrl = baseUrl)
        val wavFile = File(tempDir, "input.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 640))
        }

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setHeader("X-Goog-Upload-URL", "$baseUrl/upload"))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"file":{"name":"files/f-del-err","uri":"$baseUrl/v1beta/files/f-del-err","state":"ACTIVE"}}"""))
        // generateContent fails with 404
        mockWebServer.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"Model not found"}}"""))
        // DELETE fails with 500
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"message":"Delete error"}}"""))

        val result = api.generateSummary(wavFile, apiKey = "key")
        assertTrue("Result must be Error", result is AudioSummaryResult.Error)
        val err = result as AudioSummaryResult.Error
        assertTrue("userMessage describes 404", err.userMessage.contains("404") || err.userMessage.contains("не найден"))
        assertNotNull("cleanupWarning must be attached to Error", err.cleanupWarning)
    }

    @Test
    fun geminiApiDoesNotLeakUploadUrlOrFullBodyInErrorMessages() {
        // [R4] Secret upload URLs and raw response bodies must not leak
        val baseUrl = mockWebServer.url("").toString().removeSuffix("/")
        val api = AudioSummaryApi(baseUrl = baseUrl, uploadBaseUrl = baseUrl)
        val wavFile = File(tempDir, "input.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 640))
        }

        val secretUploadUrl = "https://untrusted-host.example.com/secret-token-abcdef12345"
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setHeader("X-Goog-Upload-URL", secretUploadUrl))

        val result = api.generateSummary(wavFile, apiKey = "key")
        assertTrue(result is AudioSummaryResult.Error)
        val msg = (result as AudioSummaryResult.Error).userMessage
        assertFalse("Secret upload token/URL must not leak into error message", msg.contains("secret-token-abcdef12345"))
    }

    // =========================================================================
    // 3. AudioSummaryManager: Concurrency, Cancellation, Precommit & Atomicity (R5, R6, R7)
    // =========================================================================

    @Test
    fun managerRejectsConcurrentDuplicateRequests() {
        val baseUrl = mockWebServer.url("").toString().removeSuffix("/")
        val api = AudioSummaryApi(baseUrl = baseUrl, uploadBaseUrl = baseUrl)
        AudioSummaryManager(api = api).use { manager ->
            val wavFile = File(tempDir, "rec.wav").apply {
                writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 32000))
            }

            val pauseLatch = CountDownLatch(1)
            mockWebServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    pauseLatch.await(5, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(500)
                }
            }

            val req1Started = CountDownLatch(1)
            manager.requestSummary(
                wavFile = wavFile,
                startSeconds = 0.0,
                endSeconds = 1.0,
                apiKey = "key",
                onStart = { req1Started.countDown() },
                onSuccess = {},
                onError = {}
            )

            assertTrue(req1Started.await(2, TimeUnit.SECONDS))

            val duplicateRejected = AtomicBoolean(false)
            val latch2 = CountDownLatch(1)
            manager.requestSummary(
                wavFile = wavFile,
                startSeconds = 0.0,
                endSeconds = 1.0,
                apiKey = "key",
                onStart = {},
                onSuccess = { latch2.countDown() },
                onError = { msg ->
                    if (msg.contains("уже выполняется")) {
                        duplicateRejected.set(true)
                    }
                    latch2.countDown()
                }
            )

            assertTrue(latch2.await(2, TimeUnit.SECONDS))
            assertTrue("Second concurrent request must be rejected", duplicateRejected.get())

            pauseLatch.countDown()
            manager.cancelCurrent()
        }
    }

    @Test
    fun managerPrecommitRaceSerializedWithCancel() {
        // [R6] Latch on precommit: A reaches precommit; cancel A; start B; release A; only B saved, callback B, busy B not reset
        val baseUrl = mockWebServer.url("").toString().removeSuffix("/")
        val api = AudioSummaryApi(baseUrl = baseUrl, uploadBaseUrl = baseUrl)
        AudioSummaryManager(api = api).use { manager ->
            val wavFile = File(tempDir, "race.wav").apply {
                writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 64000))
            }

            // Responses for request A
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setHeader("X-Goog-Upload-URL", "$baseUrl/upload-a"))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"file":{"name":"files/file-a","uri":"$baseUrl/v1beta/files/file-a","state":"ACTIVE"}}"""))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(
                JSONObject().put("candidates", JSONArray().put(
                    JSONObject().put("finishReason", "STOP").put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "SUMMARY A"))))
                )).toString()
            ))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}")) // DELETE A

            // Responses for request B
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setHeader("X-Goog-Upload-URL", "$baseUrl/upload-b"))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"file":{"name":"files/file-b","uri":"$baseUrl/v1beta/files/file-b","state":"ACTIVE"}}"""))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(
                JSONObject().put("candidates", JSONArray().put(
                    JSONObject().put("finishReason", "STOP").put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "SUMMARY B"))))
                )).toString()
            ))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}")) // DELETE B

            val aReachedPrecommitLatch = CountDownLatch(1)
            val releaseALatch = CountDownLatch(1)
            val callbackACalled = AtomicBoolean(false)
            val callbackBCalled = AtomicBoolean(false)
            val bCompletionLatch = CountDownLatch(1)

            manager.preCommitHook = {
                aReachedPrecommitLatch.countDown()
                releaseALatch.await(5, TimeUnit.SECONDS)
            }

            // 1. Start Request A
            manager.requestSummary(
                wavFile = wavFile,
                startSeconds = 0.0,
                endSeconds = 1.0,
                apiKey = "key",
                onSuccess = { callbackACalled.set(true) },
                onError = {}
            )

            // 2. Wait until A hits precommit
            assertTrue("Request A must reach precommit", aReachedPrecommitLatch.await(5, TimeUnit.SECONDS))

            // 3. Cancel A
            manager.cancelCurrent()
            manager.preCommitHook = null // clear hook so B proceeds without waiting

            // 4. Start Request B
            manager.requestSummary(
                wavFile = wavFile,
                startSeconds = 1.0,
                endSeconds = 2.0,
                apiKey = "key",
                onSuccess = {
                    callbackBCalled.set(true)
                    bCompletionLatch.countDown()
                },
                onError = { bCompletionLatch.countDown() }
            )

            // 5. Release A
            releaseALatch.countDown()

            // 6. Wait for B to finish
            assertTrue("Request B must complete", bCompletionLatch.await(5, TimeUnit.SECONDS))

            assertFalse("Callback A must NOT be called", callbackACalled.get())
            assertTrue("Callback B must be called", callbackBCalled.get())

            // Verify saved sidecar files: only B's sidecar exists
            val summaries = manager.getSavedSummaries(wavFile)
            assertEquals(1, summaries.size)
            assertEquals("SUMMARY B", summaries[0].summaryMarkdown)
        }
    }

    @Test
    fun managerAtomicSaveFailClosedPreservesOldTargetByteForByte() {
        // [R4] Target file already exists; failed atomic move must preserve old target byte-for-byte and delete temp
        val normalManager = AudioSummaryManager()
        val wavFile = File(tempDir, "record.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 64000))
        }

        // Save initial valid summary
        val initialSummary = "ORIGINAL CRITICAL SUMMARY CONTENT"
        val targetFile = normalManager.saveSummary(
            wavFile = wavFile,
            startSeconds = 0.0,
            endSeconds = 1.0,
            summaryMarkdown = initialSummary
        )
        assertTrue(targetFile.exists())
        val originalBytes = targetFile.readBytes()
        val parentDir = targetFile.parentFile ?: tempDir

        // Failing manager with publisher throwing AtomicMoveNotSupportedException
        var moveAttempted = false
        val failingManager = AudioSummaryManager(
            atomicMover = { src, dst ->
                moveAttempted = true
                throw AtomicMoveNotSupportedException(src.toString(), dst.toString(), "Atomic move simulated failure")
            }
        )

        assertFailsWith<IOException> {
            failingManager.saveSummary(
                wavFile = wavFile,
                startSeconds = 0.0,
                endSeconds = 1.0,
                summaryMarkdown = "OVERWRITE ATTEMPT THAT MUST FAIL"
            )
        }
        assertTrue("Injected atomic mover must have been called", moveAttempted)

        // Verify targetFile is byte-for-byte identical to original
        assertArrayEquals("Original target file must remain completely intact", originalBytes, targetFile.readBytes())

        // Verify temp files are cleaned up
        val tmpFiles = parentDir.listFiles { _, name -> name.contains(".tmp.") } ?: emptyArray()
        assertEquals("Temp files must be cleaned up", 0, tmpFiles.size)

        // Repeated save with working atomic move replaces body
        val updatedSummary = "UPDATED SUMMARY CONTENT AFTER SUCCESSFUL ATOMIC MOVE"
        val replacedFile = normalManager.saveSummary(
            wavFile = wavFile,
            startSeconds = 0.0,
            endSeconds = 1.0,
            summaryMarkdown = updatedSummary
        )
        assertEquals(targetFile.canonicalPath, replacedFile.canonicalPath)
        val loaded = normalManager.loadSummary(replacedFile)
        assertNotNull(loaded)
        assertEquals(updatedSummary, loaded!!.summaryMarkdown)
    }

    @Test
    fun wavCalculateFrameBoundsExactAndSampleAccurate() {
        // [R2] Calculate frame bounds helper without heuristic
        val wavFile = File(tempDir, "bounds.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 32000))
        }
        val info = WavAudioClip.parseHeader(wavFile)
        assertEquals(16000L, info.totalFrames)
        assertEquals(1.0, info.durationSeconds, 0.0001)

        // 1 frame before end: 15999 / 16000.0
        val bounds1 = WavAudioClip.calculateFrameBounds(info, 0.0, 15999.0 / 16000.0)
        assertEquals(0L, bounds1.startFrame)
        assertEquals(15999L, bounds1.endFrame)
        assertEquals(0.0, bounds1.startSeconds, 0.0001)
        assertEquals(15999.0 / 16000.0, bounds1.endSeconds, 0.00001)

        // Full duration
        val boundsFull = WavAudioClip.calculateFrameBounds(info, 0.0, 1.0)
        assertEquals(0L, boundsFull.startFrame)
        assertEquals(16000L, boundsFull.endFrame)
        assertEquals(0.0, boundsFull.startSeconds, 0.0001)
        assertEquals(1.0, boundsFull.endSeconds, 0.0001)
    }

    @Test
    fun managerExactFrameRangeIdentityDoesNotOverwriteFractionalSeconds() {
        // [R7] Distinct fractional selections 20.01 and 20.04 must have distinct frame bounds and not overwrite
        val manager = AudioSummaryManager()
        val wavFile = File(tempDir, "fractional.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 1600000))
        }

        val file1 = manager.saveSummary(wavFile, 20.01, 40.01, "Summary 20.01")
        val file2 = manager.saveSummary(wavFile, 20.04, 40.04, "Summary 20.04")

        assertNotEquals("Sidecar filenames must differ for different sample accurate ranges", file1.name, file2.name)
        assertTrue("Both sidecar files must exist", file1.exists() && file2.exists())

        val list = manager.getSavedSummaries(wavFile)
        assertEquals(2, list.size)
    }

    @Test
    fun managerSidecarPersistenceWithUtcTimestampAndExactHash() {
        // [R7] Verify exact SHA256 comparison and UTC Instant ISO format
        val manager = AudioSummaryManager()
        val wavFile = File(tempDir, "sidecar_verify.wav").apply {
            writeBytes(createSyntheticWavBytes(audioFormat = 1, channels = 1, sampleRate = 16000, bitsPerSample = 16, pcmSize = 64000))
        }

        val expectedSha = WavAudioClip.computeSha256(wavFile)
        val saved = manager.saveSummary(wavFile, 0.0, 1.0, "Summary markdown text")
        val loaded = manager.loadSummary(saved)

        assertNotNull(loaded)
        assertEquals(expectedSha, loaded!!.sourceSha256)
        assertEquals(0.0, loaded.startSeconds, 0.0001)
        assertEquals(1.0, loaded.endSeconds, 0.0001)
        assertEquals(0L, loaded.startFrame)
        assertEquals(16000L, loaded.endFrame)
        // Verify UTC Instant format ends with 'Z'
        assertTrue("Timestamp must end with Z: ${loaded.createdAt}", loaded.createdAt.endsWith("Z"))
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private fun locateContractWav(): File? {
        val candidates = listOf(
            File("record_20261114_091556_1_be8aa23e_0001.wav"),
            File("../record_20261114_091556_1_be8aa23e_0001.wav"),
            File("../../record_20261114_091556_1_be8aa23e_0001.wav")
        )
        return candidates.firstOrNull { it.exists() }
    }

    private fun writeWavFile(
        file: File,
        audioFormat: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
        pcm: ByteArray
    ) {
        val pcmSize = pcm.size
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcmSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))

        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(audioFormat.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())

        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmSize)

        FileOutputStream(file).use { fos ->
            fos.write(header.array())
            fos.write(pcm)
            fos.flush()
        }
    }

    private fun createSyntheticWavBytes(
        audioFormat: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
        pcmSize: Int,
        waveTag: String = "WAVE"
    ): ByteArray {
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val totalSize = 44 + pcmSize
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + pcmSize)
        buf.put(waveTag.toByteArray(Charsets.US_ASCII))

        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(audioFormat.toShort())
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())

        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(pcmSize)
        for (i in 0 until pcmSize) {
            buf.put((i % 128).toByte())
        }
        return buf.array()
    }

    private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit) {
        try {
            block()
            fail("Expected exception of type ${T::class.java.simpleName} was not thrown")
        } catch (e: Throwable) {
            if (e !is T) {
                throw e
            }
        }
    }
}
