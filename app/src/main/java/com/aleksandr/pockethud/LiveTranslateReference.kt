package com.aleksandr.pockethud

import org.json.JSONArray
import org.json.JSONObject

object LiveTranslateReference {
    const val MODEL_NAME = "models/gemini-3.5-live-translate-preview"
    const val TARGET_LANGUAGE_CODE = "ru"

    fun buildSetup(): JSONObject {
        val setup = JSONObject()
        setup.put("model", MODEL_NAME)

        val translationConfig = JSONObject()
        translationConfig.put("targetLanguageCode", TARGET_LANGUAGE_CODE)
        translationConfig.put("echoTargetLanguage", false)

        val generationConfig = JSONObject()
        val responseModalities = JSONArray()
        responseModalities.put("AUDIO")
        generationConfig.put("responseModalities", responseModalities)
        generationConfig.put("translationConfig", translationConfig)

        setup.put("generationConfig", generationConfig)
        setup.put("inputAudioTranscription", JSONObject())
        setup.put("outputAudioTranscription", JSONObject())

        val envelope = JSONObject()
        envelope.put("setup", setup)
        return envelope
    }

    fun encodePcm(samples: ShortArray, length: Int): ByteArray {
        require(length in 0..samples.size) {
            "length $length out of valid range 0..${samples.size}"
        }
        val bytes = ByteArray(length * 2)
        for (i in 0 until length) {
            val s = samples[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    fun outputText(serverContent: JSONObject): String {
        val direct = serverContent.optJSONObject("outputTranscription")
        if (direct != null) {
            return direct.optString("text", "")
        }
        val nested = serverContent.optJSONObject("serverContent")?.optJSONObject("outputTranscription")
        if (nested != null) {
            return nested.optString("text", "")
        }
        return ""
    }
}
