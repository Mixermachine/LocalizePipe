package de.aarondietz.localizepipe.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizePipeSettingsStoreTest {

    @Test
    fun parsesEmptyOrMissingJson() {
        val emptySettings = LocalizePipeSettingsStore.parse("")
        assertTrue(emptySettings.languages.isEmpty())

        val emptyObjectSettings = LocalizePipeSettingsStore.parse("{}")
        assertTrue(emptyObjectSettings.languages.isEmpty())
    }

    @Test
    fun parsesLanguageSettings() {
        val rawJson = """
            {
              "description_of_file": "Test header",
              "languages": {
                "sr": {
                  "translationLocaleTag": "sr-Cyrl",
                  "disabled": false,
                  "instructions": "Use formal register."
                },
                "fr": {
                  "disabled": true
                },
                "ja": {
                  "instructions": "Use polite form."
                }
              }
            }
        """.trimIndent()

        val settings = LocalizePipeSettingsStore.parse(rawJson)
        assertEquals(3, settings.languages.size)

        val sr = LocalizePipeSettingsStore.languageSettingsFor(settings, "sr")
        assertEquals("sr-Cyrl", sr?.translationLocaleTag)
        assertFalse(sr?.disabled ?: true)
        assertEquals("Use formal register.", sr?.instructions)

        val fr = LocalizePipeSettingsStore.languageSettingsFor(settings, "fr")
        assertNull(fr?.translationLocaleTag)
        assertTrue(fr?.disabled ?: false)
        assertNull(fr?.instructions)

        val ja = LocalizePipeSettingsStore.languageSettingsFor(settings, "ja")
        assertNull(ja?.translationLocaleTag)
        assertFalse(ja?.disabled ?: true)
        assertEquals("Use polite form.", ja?.instructions)
    }

    @Test
    fun serializesRoundTrip() {
        val initial = LocalizePipeSettings(
            languages = mapOf(
                "sr" to LanguageSettings(translationLocaleTag = "sr-Cyrl", instructions = "Use formal register."),
                "fr" to LanguageSettings(disabled = true),
            ),
        )

        val serialized = LocalizePipeSettingsStore.serialize(initial)
        val parsed = LocalizePipeSettingsStore.parse(serialized)

        assertEquals(initial, parsed)
    }

    @Test
    fun settingsFilePathMatchesSourceHashesConvention() {
        val androidPath = LocalizePipeSettingsStore.settingsFilePath("C:/repo/app/src/main/res")
        assertEquals("C:/repo/app/src/main/localizepipe-settings.json", androidPath)

        val kmpPath = LocalizePipeSettingsStore.settingsFilePath("C:/repo/shared/src/commonMain/composeResources")
        assertEquals("C:/repo/shared/src/commonMain/localizepipe-settings.json", kmpPath)
    }

    @Test
    fun ignoresUnknownFieldsForForwardCompatibility() {
        val rawJson = """
            {
              "description_of_file": "Test header",
              "future_global_feature": true,
              "languages": {
                "sr": {
                  "translationLocaleTag": "sr-Cyrl",
                  "future_language_feature": "abc"
                }
              }
            }
        """.trimIndent()

        val settings = LocalizePipeSettingsStore.parse(rawJson)
        val sr = LocalizePipeSettingsStore.languageSettingsFor(settings, "sr")
        assertEquals("sr-Cyrl", sr?.translationLocaleTag)
    }

    @Test
    fun serializesOmittingDefaultEntries() {
        val settings = LocalizePipeSettings(
            languages = mapOf(
                "de" to LanguageSettings(), // All default (null, false, null)
                "fr" to LanguageSettings(disabled = true),
            ),
        )

        val serialized = LocalizePipeSettingsStore.serialize(settings)
        val parsed = LocalizePipeSettingsStore.parse(serialized)

        assertNull(LocalizePipeSettingsStore.languageSettingsFor(parsed, "de"))
        assertEquals(true, LocalizePipeSettingsStore.languageSettingsFor(parsed, "fr")?.disabled)
    }
}
