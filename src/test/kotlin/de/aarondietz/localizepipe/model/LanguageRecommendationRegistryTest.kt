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
    fun knownTagsIncludeMontenegrinAndSerbian() {
        val known = LanguageRecommendationRegistry.knownTags()
        assertTrue(known.contains("cnr"))
        assertTrue(known.contains("cnr-Latn"))
        assertTrue(known.contains("cnr-Cyrl"))
        assertTrue(known.contains("sr"))
        assertTrue(known.contains("sr-ME"))
    }
}
