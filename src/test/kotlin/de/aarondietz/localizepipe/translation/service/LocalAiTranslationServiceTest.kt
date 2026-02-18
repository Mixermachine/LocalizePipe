package de.aarondietz.localizepipe.translation.service

import de.aarondietz.localizepipe.settings.OllamaRuntimeMode
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class LocalAiTranslationServiceTest {
    @Test
    fun buildsOllamaOptionsForAutoModeWithoutNumGpu() {
        val options = LocalAiTranslationService.buildOllamaOptions(
            temperature = 0.2f,
            runtimeMode = OllamaRuntimeMode.AUTO,
        )

        assertEquals(0.2, options["temperature"]?.jsonPrimitive?.content?.toDouble() ?: 0.0, 0.0001)
        assertNull(options["num_gpu"])
    }

    @Test
    fun buildsOllamaOptionsForCpuOnlyMode() {
        val options = LocalAiTranslationService.buildOllamaOptions(
            temperature = 0.2f,
            runtimeMode = OllamaRuntimeMode.CPU_ONLY,
        )

        assertEquals(0, options["num_gpu"]?.jsonPrimitive?.int)
    }

    @Test
    fun buildsOllamaOptionsForGpuPreferredMode() {
        val options = LocalAiTranslationService.buildOllamaOptions(
            temperature = 0.2f,
            runtimeMode = OllamaRuntimeMode.GPU_PREFERRED,
        )

        assertEquals(999, options["num_gpu"]?.jsonPrimitive?.int)
    }

    @Test
    fun formatsMissingOllamaModelAsActionablePullHint() {
        val message = LocalAiTranslationService.formatOllamaFailureMessage(
            model = "translategemma:12b",
            statusCode = 404,
            body = """{"error":"model 'translategemma:12b' not found, try pulling it first"}""",
        )

        assertTrue(message.contains("Ollama model 'translategemma:12b' is not available locally"))
        assertTrue(message.contains("ollama pull translategemma:12b"))
    }

    @Test
    fun formatsGenericOllamaHttpFailureWithRemoteError() {
        val message = LocalAiTranslationService.formatOllamaFailureMessage(
            model = "translategemma:4b",
            statusCode = 500,
            body = """{"error":"backend overload"}""",
        )

        assertEquals("Ollama request failed (HTTP 500): backend overload", message)
    }

    @Test
    fun formatsGenericOllamaHttpFailureWithoutBody() {
        val message = LocalAiTranslationService.formatOllamaFailureMessage(
            model = "translategemma:4b",
            statusCode = 503,
            body = null,
        )

        assertEquals("Ollama request failed (HTTP 503)", message)
    }
}
