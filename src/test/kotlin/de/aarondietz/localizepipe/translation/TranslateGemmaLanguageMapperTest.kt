package de.aarondietz.localizepipe.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslateGemmaLanguageMapperTest {
    @Test
    fun mapsTurkishLanguageTag() {
        assertEquals("tr", TranslateGemmaLanguageMapper.toGemmaCode("tr"))
    }

    @Test
    fun mapsPortugueseBrazilTagAndUnderscoreVariant() {
        assertEquals("pt-BR", TranslateGemmaLanguageMapper.toGemmaCode("pt-BR"))
        assertEquals("pt-BR", TranslateGemmaLanguageMapper.toGemmaCode("pt_BR"))
    }

    @Test
    fun mapsChineseScriptVariants() {
        assertEquals("zh-CN", TranslateGemmaLanguageMapper.toGemmaCode("zh-CN"))
        assertEquals("zh-TW", TranslateGemmaLanguageMapper.toGemmaCode("zh-TW"))
        assertEquals("zh-CN", TranslateGemmaLanguageMapper.toGemmaCode("zh-Hans"))
        assertEquals("zh-TW", TranslateGemmaLanguageMapper.toGemmaCode("zh-Hant"))
    }

    @Test
    fun mapsCatalanLanguageTags() {
        assertEquals("ca", TranslateGemmaLanguageMapper.toGemmaCode("ca"))
        assertEquals("ca", TranslateGemmaLanguageMapper.toGemmaCode("ca-ES"))
    }

    @Test
    fun keepsNllbStyleCodeForBackwardCompatibility() {
        assertEquals("eng_Latn", TranslateGemmaLanguageMapper.toGemmaCode("eng_Latn"))
    }

    @Test
    fun keepsPaperSpecificScriptAndRegionTags() {
        assertEquals("sr-Latn", TranslateGemmaLanguageMapper.toGemmaCode("sr-Latn"))
        assertEquals("sr-Cyrl", TranslateGemmaLanguageMapper.toGemmaCode("sr_Cyrl"))
        assertEquals("iu-Latn", TranslateGemmaLanguageMapper.toGemmaCode("iu-Latn"))
        assertEquals("sw-KE", TranslateGemmaLanguageMapper.toGemmaCode("sw-KE"))
        assertEquals("sw-TZ", TranslateGemmaLanguageMapper.toGemmaCode("sw-TZ"))
    }

    @Test
    fun fallsBackToBaseLanguageWhenNoSpecificVariantIsMapped() {
        assertEquals("pt", TranslateGemmaLanguageMapper.toGemmaCode("pt-AO"))
        assertEquals("fr", TranslateGemmaLanguageMapper.toGemmaCode("fr-FR"))
    }

    @Test
    fun returnsNullForInvalidLocale() {
        assertNull(TranslateGemmaLanguageMapper.toGemmaCode("xx-ZZ"))
    }
}
