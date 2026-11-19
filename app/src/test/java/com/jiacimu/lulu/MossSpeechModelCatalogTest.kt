package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Settings selector and the actual MOSS /v1/audio/speech request must agree. */
class MossSpeechModelCatalogTest {
    @Test fun allOfficialSingleSpeakerModelsAreSelectableAndAcceptedBySynthesis() {
        val documented = setOf(
            "moss-tts-2.0-flash-preview",
            "moss-tts-2.0-flash-preview-2026-11-04",
            "moss-tts-1.5-flash",
            "moss-tts-1.5-flash-2026-06-26",
            "moss-tts-1.0-pro",
            "moss-tts-1.0-pro-2026-02-07",
        )
        val choices = MossSpeech.MODEL_CHOICES
        assertEquals(documented, MossSpeech.MODELS)
        assertEquals(documented, choices.map { it.second }.toSet())
        assertEquals(documented.size, choices.size)
        assertEquals("moss-tts-2.0-flash-preview", choices.first().second)
        assertEquals("moss-tts-1.5-flash", MossSpeech.DEFAULT_MODEL)
        assertTrue(choices.all { (label, id) -> label.isNotBlank() && id.isNotBlank() })
        assertEquals("https://api.mosi.cn/v1/audio/speech", MossSpeech.ENDPOINT)
    }
}
