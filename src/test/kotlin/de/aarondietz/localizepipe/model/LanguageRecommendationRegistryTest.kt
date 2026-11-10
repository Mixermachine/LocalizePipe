package de.aarondietz.localizepipe.model

import org.junit.Assert.*
import org.junit.Test

class LanguageRecommendationRegistryTest {

    @Test
    fun findsRecommendationForMontenegrinVariants() {
        val cnr = LanguageRecommendationRegistry.findRecommendation("cnr")
        assertNotNull(cnr)
        assertEquals("cnr-Latn", cnr?.recommendedOverrideTag)
        assertEquals(RecommendationSeverity.RECOMMENDATION, cnr?.severity)
        assertTrue(cnr!!.infoMessage.contains("TranslateGemma"))

        val cnrLatn = LanguageRecommendationRegistry.findRecommendation("cnr-Latn")
        assertNotNull(cnrLatn)
        assertNull(cnrLatn?.recommendedOverrideTag)
        assertEquals(RecommendationSeverity.INFO, cnrLatn?.severity)

        val cnrCyrl = LanguageRecommendationRegistry.findRecommendation("cnr-Cyrl")
        assertNotNull(cnrCyrl)
        assertEquals("cnr-Latn", cnrCyrl?.recommendedOverrideTag)
        assertEquals(RecommendationSeverity.WARNING, cnrCyrl?.severity)
    }

    @Test
    fun handlesCaseAndSeparatorNormalization() {
        val cnrUpper = LanguageRecommendationRegistry.findRecommendation("CNR")
        assertNotNull(cnrUpper)
        assertEquals("cnr-Latn", cnrUpper?.recommendedOverrideTag)

        val cnrUnderscore = LanguageRecommendationRegistry.findRecommendation("cnr_latn")
        assertNotNull(cnrUnderscore)
        assertEquals("cnr-Latn", cnrUnderscore?.localeTag)

        val whitespace = LanguageRecommendationRegistry.findRecommendation("  cnr  ")
        assertNotNull(whitespace)
    }

    @Test
    fun returnsNullForUnknownOrBlankTag() {
        assertNull(LanguageRecommendationRegistry.findRecommendation(null))
        assertNull(LanguageRecommendationRegistry.findRecommendation(""))
        assertNull(LanguageRecommendationRegistry.findRecommendation("   "))
        assertNull(LanguageRecommendationRegistry.findRecommendation("de"))
        assertNull(LanguageRecommendationRegistry.findRecommendation("unknown-tag"))
    }

    @Test
    fun findsRecommendationForDualScriptLanguages() {
        val uz = LanguageRecommendationRegistry.findRecommendation("uz")
        assertNotNull(uz)
        assertEquals("uz-Latn", uz?.recommendedOverrideTag)
        assertEquals(RecommendationSeverity.RECOMMENDATION, uz?.severity)

        val tk = LanguageRecommendationRegistry.findRecommendation("tk")
        assertNotNull(tk)
        assertEquals("tk-Latn", tk?.recommendedOverrideTag)

        val zh = LanguageRecommendationRegistry.findRecommendation("zh")
        assertNotNull(zh)
        assertNull(zh?.recommendedOverrideTag)
        assertEquals(RecommendationSeverity.RECOMMENDATION, zh?.severity)

        val ku = LanguageRecommendationRegistry.findRecommendation("ku")
        assertNotNull(ku)
        assertEquals("ku-Latn", ku?.recommendedOverrideTag)
        assertEquals(RecommendationSeverity.WARNING, ku?.severity)
    }

    @Test
    fun dynamicallyRecommendsOverrideForLanguagesWithMultipleScripts() {
        // bjn has bjn-Arab and bjn-Latn in TranslateGemma exact tags
        val bjn = LanguageRecommendationRegistry.findRecommendation("bjn")
        assertNotNull(bjn)
        assertEquals(RecommendationSeverity.RECOMMENDATION, bjn?.severity)
        assertTrue(bjn!!.infoMessage.contains("multiple scripts"))
        assertTrue(bjn.infoMessage.contains("prevent the model from switching between alphabets"))
    }

    @Test
    fun knownTagsIncludeMontenegrinAndSerbian() {
        val known = LanguageRecommendationRegistry.knownTags()
        assertTrue(known.contains("cnr"))
        assertTrue(known.contains("cnr-Latn"))
        assertTrue(known.contains("cnr-Cyrl"))
        assertTrue(known.contains("sr"))
        assertTrue(known.contains("sr-ME"))
        assertTrue(known.contains("uz"))
        assertTrue(known.contains("tk"))
        assertTrue(known.contains("zh"))
        assertTrue(known.contains("ku"))
    }
}
