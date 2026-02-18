package de.aarondietz.localizepipe.settings

import org.junit.Assert.*
import org.junit.Test

class ProjectScanSettingsServiceTest {
    @Test
    fun usesExpectedDefaults() {
        val service = ProjectScanSettingsService()

        assertTrue(service.includeAndroidResources)
        assertTrue(service.includeComposeResources)
        assertEquals("en", service.sourceLocaleTag())
    }

    @Test
    fun updatesProjectScopedScanFlags() {
        val service = ProjectScanSettingsService()

        service.includeAndroidResources = false
        service.includeComposeResources = false
        service.sourceLocaleTag = "de"

        assertFalse(service.includeAndroidResources)
        assertFalse(service.includeComposeResources)
        assertEquals("de", service.sourceLocaleTag())
    }
}
