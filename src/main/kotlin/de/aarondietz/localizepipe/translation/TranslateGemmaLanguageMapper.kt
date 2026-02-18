package de.aarondietz.localizepipe.translation

import java.util.*

object TranslateGemmaLanguageMapper {
    // TranslateGemma language tags are based on the paper language tables.
    // Keep explicit script/region variants when present so the model can use the most specific form.
    private val supportedExactTags = setOf(
        "ar-EG",
        "ar-MA",
        "ber-Latn",
        "bjn-Arab",
        "bm-Nkoo",
        "ccp-Latn",
        "crh-Latn",
        "fa-AF",
        "fr-CA",
        "grt-Latn",
        "hoc-Wara",
        "iu-Latn",
        "ks-Deva",
        "lif-Limb",
        "mni-Mtei",
        "ms-Arab",
        "ndc-ZW",
        "pa-Arab",
        "pt-BR",
        "pt-PT",
        "rhg-Latn",
        "sat-Latn",
        "sd-Deva",
        "sr-Cyrl",
        "sr-Latn",
        "sw-KE",
        "sw-TZ",
        "unr-Deva",
        "xsr-Tibt",
        "zh-CN",
        "zh-TW",
    )

    private val byExactTag = mapOf(
        // Alias commonly used Chinese script tags to table tags.
        "zh-SG" to "zh-CN",
        "zh-Hans" to "zh-CN",
        "zh-HK" to "zh-TW",
        "zh-MO" to "zh-TW",
        "zh-Hant" to "zh-TW",
    )

    fun supportedLocaleTagsForUi(): List<String> {
        return (Locale.getISOLanguages().toSet() + supportedExactTags)
            .sorted()
    }

    fun toGemmaCode(localeTag: String): String? {
        val trimmed = localeTag.trim()
        if (trimmed.matches(Regex("[a-z]{3}_[A-Za-z]{4}"))) {
            // Keep existing NLLB-style codes usable for backward compatibility.
            return trimmed
        }

        val normalizedTag = normalizeTag(trimmed)
        byExactTag[normalizedTag]?.let { return it }
        if (supportedExactTags.contains(normalizedTag)) {
            return normalizedTag
        }

        val locale = Locale.forLanguageTag(normalizedTag)
        val language = locale.language.takeIf { it.isNotBlank() } ?: return null
        val script = locale.script.takeIf { it.isNotBlank() }
        val region = locale.country.takeIf { it.isNotBlank() }

        val localeCandidates = buildList {
            if (script != null && region != null) {
                add("$language-${script.replaceFirstChar(Char::uppercase)}-$region")
            }
            if (script != null) {
                add("$language-${script.replaceFirstChar(Char::uppercase)}")
            }
            if (region != null) {
                add("$language-$region")
            }
        }
        for (candidate in localeCandidates) {
            byExactTag[candidate]?.let { return it }
            if (supportedExactTags.contains(candidate)) {
                return candidate
            }
        }

        if (language == "zh") {
            return if (script.equals("Hant", ignoreCase = true) || region in setOf("TW", "HK", "MO")) {
                "zh-TW"
            } else {
                "zh-CN"
            }
        }

        return when {
            language.length == 2 && Locale.getISOLanguages().contains(language) -> language
            language.length == 3 -> language
            else -> null
        }
    }

    private fun normalizeTag(localeTag: String): String {
        return localeTag
            .replace('_', '-')
            .trim()
            .split('-')
            .filter { it.isNotBlank() }
            .mapIndexed { index, part ->
                when {
                    index == 0 -> part.lowercase()
                    part.length == 4 && part.all { it.isLetter() } -> {
                        part.lowercase().replaceFirstChar(Char::uppercase)
                    }

                    part.length == 2 && part.all { it.isLetter() } -> part.uppercase()
                    else -> part
                }
            }
            .joinToString("-")
    }
}
