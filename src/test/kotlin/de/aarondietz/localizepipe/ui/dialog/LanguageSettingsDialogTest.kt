package de.aarondietz.localizepipe.ui.dialog

import de.aarondietz.localizepipe.model.LanguageAddTarget
import de.aarondietz.localizepipe.model.ResourceKind
import de.aarondietz.localizepipe.scan.LanguageSettings
import de.aarondietz.localizepipe.scan.LocalizePipeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageSettingsDialogTest {

    private val root1 = "C:/project/android/res"
    private val root2 = "C:/project/kmp/composeResources"

    private val target1 = LanguageAddTarget(
        id = "root1",
        resourceRootPath = root1,
        moduleName = "app",
        originKind = ResourceKind.ANDROID_RES,
        existingLocaleTags = listOf("de", "fr"),
    )

    private val target2 = LanguageAddTarget(
        id = "root2",
        resourceRootPath = root2,
        moduleName = "shared",
        originKind = ResourceKind.COMPOSE_RESOURCES,
        existingLocaleTags = listOf("de", "sr"),
    )

    @Test
    fun mergesDetectedLocalesWithSettings() {
        val detectedLocales = setOf("de", "fr")
        val settingsByRoot = mapOf(
            root1 to LocalizePipeSettings(
                languages = mapOf(
                    "de" to LanguageSettings(instructions = "Use informal"),
                    "ja" to LanguageSettings(disabled = true), // Settings-only locale
                ),
            ),
        )

        val rows = computeLanguageSettingsRows(
            detectedLocales = detectedLocales,
            languageTargets = listOf(target1),
            settingsByRoot = settingsByRoot,
        )

        assertEquals(3, rows.size)
        val localeTags = rows.map { it.localeTag }
        assertEquals(listOf("de", "fr", "ja"), localeTags)

        val deRow = rows.first { it.localeTag == "de" }
        assertEquals("Use informal", deRow.instructions)
        assertFalse(deRow.disabled)

        val jaRow = rows.first { it.localeTag == "ja" }
        assertTrue(jaRow.disabled)
    }

    @Test
    fun buildsSaveDataPerRoot() {
        val rows = listOf(
            LanguageSettingsRow(
                localeTag = "de",
                displayName = "de (German)",
                disabled = false,
                translationLocaleTag = "de-DE",
                instructions = "Formal",
                presentInRoots = setOf(root1, root2),
            ),
            LanguageSettingsRow(
                localeTag = "fr",
                displayName = "fr (French)",
                disabled = true,
                translationLocaleTag = null,
                instructions = null,
                presentInRoots = setOf(root1),
            ),
            LanguageSettingsRow(
                localeTag = "sr",
                displayName = "sr (Serbian)",
                disabled = false,
                translationLocaleTag = "sr-Cyrl",
                instructions = null,
                presentInRoots = setOf(root2),
            ),
        )

        val saveData = buildSaveDataPerRoot(
            languageTargets = listOf(target1, target2),
            rows = rows,
        )

        val root1Settings = saveData[root1]
        val root2Settings = saveData[root2]

        assertEquals(2, root1Settings?.languages?.size)
        assertEquals(2, root2Settings?.languages?.size)

        assertEquals("de-DE", root1Settings?.languages?.get("de")?.translationLocaleTag)
        assertTrue(root1Settings?.languages?.get("fr")?.disabled == true)
        assertNull(root1Settings?.languages?.get("sr"))

        assertEquals("de-DE", root2Settings?.languages?.get("de")?.translationLocaleTag)
        assertEquals("sr-Cyrl", root2Settings?.languages?.get("sr")?.translationLocaleTag)
        assertNull(root2Settings?.languages?.get("fr"))
    }

    @Test
    fun preservesExistingSettingsForUnchangedLocales() {
        val rows = listOf(
            LanguageSettingsRow(
                localeTag = "de",
                displayName = "de (German)",
                disabled = false,
                translationLocaleTag = "de-CH",
                instructions = "Swiss German",
                presentInRoots = setOf(root1),
            ),
            LanguageSettingsRow(
                localeTag = "fr",
                displayName = "fr (French)",
                disabled = true,
                translationLocaleTag = null,
                instructions = "Formal",
                presentInRoots = setOf(root1),
            ),
        )

        val saveData = buildSaveDataPerRoot(
            languageTargets = listOf(target1),
            rows = rows,
        )

        val settings = saveData[root1]
        assertEquals("de-CH", settings?.languages?.get("de")?.translationLocaleTag)
        assertEquals("Swiss German", settings?.languages?.get("de")?.instructions)
        assertTrue(settings?.languages?.get("fr")?.disabled == true)
        assertEquals("Formal", settings?.languages?.get("fr")?.instructions)
    }
}
