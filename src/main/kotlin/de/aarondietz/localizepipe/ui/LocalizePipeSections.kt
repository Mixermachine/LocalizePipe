package de.aarondietz.localizepipe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.aarondietz.localizepipe.model.RowStatus
import org.jetbrains.jewel.ui.component.Text
import java.util.Locale

/**
 * Horizontal divider used between major screen sections.
 */
@Composable
internal fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x55808080)),
    )
}

/**
 * Renders detected locale summary for currently scanned scope.
 */
@Composable
internal fun DetectedLocalesSummary(state: ToolWindowUiState) {
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

/**
 * Compact status line showing operation state and aggregate counts.
 */
@Composable
internal fun StatusBar(state: ToolWindowUiState) {
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

/**
 * Details panel for the currently selected grouped key.
 */
@Composable
internal fun SelectedGroupDetailsCard(
    selectedGroup: GroupedStringRow,
    sourceLocaleName: String,
) {
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

internal fun localeDisplayLabel(localeTag: String): String {
    val locale = Locale.forLanguageTag(localeTag)
    val displayName = locale.getDisplayName(Locale.ENGLISH)
        .replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.ENGLISH) else char.toString() }
        .takeIf { it.isNotBlank() && it != localeTag }
        ?: localeTag
    return "$displayName ($localeTag)"
}
