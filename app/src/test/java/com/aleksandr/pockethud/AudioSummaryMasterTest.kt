package com.aleksandr.pockethud

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Independent master contracts. Worker must not edit or weaken these tests. */
class AudioSummaryMasterTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private lateinit var audio: File

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        directory = Files.createTempDirectory("audio-master-").toFile()
        val buffer = ByteBuffer.allocate(32044).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(32036).put("WAVEfmt ".toByteArray())
        buffer.putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000)
        buffer.putShort(2).putShort(16).put("data".toByteArray()).putInt(32000)
        repeat(16000) { buffer.putShort((it % 32767).toShort()) }
        audio = File(directory, "synthetic.wav").apply { writeBytes(buffer.array()) }
    }

    @After fun tearDown() {
        server.shutdown()
        directory.deleteRecursively()
    }

    private fun api(maxPollAttempts: Int = 2): AudioSummaryApi {
        val origin = server.url("/").toString().trimEnd('/')
        return AudioSummaryApi(
            client = OkHttpClient.Builder().retryOnConnectionFailure(false)
                .readTimeout(2, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS).build(),
            baseUrl = origin, uploadBaseUrl = origin,
            maxPollAttempts = maxPollAttempts, pollIntervalMs = 1
        )
    }

    private fun upload(state: String) {
        server.enqueue(MockResponse().setHeader("X-Goog-Upload-URL", server.url("/session")))
        server.enqueue(MockResponse().setBody(
            """{"file":{"name":"files/master-contract","uri":"${server.url("/v1beta/files/master-contract")}","state":"$state"}}"""
        ))
    }

    private fun assertFinalDelete(expectedCount: Int) {
        assertEquals("Uploaded file must be cleaned up", expectedCount, server.requestCount)
        var last: okhttp3.mockwebserver.RecordedRequest? = null
        repeat(expectedCount) { last = server.takeRequest(2, TimeUnit.SECONDS) }
        assertNotNull(last)
        assertEquals("DELETE", last!!.method)
        assertEquals("/v1beta/files/master-contract", last!!.path)
    }

    @Test fun disconnectAfterUploadStillDeletesRemoteFile() {
        upload("ACTIVE")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("{}"))
        val result = api().generateSummary(audio, "fake-test-key")
        assertTrue(result is AudioSummaryResult.Error)
        assertFinalDelete(4)
    }

    @Test fun cancellationDuringProcessingStillDeletesRemoteFile() {
        upload("PROCESSING")
        server.enqueue(MockResponse().setBody("{}"))
        val cancelled = AtomicBoolean(false)
        val result = api().generateSummary(
            audio, "fake-test-key",
            onProgress = { if (it.startsWith("Обработка аудио")) cancelled.set(true) },
            isCancelled = { cancelled.get() }
        )
        assertTrue(cancelled.get())
        assertTrue(result is AudioSummaryResult.Error)
        assertFinalDelete(3)
    }

    @Test fun intervalEndingOneFrameBeforeEndDoesNotIncludeLastFrame() {
        val output = File(directory, "clip.wav")
        WavAudioClip.extractClip(audio, 0.0, 15999.0 / 16000.0, output)
        val parsed = WavAudioClip.parseHeader(output)
        assertEquals(31998L, parsed.dataSize)
        assertArrayEquals(
            audio.readBytes().copyOfRange(44, 44 + 31998),
            output.readBytes().copyOfRange(parsed.dataOffset.toInt(), output.length().toInt())
        )
    }

    @Test fun authAndQuotaErrorsStopWithoutFallback() {
        for (status in listOf(401, 403, 404, 429, 503)) {
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(status).setBody("{}"))
            val result = api().generateSummary(audio, "fake-test-key")
            assertTrue("HTTP $status must fail", result is AudioSummaryResult.Error)
            assertEquals("HTTP $status must not trigger fallback", before + 1, server.requestCount)
            server.takeRequest(2, TimeUnit.SECONDS)
        }
    }

    @Test fun processingTimeoutDoesNotGenerateAndStillCleansUp() {
        upload("PROCESSING")
        repeat(2) { server.enqueue(MockResponse().setBody("""{"state":"PROCESSING"}""")) }
        server.enqueue(MockResponse().setBody("{}"))
        val result = api().generateSummary(audio, "fake-test-key")
        assertTrue(result is AudioSummaryResult.Error)
        assertFinalDelete(5)
    }
}
