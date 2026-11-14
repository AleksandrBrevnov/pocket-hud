package com.aleksandr.pockethud

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveTranslateReferenceTest {

    @Test
    fun buildSetupSchemaWhitelistStrictKeys() {
        val root = LiveTranslateReference.buildSetup()
        assertEquals(setOf("setup"), root.keySet())

        val setup = root.getJSONObject("setup")
        assertEquals(
            setOf("model", "generationConfig", "inputAudioTranscription", "outputAudioTranscription"),
            setup.keySet()
        )
        assertEquals("models/gemini-3.5-live-translate-preview", setup.getString("model"))

        val generationConfig = setup.getJSONObject("generationConfig")
        assertEquals(setOf("responseModalities", "translationConfig"), generationConfig.keySet())

        val modalities = generationConfig.getJSONArray("responseModalities")
        assertEquals(1, modalities.length())
        assertEquals("AUDIO", modalities.getString(0))

        val translationConfig = generationConfig.getJSONObject("translationConfig")
        assertEquals(setOf("targetLanguageCode", "echoTargetLanguage"), translationConfig.keySet())
        assertEquals("ru", translationConfig.getString("targetLanguageCode"))
        assertFalse(translationConfig.getBoolean("echoTargetLanguage"))

        assertEquals(0, setup.getJSONObject("inputAudioTranscription").length())
        assertEquals(0, setup.getJSONObject("outputAudioTranscription").length())
    }

    @Test
    fun buildSetupExcludesVoiceVadAndSystemInstruction() {
        val setup = LiveTranslateReference.buildSetup().getJSONObject("setup")
        assertFalse("setup must not contain voiceConfig", setup.has("voiceConfig"))
        assertFalse("setup must not contain speechConfig", setup.has("speechConfig"))
        assertFalse("setup must not contain realtimeInputConfig", setup.has("realtimeInputConfig"))
        assertFalse("setup must not contain systemInstruction", setup.has("systemInstruction"))
        assertFalse("setup must not contain sessionConfig", setup.has("sessionConfig"))

        val generationConfig = setup.getJSONObject("generationConfig")
        assertFalse("generationConfig must not contain speechConfig", generationConfig.has("speechConfig"))
        assertFalse("generationConfig must not contain voiceConfig", generationConfig.has("voiceConfig"))
    }

    @Test
    fun encodePcmQuietSpeechPreservesExactValuesWithoutGainOrGate() {
        // Quiet samples that would previously be attenuated by custom soft-knee gate (< 1.0% / ~328)
        val quietSamples = shortArrayOf(0, 10, -10, 50, -50, 150, -150, 300)
        val copy = quietSamples.copyOf()
        val pcm = LiveTranslateReference.encodePcm(quietSamples, quietSamples.size)

        assertEquals(quietSamples.size * 2, pcm.size)
        // Original buffer must not be mutated
        assertArrayEquals(copy, quietSamples)

        // Verify little-endian decoding matches original exact shorts
        for (i in quietSamples.indices) {
            val low = pcm[i * 2].toInt() and 0xFF
            val high = pcm[i * 2 + 1].toInt()
            val decoded = (high shl 8) or low
            assertEquals("Sample $i mismatch", quietSamples[i].toInt(), decoded.toShort().toInt())
        }
    }

    @Test
    fun encodePcmFullRangeAndExtremesWithoutSaturation() {
        val extremeSamples = shortArrayOf(-32768, -32767, -1, 0, 1, 32766, 32767)
        val pcm = LiveTranslateReference.encodePcm(extremeSamples, extremeSamples.size)

        assertEquals(extremeSamples.size * 2, pcm.size)
        for (i in extremeSamples.indices) {
            val low = pcm[i * 2].toInt() and 0xFF
            val high = pcm[i * 2 + 1].toInt()
            val decoded = (high shl 8) or low
            assertEquals(extremeSamples[i].toInt(), decoded.toShort().toInt())
        }
    }

    @Test
    fun encodePcmPartialReadDoesNotMutateAndEncodesOnlyRequestedLength() {
        val buffer = ShortArray(1600) { (it % 100).toShort() }
        val readLength = 320
        val pcm = LiveTranslateReference.encodePcm(buffer, readLength)

        assertEquals(readLength * 2, pcm.size)
        for (i in 0 until readLength) {
            val low = pcm[i * 2].toInt() and 0xFF
            val high = pcm[i * 2 + 1].toInt()
            val decoded = (high shl 8) or low
            assertEquals(buffer[i].toInt(), decoded.toShort().toInt())
        }
    }

    @Test
    fun encodePcmZeroLengthReturnsEmptyArray() {
        val buffer = shortArrayOf(1, 2, 3)
        val pcm = LiveTranslateReference.encodePcm(buffer, 0)
        assertEquals(0, pcm.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encodePcmNegativeLengthThrows() {
        LiveTranslateReference.encodePcm(shortArrayOf(10, 20), -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encodePcmLengthExceedingBufferSizeThrows() {
        LiveTranslateReference.encodePcm(shortArrayOf(10, 20), 3)
    }

    @Test
    fun outputTextExtractsDirectOutputTranscription() {
        val json = JSONObject("""{"outputTranscription":{"text":"Buongiorno, come sta?"}}""")
        assertEquals("Buongiorno, come sta?", LiveTranslateReference.outputText(json))
    }

    @Test
    fun outputTextExtractsNestedServerContentOutputTranscription() {
        val json = JSONObject("""{"serverContent":{"outputTranscription":{"text":"Здравствуйте!"}}}""")
        assertEquals("Здравствуйте!", LiveTranslateReference.outputText(json))
    }

    @Test
    fun outputTextIgnoresModelTurnPartsAndPreventsDoubleSource() {
        // modelTurn has text, but outputTranscription has official translated text
        val json = JSONObject(
            """{
                "outputTranscription": {"text": " Официальный перевод."},
                "modelTurn": {
                    "parts": [
                        {"text": " Дублирующий текст из parts"},
                        {"inlineData": {"data": "PCM_BYTES"}}
                    ]
                }
            }"""
        )
        assertEquals(" Официальный перевод.", LiveTranslateReference.outputText(json))

        // If only modelTurn.parts text is present, outputText must return empty string
        val onlyModelTurn = JSONObject(
            """{
                "modelTurn": {
                    "parts": [{"text": "Только в modelTurn"}]
                }
            }"""
        )
        assertEquals("", LiveTranslateReference.outputText(onlyModelTurn))
    }

    @Test
    fun outputTextPreservesLegitimateRepetitions() {
        val chunk1 = JSONObject("""{"outputTranscription":{"text":"Да, "}}""")
        val chunk2 = JSONObject("""{"outputTranscription":{"text":"да, "}}""")
        val chunk3 = JSONObject("""{"outputTranscription":{"text":"да."}}""")

        assertEquals("Да, ", LiveTranslateReference.outputText(chunk1))
        assertEquals("да, ", LiveTranslateReference.outputText(chunk2))
        assertEquals("да.", LiveTranslateReference.outputText(chunk3))
    }

    @Test
    fun outputTextEmptyAndMissingSafe() {
        assertEquals("", LiveTranslateReference.outputText(JSONObject()))
        assertEquals("", LiveTranslateReference.outputText(JSONObject("""{"outputTranscription":{}}""")))
        assertEquals("", LiveTranslateReference.outputText(JSONObject("""{"serverContent":{}}""")))
        assertEquals("", LiveTranslateReference.outputText(JSONObject("""{"serverContent":{"outputTranscription":{}}}""")))
    }
}
