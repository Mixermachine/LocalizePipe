package de.aarondietz.localizepipe.ui.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import de.aarondietz.localizepipe.model.LanguageAddTarget
import de.aarondietz.localizepipe.model.ResourceKind
import java.awt.BorderLayout
import java.awt.Dimension
import java.util.Locale
import javax.swing.ButtonGroup
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal data class AddLanguageRequest(
    val localeTag: String,
    val targetIds: Set<String>,
)

internal fun chooseAddLanguageRequest(
    project: Project,
    targets: List<LanguageAddTarget>,
    preselectedLocaleTag: String?,
    preselectedTargetIds: Set<String>,
): AddLanguageRequest? {
    if (targets.isEmpty()) {
        return null
    }
    val dialog = AddLanguageDialog(project, targets, preselectedLocaleTag, preselectedTargetIds)
    return if (dialog.showAndGet()) dialog.selectedRequest else null
}

private class AddLanguageDialog(
    project: Project,
    private val targets: List<LanguageAddTarget>,
    preselectedLocaleTag: String?,
    preselectedTargetIds: Set<String>,
) : DialogWrapper(project, true) {
    private val localeComboBox = ComboBox<LocaleOption>()
    private val localeFilterField = SearchTextField(false)
    private val targetRows = linkedMapOf<String, TargetRow>()
    private val detailsLabel = JBLabel()
    private val kindButtons = linkedMapOf<ResourceKind, JBRadioButton>()
    private val kindButtonGroup = ButtonGroup()
    private val targetsPanel = JPanel()
    private var allLocaleOptions: List<LocaleOption> = emptyList()
    private var pendingPreselectedLocaleTag: String? = normalizeLocaleTag(preselectedLocaleTag)
    private val allKinds = targets.map { it.originKind }.distinct()
    private val activeKindsByExistingLocales = targets
        .filter { it.existingLocaleTags.isNotEmpty() }
        .map { it.originKind }
        .distinct()
    private val autoDetectedKind = activeKindsByExistingLocales.singleOrNull()

    var selectedRequest: AddLanguageRequest? = null
        private set

    init {
        title = "Add Language"
        setOKButtonText("Add")
        isResizable = true
        init()

        localeComboBox.renderer = LocaleOptionRenderer()
        localeComboBox.isEditable = false
        localeComboBox.maximumRowCount = 14
        installLocaleFiltering()

        val targetIdsByKind = targets.associate { target -> target.id to target.originKind }
        val preselectedKind = preselectedTargetIds
            .asSequence()
            .mapNotNull { id -> targetIdsByKind[id] }
            .firstOrNull()
            ?: autoDetectedKind
            ?: allKinds.firstOrNull()

        if (preselectedKind != null) {
            kindButtons[preselectedKind]?.isSelected = true
        }

        for ((id, row) in targetRows) {
            if (preselectedTargetIds.isNotEmpty()) {
                row.checkbox.isSelected = id in preselectedTargetIds
            }
        }
        refreshVisibleTargets()
        refreshLocaleOptions()
        updateSelectionState()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 10))
        panel.preferredSize = Dimension(1000, 700)

        val topPanel = JPanel(BorderLayout(0, 6))
        topPanel.add(
            JBLabel("Select a new language and the resource roots where locale files should be created."),
            BorderLayout.NORTH,
        )
        val localePanel = JPanel(BorderLayout(0, 6))
        localePanel.add(languageTypePanel(), BorderLayout.NORTH)
        localeFilterField.textEditor.emptyText.text = "Filter languages by name or locale code"
        localePanel.add(localeFilterField, BorderLayout.CENTER)
        localePanel.add(localeComboBox, BorderLayout.SOUTH)
        topPanel.add(localePanel, BorderLayout.CENTER)
        panel.add(topPanel, BorderLayout.NORTH)

        targetsPanel.layout = BoxLayout(targetsPanel, BoxLayout.Y_AXIS)
        targetsPanel.border = JBUI.Borders.empty(4, 0)
        targets.forEach { target ->
            val checkbox = JBCheckBox(targetLabel(target), true)
            checkbox.toolTipText = target.resourceRootPath
            checkbox.addActionListener { updateSelectionState() }
            val details = JBLabel("    Existing locales: ${existingLocalesText(target)}").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            }
            val spacer = JPanel().apply { preferredSize = Dimension(0, 6) }
            targetRows[target.id] = TargetRow(
                target = target,
                checkbox = checkbox,
                detailsLabel = details,
                spacer = spacer,
            )
            targetsPanel.add(checkbox)
            targetsPanel.add(details)
            targetsPanel.add(spacer)
        }
        panel.add(JBScrollPane(targetsPanel), BorderLayout.CENTER)
        panel.add(detailsLabel, BorderLayout.SOUTH)

        return panel
    }

    override fun doOKAction() {
        val selectedLocale = localeComboBox.selectedItem as? LocaleOption
        if (selectedLocale == null) {
            setErrorText("Select a language from the supported language list.")
            return
        }
        val localeTag = selectedLocale.tag

        val selectedIds = visibleTargetRows()
            .asSequence()
            .filter { row -> row.checkbox.isSelected }
            .map { row -> row.target.id }
            .toSet()

        if (selectedIds.isEmpty()) {
            setErrorText("Select at least one resource root.")
            return
        }

        val selectedTargets = targets.filter { target -> target.id in selectedIds }
        val localeAlreadyExistsEverywhere = selectedTargets.all { target ->
            target.existingLocaleTags.any { existing -> existing.equals(localeTag, ignoreCase = true) }
        }
        if (localeAlreadyExistsEverywhere) {
            setErrorText("Locale '$localeTag' already exists in all selected resource roots.")
            return
        }

        selectedRequest = AddLanguageRequest(
            localeTag = localeTag,
            targetIds = selectedIds,
        )
        super.doOKAction()
    }

    private fun updateSelectionState() {
        val visibleRows = visibleTargetRows()
        val selectedCount = visibleRows.count { row -> row.checkbox.isSelected }
        val kindLabel = when (selectedKind()) {
            ResourceKind.ANDROID_RES -> "Android"
            ResourceKind.COMPOSE_RESOURCES -> "Compose"
            null -> "-"
        }
        val selectedLocale = localeComboBox.selectedItem as? LocaleOption
        val localeTag = selectedLocale?.tag
        val localeAlreadyPresent = if (localeTag == null) {
            false
        } else {
            visibleRows.any { row -> row.target.existingLocaleTags.any { existing -> existing.equals(localeTag, ignoreCase = true) } }
        }
        val localeSuffix = when {
            selectedLocale == null -> " | Select a supported language"
            localeAlreadyPresent -> " | Selected locale already detected in this type"
            else -> ""
        }
        detailsLabel.text = "Type: $kindLabel | Selected resource roots: $selectedCount / ${visibleRows.size}$localeSuffix"
        isOKActionEnabled = selectedCount > 0 && selectedLocale != null
    }

    private fun languageTypePanel(): JPanel {
        val panel = JPanel(BorderLayout(8, 0))
        panel.add(JBLabel("Resource type (can be changed)"), BorderLayout.WEST)
        val options = JPanel()
        options.layout = BoxLayout(options, BoxLayout.X_AXIS)

        allKinds.forEachIndexed { index, kind ->
            val baseLabel = when (kind) {
                ResourceKind.ANDROID_RES -> "Android"
                ResourceKind.COMPOSE_RESOURCES -> "Compose"
            }
            val text = if (kind == autoDetectedKind) "$baseLabel (Auto detected)" else baseLabel
            val radio = JBRadioButton(
                text,
                index == 0,
            )
            radio.addActionListener {
                refreshVisibleTargets()
                refreshLocaleOptions()
                updateSelectionState()
            }
            kindButtons[kind] = radio
            kindButtonGroup.add(radio)
            options.add(radio)
        }
        panel.add(options, BorderLayout.CENTER)
        return panel
    }

    private fun refreshVisibleTargets() {
        val kind = selectedKind()
        targetRows.values.forEach { row ->
            val visible = kind == null || row.target.originKind == kind
            row.checkbox.isVisible = visible
            row.detailsLabel.isVisible = visible
            row.spacer.isVisible = visible
            if (!visible) {
                row.checkbox.isSelected = false
            }
        }

        val visibleRows = visibleTargetRows()
        if (visibleRows.isNotEmpty() && visibleRows.none { row -> row.checkbox.isSelected }) {
            visibleRows.forEach { row -> row.checkbox.isSelected = true }
        }
        targetsPanel.revalidate()
        targetsPanel.repaint()
    }

    private fun refreshLocaleOptions() {
        val existingForKind = visibleTargetRows()
            .flatMap { row -> row.target.existingLocaleTags }
            .map { normalizeLocaleTag(it) ?: it.replace('_', '-') }
            .toSet()

        val previousSelectedTag = (localeComboBox.selectedItem as? LocaleOption)?.tag
        allLocaleOptions = localeSuggestions(targets).map { tag ->
            val normalizedTag = normalizeLocaleTag(tag) ?: tag
            LocaleOption(
                tag = normalizedTag,
                displayLabel = "${localeDisplayName(normalizedTag)} ($normalizedTag)",
                alreadyDetected = normalizedTag in existingForKind,
            )
        }
        val preferredTag = previousSelectedTag ?: pendingPreselectedLocaleTag
        applyLocaleFilter(localeFilterField.text, preferredTag)
        if (preferredTag != null) {
            pendingPreselectedLocaleTag = null
        }
    }

    private fun applyLocaleFilter(queryInput: String?, preferredTag: String? = null) {
        val query = queryInput?.trim().orEmpty()
        val filteredOptions = if (query.isBlank()) {
            allLocaleOptions
        } else {
            allLocaleOptions.filter { option ->
                option.displayLabel.contains(query, ignoreCase = true) ||
                        option.tag.contains(query, ignoreCase = true)
            }
        }

        localeComboBox.removeAllItems()
        filteredOptions.forEach { option -> localeComboBox.addItem(option) }

        val preferredOption = filteredOptions.firstOrNull { option -> option.tag.equals(preferredTag, ignoreCase = true) }
            ?: if (query.isBlank()) {
                filteredOptions.firstOrNull { option -> !option.alreadyDetected } ?: filteredOptions.firstOrNull()
            } else {
                filteredOptions.firstOrNull()
            }
        if (preferredOption != null) {
            localeComboBox.selectedItem = preferredOption
        }
    }

    private fun selectedKind(): ResourceKind? {
        val selectedFromButtons = kindButtons.entries.firstOrNull { (_, button) -> button.isSelected }?.key
        return selectedFromButtons ?: autoDetectedKind ?: allKinds.firstOrNull()
    }

    private fun visibleTargetRows(): List<TargetRow> {
        return targetRows.values.filter { row -> row.checkbox.isVisible }
    }

    private fun targetLabel(target: LanguageAddTarget): String {
        val module = target.moduleName ?: "-"
        return "module: $module | ${target.resourceRootPath}"
    }

    private fun normalizeLocaleTag(input: String?): String? {
        val raw = input?.trim()?.replace('_', '-') ?: return null
        if (raw.isBlank()) {
            return null
        }
        val normalized = Locale.forLanguageTag(raw).toLanguageTag()
        if (normalized.isBlank() || normalized == "und") {
            return null
        }
        return normalized
    }

    private fun installLocaleFiltering() {
        localeFilterField.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onLocaleEditorChanged()
            override fun removeUpdate(e: DocumentEvent?) = onLocaleEditorChanged()
            override fun changedUpdate(e: DocumentEvent?) = onLocaleEditorChanged()
        })
    }

    private fun onLocaleEditorChanged() {
        applyLocaleFilter(localeFilterField.text)
        updateSelectionState()
    }
}

