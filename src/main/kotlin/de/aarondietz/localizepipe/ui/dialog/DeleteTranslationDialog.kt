package de.aarondietz.localizepipe.ui.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import de.aarondietz.localizepipe.model.TranslationDeleteTarget
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal fun chooseDeleteTranslationTarget(
    project: Project,
    targets: List<TranslationDeleteTarget>,
    preselectedId: String?,
): TranslationDeleteTarget? {
    if (targets.isEmpty()) {
        return null
    }
    val dialog = DeleteTranslationDialog(project, targets, preselectedId)
    return if (dialog.showAndGet()) dialog.selectedTarget else null
}

private class DeleteTranslationDialog(
    project: Project,
    private val targets: List<TranslationDeleteTarget>,
    preselectedId: String?,
) : DialogWrapper(project, true) {
    private val searchField = SearchTextField(false)
    private val listModel = DefaultListModel<TranslationDeleteTarget>()
    private val targetList = JBList(listModel)
    private val detailsLabel = JBLabel()

    var selectedTarget: TranslationDeleteTarget? = null
        private set

    init {
        title = "Delete Translation"
        setOKButtonText("Delete")
        isResizable = true
        init()

        val initialTarget = targets.firstOrNull { it.id == preselectedId }
        refreshList(initialTarget)
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.preferredSize = Dimension(980, 640)

        val topPanel = JPanel(BorderLayout(0, 6))
        topPanel.add(
            JBLabel("Select a key to delete from all translated locale files (source language stays unchanged)."),
            BorderLayout.NORTH,
        )
        searchField.textEditor.emptyText.text = "Filter by key, text, module, or path"
        topPanel.add(searchField, BorderLayout.CENTER)
        panel.add(topPanel, BorderLayout.NORTH)

        targetList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        targetList.cellRenderer = object : ColoredListCellRenderer<TranslationDeleteTarget>() {
            override fun customizeCellRenderer(
                list: JList<out TranslationDeleteTarget>,
                value: TranslationDeleteTarget?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) {
                    return
                }
                append(value.key)
                append("  (${value.localeEntries.size} locales)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                value.moduleName?.takeIf { it.isNotBlank() }?.let {
                    append("  module: $it", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
        targetList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateSelectionState()
            }
        }
        panel.add(JBScrollPane(targetList), BorderLayout.CENTER)
        panel.add(detailsLabel, BorderLayout.SOUTH)

        searchField.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = refreshList(targetList.selectedValue)
            override fun removeUpdate(e: DocumentEvent?) = refreshList(targetList.selectedValue)
            override fun changedUpdate(e: DocumentEvent?) = refreshList(targetList.selectedValue)
        })

        return panel
    }

    override fun doOKAction() {
        val selected = targetList.selectedValue ?: return
        selectedTarget = selected
        super.doOKAction()
    }

    private fun refreshList(preferredSelection: TranslationDeleteTarget?) {
        val query = searchField.text.trim()
        val filtered = if (query.isBlank()) {
            targets
        } else {
            targets.filter { target ->
                target.key.contains(query, ignoreCase = true) ||
                        target.baseText.contains(query, ignoreCase = true) ||
                        (target.moduleName ?: "").contains(query, ignoreCase = true) ||
                        target.resourceRootPath.contains(query, ignoreCase = true)
            }
        }

        listModel.removeAllElements()
        filtered.forEach { listModel.addElement(it) }

        val targetToSelect = preferredSelection?.takeIf { selected ->
            filtered.any { it.id == selected.id }
        } ?: filtered.firstOrNull()

        if (targetToSelect != null) {
            targetList.setSelectedValue(targetToSelect, true)
        } else {
            targetList.clearSelection()
        }
        updateSelectionState()
    }

    private fun updateSelectionState() {
        val selected = targetList.selectedValue
        selectedTarget = selected
        isOKActionEnabled = selected != null
        detailsLabel.text = if (selected == null) {
            "No translated keys found for the current filter."
        } else {
            "Path: ${selected.resourceRootPath} | Base: ${selected.baseText}"
        }
    }
}
