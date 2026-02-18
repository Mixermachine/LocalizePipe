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
            Regex("""<string name="ppos_option_nfc_position">Position de l\\(?:&apos;|&#39;|')image NFC</string>""")
                .containsMatchIn(updated),
        )
        assertFalse(updated.contains(">old</string>"))
    }
}
