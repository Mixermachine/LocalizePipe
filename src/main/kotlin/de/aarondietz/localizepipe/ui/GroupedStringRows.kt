package de.aarondietz.localizepipe.ui

import de.aarondietz.localizepipe.model.RowStatus
import de.aarondietz.localizepipe.model.StringEntryRow

data class GroupedStringRow(
    val id: String,
    val key: String,
    val baseText: String,
    val resourceRootPath: String,
    val moduleName: String?,
    val rows: List<StringEntryRow>,
    val missingLocales: List<String>,
    val proposedCount: Int,
    val aggregateStatus: RowStatus,
)

object GroupedStringRows {
    fun fromRows(rows: List<StringEntryRow>): List<GroupedStringRow> {
        val grouped = rows.groupBy {
            GroupKey(
                resourceRootPath = it.resourceRootPath,
                moduleName = it.moduleName,
                key = it.key,
                baseText = it.baseText,
            )
        }

        return grouped.entries
            .map { (key, groupedRows) ->
                val sortedRows = groupedRows.sortedWith(compareBy<StringEntryRow> { it.localeTag }.thenBy { it.id })
                GroupedStringRow(
                    id = "${key.resourceRootPath}|${key.moduleName ?: ""}|${key.key}|${key.baseText.hashCode()}",
                    key = key.key,
                    baseText = key.baseText,
                    resourceRootPath = key.resourceRootPath,
                    moduleName = key.moduleName,
                    rows = sortedRows,
                    missingLocales = sortedRows.filter { it.status == RowStatus.MISSING }.map { it.localeTag },
                    proposedCount = sortedRows.count { !it.proposedText.isNullOrBlank() && it.status != RowStatus.ERROR },
                    aggregateStatus = aggregateStatus(sortedRows),
                )
            }
            .sortedWith(
                compareBy<GroupedStringRow> { it.key }
                    .thenBy { it.moduleName ?: "" }
                    .thenBy { it.resourceRootPath },
            )
    }

    private fun aggregateStatus(rows: List<StringEntryRow>): RowStatus {
        val severity = listOf(
            RowStatus.ERROR,
            RowStatus.MISSING,
            RowStatus.IDENTICAL,
            RowStatus.READY,
            RowStatus.UP_TO_DATE,
        )
        return severity.firstOrNull { status -> rows.any { it.status == status } } ?: RowStatus.UP_TO_DATE
    }

    private data class GroupKey(
        val resourceRootPath: String,
        val moduleName: String?,
        val key: String,
        val baseText: String,
    )
}

fun GroupedStringRow.preferredRow(selectedRowId: String?): StringEntryRow {
    rows.firstOrNull { it.id == selectedRowId }?.let { return it }
    rows.firstOrNull { it.status == RowStatus.MISSING }?.let { return it }
    rows.firstOrNull { it.status == RowStatus.IDENTICAL }?.let { return it }
    return rows.first()
}
