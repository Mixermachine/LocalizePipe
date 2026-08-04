package de.aarondietz.localizepipe.settings

import org.junit.Assert.*
import org.junit.Test

class TranslationSettingsServiceTest {
    @Test
    fun usesExpectedOllamaDefaults() {
        val service = TranslationSettingsService()
        service.ollamaModel = "translategemma:4b"

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
        service.ollamaModel = "translategemma:4b"

        service.toggleProvider()

        assertEquals(TranslationProviderType.HUGGING_FACE, service.providerType)
        assertEquals("google/translategemma-4b-it", service.activeModel())
        assertEquals(
            "https://api-inference.huggingface.co/models/google/translategemma-4b-it",
            service.activeEndpoint(),
        )

        service.toggleProvider()

        assertEquals(TranslationProviderType.OPENAI_COMPATIBLE, service.providerType)
        assertEquals("translategemma-4b", service.activeModel())
        assertEquals("http://127.0.0.1:8080/v1/chat/completions", service.activeEndpoint())

        service.toggleProvider()

        assertEquals(TranslationProviderType.OLLAMA, service.providerType)
        assertEquals("translategemma:4b", service.activeModel())
        assertEquals("http://127.0.0.1:11434/api/generate", service.activeEndpoint())
    }

    @Test
    fun storesAndRetrievesOpenAiCompatibleSettings() {
        val service = TranslationSettingsService()
        service.openAiCompatibleBaseUrl = "http://localhost:1234"
        service.openAiCompatibleModel = "translategemma-12b"
        service.openAiCompatibleApiKey = "test-sk-key"

        assertEquals("http://localhost:1234", service.openAiCompatibleBaseUrl())
        assertEquals("translategemma-12b", service.openAiCompatibleModel())
        assertEquals("test-sk-key", service.openAiCompatibleApiKey())
    }
}
