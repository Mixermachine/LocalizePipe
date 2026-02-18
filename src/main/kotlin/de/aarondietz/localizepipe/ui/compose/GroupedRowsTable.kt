package de.aarondietz.localizepipe.ui.compose

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.max
import org.jetbrains.jewel.ui.component.Text

/**
 * Scrollable grouped-rows table for missing/identical translation candidates.
 */
@Composable
internal fun GroupedRowsTable(
    groupedRows: List<GroupedStringRow>,
    selectedRowId: String?,
    onSelectRow: (String) -> Unit,
) {
    val listHorizontalScroll = rememberScrollState()
    val tableVerticalScroll = rememberScrollState()
    val density = LocalDensity.current
    var measuredRowHeightPx by remember { mutableStateOf(0) }

    val tableMaxHeightDp = TableMaxHeight
    val defaultRowHeightDp = DefaultRowHeight
    val rowSpacingDp = TableRowSpacing

    val tableMaxHeightPx = with(density) { tableMaxHeightDp.dp.roundToPx() }
    val defaultRowHeightPx = with(density) { defaultRowHeightDp.dp.roundToPx() }
    val rowSpacingPx = with(density) { rowSpacingDp.dp.roundToPx() }
    val effectiveRowHeightPx = max(measuredRowHeightPx, defaultRowHeightPx)
    val rowContentHeightPx = if (groupedRows.isEmpty()) {
        defaultRowHeightPx
    } else {
        (groupedRows.size * effectiveRowHeightPx) + ((groupedRows.size - 1) * rowSpacingPx)
    }
    val dynamicTableHeight = with(density) { rowContentHeightPx.coerceAtMost(tableMaxHeightPx).toDp() }
    val showTableVerticalScrollbar = rowContentHeightPx > tableMaxHeightPx

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(listHorizontalScroll),
    ) {
        Box(
            modifier = Modifier
                .width(980.dp)
                .height(dynamicTableHeight),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(tableVerticalScroll)
                    .padding(end = if (showTableVerticalScrollbar) 10.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(rowSpacingDp.dp),
            ) {
                groupedRows.forEach { group ->
                    val preferred = group.preferredRow(selectedRowId)
                    val isSelected = preferred.id == selectedRowId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = defaultRowHeightDp.dp)
                            .background(
                                color = if (isSelected) SelectedRowBackground else Color.Transparent,
                                shape = RoundedCornerShape(4.dp),
                            )
                            .clickable { onSelectRow(preferred.id) }
                            .padding(horizontal = 6.dp, vertical = 5.dp)
                            .onSizeChanged { size ->
                                if (size.height > measuredRowHeightPx) {
                                    measuredRowHeightPx = size.height
                                }
                            },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TableCellText(text = if (isSelected) ">" else "-", width = SelectGlyphColumnWidth)
                        TableCellText(text = group.aggregateStatus.name, width = StatusColumnWidth)
                        TableCellText(text = group.key, width = KeyColumnWidth)
                        TableCellText(text = group.baseText, width = BaseTextColumnWidth)
                        TableCellText(
                            text = if (group.missingLocales.isEmpty()) "<none>" else group.missingLocales.joinToString(", "),
                            width = MissingLocalesColumnWidth,
                        )
                        TableCellText(text = "${group.proposedCount}/${group.rows.size}", width = ProgressColumnWidth)
                        TableCellText(text = group.moduleName ?: "-", width = ModuleColumnWidth)
                        TableCellText(text = group.resourceRootPath, width = PathColumnWidth)
                        TableCellText(text = "Select", width = SelectTextColumnWidth)
                    }
                }
            }

            if (showTableVerticalScrollbar) {
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(tableVerticalScroll),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight(),
                )
            }
        }
    }

    HorizontalScrollbar(
        adapter = rememberScrollbarAdapter(listHorizontalScroll),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TableCellText(
    text: String,
    width: Int,
) {
    Text(
        text,
        modifier = Modifier.width(width.dp),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

private const val TableMaxHeight = 260
private const val DefaultRowHeight = 44
private const val TableRowSpacing = 2

private const val SelectGlyphColumnWidth = 16
private const val StatusColumnWidth = 96
private const val KeyColumnWidth = 160
private const val BaseTextColumnWidth = 140
private const val MissingLocalesColumnWidth = 110
private const val ProgressColumnWidth = 70
private const val ModuleColumnWidth = 90
private const val PathColumnWidth = 180
private const val SelectTextColumnWidth = 52

private val SelectedRowBackground = Color(0x1A4C8BF5)
