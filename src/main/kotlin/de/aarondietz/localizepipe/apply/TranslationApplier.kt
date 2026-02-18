package de.aarondietz.localizepipe.apply

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import de.aarondietz.localizepipe.model.ResourceKind
import de.aarondietz.localizepipe.model.StringEntryRow

class TranslationApplier(private val project: Project) {
    fun apply(
        rows: List<StringEntryRow>,
        onProgress: (processedCount: Int, appliedCount: Int) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false },
    ): ApplyResult {
        val errors = mutableListOf<String>()
        var appliedCount = 0
        var processedCount = 0
        LOG.info("Apply operation started (rows=${rows.size})")

        WriteCommandAction.writeCommandAction(project)
            .withName("Apply AI XML localisations")
            .run<Throwable> {
                for (row in rows) {
                    if (shouldCancel()) {
                        throw ProcessCanceledException()
                    }
                    val proposed = row.proposedText
                    if (proposed.isNullOrBlank()) {
                        processedCount++
                        onProgress(processedCount, appliedCount)
                        continue
                    }

                    try {
                        val localeFile = ensureLocaleFile(row)
                        upsertString(localeFile, row.key, proposed, row.originKind)
                        appliedCount++
                    } catch (error: Throwable) {
                        LOG.warn("Failed to apply translation for key='${row.key}' locale='${row.localeTag}'", error)
                        errors += "${row.key} (${row.localeTag}): ${error.message}"
                    }
                    processedCount++
                    onProgress(processedCount, appliedCount)
                }
            }

        LOG.info("Apply operation completed (processed=$processedCount, applied=$appliedCount, errors=${errors.size})")
        return ApplyResult(appliedCount = appliedCount, errors = errors)
    }

    private fun ensureLocaleFile(row: StringEntryRow) =
        if (row.localeFilePath != null) {
            LocalFileSystem.getInstance().findFileByPath(row.localeFilePath)
                ?: error("Locale file not found: ${row.localeFilePath}")
        } else {
            val root = LocalFileSystem.getInstance().findFileByPath(row.resourceRootPath)
                ?: error("Resource root not found: ${row.resourceRootPath}")
            val folderName = "values-${row.localeQualifierRaw}".removeSuffix("-")
            val localeFolder = root.findChild(folderName) ?: root.createChildDirectory(this, folderName)
            val localeFile = localeFolder.findChild("strings.xml") ?: localeFolder.createChildData(this, "strings.xml")
            if (localeFile.length == 0L) {
                VfsUtil.saveText(localeFile, "<resources>\n</resources>\n")
            }
            localeFile
        }

    private fun upsertString(
        localeFile: com.intellij.openapi.vfs.VirtualFile,
        key: String,
        translatedText: String,
        kind: ResourceKind,
    ) {
        val normalizedText = normalizeForWrite(translatedText, kind)
        val psiManager = PsiManager.getInstance(project)
        var xmlFile = psiManager.findFile(localeFile) as? XmlFile
        if (xmlFile?.rootTag == null) {
            VfsUtil.saveText(localeFile, "<resources>\n</resources>\n")
            localeFile.refresh(false, false)
            xmlFile = psiManager.findFile(localeFile) as? XmlFile
        }

        if (xmlFile?.rootTag == null) {
            error("Invalid XML resources file: ${localeFile.path}")
        }

        val currentText = localeFile.inputStream.bufferedReader().use { it.readText() }
        val updatedText = upsertStringText(currentText, key, normalizedText)
        VfsUtil.saveText(localeFile, updatedText)
    }

    internal companion object {
        internal fun normalizeForWrite(translatedText: String, kind: ResourceKind): String {
            val xmlUnescaped = StringUtil.unescapeXmlEntities(translatedText).trimEnd('\r', '\n')
            return when (kind) {
                ResourceKind.ANDROID_RES -> normalizeAndroidString(xmlUnescaped)
                ResourceKind.COMPOSE_RESOURCES -> xmlUnescaped
            }
        }

        // Normalizes to safe Android resources string escapes so aapt2 parsing remains valid.
        private fun normalizeAndroidString(input: String): String {
            if (input.isEmpty()) {
                return input
            }

            val sanitizedEscapes = StringBuilder(input.length + 8)
            var index = 0
            while (index < input.length) {
                val ch = input[index]
                if (ch == '\\') {
                    if (index == input.lastIndex) {
                        sanitizedEscapes.append("\\\\")
                        index++
                        continue
                    }
                    val next = input[index + 1]
                    val keepAsEscape = when (next) {
                        'n', 't', 'r', '\'', '"', '@', '?', '\\' -> true
                        'u' -> index + 5 < input.length && input.substring(index + 2, index + 6).all { it.isHexDigit() }
                        else -> false
                    }
                    if (keepAsEscape) {
                        sanitizedEscapes.append('\\').append(next)
                        index += 2
                        continue
                    }
                    sanitizedEscapes.append("\\\\")
                    index++
                    continue
                }
                sanitizedEscapes.append(ch)
                index++
            }

            val escapedApostrophes = StringBuilder(sanitizedEscapes.length + 4)
            for (i in sanitizedEscapes.indices) {
                val current = sanitizedEscapes[i]
                if (current == '\'' && (i == 0 || sanitizedEscapes[i - 1] != '\\')) {
                    escapedApostrophes.append('\\')
                }
                escapedApostrophes.append(current)
            }

            return when {
                escapedApostrophes.isEmpty() -> ""
                escapedApostrophes[0] == '@' || escapedApostrophes[0] == '?' -> "\\$escapedApostrophes"
                else -> escapedApostrophes.toString()
            }
        }

        private fun Char.isHexDigit(): Boolean {
            return this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
        }

        internal fun upsertStringText(currentText: String, key: String, translatedText: String): String {
            val escapedKey = StringUtil.escapeXmlEntities(key)
            val escapedValue = StringUtil.escapeXmlEntities(translatedText)
            val existingTagPattern = Regex(
                pattern = """<string(\s+[^>]*?\bname\s*=\s*"${Regex.escape(escapedKey)}"[^>]*)>.*?</string>""",
                options = setOf(RegexOption.DOT_MATCHES_ALL),
            )

            val existingTagMatch = existingTagPattern.find(currentText)
            if (existingTagMatch != null) {
                val replacement = "<string${existingTagMatch.groupValues[1]}>$escapedValue</string>"
                return currentText.replaceRange(existingTagMatch.range, replacement)
            }

            val closingTagIndex = currentText.lastIndexOf("</resources>")
            val insert = "    <string name=\"$escapedKey\">$escapedValue</string>\n"

            return if (closingTagIndex >= 0) {
                val prefix = currentText.substring(0, closingTagIndex)
                buildString {
                    append(prefix)
                    if (!prefix.endsWith('\n')) {
                        append('\n')
                    }
                    append(insert)
                    append(currentText.substring(closingTagIndex))
                }
            } else {
                "<resources>\n$insert</resources>\n"
            }
        }
    }
}

data class ApplyResult(
    val appliedCount: Int,
    val errors: List<String>,
)

private val LOG = Logger.getInstance(TranslationApplier::class.java)
