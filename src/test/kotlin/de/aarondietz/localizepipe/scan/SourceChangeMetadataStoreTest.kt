package de.aarondietz.localizepipe.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceChangeMetadataStoreTest {
    @Test
    fun parsesAndSerializesLocaleAndKeyHashes() {
        val raw = """
            {
              "de": {
                "title": "0123456789abcdef"
              },
              "fr": {
                "title": "fedcba9876543210"
              }
            }
        """.trimIndent()

        val metadata = SourceChangeMetadataStore.parse(raw)
        val serialized = SourceChangeMetadataStore.serialize(metadata)

        assertEquals("0123456789abcdef", SourceChangeMetadataStore.hashFor(metadata, "de", "title"))
        assertTrue(serialized.contains("\"de\""))
        assertTrue(serialized.contains("\"title\""))
    }

    @Test
    fun upsertAndRemoveHashKeepsOtherEntries() {
        val initial = SourceChangeMetadata(
            localeHashes = mapOf(
                "de" to mapOf("title" to "0123456789abcdef"),
                "fr" to mapOf("logout" to "fedcba9876543210"),
            ),
        )

        val updated = SourceChangeMetadataStore.upsertHash(initial, "de", "logout", "1111111111111111")
        val removed = SourceChangeMetadataStore.removeHash(updated, "de", "title")

        assertEquals("1111111111111111", SourceChangeMetadataStore.hashFor(removed, "de", "logout"))
        assertEquals("fedcba9876543210", SourceChangeMetadataStore.hashFor(removed, "fr", "logout"))
        assertNull(SourceChangeMetadataStore.hashFor(removed, "de", "title"))
    }

    @Test
    fun removeHashDropsEmptyLocaleBucket() {
        val initial = SourceChangeMetadata(localeHashes = mapOf("de" to mapOf("title" to "0123456789abcdef")))

        val removed = SourceChangeMetadataStore.removeHash(initial, "de", "title")

        assertTrue(removed.isEmpty())
    }
}
