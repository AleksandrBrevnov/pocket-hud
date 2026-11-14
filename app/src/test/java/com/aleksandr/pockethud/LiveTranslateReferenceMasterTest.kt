package com.aleksandr.pockethud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiveTranslateReferenceMasterTest {
    @Test fun setupMatchesDedicatedTranslationSchema() {
        val envelope = LiveTranslateReference.buildSetup()
        assertEquals(setOf("setup"), envelope.keySet())
        val setup = envelope.getJSONObject("setup")
        assertEquals(setOf("model", "generationConfig", "inputAudioTranscription", "outputAudioTranscription"), setup.keySet())
        assertEquals("models/gemini-3.5-live-translate-preview", setup.getString("model"))
        val generation = setup.getJSONObject("generationConfig")
        assertEquals(setOf("responseModalities", "translationConfig"), generation.keySet())
        assertEquals(listOf("AUDIO"), generation.getJSONArray("responseModalities").let { (0 until it.length()).map(it::getString) })
        val translation = generation.getJSONObject("translationConfig")
        assertEquals(setOf("targetLanguageCode", "echoTargetLanguage"), translation.keySet())
        assertEquals("ru", translation.getString("targetLanguageCode"))
        assertFalse(translation.getBoolean("echoTargetLanguage"))
        assertEquals(0, setup.getJSONObject("inputAudioTranscription").length())
        assertEquals(0, setup.getJSONObject("outputAudioTranscription").length())
    }

    @Test fun pcmPreservesQuietSpeechExtremesAndPartialRead() {
        val samples = shortArrayOf(0, 1, -1, 200, -200, 32767, -32768, 999)
        val copy = samples.copyOf()
        val pcm = LiveTranslateReference.encodePcm(samples, 7)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0, -1, -1, -56, 0, 56, -1, -1, 127, 0, -128), pcm)
        assertArrayEquals(copy, samples)
        assertEquals(0, LiveTranslateReference.encodePcm(samples, 0).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pcmRejectsReadPastBuffer() { LiveTranslateReference.encodePcm(shortArrayOf(1), 2) }

    @Test(expected = IllegalArgumentException::class)
    fun pcmRejectsNegativeRead() { LiveTranslateReference.encodePcm(shortArrayOf(1), -1) }

    @Test fun translationHasOneSourceAndKeepsLegitimateRepetition() {
        val content = JSONObject("""{"outputTranscription":{"text":" Да, да."},"modelTurn":{"parts":[{"text":" Да, да."}]}}""")
        assertEquals(" Да, да.", LiveTranslateReference.outputText(content))
        assertEquals(" Да, да.", LiveTranslateReference.outputText(content))
        assertEquals("", LiveTranslateReference.outputText(JSONObject("""{"modelTurn":{"parts":[{"text":"duplicate"}]}}""")))
    }

    @Test fun emptyTranscriptionIsSafe() {
        assertEquals("", LiveTranslateReference.outputText(JSONObject()))
        assertEquals("", LiveTranslateReference.outputText(JSONObject("""{"outputTranscription":{}}""")))
    }
}
