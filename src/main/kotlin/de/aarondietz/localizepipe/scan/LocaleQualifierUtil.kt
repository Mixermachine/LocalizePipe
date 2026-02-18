package de.aarondietz.localizepipe.scan

object LocaleQualifierUtil {
    fun qualifierToLocaleTag(qualifierRaw: String): String? {
        if (qualifierRaw.isBlank()) {
            return null
        }

        val chunks = qualifierRaw.split('-')
        val languageChunk = chunks.firstOrNull()?.lowercase()?.takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: return null
        val regionChunk = chunks.firstOrNull { it.matches(Regex("r[A-Za-z]{2}")) }

        return if (regionChunk != null) {
            "$languageChunk-${regionChunk.drop(1).uppercase()}"
        } else {
            languageChunk
        }
    }

    fun localeTagToQualifier(localeTag: String): String {
        val normalized = localeTag.replace('_', '-').trim()
        val parts = normalized.split('-').filter { it.isNotBlank() }
        val language = parts.firstOrNull()?.lowercase() ?: normalized.lowercase()
        val region = parts.getOrNull(1)?.uppercase()
        return if (region != null) "$language-r$region" else language
    }
}
