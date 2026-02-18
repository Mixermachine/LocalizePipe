package de.aarondietz.localizepipe.translation

import org.junit.Assert.*
import org.junit.Test

class OllamaRecordedResponseContractTest {
    @Test
    fun extractsResponseTextFromRecordedJsonFixtures() {
        val turkish = parseFixture("en_tr_settings.raw.json")
        val german = parseFixture("en_de_welcome_placeholder.raw.json")
        val french = parseFixture("en_fr_bold_save.raw.json")

        assertEquals("Ayarlar", turkish)
        assertEquals("Willkommen, %1\$s!", german)
        assertEquals("<b >Sauvegarder</b > maintenant", french)
    }

    @Test
    fun placeholderFixturePassesValidation() {
        val translated = parseFixture("en_de_welcome_placeholder.raw.json")
        val result = TranslationOutputValidator.validate(
            baseText = "Welcome, %1\$s!",
            translatedText = translated,
        )

        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun xmlTagFixtureDetectsTagMutation() {
        val translated = parseFixture("en_fr_bold_save.raw.json")
        val result = TranslationOutputValidator.validate(
            baseText = "<b>Save</b> now",
            translatedText = translated,
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.contains(ValidationError.TAGS_CHANGED))
    }

    private fun parseFixture(fileName: String): String {
        val path = "fixtures/ollama/translategemma_4b/$fileName"
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(path)) {
            "Missing fixture: $path"
        }
        val rawJson = stream.bufferedReader().use { it.readText() }
        return OllamaGenerateResponseParser.extractResponseText(rawJson)
    }
}
