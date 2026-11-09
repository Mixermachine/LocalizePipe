package de.aarondietz.localizepipe.model

import java.util.Locale

object LocaleDisplayUtil {
    private val customDisplayNames = mapOf(
        "cnr" to "Montenegrin",
        "cnr-latn" to "Montenegrin (Latin)",
        "cnr-cyrl" to "Montenegrin (Cyrillic)",
        "sr-me" to "Serbian (Montenegro)",
        "sr-latn-me" to "Serbian (Latin, Montenegro)",
        "ber-latn" to "Berber (Latin)",
        "bjn-arab" to "Banjarese (Arabic)",
        "bm-nkoo" to "Bambara (N'Ko)",
        "ccp-latn" to "Chakma (Latin)",
        "crh-latn" to "Crimean Tatar (Latin)",
        "grt-latn" to "Garo (Latin)",
        "hoc-wara" to "Ho (Warang Citi)",
        "lif-limb" to "Limbu (Limbu)",
        "mni-mtei" to "Manipuri (Meitei Mayek)",
        "ndc-zw" to "Ndau (Zimbabwe)",
        "rhg-latn" to "Rohingya (Latin)",
        "sat-latn" to "Santali (Latin)",
        "unr-deva" to "Mundari (Devanagari)",
        "xsr-tibt" to "Sherpa (Tibetan)",
    )

    fun localeDisplayName(localeTag: String): String {
        val normalized = localeTag.trim().replace('_', '-')
        customDisplayNames[normalized.lowercase()]?.let { return it }

        val locale = Locale.forLanguageTag(normalized)
        val name = locale.getDisplayName(Locale.ENGLISH)

        val langCustom = customDisplayNames[locale.language.lowercase()]
        if (langCustom != null) {
            val script = locale.script.takeIf { it.isNotBlank() }
            val country = locale.country.takeIf { it.isNotBlank() }
            return when {
                script != null && country != null -> "$langCustom ($script, $country)"
                script != null -> "$langCustom ($script)"
                country != null -> "$langCustom ($country)"
                else -> langCustom
            }
        }

        if (name.isNotBlank() && !name.equals(normalized, ignoreCase = true)) {
            return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
        }

        return normalized
    }

    fun localeDisplayLabel(localeTag: String): String {
        val normalized = localeTag.trim().replace('_', '-')
        val name = localeDisplayName(normalized)
        return if (name != normalized && name.isNotBlank()) {
            "$normalized ($name)"
        } else {
            normalized
        }
    }
}
