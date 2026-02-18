package de.aarondietz.localizepipe

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import de.aarondietz.localizepipe.model.RowStatus
import de.aarondietz.localizepipe.model.ScanScope
import de.aarondietz.localizepipe.settings.LocalizePipeSettingsConfigurable
import de.aarondietz.localizepipe.settings.ProjectScanSettingsService
import de.aarondietz.localizepipe.settings.TranslationSettingsService
import de.aarondietz.localizepipe.ui.GroupedStringRows
import de.aarondietz.localizepipe.ui.LocalizePipeToolWindowController
import de.aarondietz.localizepipe.ui.ToolWindowUiState
import de.aarondietz.localizepipe.ui.preferredRow
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.util.*
import kotlin.math.max

class MyToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val controllerDisposable = Disposer.newDisposable("LocalizePipeToolWindow")
        Disposer.register(toolWindow.contentManager, controllerDisposable)

        val settings = service<TranslationSettingsService>()
        val projectScanSettings = project.service<ProjectScanSettingsService>()
        val controller = LocalizePipeToolWindowController(
            project = project,
            settings = settings,
            projectScanSettings = projectScanSettings,
            parentDisposable = controllerDisposable,
        )

        toolWindow.addComposeTab("LocalizePipe", focusOnClickInside = true) {
            LaunchedEffect(Unit) {
                controller.rescan()
            }

            LocalizePipeToolWindowContent(
                project = project,
                controller = controller,
                disposable = controllerDisposable,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun LocalizePipeToolWindowContent(
    project: Project,
    controller: LocalizePipeToolWindowController,
    disposable: Disposable,
) {
    var state by remember { mutableStateOf(controller.snapshot()) }

    DisposableEffect(disposable) {
        val unsubscribe = controller.addStateListener { state = controller.snapshot() }
        onDispose { unsubscribe() }
    }

    val groupedRows = remember(state.rows) { GroupedStringRows.fromRows(state.rows) }
    val sourceLocaleTag = project.service<ProjectScanSettingsService>().sourceLocaleTag()
    val sourceLocaleName = remember(sourceLocaleTag) {
        val locale = Locale.forLanguageTag(sourceLocaleTag)
        locale.getDisplayName(Locale.ENGLISH)
            .replaceFirstChar { char ->
                if (char.isLowerCase()) {
                    char.titlecase(Locale.ENGLISH)
                } else {
                    char.toString()
                }
            }
            .takeIf { it.isNotBlank() && it != sourceLocaleTag }
            ?: sourceLocaleTag
    }
    val listHorizontalScroll = rememberScrollState()
    val tableVerticalScroll = rememberScrollState()
    val pageVerticalScroll = rememberScrollState()
    val density = LocalDensity.current
    var measuredRowHeightPx by remember { mutableStateOf(0) }
    val tableMaxHeightDp = 260
    val defaultRowHeightDp = 44
    val rowSpacingDp = 2
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
    val selectedGroup = groupedRows.firstOrNull { group -> group.rows.any { it.id == state.selectedRowId } }
    val canTranslate = state.rows.any { row ->
        row.status == RowStatus.MISSING ||
                row.status == RowStatus.IDENTICAL ||
                (!row.proposedText.isNullOrBlank() && row.status != RowStatus.ERROR)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
                .verticalScroll(pageVerticalScroll)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TopBar(
                state = state,
                onToggleScope = controller::toggleScope,
                onRescan = controller::rescan,
                onTranslate = controller::translate,
                onCancel = controller::cancelCurrentOperation,
                onOpenSettings = {
                    ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, LocalizePipeSettingsConfigurable::class.java)
                    controller.scheduleRescan(100)
                },
                canTranslate = canTranslate,
            )

            SectionDivider()
            DetectedLocalesSummary(state)

            SectionDivider()
            StatusBar(state)
            SectionDivider()

            if (!state.hasCompletedInitialScan) {
                Text("Initial scan in progress.")
                Text("No translation data is available yet. Results will appear after scanning completes.")
            } else {
                Text("Strings: ${groupedRows.size} | Locale rows: ${state.rows.size}")
                if (groupedRows.isEmpty()) {
                    SectionDivider()
                    Text("No missing translations found for the current scope and filters.")
                }
                Text("Tip: use the horizontal scrollbar below the table if columns are clipped.")
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
                                val preferred = group.preferredRow(state.selectedRowId)
                                val isSelected = preferred.id == state.selectedRowId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = defaultRowHeightDp.dp)
                                        .background(
                                            color = if (isSelected) Color(0x1A4C8BF5) else Color.Transparent,
                                            shape = RoundedCornerShape(4.dp),
                                        )
                                        .clickable { controller.selectRow(preferred.id) }
                                        .padding(horizontal = 6.dp, vertical = 5.dp)
                                        .onSizeChanged { size: IntSize ->
                                            if (size.height > measuredRowHeightPx) {
                                                measuredRowHeightPx = size.height
                                            }
                                        },
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TableCellText(
                                        text = if (isSelected) "▶" else "›",
                                        width = 16,
                                    )
                                    TableCellText(
                                        text = group.aggregateStatus.name,
                                        width = 96,
                                    )
                                    TableCellText(
                                        text = group.key,
                                        width = 160,
                                    )
                                    TableCellText(
                                        text = group.baseText,
                                        width = 140,
                                    )
                                    TableCellText(
                                        text = if (group.missingLocales.isEmpty()) "<none>" else group.missingLocales.joinToString(
                                            ", "
                                        ),
                                        width = 110,
                                    )
                                    TableCellText(
                                        text = "${group.proposedCount}/${group.rows.size}",
                                        width = 70,
                                    )
                                    TableCellText(
                                        text = group.moduleName ?: "-",
                                        width = 90,
                                    )
                                    TableCellText(
                                        text = group.resourceRootPath,
                                        width = 180,
                                    )
                                    TableCellText(
                                        text = "Select",
                                        width = 52,
                                    )
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

                if (selectedGroup != null) {
                    val targetLocalesText = selectedGroup.rows
                        .map { row -> localeDisplayLabel(row.localeTag) }
                        .distinct()
                        .joinToString(", ")
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x0A4C8BF5), RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0x334C8BF5), RoundedCornerShape(6.dp))
                            .padding(10.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Key: ${selectedGroup.key}")
                            Text("$sourceLocaleName text: ${selectedGroup.baseText}")

                            Text("Target locales: $targetLocalesText")
                            val firstMessage = selectedGroup.rows.firstOrNull { !it.message.isNullOrBlank() }?.message
                            if (!firstMessage.isNullOrBlank()) {
                                Text("Message: $firstMessage")
                            }
                        }
                    }
                }
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(pageVerticalScroll),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
        )
    }
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

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x55808080)),
    )
}

