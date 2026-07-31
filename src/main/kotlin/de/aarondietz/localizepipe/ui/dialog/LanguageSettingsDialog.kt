package de.aarondietz.localizepipe.ui.dialog

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import de.aarondietz.localizepipe.model.localeDisplayLabel
import de.aarondietz.localizepipe.scan.LanguageSettings
import de.aarondietz.localizepipe.scan.LocalizePipeSettings
import de.aarondietz.localizepipe.translation.TranslateGemmaLanguageMapper
import de.aarondietz.localizepipe.ui.toolwindow.LocalizePipeToolWindowController
import java.awt.*
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

internal fun openLanguageSettingsDialog(
    project: Project,
    controller: LocalizePipeToolWindowController,
) {
    val dialog = LanguageSettingsDialog(project, controller)
    dialog.show()
}

@JvmOverloads
internal fun computeLanguageSettingsRows(
    detectedLocales: Set<String>,
    languageTargets: List<de.aarondietz.localizepipe.model.LanguageAddTarget>,
    settingsByRoot: Map<String, LocalizePipeSettings>,
    extraLocaleTags: Set<String> = emptySet(),
): List<LanguageSettingsRow> {
    val allLocaleTags = mutableSetOf<String>()
    allLocaleTags.addAll(detectedLocales)
    allLocaleTags.addAll(extraLocaleTags)
    settingsByRoot.values.forEach { settings ->
        allLocaleTags.addAll(settings.languages.keys)
    }

    val newRows = mutableListOf<LanguageSettingsRow>()
    for (localeTag in allLocaleTags.sorted()) {
        val rootsForLocale = languageTargets
            .filter { target ->
                target.existingLocaleTags.contains(localeTag) ||
                    settingsByRoot[target.resourceRootPath]?.languages?.containsKey(localeTag) == true
            }
            .map { it.resourceRootPath }
            .toSet()

        val effectiveRoots = if (rootsForLocale.isEmpty()) {
            languageTargets.map { it.resourceRootPath }.toSet()
        } else {
            rootsForLocale
        }

        var mergedDisabled = false
        var mergedOverrideTag: String? = null
        var mergedInstructions: String? = null

        for (rootPath in effectiveRoots) {
            val langSettings = settingsByRoot[rootPath]?.languages?.get(localeTag) ?: continue
            if (langSettings.disabled) mergedDisabled = true
            if (langSettings.translationLocaleTag != null) mergedOverrideTag = langSettings.translationLocaleTag
            if (langSettings.instructions != null) mergedInstructions = langSettings.instructions
        }

        newRows.add(
            LanguageSettingsRow(
                localeTag = localeTag,
                displayName = localeDisplayLabel(localeTag),
                disabled = mergedDisabled,
                translationLocaleTag = mergedOverrideTag,
                instructions = mergedInstructions,
                presentInRoots = effectiveRoots,
            ),
        )
    }
    return newRows
}

internal fun buildSaveDataPerRoot(
    languageTargets: List<de.aarondietz.localizepipe.model.LanguageAddTarget>,
    rows: List<LanguageSettingsRow>,
): Map<String, LocalizePipeSettings> {
    val settingsByRoot = mutableMapOf<String, LocalizePipeSettings>()

    for (target in languageTargets) {
        val rootPath = target.resourceRootPath
        val rootLanguages = mutableMapOf<String, LanguageSettings>()

        for (row in rows) {
            if (row.presentInRoots.contains(rootPath)) {
                rootLanguages[row.localeTag] = LanguageSettings(
                    translationLocaleTag = row.translationLocaleTag,
                    disabled = row.disabled,
                    instructions = row.instructions,
                )
            }
        }

        settingsByRoot[rootPath] = LocalizePipeSettings(languages = rootLanguages)
    }
    return settingsByRoot
}

internal data class LanguageSettingsRow(
    val localeTag: String,
    val displayName: String,
    var disabled: Boolean = false,
    var translationLocaleTag: String? = null,
    var instructions: String? = null,
    val presentInRoots: Set<String> = emptySet(),
)