private data class LocaleOption(
    val tag: String,
    val displayLabel: String,
    val alreadyDetected: Boolean,
)

private data class TargetRow(
    val target: LanguageAddTarget,
    val checkbox: JBCheckBox,
    val detailsLabel: JBLabel,
    val spacer: JPanel,
)

private class LocaleOptionRenderer : ColoredListCellRenderer<LocaleOption>() {
    override fun customizeCellRenderer(
        list: JList<out LocaleOption>,
        value: LocaleOption?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        if (value == null) {
            return
        }
        append(value.displayLabel)
        if (value.alreadyDetected) {
            append("  already detected", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}

private fun localeSuggestions(targets: List<LanguageAddTarget>): List<String> {
    val common = listOf(
        "de", "fr", "es", "it", "pt", "pt-BR", "nl", "pl", "tr",
        "ru", "ja", "ko", "zh-CN", "zh-TW", "ar",
    )
    val existing = targets.flatMap { it.existingLocaleTags }
    val isoLanguages = Locale.getISOLanguages().toList()

    return (common + existing + isoLanguages)
        .map { it.replace('_', '-') }
        .distinct()
        .sortedBy { localeDisplayName(it) }
}

private fun existingLocalesText(target: LanguageAddTarget): String {
    if (target.existingLocaleTags.isEmpty()) {
        return "none"
    }
    return target.existingLocaleTags
        .sortedBy { localeDisplayName(it) }
        .joinToString(", ") { localeTag -> "${localeDisplayName(localeTag)} ($localeTag)" }
}

private fun localeDisplayName(localeTag: String): String {
    val locale = Locale.forLanguageTag(localeTag)
    val displayName = locale.getDisplayName(Locale.ENGLISH)
    if (displayName.isBlank() || displayName == localeTag) {
        return localeTag
    }
    return displayName.replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase(Locale.ENGLISH) else char.toString()
    }
}