@Composable
private fun DetectedLocalesSummary(state: ToolWindowUiState) {
    Text("Detected locales:")
    if (state.detectedLocales.isEmpty()) {
        Text("No locale folders detected yet. Results will appear after scanning completes.")
        return
    }

    val localeSummary = state.detectedLocales
        .sorted()
        .joinToString(", ") { localeTag -> localeDisplayLabel(localeTag) }
    Text(localeSummary)
}

private fun localeDisplayLabel(localeTag: String): String {
    val locale = Locale.forLanguageTag(localeTag)
    val displayName = locale.getDisplayName(Locale.ENGLISH)
        .replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.ENGLISH) else char.toString() }
        .takeIf { it.isNotBlank() && it != localeTag }
        ?: localeTag
    return "$displayName ($localeTag)"
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TopBar(
    state: ToolWindowUiState,
    onToggleScope: () -> Unit,
    onRescan: () -> Unit,
    onTranslate: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    canTranslate: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TooltipButton(
                tooltip = "Switch between whole project and current module scan scope.",
                onClick = onToggleScope,
                enabled = !state.isBusy,
            ) {
                Text("Scope: ${if (state.scanScope == ScanScope.WHOLE_PROJECT) "Project" else "Module"}")
            }
            TooltipButton(
                tooltip = "Scan resource files again for missing or identical translations.",
                onClick = onRescan,
                enabled = !state.isBusy,
            ) {
                Text(if (state.isBusy) "Rescan..." else "Rescan")
            }
            TooltipButton(
                tooltip = "Generate translations and write all prepared translations directly into locale strings.xml files.",
                onClick = onTranslate,
                enabled = canTranslate && !state.isBusy,
            ) {
                Text(if (state.isBusy) "Translate + Write..." else "Translate + Write")
            }
            TooltipButton(
                tooltip = "Request cancellation of the currently running operation.",
                onClick = onCancel,
                enabled = state.isBusy,
            ) {
                Text("Cancel")
            }
            TooltipButton(
                tooltip = "Open plugin settings for provider and project configuration.",
                onClick = onOpenSettings,
                enabled = !state.isBusy,
            ) {
                Text("Settings")
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TooltipButton(
    tooltip: String,
    onClick: () -> Unit,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    TooltipArea(
        tooltip = {
            Box(
                modifier = Modifier
                    .background(
                        color = Color(0xFF1F2329),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = Color(0xFF4C566A),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(tooltip, color = Color(0xFFF1F3F5))
            }
        },
        delayMillis = 400,
    ) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
        ) {
            content()
        }
    }
}

@Composable
private fun StatusBar(state: ToolWindowUiState) {
    val errors = state.rows.count { it.status == RowStatus.ERROR }
    val ready = state.rows.count { it.status == RowStatus.READY }
    val progress = if (state.isBusy && state.progressTotal > 0) {
        " ${state.progressCurrent}/${state.progressTotal}"
    } else {
        ""
    }
    val headline = if (state.isBusy) state.activeOperation.displayName else state.statusText

    Text(
        "Status: $headline$progress | Ready: $ready | Errors: $errors" +
                (if (state.lastMessage.isNullOrBlank()) "" else " | ${state.lastMessage}"),
    )
}
