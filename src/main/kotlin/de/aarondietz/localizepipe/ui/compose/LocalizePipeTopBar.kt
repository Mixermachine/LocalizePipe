package de.aarondietz.localizepipe.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.aarondietz.localizepipe.model.ScanScope
import de.aarondietz.localizepipe.ui.toolwindow.ToolWindowUiState
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * Top action row for scan/translate/delete/settings operations.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun LocalizePipeTopBar(
    state: ToolWindowUiState,
    onToggleScope: () -> Unit,
    onRescan: () -> Unit,
    onTranslate: () -> Unit,
    onDeleteTranslations: () -> Unit,
    onAddLanguage: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    canTranslate: Boolean,
    canDeleteTranslations: Boolean,
    canAddLanguage: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TopBarSpacing)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TopBarSpacing),
            verticalArrangement = Arrangement.spacedBy(TopBarSpacing),
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
                tooltip = "Delete translated entries for one selected key across all target locale files.",
                onClick = onDeleteTranslations,
                enabled = canDeleteTranslations && !state.isBusy,
            ) {
                Text("Delete Translation")
            }
            TooltipButton(
                tooltip = "Add a new target language and create locale files for selected resource roots.",
                onClick = onAddLanguage,
                enabled = canAddLanguage && !state.isBusy,
            ) {
                Text("Add Language")
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
                        color = TooltipBackgroundColor,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = TooltipBorderColor,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(tooltip, color = TooltipTextColor)
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

private val TopBarSpacing = 6.dp
private val TooltipBackgroundColor = Color(0xFF1F2329)
private val TooltipBorderColor = Color(0xFF4C566A)
private val TooltipTextColor = Color(0xFFF1F3F5)
