package de.aarondietz.localizepipe.ui.swing

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import de.aarondietz.localizepipe.model.GroupedStringRow
import de.aarondietz.localizepipe.model.GroupedStringRows
import de.aarondietz.localizepipe.model.RowStatus
import de.aarondietz.localizepipe.model.ScanScope
import de.aarondietz.localizepipe.model.localeDisplayLabel
import de.aarondietz.localizepipe.model.preferredRow
import de.aarondietz.localizepipe.settings.LocalizePipeSettingsConfigurable
import de.aarondietz.localizepipe.settings.ProjectScanSettingsService
import de.aarondietz.localizepipe.settings.TranslationSettingsService
import de.aarondietz.localizepipe.ui.dialog.chooseAddLanguageRequest
import de.aarondietz.localizepipe.ui.dialog.chooseDeleteTranslationTarget
import de.aarondietz.localizepipe.ui.toolwindow.LocalizePipeToolWindowController
import de.aarondietz.localizepipe.ui.toolwindow.ToolWindowUiState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

class LocalizePipeSwingPanel(
    private val project: Project,
    private val controller: LocalizePipeToolWindowController,
    disposable: Disposable,
) : JBPanel<LocalizePipeSwingPanel>(BorderLayout()) {

    private var selectedDeleteTargetId: String? = null
    private var selectedLocaleTag: String? = null
    private var selectedLanguageTargetIds: Set<String> = emptySet()

    private var currentState: ToolWindowUiState = controller.snapshot()
    private var groupedRows: List<GroupedStringRow> = emptyList()

    private val tableModel = GroupedRowsTableModel()
    private val table = JBTable(tableModel)

    private val localesLabel = JBLabel()
    private val statusLabel = JBLabel()
    private val summaryLabel = JBLabel()
    private val progressBar = JProgressBar()

    // Details card components
    private val detailsCardPanel = JBPanel<JBPanel<*>>(GridBagLayout())
    private val detailKeyLabel = JBLabel()
    private val detailBaseTextArea = createMultiLineTextArea()
    private val detailTargetsLabel = JBLabel()
    private val detailProposedTextArea = createMultiLineTextArea()
    private val detailMessageArea = createMultiLineTextArea()

    init {
        setupUi()
        val removeListener = controller.addStateListener {
            SwingUtilities.invokeLater {
                updateState(controller.snapshot())
            }
        }
        Disposer.register(disposable, Disposable { removeListener() })

        // Initial scan trigger
        controller.rescan()
    }

    private fun setupUi() {
        // Toolbar
        val actionGroup = DefaultActionGroup().apply {
            add(ToggleScopeAction())
            add(RescanAction())
            add(TranslateOrCancelAction())
            add(DeleteTranslationAction())
            add(AddLanguageAction())
            add(SettingsAction())
        }
        val toolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.TOOLWINDOW_TITLE,
            actionGroup,
            true,
        ).apply {
            layoutStrategy = ToolbarLayoutStrategy.WRAP_STRATEGY
        }
        toolbar.targetComponent = this

        val topPanel = JPanel()
        topPanel.layout = BoxLayout(topPanel, BoxLayout.Y_AXIS)
        topPanel.add(toolbar.component)

        // Status & Locales info section
        val infoPanel = JPanel(GridBagLayout())
        infoPanel.border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            gridx = 0
            gridy = 0
            insets = Insets(2, 2, 2, 2)
        }

        localesLabel.font = localesLabel.font.deriveFont(Font.BOLD)
        infoPanel.add(localesLabel, gbc)

        gbc.gridy++
        infoPanel.add(statusLabel, gbc)

        gbc.gridy++
        progressBar.isVisible = false
        infoPanel.add(progressBar, gbc)

        gbc.gridy++
        summaryLabel.foreground = summaryLabel.foreground.darker()
        infoPanel.add(summaryLabel, gbc)

        topPanel.add(infoPanel)
        add(topPanel, BorderLayout.NORTH)

        // Table
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                val selectedRowIndex = table.selectedRow
                if (selectedRowIndex in 0 until groupedRows.size) {
                    val group = groupedRows[selectedRowIndex]
                    val preferred = group.preferredRow(currentState.selectedRowId)
                    if (preferred.id != currentState.selectedRowId) {
                        controller.selectRow(preferred.id)
                    }
                }
                updateDetailsPanel()
            }
        }
        table.rowHeight = 26
        table.autoResizeMode = JBTable.AUTO_RESIZE_OFF
        configureColumnWidths()

        val scrollPane = JBScrollPane(table)
        scrollPane.border = BorderFactory.createEmptyBorder()

        // Details Panel at bottom
        setupDetailsCard()

        val centerPanel = JPanel(BorderLayout())
        centerPanel.add(scrollPane, BorderLayout.CENTER)
        centerPanel.add(detailsCardPanel, BorderLayout.SOUTH)
        add(centerPanel, BorderLayout.CENTER)

        updateState(currentState)
    }

    private fun configureColumnWidths() {
        val columnWidths = intArrayOf(100, 160, 180, 140, 70, 100, 220)
        for (i in columnWidths.indices) {
            if (i < table.columnModel.columnCount) {
                table.columnModel.getColumn(i).preferredWidth = columnWidths[i]
            }
        }
    }

    private fun setupDetailsCard() {
        detailsCardPanel.border = IdeBorderFactory.createTitledBorder("Selected Key Details", false)
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            gridx = 0
            gridy = 0
            insets = Insets(2, 6, 2, 6)
        }

        detailsCardPanel.add(detailKeyLabel, gbc)
        gbc.gridy++
        detailsCardPanel.add(detailBaseTextArea, gbc)
        gbc.gridy++
        detailsCardPanel.add(detailTargetsLabel, gbc)
        gbc.gridy++
        detailsCardPanel.add(detailProposedTextArea, gbc)
        gbc.gridy++
        detailsCardPanel.add(detailMessageArea, gbc)

        detailsCardPanel.isVisible = false
    }

    private fun updateState(newState: ToolWindowUiState) {
        currentState = newState
        groupedRows = GroupedStringRows.fromRows(newState.rows)
        tableModel.setRows(groupedRows)

        // Sync table selection with state
        if (newState.selectedRowId != null) {
            val selectedIndex = groupedRows.indexOfFirst { group ->
                group.rows.any { it.id == newState.selectedRowId }
            }
            if (selectedIndex >= 0 && table.selectedRow != selectedIndex) {
                table.setRowSelectionInterval(selectedIndex, selectedIndex)
            }
        }

        // Update Locales Summary
        localesLabel.text = if (newState.detectedLocales.isEmpty()) {
            "Detected locales: None (scanning...)"
        } else {
            "Detected locales: " + newState.detectedLocales.sorted().joinToString(", ") { localeDisplayLabel(it) }
        }

        // Update Status & Progress Bar
        val errors = newState.rows.count { it.status == RowStatus.ERROR }
        val ready = newState.rows.count { it.status == RowStatus.READY }
        val progressText = if (newState.isBusy && newState.progressTotal > 0) {
            " ${newState.progressCurrent}/${newState.progressTotal}"
        } else ""
        val headline = if (newState.isBusy) newState.activeOperation.displayName else newState.statusText
        val msgSuffix = if (newState.lastMessage.isNullOrBlank()) "" else " | ${newState.lastMessage}"
        statusLabel.text = "Status: $headline$progressText | Ready: $ready | Errors: $errors$msgSuffix"

        if (newState.isBusy) {
            progressBar.isVisible = true
            if (newState.progressTotal > 0) {
                progressBar.isIndeterminate = false
                progressBar.minimum = 0
                progressBar.maximum = newState.progressTotal
                progressBar.value = newState.progressCurrent
            } else {
                progressBar.isIndeterminate = true
            }
        } else {
            progressBar.isVisible = false
        }

        // Update Summary Line
        summaryLabel.text = if (!newState.hasCompletedInitialScan) {
            "Initial scan in progress. No translation data is available yet."
        } else {
            "Strings: ${groupedRows.size} | Locale rows: ${newState.rows.size}"
        }

        updateDetailsPanel()
    }

    private fun updateDetailsPanel() {
        val selectedIndex = table.selectedRow
        val selectedGroup = if (selectedIndex in 0 until groupedRows.size) groupedRows[selectedIndex] else null

        if (selectedGroup != null) {
            val sourceLocaleTag = project.service<ProjectScanSettingsService>().sourceLocaleTag()
            val sourceLocaleName = sourceLocaleDisplayName(sourceLocaleTag)
            val targetLocalesText = selectedGroup.rows
                .map { row -> localeDisplayLabel(row.localeTag) }
                .distinct()
                .joinToString(", ")

            detailKeyLabel.text = "Key: ${selectedGroup.key}"
            detailBaseTextArea.text = "$sourceLocaleName text: ${escapeForDisplay(selectedGroup.baseText)}"
            detailBaseTextArea.caretPosition = 0
            detailTargetsLabel.text = "Target locales: $targetLocalesText"

            val preferredRow = selectedGroup.preferredRow(currentState.selectedRowId)
            val proposed = preferredRow.proposedText
            if (!proposed.isNullOrBlank()) {
                if (proposed.startsWith("Startup ") || proposed.startsWith("Processing ")) {
                    detailProposedTextArea.text = "Status: $proposed"
                } else {
                    detailProposedTextArea.text = tailForDisplay("Proposed text", proposed, maxChars = 50)
                }
                detailProposedTextArea.caretPosition = detailProposedTextArea.document.length
                detailProposedTextArea.isVisible = true
            } else {
                detailProposedTextArea.isVisible = false
            }

            val firstMessage = selectedGroup.rows.firstOrNull { !it.message.isNullOrBlank() }?.message
            if (!firstMessage.isNullOrBlank()) {
                detailMessageArea.text = "Message: ${escapeForDisplay(firstMessage)}"
                detailMessageArea.caretPosition = 0
                detailMessageArea.isVisible = true
            } else {
                detailMessageArea.isVisible = false
            }
            detailsCardPanel.isVisible = true
        } else {
            detailsCardPanel.isVisible = false
        }
    }

    private fun escapeForDisplay(text: String): String {
        return text
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun tailForDisplay(prefix: String, text: String, maxChars: Int = 50): String {
        val escaped = escapeForDisplay(text)
        val combined = "$prefix: $escaped"
        return if (combined.length > maxChars) {
            "$prefix: …" + escaped.takeLast(maxChars)
        } else {
            combined
        }
    }

    private fun createMultiLineTextArea(): JBTextArea {
        return object : JBTextArea() {
            override fun getPreferredSize(): java.awt.Dimension {
                val pref = super.getPreferredSize()
                val fm = getFontMetrics(font)
                val maxH = fm.height * 3 + 4
                return java.awt.Dimension(pref.width, pref.height.coerceAtMost(maxH))
            }
        }.apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            rows = 1
            isOpaque = false
            border = BorderFactory.createEmptyBorder()
            background = null
        }
    }

    private fun sourceLocaleDisplayName(sourceLocaleTag: String): String {
        val locale = Locale.forLanguageTag(sourceLocaleTag)
        val displayName = locale.getDisplayName(Locale.ENGLISH)
            .replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.ENGLISH) else char.toString() }
            .takeIf { it.isNotBlank() && it != sourceLocaleTag }
            ?: sourceLocaleTag
        return displayName
    }

    // --- Actions ---

    private inner class ToggleScopeAction : AnAction(), CustomComponentAction {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun createCustomComponent(presentation: Presentation, place: String): javax.swing.JComponent {
            return ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
        }

        override fun update(e: AnActionEvent) {
            val isProject = currentState.scanScope == ScanScope.WHOLE_PROJECT
            e.presentation.text = if (isProject) "Project" else "Module"
            e.presentation.icon = if (isProject) AllIcons.Nodes.Project else AllIcons.Nodes.Module
            e.presentation.description = "Switch scan scope between Project and Module"
            e.presentation.isEnabled = !currentState.isBusy
        }

        override fun actionPerformed(e: AnActionEvent) {
            controller.toggleScope()
        }
    }

    private inner class RescanAction : AnAction("Rescan", "Scan resource files for missing translations", AllIcons.Actions.Refresh), CustomComponentAction {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun createCustomComponent(presentation: Presentation, place: String): javax.swing.JComponent {
            return ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !currentState.isBusy
        }

        override fun actionPerformed(e: AnActionEvent) {
            controller.rescan()
        }
    }

    private inner class TranslateOrCancelAction : AnAction(), CustomComponentAction {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun createCustomComponent(presentation: Presentation, place: String): javax.swing.JComponent {
            return ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
        }

        override fun update(e: AnActionEvent) {
            if (currentState.isBusy) {
                e.presentation.text = "Cancel"
                e.presentation.icon = AllIcons.Actions.Suspend
                e.presentation.description = "Request cancellation of active operation"
                e.presentation.isEnabled = true
            } else {
                val canTranslate = currentState.rows.any { row ->
                    row.status == RowStatus.MISSING ||
                            row.status == RowStatus.SOURCE_CHANGED ||
                            row.status == RowStatus.IDENTICAL ||
                            (!row.proposedText.isNullOrBlank() && row.status != RowStatus.ERROR)
                }
                e.presentation.text = "Translate"
                e.presentation.icon = AllIcons.Actions.Execute
                e.presentation.description = "Generate translations and write to resource files"
                e.presentation.isEnabled = canTranslate
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (currentState.isBusy) {
                controller.cancelCurrentOperation()
            } else {
                controller.translate()
            }
        }
    }

    private inner class DeleteTranslationAction : AnAction("Translation", "Delete translated entries for a key", AllIcons.Actions.GC), CustomComponentAction {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun createCustomComponent(presentation: Presentation, place: String): javax.swing.JComponent {
            return ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.text = "Translation"
            e.presentation.isEnabled = currentState.deleteTargets.isNotEmpty() && !currentState.isBusy
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (currentState.deleteTargets.isEmpty()) return
            val preselectedTargetId = selectedDeleteTargetId
                ?.takeIf { selectedId -> currentState.deleteTargets.any { it.id == selectedId } }
                ?: currentState.deleteTargets.firstOrNull()?.id
            val selectedTarget = chooseDeleteTranslationTarget(
                project = project,
                targets = currentState.deleteTargets,
                preselectedId = preselectedTargetId,
            ) ?: return
            selectedDeleteTargetId = selectedTarget.id
            controller.deleteTranslationsForTarget(selectedTarget)
        }
    }

    private inner class AddLanguageAction : AnAction("Language", "Add a new target language resource file", AllIcons.General.Add), CustomComponentAction {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun createCustomComponent(presentation: Presentation, place: String): javax.swing.JComponent {
            return ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.text = "Language"
            e.presentation.isEnabled = currentState.languageTargets.isNotEmpty() && !currentState.isBusy
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (currentState.languageTargets.isEmpty()) return
            val request = chooseAddLanguageRequest(
                project = project,
                targets = currentState.languageTargets,
                preselectedLocaleTag = selectedLocaleTag,
                preselectedTargetIds = selectedLanguageTargetIds,
            ) ?: return
            selectedLocaleTag = request.localeTag
            selectedLanguageTargetIds = request.targetIds
            controller.addLanguage(
                localeTag = request.localeTag,
                targetIds = request.targetIds,
            )
        }
    }

    private inner class SettingsAction : AnAction("Settings", "Open plugin settings", AllIcons.General.Settings), CustomComponentAction {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun createCustomComponent(presentation: Presentation, place: String): javax.swing.JComponent {
            return ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
        }
        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, LocalizePipeSettingsConfigurable::class.java)
            controller.scheduleRescan(100)
        }
    }

    // --- Table Model ---

    private class GroupedRowsTableModel : AbstractTableModel() {
        private val columns = arrayOf("Status", "Key", "Base Text", "Missing Locales", "Progress", "Module", "Path")
        private var rows: List<GroupedStringRow> = emptyList()

        fun setRows(newRows: List<GroupedStringRow>) {
            this.rows = newRows
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(col: Int): String = columns[col]

        override fun getValueAt(row: Int, col: Int): Any {
            val group = rows[row]
            return when (col) {
                0 -> group.aggregateStatus.name
                1 -> group.key
                2 -> group.baseText
                3 -> if (group.missingLocales.isEmpty()) "<none>" else group.missingLocales.joinToString(", ")
                4 -> "${group.proposedCount}/${group.rows.size}"
                5 -> group.moduleName ?: "-"
                6 -> group.resourceRootPath
                else -> ""
            }
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
    }
}
