package de.aarondietz.localizepipe.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationSettingsServiceTest {
    @Test
    fun usesExpectedOllamaDefaults() {
        val service = TranslationSettingsService()

        assertEquals(TranslationProviderType.OLLAMA, service.providerType)
        assertEquals("translategemma:4b", service.activeModel())
        assertEquals("http://127.0.0.1:11434/api/generate", service.activeEndpoint())
        assertEquals(OllamaRuntimeMode.AUTO, service.ollamaRuntimeMode())
        assertTrue(service.removeAddedTrailingPeriod())
        assertFalse(service.hasHuggingFaceToken())
    }

    @Test
    fun togglesProviderAndSwitchesActiveEndpointAndModel() {
        val service = TranslationSettingsService()

        service.toggleProvider()

        assertEquals(TranslationProviderType.HUGGING_FACE, service.providerType)
        assertEquals("google/translategemma-4b-it", service.activeModel())
        assertEquals(
            "https://api-inference.huggingface.co/models/google/translategemma-4b-it",
            service.activeEndpoint(),
        )

        service.toggleProvider()

        assertEquals(TranslationProviderType.OLLAMA, service.providerType)
        assertEquals("translategemma:4b", service.activeModel())
        assertEquals("http://127.0.0.1:11434/api/generate", service.activeEndpoint())
    }
}
