package de.aarondietz.localizepipe.ui.compose

import de.aarondietz.localizepipe.model.ResourceKind
import de.aarondietz.localizepipe.model.RowStatus
import de.aarondietz.localizepipe.model.StringEntryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GroupedStringRowsTest {
    @Test
    fun groupsMultipleMissingLocalesIntoSingleEntryPerKey() {
        val rows = listOf(
            row(locale = "de", status = RowStatus.MISSING),
            row(locale = "fr", status = RowStatus.MISSING),
            row(locale = "tr", status = RowStatus.MISSING),
            row(key = "logout", locale = "de", status = RowStatus.MISSING),
        )

        val grouped = GroupedStringRows.fromRows(rows)

        assertEquals(2, grouped.size)
        val loginGroup = grouped.firstOrNull { it.key == "login" }
        assertNotNull(loginGroup)
        assertEquals(listOf("de", "fr", "tr"), loginGroup!!.missingLocales)
        assertEquals(3, loginGroup.rows.size)
        assertEquals(RowStatus.MISSING, loginGroup.aggregateStatus)
    }

    @Test
    fun preferredRowKeepsSelectionAndFallsBackToMissingLocale() {
        val rows = listOf(
            row(locale = "de", status = RowStatus.IDENTICAL),
            row(locale = "fr", status = RowStatus.MISSING),
            row(locale = "tr", status = RowStatus.READY),
        )
        val group = GroupedStringRows.fromRows(rows).first()

        val selected = group.preferredRow(selectedRowId = rows[2].id)
        val fallback = group.preferredRow(selectedRowId = "missing-id")

        assertEquals("tr", selected.localeTag)
        assertEquals("fr", fallback.localeTag)
    }

    private fun row(
        key: String = "login",
        locale: String,
        status: RowStatus,
    ): StringEntryRow {
        return StringEntryRow(
            id = "root|$locale|$key",
            key = key,
            baseText = "Login",
            localizedText = null,
            proposedText = null,
            localeTag = locale,
            localeQualifierRaw = locale,
            localeFilePath = null,
            resourceRootPath = "/tmp/res",
            moduleName = "app",
            originKind = ResourceKind.ANDROID_RES,
            status = status,
            message = null,
        )
    }
}
