package de.aarondietz.localizepipe.translation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationOutputValidatorTest {
    @Test
    fun acceptsPlaceholderAndTagsWhenPreserved() {
        val result = TranslationOutputValidator.validate(
            baseText = "<b>Welcome</b>, %1\$s!",
            translatedText = "<b>Willkommen</b>, %1\$s!",
        )

        assertTrue(result.isValid)
    }

    @Test
    fun rejectsChangedPlaceholder() {
        val result = TranslationOutputValidator.validate(
            baseText = "Welcome, %1\$s!",
            translatedText = "Willkommen!",
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.contains(ValidationError.PLACEHOLDERS_CHANGED))
    }

    @Test
    fun rejectsEmptyOutput() {
        val result = TranslationOutputValidator.validate(
            baseText = "Settings",
            translatedText = "   ",
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.contains(ValidationError.EMPTY_OUTPUT))
    }

    @Test
    fun acceptsRawAmpersandInText() {
        val result = TranslationOutputValidator.validate(
            baseText = "Fish and chips",
            translatedText = "Poisson & frites",
        )

        assertTrue(result.isValid)
    }

    @Test
    fun rejectsMalformedXmlTags() {
        val result = TranslationOutputValidator.validate(
            baseText = "Settings",
            translatedText = "<b>Parametres",
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.contains(ValidationError.XML_UNSAFE))
    }
}
