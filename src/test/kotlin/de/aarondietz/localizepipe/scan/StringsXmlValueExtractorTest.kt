package de.aarondietz.localizepipe.scan

import org.junit.Assert.*
import org.junit.Test

class StringsXmlValueExtractorTest {
    @Test
    fun skipsStringsMarkedAsNotTranslatable() {
        val xml = """
            <resources>
                <string name="title">Settings</string>
                <string name="debug_label" translatable="false">Debug</string>
                <string name="cta" translatable="TRUE">Save</string>
            </resources>
        """.trimIndent()

        val values = StringsXmlValueExtractor.extract(xml)

        assertEquals(2, values.size)
        assertEquals("Settings", values["title"])
        assertEquals("Save", values["cta"])
        assertFalse(values.containsKey("debug_label"))
    }

    @Test
    fun returnsEmptyMapForInvalidXml() {
        val values = StringsXmlValueExtractor.extract("<resources><string name=\"x\">Hello")
        assertTrue(values.isEmpty())
    }
}
