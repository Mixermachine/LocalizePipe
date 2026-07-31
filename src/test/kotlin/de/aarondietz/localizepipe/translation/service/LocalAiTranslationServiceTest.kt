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

    @Test
    fun removesTrailingPeriodWhenSourceHasNoPeriodAndSettingEnabled() {
        val normalized = LocalAiTranslationService.alignTrailingPeriodToSource(
            baseText = "Save changes",
            translatedText = "Speichern.",
            removeAddedTrailingPeriod = true,
        )

        assertEquals("Speichern", normalized)
    }

    @Test
    fun keepsTrailingPeriodWhenSourceHasPeriod() {
        val normalized = LocalAiTranslationService.alignTrailingPeriodToSource(
            baseText = "Save changes.",
            translatedText = "Speichern.",
            removeAddedTrailingPeriod = true,
        )

        assertEquals("Speichern.", normalized)
    }

    @Test
    fun keepsTrailingPeriodWhenSettingDisabled() {
        val normalized = LocalAiTranslationService.alignTrailingPeriodToSource(
            baseText = "Save changes",
            translatedText = "Speichern.",
            removeAddedTrailingPeriod = false,
        )

        assertEquals("Speichern.", normalized)
    }

    @Test
    fun normalizesLeadingEllipsisWhenSourceStartsWithUnicodeEllipsis() {
        val normalized = LocalAiTranslationService.normalizeEdgeEllipsisToSource(
            baseText = "…Open settings",
            translatedText = "...Einstellungen offnen",
        )

        assertEquals("…Einstellungen offnen", normalized)
    }

    @Test
    fun normalizesTrailingEllipsisWhenSourceEndsWithUnicodeEllipsis() {
        val normalized = LocalAiTranslationService.normalizeEdgeEllipsisToSource(
            baseText = "Loading…",
            translatedText = "Wird geladen...",
        )

        assertEquals("Wird geladen…", normalized)
    }

    @Test
    fun normalizesBothEdgeEllipsesWhenSourceUsesUnicodeEllipsisAtBothEdges() {
        val normalized = LocalAiTranslationService.normalizeEdgeEllipsisToSource(
            baseText = "…Loading…",
            translatedText = "...Wird geladen...",
        )

        assertEquals("…Wird geladen…", normalized)
    }

    @Test
    fun keepsTranslationUnchangedWhenEllipsisIsOmitted() {
        val normalized = LocalAiTranslationService.normalizeEdgeEllipsisToSource(
            baseText = "…Open settings…",
            translatedText = "Einstellungen offnen",
        )

        assertEquals("Einstellungen offnen", normalized)
    }

    @Test
    fun keepsInternalThreeDotsUnchanged() {
        val normalized = LocalAiTranslationService.normalizeEdgeEllipsisToSource(
            baseText = "…Open settings",
            translatedText = "…Einstellungen ... jetzt offnen",
        )

        assertEquals("…Einstellungen ... jetzt offnen", normalized)
    }

    @Test
    fun normalizeTranslationToSourceAppliesTrailingPeriodAndEdgeEllipsisRules() {
        val normalized = LocalAiTranslationService.normalizeTranslationToSource(
            baseText = "…Loading",
            translatedText = "...Wird geladen.",
            removeAddedTrailingPeriod = true,
        )

        assertEquals("…Wird geladen", normalized)
    }

    @Test
    fun buildPromptIncludesAdditionalContextWhenPresent() {
        val prompt = LocalAiTranslationService.buildPrompt(
            baseText = "Save",
            translationContext = "Verb on a toolbar button",
            sourceLangCode = "eng_Latn",
            targetLangCode = "deu_Latn",
        )

        assertTrue(prompt.contains("Additional context: Verb on a toolbar button"))
        assertTrue(prompt.contains("Do not mention it in the output."))
        assertTrue(prompt.endsWith("Text: Save"))
    }

    @Test
    fun buildPromptOmitsAdditionalContextWhenBlank() {
        val prompt = LocalAiTranslationService.buildPrompt(
            baseText = "Save",
            translationContext = "   ",
            sourceLangCode = "eng_Latn",
            targetLangCode = "deu_Latn",
        )

        assertFalse(prompt.contains("Additional context:"))
        assertFalse(prompt.contains("Do not mention it in the output."))
        assertTrue(prompt.endsWith("Text: Save"))
    }

    @Test
    fun buildPromptIncludesLanguageInstructions() {
        val prompt = LocalAiTranslationService.buildPrompt(
            baseText = "Welcome",
            sourceLangCode = "eng_Latn",
            targetLangCode = "srp_Cyrl",
            languageInstructions = "Use formal register.",
        )

        assertTrue(prompt.contains("Language-specific instructions: Use formal register."))
    }

    @Test
    fun buildPromptOmitsLanguageInstructionsWhenNull() {
        val prompt = LocalAiTranslationService.buildPrompt(
            baseText = "Welcome",
            sourceLangCode = "eng_Latn",
            targetLangCode = "srp_Cyrl",
            languageInstructions = null,
        )

        assertFalse(prompt.contains("Language-specific instructions:"))
    }

    @Test
    fun translateRowsTriggersOnRowTranslatedCallbackForReadyRows() {
        val row = de.aarondietz.localizepipe.model.StringEntryRow(
            id = "app:values:de:key1",
            key = "key1",
            localeTag = "de",
            localeQualifierRaw = "de",
            localeFilePath = "/tmp/strings.xml",
            resourceRootPath = "/tmp",
            moduleName = "app",
            baseText = "Hello",
            localizedText = null,
            proposedText = null,
            status = de.aarondietz.localizepipe.model.RowStatus.MISSING,
            originKind = de.aarondietz.localizepipe.model.ResourceKind.ANDROID_RES,
        )

        val settings = de.aarondietz.localizepipe.settings.TranslationSettingsService()
        val service = LocalAiTranslationService(settings) { "en" }

        val translatedReadyRows = mutableListOf<de.aarondietz.localizepipe.model.StringEntryRow>()
        service.translateRows(
            rows = listOf(row),
            onRowTranslated = { translatedReadyRows.add(it) },
            languageSettings = mapOf("de" to de.aarondietz.localizepipe.scan.LanguageSettings(disabled = true)),
        )

        assertTrue(translatedReadyRows.isEmpty())
    }
}
