package de.aarondietz.localizepipe.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LocaleDisplayUtilTest {

    @Test
    fun formatsStandardLanguages() {
        assertEquals("German", LocaleDisplayUtil.localeDisplayName("de"))
        assertEquals("de (German)", LocaleDisplayUtil.localeDisplayLabel("de"))

        assertEquals("French", LocaleDisplayUtil.localeDisplayName("fr"))
        assertEquals("fr (French)", LocaleDisplayUtil.localeDisplayLabel("fr"))
    }

    @Test
    fun formatsIso6393MontenegrinVariants() {
        assertEquals("Montenegrin", LocaleDisplayUtil.localeDisplayName("cnr"))
        assertEquals("cnr (Montenegrin)", LocaleDisplayUtil.localeDisplayLabel("cnr"))

        assertEquals("Montenegrin (Latin)", LocaleDisplayUtil.localeDisplayName("cnr-Latn"))
        assertEquals("cnr-Latn (Montenegrin (Latin))", LocaleDisplayUtil.localeDisplayLabel("cnr-Latn"))

        assertEquals("Montenegrin (Cyrillic)", LocaleDisplayUtil.localeDisplayName("cnr-Cyrl"))
        assertEquals("cnr-Cyrl (Montenegrin (Cyrillic))", LocaleDisplayUtil.localeDisplayLabel("cnr-Cyrl"))
    }

    @Test
    fun formatsSerbianVariants() {
        assertEquals("Serbian (Montenegro)", LocaleDisplayUtil.localeDisplayName("sr-ME"))
        assertEquals("sr-ME (Serbian (Montenegro))", LocaleDisplayUtil.localeDisplayLabel("sr-ME"))
    }

    @Test
    fun handlesUnderscoresAndCaseInsensitivity() {
        assertEquals("Montenegrin", LocaleDisplayUtil.localeDisplayName("CNR"))
        assertEquals("Montenegrin (Latin)", LocaleDisplayUtil.localeDisplayName("cnr_latn"))
    }

    @Test
    fun formatsExactScriptTags() {
        assertEquals("Crimean Tatar (Latin)", LocaleDisplayUtil.localeDisplayName("crh-Latn"))
        assertEquals("Banjarese (Arabic)", LocaleDisplayUtil.localeDisplayName("bjn-Arab"))
        assertEquals("Uzbek (Latin)", LocaleDisplayUtil.localeDisplayName("uz-Latn"))
        assertEquals("Turkmen (Latin)", LocaleDisplayUtil.localeDisplayName("tk-Latn"))
        assertEquals("Central Kurdish (Sorani)", LocaleDisplayUtil.localeDisplayName("ckb"))
    }
}
