package de.aarondietz.localizepipe.translation

import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

enum class ValidationError {
    EMPTY_OUTPUT,
    PLACEHOLDERS_CHANGED,
    TAGS_CHANGED,
    XML_UNSAFE,
}

data class ValidationResult(
    val isValid: Boolean,
    val errors: Set<ValidationError>,
)

object TranslationOutputValidator {
    private val androidPlaceholderRegex = Regex("%(?:\\d+\\$)?[#+ 0,(<]*\\d*(?:\\.\\d+)?[a-zA-Z]")
    private val simpleXmlTagRegex = Regex("</?[A-Za-z][A-Za-z0-9]*>")
    private val bareAmpersandRegex = Regex("&(?!#\\d+;|#x[0-9A-Fa-f]+;|[A-Za-z][A-Za-z0-9]+;)")

    fun validate(baseText: String, translatedText: String): ValidationResult {
        val errors = linkedSetOf<ValidationError>()

        if (translatedText.isBlank()) {
            errors += ValidationError.EMPTY_OUTPUT
        }

        val basePlaceholders = extractPlaceholders(baseText)
        val translatedPlaceholders = extractPlaceholders(translatedText)
        if (basePlaceholders != translatedPlaceholders) {
            errors += ValidationError.PLACEHOLDERS_CHANGED
        }

        val baseTags = extractTags(baseText)
        val translatedTags = extractTags(translatedText)
        if (baseTags != translatedTags) {
            errors += ValidationError.TAGS_CHANGED
        }
        if (!isXmlSafe(translatedText)) {
            errors += ValidationError.XML_UNSAFE
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
        )
    }

    private fun extractPlaceholders(value: String): List<String> =
        androidPlaceholderRegex.findAll(value).map { it.value }.toList()

    private fun extractTags(value: String): List<String> =
        simpleXmlTagRegex.findAll(value).map { it.value }.toList()

    private fun isXmlSafe(translatedText: String): Boolean {
        val sanitized = stripInvalidXmlChars(translatedText)
        return canParseWrapped(sanitized) || canParseWrapped(escapeBareAmpersands(sanitized))
    }

    private fun canParseWrapped(value: String): Boolean {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val builder = factory.newDocumentBuilder()
            val wrappedXml = "<resources><string name=\"x\">$value</string></resources>"
            builder.parse(InputSource(StringReader(wrappedXml)))
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun escapeBareAmpersands(value: String): String = value.replace(bareAmpersandRegex, "&amp;")

    private fun stripInvalidXmlChars(value: String): String {
        if (value.isEmpty()) {
            return value
        }
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            val isAllowed = codePoint == 0x9 ||
                    codePoint == 0xA ||
                    codePoint == 0xD ||
                    codePoint in 0x20..0xD7FF ||
                    codePoint in 0xE000..0xFFFD ||
                    codePoint in 0x10000..0x10FFFF
            if (isAllowed) {
                out.appendCodePoint(codePoint)
            }
            index += Character.charCount(codePoint)
        }
        return out.toString()
    }
}
