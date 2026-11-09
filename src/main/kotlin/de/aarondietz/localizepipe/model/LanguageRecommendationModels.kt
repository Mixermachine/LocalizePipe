package de.aarondietz.localizepipe.model

enum class RecommendationSeverity {
    INFO,
    RECOMMENDATION,
    WARNING,
}

data class LanguageRecommendation(
    val localeTag: String,
    val recommendedOverrideTag: String? = null,
    val recommendedInstructions: String? = null,
    val infoMessage: String,
    val severity: RecommendationSeverity = RecommendationSeverity.RECOMMENDATION,
)

object LanguageRecommendationRegistry {
    private val recommendations = listOf(
        LanguageRecommendation(
            localeTag = "cnr",
            recommendedOverrideTag = "cnr-Latn",
            infoMessage = "TranslateGemma requires 'cnr-Latn' to avoid hallucinations. External LLMs may support direct 'cnr'. Compatible with both Android and Compose.",
            severity = RecommendationSeverity.RECOMMENDATION,
        ),
        LanguageRecommendation(
            localeTag = "cnr-Latn",
            recommendedOverrideTag = null,
            infoMessage = "TranslateGemma supports 'cnr-Latn' natively. Note: folder 'values-b+cnr+Latn' is Android-only; for Compose Multiplatform, use 'cnr' with override 'cnr-Latn'.",
            severity = RecommendationSeverity.INFO,
        ),
        LanguageRecommendation(
            localeTag = "cnr-Cyrl",
            recommendedOverrideTag = "cnr-Latn",
            infoMessage = "Cyrillic Montenegrin often drifts into Russian in smaller translation models. 'cnr-Latn' is recommended unless using a frontier external LLM.",
            severity = RecommendationSeverity.WARNING,
        ),
        LanguageRecommendation(
            localeTag = "sr",
            recommendedOverrideTag = null,
            infoMessage = "Serbian supports both 'sr-Latn' (Latin) and 'sr-Cyrl' (Cyrillic). Set an override to pin your desired script.",
            severity = RecommendationSeverity.INFO,
        ),
        LanguageRecommendation(
            localeTag = "sr-ME",
            recommendedOverrideTag = "cnr-Latn",
            infoMessage = "Pin 'cnr-Latn' (Ijekavian Latin) or 'sr-Latn' for consistent script and dialect in translation models.",
            severity = RecommendationSeverity.RECOMMENDATION,
        ),
    ).associateBy { normalizeTag(it.localeTag) }

    fun findRecommendation(localeTag: String?): LanguageRecommendation? {
        if (localeTag.isNullOrBlank()) return null
        return recommendations[normalizeTag(localeTag)]
    }

    fun knownTags(): Set<String> {
        return recommendations.values.map { it.localeTag }.toSet()
    }

    private fun normalizeTag(tag: String): String {
        return tag.trim().replace('_', '-').lowercase()
    }
}
