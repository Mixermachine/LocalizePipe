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
    fun extractEntriesReadsNormalStringFieldsWithoutSourceHash() {
        val xml = """
            <resources>
                <string name="title">Einstellungen</string>
            </resources>
        """.trimIndent()

        val entry = StringsXmlValueExtractor.extractEntries(xml)["title"]

        assertNotNull(entry)
        assertEquals("Einstellungen", entry?.text)
        assertNull(entry?.localizePipeContext)
    }

    @Test
    fun returnsEmptyMapForInvalidXml() {
        val values = StringsXmlValueExtractor.extract("<resources><string name=\"x\">Hello")
        assertTrue(values.isEmpty())
    }

    @Test
    fun extractsLocalizePipeContextWhenPresent() {
        val xml = """
            <resources>
                <string name="save" localizePipeContext="Verb on a toolbar button">Save</string>
                <string name="home">Home</string>
            </resources>
        """.trimIndent()

        val values = StringsXmlValueExtractor.extractEntries(xml)

        assertEquals("Save", values["save"]?.text)
        assertEquals("Verb on a toolbar button", values["save"]?.localizePipeContext)
        assertEquals("Home", values["home"]?.text)
        assertNull(values["home"]?.localizePipeContext)
    }
}
