package de.aarondietz.localizepipe.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceChangeMarkerSupportTest {
    @Test
    fun computeSourceHashIsStableAndShort() {
        val hash = SourceChangeMarkerSupport.computeSourceHash("Settings")

        assertEquals(16, hash.length)
        assertEquals(hash, SourceChangeMarkerSupport.computeSourceHash("Settings"))
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun computeSourceHashChangesWhenSourceTextChanges() {
        val first = SourceChangeMarkerSupport.computeSourceHash("Settings")
        val second = SourceChangeMarkerSupport.computeSourceHash("Settings updated")

        assertNotEquals(first, second)
    }

    @Test
    fun computeSourceHashChangesWhenContextChanges() {
        val withoutContext = SourceChangeMarkerSupport.computeSourceHash("Save")
        val withContext = SourceChangeMarkerSupport.computeSourceHash("Save", "Verb on a toolbar button")

        assertNotEquals(withoutContext, withContext)
    }

    @Test
    fun blankContextDoesNotChangeHash() {
        val withoutContext = SourceChangeMarkerSupport.computeSourceHash("Save")
        val blankContext = SourceChangeMarkerSupport.computeSourceHash("Save", "   ")

        assertEquals(withoutContext, blankContext)
    }
}
