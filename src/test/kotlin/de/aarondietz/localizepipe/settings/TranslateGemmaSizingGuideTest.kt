package de.aarondietz.localizepipe.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateGemmaSizingGuideTest {
    @Test
    fun parsesOllamaAndHuggingFaceModelSizes() {
        assertEquals(
            TranslateGemmaSize.SIZE_4B,
            TranslateGemmaSizingGuide.sizeForModelId("translategemma:4b-it-q4_K_M"),
        )
        assertEquals(
            TranslateGemmaSize.SIZE_12B,
            TranslateGemmaSizingGuide.sizeForModelId("google/translategemma-12b-it"),
        )
        assertEquals(
            TranslateGemmaSize.SIZE_27B,
            TranslateGemmaSizingGuide.sizeForModelId("translategemma:27b"),
        )
    }

    @Test
    fun recommendsLargestSizeThatFitsDetectedRam() {
        assertEquals(TranslateGemmaSize.SIZE_4B, TranslateGemmaSizingGuide.recommendedSize(8))
        assertEquals(TranslateGemmaSize.SIZE_12B, TranslateGemmaSizingGuide.recommendedSize(16))
        assertEquals(TranslateGemmaSize.SIZE_27B, TranslateGemmaSizingGuide.recommendedSize(32))
        assertEquals(TranslateGemmaSize.SIZE_4B, TranslateGemmaSizingGuide.recommendedSize(null))
    }

    @Test
    fun rendersGuidanceHtmlWithRecommendedModel() {
        val html = TranslateGemmaSizingGuide.guidanceHtml(
            providerType = TranslationProviderType.OLLAMA,
            totalSystemRamGb = 24,
            recommendedSize = TranslateGemmaSize.SIZE_12B,
            availableStorageGb = 40,
            selectedModelId = "translategemma:12b",
        )

        assertTrue(html.contains("Detected system RAM: 24 GB"))
        assertTrue(html.contains("Detected free storage: 40 GB free"))
        assertTrue(html.contains("Recommended now: translategemma:12b"))
        assertTrue(html.contains("runtime memory"))
        assertTrue(html.contains("4B"))
        assertTrue(html.contains("12B"))
        assertTrue(html.contains("27B"))
    }

    @Test
    fun rendersStorageWarningWhenSelectedModelExceedsFreeSpace() {
        val html = TranslateGemmaSizingGuide.guidanceHtml(
            providerType = TranslationProviderType.OLLAMA,
            totalSystemRamGb = 32,
            recommendedSize = TranslateGemmaSize.SIZE_27B,
            availableStorageGb = 10,
            selectedModelId = "translategemma:27b",
        )

        assertTrue(html.contains("Warning:"))
        assertTrue(html.contains("translategemma:27b"))
        assertTrue(html.contains("only 10 GB is available"))
    }
}
