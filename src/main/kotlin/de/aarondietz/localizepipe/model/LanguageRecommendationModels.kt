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
            recommendedOverrideTag = "sr-Latn",
            infoMessage = "Serbian supports both 'sr-Latn' (Latin) and 'sr-Cyrl' (Cyrillic). Overriding to 'sr-Latn' ensures modern mobile UI and prevents the model from switching between alphabets.",
            severity = RecommendationSeverity.RECOMMENDATION,
        ),
        LanguageRecommendation(
            localeTag = "sr-ME",
            recommendedOverrideTag = "cnr-Latn",
            infoMessage = "Pin 'cnr-Latn' (Ijekavian Latin) or 'sr-Latn' for consistent script and dialect in translation models.",
            severity = RecommendationSeverity.RECOMMENDATION,
        ),
        LanguageRecommendation(
            localeTag = "uz",
            recommendedOverrideTag = "uz-Latn",
            infoMessage = "Uzbek digital UIs exclusively use Latin script. Overriding to 'uz-Latn' prevents models from drifting into legacy Soviet Cyrillic.",
            severity = RecommendationSeverity.RECOMMENDATION,
        ),
        LanguageRecommendation(
            localeTag = "tk",
            recommendedOverrideTag = "tk-Latn",
            infoMessage = "Turkmen officially and digitally uses Latin script. Overriding to 'tk-Latn' prevents models from generating legacy Cyrillic.",
            severity = RecommendationSeverity.RECOMMENDATION,
        ),
        LanguageRecommendation(
            localeTag = "zh",
            recommendedOverrideTag = null,
            infoMessage = "Chinese uses multiple character sets: Simplified ('zh-CN' / 'zh-Hans') or Traditional ('zh-TW' / 'zh-Hant'). Set an override to prevent the model from mixing scripts.",
            severity = RecommendationSeverity.RECOMMENDATION,
        ),
        LanguageRecommendation(
            localeTag = "ku",
            recommendedOverrideTag = "ku-Latn",
            infoMessage = "Kurdish uses Latin script ('ku-Latn' / Kurmanji) or Arabic RTL ('ckb' / Sorani). Set 'ku-Latn' or 'ckb' to prevent script and layout mixing.",
            severity = RecommendationSeverity.WARNING,
        ),
        LanguageRecommendation(
            localeTag = "iu",
            recommendedOverrideTag = null,
            infoMessage = "Inuktitut uses multiple scripts: Canadian Aboriginal Syllabics ('iu-Cans') or Latin ('iu-Latn'). Set an override to prevent the model from switching alphabets.",
            severity = RecommendationSeverity.RECOMMENDATION,
        ),
        LanguageRecommendation(
            localeTag = "sd",
            recommendedOverrideTag = null,
            infoMessage = "Sindhi uses multiple alphabets: Arabic script ('sd-Arab') or Devanagari ('sd-Deva'). Set an override so the model does not switch alphabets.",
            severity = RecommendationSeverity.RECOMMENDATION,
        ),
        LanguageRecommendation(
            localeTag = "bjn-Arab",
            recommendedOverrideTag = "bjn-Latn",
            infoMessage = "TranslateGemma lists Banjarese in Arabic Jawi script ('bjn-Arab'), but everyday mobile users read Latin ('bjn-Latn').",
            severity = RecommendationSeverity.RECOMMENDATION,
        ),
        LanguageRecommendation(
            localeTag = "bm-Nkoo",
            recommendedOverrideTag = "bm-Latn",
            infoMessage = "TranslateGemma lists Bambara in N'Ko script ('bm-Nkoo'), but standard mobile apps in Mali use Latin ('bm-Latn').",
            severity = RecommendationSeverity.RECOMMENDATION,
        ),
    ).associateBy { normalizeTag(it.localeTag) }

    fun findRecommendation(localeTag: String?): LanguageRecommendation? {
        if (localeTag.isNullOrBlank()) return null
        val normalized = normalizeTag(localeTag)
        val direct = recommendations[normalized]
        if (direct != null) return direct

        // Dynamic check: If a base language has multiple recognized script variants (multi-script / multi-font),
        // advise the developer to pin an explicit script override so the LLM does not switch between alphabets.
        if (!normalized.contains('-')) {
            val variants = de.aarondietz.localizepipe.translation.TranslateGemmaLanguageMapper.scriptVariantsForLanguage(normalized)
            if (variants.size > 1) {
                val formattedVariants = variants.sorted().joinToString(" or ") { "'$normalized-$it'" }
                return LanguageRecommendation(
                    localeTag = localeTag.trim(),
                    recommendedOverrideTag = null,
                    infoMessage = "This language uses multiple scripts or alphabets ($formattedVariants). Set a translation override to prevent the model from switching between alphabets.",
                    severity = RecommendationSeverity.RECOMMENDATION,
                )
            }
        }

        return null
    }

    fun knownTags(): Set<String> {
        return recommendations.values.map { it.localeTag }.toSet()
    }

    private fun normalizeTag(tag: String): String {
        return tag.trim().replace('_', '-').lowercase()
    }
}
