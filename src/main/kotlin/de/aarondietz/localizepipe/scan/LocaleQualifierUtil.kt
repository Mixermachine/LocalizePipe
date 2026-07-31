package de.aarondietz.localizepipe.scan

import java.util.Locale

object LocaleQualifierUtil {
    fun qualifierToLocaleTag(qualifierRaw: String): String? {
        if (qualifierRaw.isBlank()) {
            return null
        }

        if (qualifierRaw.startsWith("b+")) {
            val parts = qualifierRaw.split('+').drop(1).filter { it.isNotBlank() }
            if (parts.isEmpty()) return null
            val bcp47 = parts.joinToString("-")
            val locale = Locale.forLanguageTag(bcp47)
            val lang = locale.language.lowercase()
            if (lang.isBlank() || lang == "und") return null
            val script = locale.script.takeIf { it.isNotBlank() }?.lowercase()?.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
                ?: parts.drop(1).firstOrNull { it.matches(Regex("[A-Za-z]{4}")) }?.lowercase()?.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
            val country = locale.country.takeIf { it.isNotBlank() }?.uppercase()
                ?: parts.drop(1).firstOrNull { it.matches(Regex("[A-Za-z]{2}")) && !it.matches(Regex("[A-Za-z]{4}")) }?.uppercase()

            return buildString {
                append(lang)
                if (script != null) append("-$script")
                if (country != null) append("-$country")
            }
        }

        val chunks = qualifierRaw.split('-')
        val languageChunk = chunks.firstOrNull()?.lowercase()?.takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: return null
        val scriptChunk = chunks.drop(1).firstOrNull { it.matches(Regex("[A-Za-z]{4}")) }?.lowercase()?.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
        val regionChunk = chunks.drop(1).firstOrNull { it.matches(Regex("r[A-Za-z]{2}")) }?.drop(1)?.uppercase()

        return buildString {
            append(languageChunk)
            if (scriptChunk != null) append("-$scriptChunk")
            if (regionChunk != null) append("-$regionChunk")
        }
    }

    fun localeTagToQualifier(localeTag: String): String {
        val normalized = localeTag.replace('_', '-').trim()
        if (normalized.isBlank()) return ""
        val locale = Locale.forLanguageTag(normalized)
        val parts = normalized.split('-').filter { it.isNotBlank() }

        val language = locale.language.lowercase().ifBlank {
            parts.firstOrNull()?.lowercase().orEmpty()
        }
        val script = locale.script.takeIf { it.isNotBlank() }?.lowercase()?.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
            ?: parts.drop(1).firstOrNull { it.matches(Regex("[A-Za-z]{4}")) }?.lowercase()?.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
        val country = locale.country.takeIf { it.isNotBlank() }?.uppercase()
            ?: parts.drop(1).firstOrNull { it.matches(Regex("[A-Za-z]{2}")) && !it.matches(Regex("[A-Za-z]{4}")) }?.uppercase()

        return if (script != null) {
            buildString {
                append("b+").append(language).append("+").append(script)
                if (country != null) {
                    append("+").append(country)
                }
            }
        } else if (country != null) {
            "$language-r$country"
        } else {
            language
        }
    }
}
