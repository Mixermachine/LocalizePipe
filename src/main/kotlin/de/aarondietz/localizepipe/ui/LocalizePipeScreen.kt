package de.aarondietz.localizepipe.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import de.aarondietz.localizepipe.model.RowStatus
import de.aarondietz.localizepipe.settings.LocalizePipeSettingsConfigurable
import de.aarondietz.localizepipe.settings.ProjectScanSettingsService
import org.jetbrains.jewel.ui.component.Text
import java.util.Locale

/**
 * Main screen composable for the LocalizePipe tool window.
 * Owns screen-level UI state and delegates focused rendering to section composables.
 */
@Composable
internal fun LocalizePipeToolWindowContent(
    project: Project,
    controller: LocalizePipeToolWindowController,
    disposable: Disposable,
) {
    var state by remember { mutableStateOf(controller.snapshot()) }
    var selectedDeleteTargetId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(disposable) {
        val unsubscribe = controller.addStateListener { state = controller.snapshot() }
        onDispose { unsubscribe() }
    }

    LaunchedEffect(Unit) {
        controller.rescan()
    }

    val groupedRows = remember(state.rows) { GroupedStringRows.fromRows(state.rows) }
    val sourceLocaleTag = project.service<ProjectScanSettingsService>().sourceLocaleTag()
    val sourceLocaleName = remember(sourceLocaleTag) { sourceLocaleDisplayName(sourceLocaleTag) }
    val selectedGroup = groupedRows.firstOrNull { group -> group.rows.any { it.id == state.selectedRowId } }
    val canTranslate = state.rows.any { row ->
        row.status == RowStatus.MISSING ||
                row.status == RowStatus.IDENTICAL ||
                (!row.proposedText.isNullOrBlank() && row.status != RowStatus.ERROR)
    }
    val canDeleteTranslations = state.deleteTargets.isNotEmpty()
    val pageVerticalScroll = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(ScreenPadding)
                .fillMaxWidth()
                .verticalScroll(pageVerticalScroll)
                .padding(end = ScreenEndPadding),
            verticalArrangement = Arrangement.spacedBy(ScreenSpacing),
        ) {
            LocalizePipeTopBar(
                state = state,
                onToggleScope = controller::toggleScope,
                onRescan = controller::rescan,
                onTranslate = controller::translate,
                onDeleteTranslations = {
                    if (!canDeleteTranslations) {
                        return@LocalizePipeTopBar
                    }
                    val preselectedTargetId = selectedDeleteTargetId
                        ?.takeIf { selectedId -> state.deleteTargets.any { it.id == selectedId } }
                        ?: state.deleteTargets.firstOrNull()?.id
                    val selectedTarget = chooseDeleteTranslationTarget(
                        project = project,
                        targets = state.deleteTargets,
                        preselectedId = preselectedTargetId,
                    ) ?: return@LocalizePipeTopBar
                    selectedDeleteTargetId = selectedTarget.id
                    controller.deleteTranslationsForTarget(selectedTarget)
                },
                onCancel = controller::cancelCurrentOperation,
                onOpenSettings = {
                    ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, LocalizePipeSettingsConfigurable::class.java)
                    controller.scheduleRescan(100)
                },
                canTranslate = canTranslate,
                canDeleteTranslations = canDeleteTranslations,
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

                GroupedRowsTable(
                    groupedRows = groupedRows,
                    selectedRowId = state.selectedRowId,
                    onSelectRow = controller::selectRow,
                )

                if (selectedGroup != null) {
                    SelectedGroupDetailsCard(
                        selectedGroup = selectedGroup,
                        sourceLocaleName = sourceLocaleName,
                    )
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

private val ScreenPadding = 12.dp
private val ScreenEndPadding = 8.dp
private val ScreenSpacing = 8.dp

private fun sourceLocaleDisplayName(sourceLocaleTag: String): String {
    val locale = Locale.forLanguageTag(sourceLocaleTag)
    val displayName = locale.getDisplayName(Locale.ENGLISH)
        .replaceFirstChar { char ->
            if (char.isLowerCase()) {
                char.titlecase(Locale.ENGLISH)
            } else {
                char.toString()
            }
        }
    return displayName
        .takeIf { it.isNotBlank() && it != sourceLocaleTag }
        ?: sourceLocaleTag
}
