package de.aarondietz.localizepipe.apply

import de.aarondietz.localizepipe.model.ResourceKind
import org.junit.Assert.*
import org.junit.Test

class TranslationApplierFormattingTest {
    @Test
    fun insertsRealNewlineAndKeepsClosingTagOnSeparateLine() {
        val input = "<resources>\n</resources>\n"
        val updated = TranslationApplier.upsertStringText(
            currentText = input,
            key = "settings_title",
            translatedText = "Einstellungen",
        )

        assertTrue(updated.contains("<string name=\"settings_title\">Einstellungen</string>\n</resources>"))
        assertFalse(updated.contains("\\n</resources>"))
    }

    @Test
    fun insertsNewlineBeforeClosingTagWhenMissing() {
        val input = "<resources><string name=\"a\">A</string></resources>"
        val updated = TranslationApplier.upsertStringText(
            currentText = input,
            key = "b",
            translatedText = "B",
        )

        assertTrue(updated.contains("</string>\n    <string name=\"b\">B</string>\n</resources>"))
        assertFalse(updated.contains("\\n"))
    }

    @Test
    fun normalizeForWriteEscapesAndroidApostrophe() {
        val normalized = TranslationApplier.normalizeForWrite(
            translatedText = "Position de l'image NFC",
            kind = ResourceKind.ANDROID_RES,
        )

        assertEquals("Position de l\\'image NFC", normalized)
    }

    @Test
    fun normalizeForWriteKeepsComposeApostropheLiteral() {
        val normalized = TranslationApplier.normalizeForWrite(
            translatedText = "Position de l'image NFC",
            kind = ResourceKind.COMPOSE_RESOURCES,
        )

        assertEquals("Position de l'image NFC", normalized)
    }

    @Test
    fun upsertStringTextReplacesExistingEntry() {
        val input = """
            <resources>
                <string name="ppos_option_nfc_position">old</string>
            </resources>
            
        """.trimIndent()
        val updated = TranslationApplier.upsertStringText(
            currentText = input,
            key = "ppos_option_nfc_position",
            translatedText = "Position de l\\'image NFC",
        )

        assertTrue(
            Regex("""<string name="ppos_option_nfc_position">Position de l\\'image NFC</string>""")
                .containsMatchIn(updated),
        )
        assertFalse(updated.contains(">old</string>"))
    }

    @Test
    fun upsertStringTextKeepsLiteralQuotesAndApostrophesInTextNode() {
        val updated = TranslationApplier.upsertStringText(
            currentText = "<resources>\n</resources>\n",
            key = "delete_student_confirm",
            translatedText = "Supprimer \"%1\$s\" ? L\\'etudiant",
        )

        assertTrue(updated.contains("<string name=\"delete_student_confirm\">Supprimer \"%1\$s\" ? L\\'etudiant</string>"))
        assertFalse(updated.contains("&quot;"))
        assertFalse(updated.contains("&apos;"))
        assertFalse(updated.contains("&#39;"))
    }

    @Test
    fun normalizeForWriteStripsInvalidXmlControlChars() {
        val normalized = TranslationApplier.normalizeForWrite(
            translatedText = "Bon\u0000jour\u0007 !",
            kind = ResourceKind.COMPOSE_RESOURCES,
        )

        assertEquals("Bonjour !", normalized)
    }

    @Test
    fun removeStringTextDeletesExistingStringEntry() {
        val input = """
            <resources>
                <string name="delete_me">Bonjour</string>
                <string name="keep_me">Salut</string>
            </resources>
        """.trimIndent()

        val (updated, deleted) = TranslationApplier.removeStringText(
            currentText = input,
            key = "delete_me",
        )

        assertTrue(deleted)
        assertFalse(updated.contains("name=\"delete_me\""))
        assertTrue(updated.contains("name=\"keep_me\""))
    }

    @Test
    fun removeStringTextReturnsUnchangedWhenMissing() {
        val input = "<resources>\n    <string name=\"keep_me\">Salut</string>\n</resources>\n"

        val (updated, deleted) = TranslationApplier.removeStringText(
            currentText = input,
            key = "missing_key",
        )

        assertFalse(deleted)
        assertEquals(input, updated)
    }

    @Test
    fun normalizeForWriteConvertsNbspVariantsToRegularSpaces() {
        val normalized = TranslationApplier.normalizeForWrite(
            translatedText = "Supprimer \"%1\$s\"\u00A0? Total\u202F: %1\$d\u2007Mo",
            kind = ResourceKind.COMPOSE_RESOURCES,
        )

        assertEquals("Supprimer \"%1\$s\" ? Total : %1\$d Mo", normalized)
    }
}