internal class LanguageSettingsDialog(
    private val project: Project,
    private val controller: LocalizePipeToolWindowController,
) : DialogWrapper(project, true) {

    private val rows = mutableListOf<LanguageSettingsRow>()
    private val tableModel = LanguageSettingsTableModel()
    private val table = JBTable(tableModel)

    private val selectedTitleLabel = JBLabel("Selected Language: None")
    private val disabledCheckBox = JBCheckBox("Disabled from translation")
    private val overrideTagField = JTextField()
    private val selectLanguageButton = JButton("Select Language", AllIcons.Actions.Find)
    private val clearOverrideButton = JButton("Clear", AllIcons.Actions.Cancel)
    private val instructionsArea = JBTextArea(3, 40)

    private var selectedIndex = -1
    private var isUpdatingUi = false
    private var pendingLocaleTagToSelect: String? = null

    init {
        title = "Language Settings"
        setOKButtonText("Apply")
        isResizable = true
        init()

        loadData()

        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                onSelectionChanged()
            }
        }

        installDetailListeners()

        val removeListener = controller.addStateListener {
            SwingUtilities.invokeLater {
                if (!isDisposed) {
                    val targetLocale = pendingLocaleTagToSelect
                        ?: if (selectedIndex in rows.indices) rows[selectedIndex].localeTag else null
                    loadData(preserveLocaleTag = targetLocale)
                    pendingLocaleTagToSelect = null
                }
            }
        }
        Disposer.register(disposable, Disposable { removeListener() })

        if (rows.isNotEmpty()) {
            table.setRowSelectionInterval(0, 0)
        } else {
            updateDetailPanelState(null)
        }
    }

    private fun loadData(preserveLocaleTag: String? = null) {
        val existingEditsByTag = rows.associate { row ->
            row.localeTag to Triple(row.disabled, row.translationLocaleTag, row.instructions)
        }

        val state = controller.snapshot()
        val settingsByRoot = controller.readAllLanguageSettings()

        val newRows = computeLanguageSettingsRows(
            detectedLocales = state.detectedLocales,
            languageTargets = state.languageTargets,
            settingsByRoot = settingsByRoot,
            extraLocaleTags = listOfNotNull(pendingLocaleTagToSelect).toSet(),
        )

        for (row in newRows) {
            val edits = existingEditsByTag[row.localeTag] ?: continue
            row.disabled = edits.first
            row.translationLocaleTag = edits.second
            row.instructions = edits.third
        }

        rows.clear()
        rows.addAll(newRows)
        tableModel.fireTableDataChanged()

        val targetIndex = if (preserveLocaleTag != null) {
            rows.indexOfFirst { it.localeTag == preserveLocaleTag }.takeIf { it >= 0 }
                ?: if (selectedIndex in rows.indices) selectedIndex else 0
        } else if (selectedIndex in rows.indices) {
            selectedIndex
        } else {
            0
        }

        if (rows.isNotEmpty()) {
            val safeIndex = targetIndex.coerceIn(0, rows.size - 1)
            table.setRowSelectionInterval(safeIndex, safeIndex)
            onSelectionChanged()
        } else {
            updateDetailPanelState(null)
        }
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(0, 10))
        mainPanel.preferredSize = Dimension(750, 550)

        // Top panel with Add Language button
        val topPanel = JPanel(BorderLayout(0, 6))
        val headerLabel = JBLabel("<html>Manage target languages, locale overrides, and translation instructions.</html>")
        topPanel.add(headerLabel, BorderLayout.WEST)

        val addLanguageBtn = JButton("Add Language", AllIcons.General.Add)
        addLanguageBtn.addActionListener {
            val state = controller.snapshot()
            if (state.languageTargets.isNotEmpty()) {
                val request = chooseAddLanguageRequest(
                    project = project,
                    targets = state.languageTargets,
                    preselectedLocaleTag = null,
                    preselectedTargetIds = emptySet(),
                )
                if (request != null) {
                    pendingLocaleTagToSelect = request.localeTag
                    controller.addLanguage(request.localeTag, request.targetIds)
                    loadData(preserveLocaleTag = request.localeTag)
                }
            }
        }
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        buttonPanel.add(addLanguageBtn)
        topPanel.add(buttonPanel, BorderLayout.EAST)

        mainPanel.add(topPanel, BorderLayout.NORTH)

        // Center split: Table on top, Details below
        val centerPanel = JPanel(BorderLayout(0, 10))

        val scrollPane = JBScrollPane(table)
        scrollPane.preferredSize = Dimension(730, 200)
        centerPanel.add(scrollPane, BorderLayout.CENTER)

        // Detail panel
        val detailPanel = JBPanel<JBPanel<*>>(GridBagLayout())
        detailPanel.border = IdeBorderFactory.createTitledBorder("Selected Language Details", false)

        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            gridx = 0
            gridy = 0
            insets = JBUI.insets(4, 8)
        }

        detailPanel.add(selectedTitleLabel, gbc)

        gbc.gridy++
        detailPanel.add(disabledCheckBox, gbc)

        gbc.gridy++
        val overrideLabel = JBLabel("Translation locale tag override:")
        detailPanel.add(overrideLabel, gbc)

        gbc.gridy++
        val overrideHelp = JBLabel("<html>(Overrides locale tag sent to translation model. Folder stays unchanged. Helpful for languages with multiple alphabets (e.g. Latin + Cyrillic or others))</html>").apply {
            font = font.deriveFont(font.size2D - 1f)
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }
        detailPanel.add(overrideHelp, gbc)

        gbc.gridy++
        val overrideContainer = JPanel(GridLayout(1, 2, 8, 0))
        overrideContainer.add(overrideTagField)

        val overrideActionsPanel = JPanel(GridBagLayout())
        selectLanguageButton.addActionListener {
            if (overrideTagField.isEnabled) {
                val preselected = overrideTagField.text.trim().ifEmpty { null }
                val selectionDialog = LanguageSelectionDialog(project, preselected)
                if (selectionDialog.showAndGet()) {
                    val selectedTag = selectionDialog.selectedLocaleTag
                    if (selectedTag != null) {
                        overrideTagField.text = selectedTag
                    }
                }
            }
        }

        clearOverrideButton.addActionListener {
            if (overrideTagField.isEnabled) {
                overrideTagField.text = ""
            }
        }

        val btnGbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            gridy = 0
        }

        btnGbc.gridx = 0
        btnGbc.weightx = 0.65
        btnGbc.insets = JBUI.insetsRight(4)
        overrideActionsPanel.add(selectLanguageButton, btnGbc)

        btnGbc.gridx = 1
        btnGbc.weightx = 0.35
        btnGbc.insets = JBUI.emptyInsets()
        overrideActionsPanel.add(clearOverrideButton, btnGbc)

        overrideContainer.add(overrideActionsPanel)

        detailPanel.add(overrideContainer, gbc)

        gbc.gridy++
        val instructionsLabel = JBLabel("Custom instructions:")
        detailPanel.add(instructionsLabel, gbc)

        gbc.gridy++
        val instructionsHelp = JBLabel("<html>(Appended to translation prompt for this language.)</html>").apply {
            font = font.deriveFont(font.size2D - 1f)
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }
        detailPanel.add(instructionsHelp, gbc)

        gbc.gridy++
        instructionsArea.lineWrap = true
        instructionsArea.wrapStyleWord = true
        val instructionsScroll = JBScrollPane(instructionsArea)
        instructionsScroll.preferredSize = Dimension(700, 70)
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        detailPanel.add(instructionsScroll, gbc)

        centerPanel.add(detailPanel, BorderLayout.SOUTH)
        mainPanel.add(centerPanel, BorderLayout.CENTER)

        return mainPanel
    }

    private fun installDetailListeners() {
        disabledCheckBox.addActionListener {
            if (!isUpdatingUi && selectedIndex in rows.indices) {
                rows[selectedIndex].disabled = disabledCheckBox.isSelected
                tableModel.fireTableCellUpdated(selectedIndex, 1)
            }
        }

        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateFromFields()
            override fun removeUpdate(e: DocumentEvent) = updateFromFields()
            override fun changedUpdate(e: DocumentEvent) = updateFromFields()
        }

        overrideTagField.document.addDocumentListener(docListener)
        instructionsArea.document.addDocumentListener(docListener)
    }

    private fun updateFromFields() {
        if (isUpdatingUi || selectedIndex !in rows.indices) return
        val row = rows[selectedIndex]
        row.translationLocaleTag = overrideTagField.text.trim().ifEmpty { null }
        row.instructions = instructionsArea.text.trim().ifEmpty { null }
        tableModel.fireTableCellUpdated(selectedIndex, 2)
        tableModel.fireTableCellUpdated(selectedIndex, 3)
    }

    private fun onSelectionChanged() {
        val idx = table.selectedRow
        if (idx == selectedIndex) return
        selectedIndex = idx
        val selectedRow = if (idx in rows.indices) rows[idx] else null
        updateDetailPanelState(selectedRow)
    }

    private fun updateDetailPanelState(row: LanguageSettingsRow?) {
        isUpdatingUi = true
        try {
            if (row == null) {
                selectedTitleLabel.text = "Selected Language: None"
                disabledCheckBox.isSelected = false
                disabledCheckBox.isEnabled = false
                overrideTagField.text = ""
                overrideTagField.isEnabled = false
                selectLanguageButton.isEnabled = false
                clearOverrideButton.isEnabled = false
                instructionsArea.text = ""
                instructionsArea.isEnabled = false
            } else {
                selectedTitleLabel.text = "Selected Language: ${row.displayName}"
                disabledCheckBox.isSelected = row.disabled
                disabledCheckBox.isEnabled = true
                overrideTagField.text = row.translationLocaleTag.orEmpty()
                overrideTagField.isEnabled = true
                selectLanguageButton.isEnabled = true
                clearOverrideButton.isEnabled = true
                instructionsArea.text = row.instructions.orEmpty()
                instructionsArea.isEnabled = true
            }
        } finally {
            isUpdatingUi = false
        }
    }

    override fun doOKAction() {
        if (selectedIndex in rows.indices) {
            val row = rows[selectedIndex]
            row.disabled = disabledCheckBox.isSelected
            row.translationLocaleTag = overrideTagField.text.trim().ifEmpty { null }
            row.instructions = instructionsArea.text.trim().ifEmpty { null }
        }

        val state = controller.snapshot()
        val settingsByRoot = buildSaveDataPerRoot(
            languageTargets = state.languageTargets,
            rows = rows,
        )

        controller.writeAllLanguageSettings(settingsByRoot)
        super.doOKAction()
    }

    private inner class LanguageSettingsTableModel : AbstractTableModel() {
        private val columns = arrayOf("Locale", "Disabled", "Override Tag", "Instructions")

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.displayName
                1 -> if (row.disabled) "Yes" else "No"
                2 -> row.translationLocaleTag.orEmpty()
                3 -> row.instructions?.replace('\n', ' ')?.take(30).orEmpty()
                else -> ""
            }
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
    }
}

