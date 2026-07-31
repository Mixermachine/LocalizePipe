package de.aarondietz.localizepipe.ui.dialog

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import de.aarondietz.localizepipe.model.GroupedStringRow
import de.aarondietz.localizepipe.model.RowStatus
import de.aarondietz.localizepipe.model.StringEntryRow
import de.aarondietz.localizepipe.scan.ResourcePathClassifier
import de.aarondietz.localizepipe.scan.StringsXmlValueExtractor
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

class KeyTranslationsDialog(
    private val project: Project,
    private val group: GroupedStringRow,
) : DialogWrapper(project, true) {

    private val allVariantRows: List<StringEntryRow> = resolveAllVariantsForKey(group)
    private val tableModel = TranslationsTableModel(allVariantRows)
    private val table = JBTable(tableModel)

    init {
        title = "Translations for '${group.key}'"
        setOKButtonText("Navigate to File")
        setCancelButtonText("Close")
        isResizable = true
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.preferredSize = Dimension(800, 400)

        val headerPanel = JPanel(BorderLayout(0, 4))
        headerPanel.add(JBLabel("Key: ${group.key}"), BorderLayout.NORTH)
        val sourceLocaleLabel = JBLabel("Base text: ${escapeForDisplay(group.baseText)}")
        headerPanel.add(sourceLocaleLabel, BorderLayout.SOUTH)
        panel.add(headerPanel, BorderLayout.NORTH)

        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        configureColumnWidths()

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    val row = table.rowAtPoint(e.point)
                    if (row in 0 until allVariantRows.size) {
                        navigateToRow(allVariantRows[row])
                    }
                }
            }
        })

        if (allVariantRows.isNotEmpty()) {
            table.setRowSelectionInterval(0, 0)
        }

        val scrollPane = JBScrollPane(table)
        panel.add(scrollPane, BorderLayout.CENTER)

        val helpLabel = JBLabel("Double-click an entry or select and click 'Navigate to File' to open XML in editor.")
        panel.add(helpLabel, BorderLayout.SOUTH)

        return panel
    }

    override fun doOKAction() {
        val selectedRowIndex = table.selectedRow
        if (selectedRowIndex in 0 until allVariantRows.size) {
            navigateToRow(allVariantRows[selectedRowIndex])
        }
        super.doOKAction()
    }

    private fun navigateToRow(row: StringEntryRow) {
        val filePath = row.localeFilePath ?: return
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return

        val psiManager = PsiManager.getInstance(project)
        val psiFile = psiManager.findFile(virtualFile)
        var navigated = false

        if (psiFile is XmlFile) {
            val rootTag = psiFile.rootTag
            val targetTag = rootTag?.subTags?.find { tag ->
                tag.name == "string" && tag.getAttributeValue("name") == row.key
            }
            if (targetTag != null) {
                OpenFileDescriptor(project, virtualFile, targetTag.textOffset).navigate(true)
                navigated = true
            }
        }

        if (!navigated) {
            OpenFileDescriptor(project, virtualFile).navigate(true)
        }
        close(OK_EXIT_CODE)
    }

    private fun configureColumnWidths() {
        val columnWidths = intArrayOf(120, 100, 300, 250)
        for (i in columnWidths.indices) {
            if (i < table.columnModel.columnCount) {
                table.columnModel.getColumn(i).preferredWidth = columnWidths[i]
            }
        }
    }

    private fun escapeForDisplay(text: String): String {
        return text
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private class TranslationsTableModel(
        private val rows: List<StringEntryRow>,
    ) : AbstractTableModel() {
        private val columns = arrayOf("Locale", "Status", "Text", "File Path")

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> localeDisplayLabel(row.localeTag)
                1 -> row.status.name
                2 -> row.localizedText ?: row.proposedText ?: "(missing)"
                3 -> row.localeFilePath ?: "(file missing)"
                else -> ""
            }
        }
    }

    private companion object {
        private fun resolveAllVariantsForKey(group: GroupedStringRow): List<StringEntryRow> {
            val existingByLocale = group.rows.associateBy { it.localeTag }.toMutableMap()
            val resourceRootDir = LocalFileSystem.getInstance().findFileByPath(group.resourceRootPath)
                ?: return group.rows

            val children = resourceRootDir.children ?: emptyArray()
            for (child in children) {
                if (!child.isDirectory) continue
                val stringsFile = child.findChild("strings.xml") ?: continue
                val classified = ResourcePathClassifier.classify(stringsFile.path) ?: continue
                val localeTag = classified.normalizedLocaleTag ?: continue
                if (existingByLocale.containsKey(localeTag)) continue

                val xmlText = runCatching {
                    stringsFile.inputStream.bufferedReader().use { it.readText() }
                }.getOrDefault("")
                val localizedText = StringsXmlValueExtractor.extract(xmlText)[group.key]
                val status = if (localizedText == null) RowStatus.MISSING else RowStatus.UP_TO_DATE

                val filledRow = StringEntryRow(
                    id = "${group.resourceRootPath}|$localeTag|${group.key}",
                    key = group.key,
                    baseText = group.baseText,
                    localizedText = localizedText,
                    proposedText = null,
                    localeTag = localeTag,
                    localeQualifierRaw = classified.qualifierRaw,
                    localeFilePath = stringsFile.path,
                    resourceRootPath = group.resourceRootPath,
                    moduleName = group.moduleName,
                    originKind = classified.kind,
                    status = status,
                )
                existingByLocale[localeTag] = filledRow
            }

            return existingByLocale.values.sortedBy { it.localeTag }
        }

        private fun localeDisplayLabel(localeTag: String): String {
            val locale = Locale.forLanguageTag(localeTag)
            val displayName = locale.getDisplayName(Locale.ENGLISH)
                .replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.ENGLISH) else char.toString() }
                .takeIf { it.isNotBlank() && it != localeTag }
                ?: localeTag
            return "$displayName ($localeTag)"
        }
    }
}