private data class OverrideLocaleOption(
    val tag: String,
    val displayLabel: String,
)

internal class LanguageSelectionDialog(
    project: Project,
    private val preselectedTag: String?,
) : DialogWrapper(project, true) {

    private val searchField = SearchTextField(false)
    private val listModel = DefaultListModel<OverrideLocaleOption>()
    private val list = JBList(listModel)
    private val allOptions: List<OverrideLocaleOption>

    var selectedLocaleTag: String? = null
        private set

    init {
        title = "Select Translation Locale Override"
        setOKButtonText("Select")
        isResizable = true

        val common = listOf(
            "de", "fr", "es", "it", "pt", "pt-BR", "nl", "pl", "tr",
            "ru", "ja", "ko", "zh-CN", "zh-TW", "ar",
        )
        val isoLanguages = Locale.getISOLanguages().toList()
        val gemmaSupported = TranslateGemmaLanguageMapper.supportedLocaleTagsForUi()

        val allTags = (common + isoLanguages + gemmaSupported)
            .map { it.replace('_', '-') }
            .distinct()
            .sortedBy { localeDisplayLabel(it) }

        allOptions = allTags.map { tag ->
            OverrideLocaleOption(tag = tag, displayLabel = localeDisplayLabel(tag))
        }

        init()

        list.cellRenderer = object : ColoredListCellRenderer<OverrideLocaleOption>() {
            override fun customizeCellRenderer(
                list: JList<out OverrideLocaleOption>,
                value: OverrideLocaleOption?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value != null) {
                    append(value.displayLabel)
                }
            }
        }
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (list.selectedIndex >= 0) {
                    doOKAction()
                    return true
                }
                return false
            }
        }.installOn(list)

        searchField.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filterList()
            override fun removeUpdate(e: DocumentEvent) = filterList()
            override fun changedUpdate(e: DocumentEvent) = filterList()
        })

        filterList(preselectedTag)
    }

    private fun filterList(preferredTag: String? = null) {
        val query = searchField.text.trim()
        val filtered = if (query.isBlank()) {
            allOptions
        } else {
            allOptions.filter {
                it.displayLabel.contains(query, ignoreCase = true) ||
                    it.tag.contains(query, ignoreCase = true)
            }
        }

        listModel.clear()
        filtered.forEach { listModel.addElement(it) }

        val targetTag = preferredTag ?: searchField.text.trim()
        val matchIndex = if (targetTag.isNotBlank()) {
            filtered.indexOfFirst { it.tag.equals(targetTag, ignoreCase = true) }
        } else -1

        if (matchIndex >= 0) {
            list.selectedIndex = matchIndex
            list.ensureIndexIsVisible(matchIndex)
        } else if (filtered.isNotEmpty()) {
            list.selectedIndex = 0
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.preferredSize = Dimension(500, 400)

        searchField.textEditor.emptyText.text = "Filter languages by name or locale code"
        panel.add(searchField, BorderLayout.NORTH)

        val scrollPane = JBScrollPane(list)
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    override fun doOKAction() {
        val selected = list.selectedValue
        if (selected != null) {
            selectedLocaleTag = selected.tag
            super.doOKAction()
        }
    }
}
