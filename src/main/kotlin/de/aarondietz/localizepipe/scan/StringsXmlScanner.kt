package de.aarondietz.localizepipe.scan

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import de.aarondietz.localizepipe.model.*

class StringsXmlScanner(private val project: Project) {
    fun scan(options: ScanOptions, shouldCancel: () -> Boolean = { false }): ScanResult {
        return ReadAction.compute<ScanResult, RuntimeException> {
            checkCanceled(shouldCancel)
            val files = FilenameIndex.getVirtualFilesByName(
                "strings.xml",
                GlobalSearchScope.projectScope(project),
            )

            val localizedFiles = files.mapNotNull { file -> classify(file) }
                .filter { includeByResourceKind(it.kind, options) }
                .filter { includeByScope(it.moduleName, options) }

            val grouped = localizedFiles.groupBy { GroupKey(it.resourceRootPath, it.kind, it.moduleName) }

            val detectedLocales = linkedSetOf<String>()
            val rows = mutableListOf<StringEntryRow>()

            for ((groupKey, groupFiles) in grouped) {
                checkCanceled(shouldCancel)
                val baseFile = groupFiles.firstOrNull { it.folderName == "values" } ?: continue
                val baseMap = readStringMap(baseFile.file)
                if (baseMap.isEmpty()) {
                    continue
                }

                val localeFiles = groupFiles.filter { it.normalizedLocaleTag != null }
                localeFiles.mapNotNullTo(detectedLocales) { it.normalizedLocaleTag }

                val effectiveTargets = localeFiles.mapNotNull { it.normalizedLocaleTag }.toSet()
                val localeLookup = localeFiles.associateBy { it.normalizedLocaleTag ?: "" }

                for (targetLocale in effectiveTargets.sorted()) {
                    checkCanceled(shouldCancel)
                    val localeFile = localeLookup[targetLocale]
                    val localizedMap = localeFile?.let { readStringMap(it.file) } ?: emptyMap()
                    val qualifierRaw =
                        localeFile?.qualifierRaw ?: LocaleQualifierUtil.localeTagToQualifier(targetLocale)

                    for ((key, baseText) in baseMap.toSortedMap()) {
                        checkCanceled(shouldCancel)
                        val localizedText = localizedMap[key]
                        val status = when {
                            localizedText == null -> RowStatus.MISSING
                            localizedText == baseText -> RowStatus.IDENTICAL
                            else -> RowStatus.UP_TO_DATE
                        }

                        if (status == RowStatus.UP_TO_DATE) {
                            continue
                        }
                        if (status == RowStatus.IDENTICAL && !options.includeIdenticalToBase) {
                            continue
                        }

                        rows += StringEntryRow(
                            id = "${groupKey.resourceRootPath}|$targetLocale|$key",
                            key = key,
                            baseText = baseText,
                            localizedText = localizedText,
                            proposedText = null,
                            localeTag = targetLocale,
                            localeQualifierRaw = qualifierRaw,
                            localeFilePath = localeFile?.file?.path,
                            resourceRootPath = groupKey.resourceRootPath,
                            moduleName = groupKey.moduleName,
                            originKind = groupKey.kind,
                            status = status,
                        )
                    }
                }
            }

            ScanResult(
                rows = rows.sortedWith(compareBy<StringEntryRow> { it.localeTag }.thenBy { it.key }),
                detectedLocales = detectedLocales,
            )
        }
    }

    fun scanDeletionTargets(
        options: ScanOptions,
        shouldCancel: () -> Boolean = { false },
    ): List<TranslationDeleteTarget> {
        return ReadAction.compute<List<TranslationDeleteTarget>, RuntimeException> {
            checkCanceled(shouldCancel)
            val files = FilenameIndex.getVirtualFilesByName(
                "strings.xml",
                GlobalSearchScope.projectScope(project),
            )

            val localizedFiles = files.mapNotNull { file -> classify(file) }
                .filter { includeByResourceKind(it.kind, options) }
                .filter { includeByScope(it.moduleName, options) }

            val grouped = localizedFiles.groupBy { GroupKey(it.resourceRootPath, it.kind, it.moduleName) }
            val targets = mutableListOf<TranslationDeleteTarget>()

            for ((groupKey, groupFiles) in grouped) {
                checkCanceled(shouldCancel)
                val baseFile = groupFiles.firstOrNull { it.folderName == "values" } ?: continue
                val baseMap = readStringMap(baseFile.file)
                if (baseMap.isEmpty()) {
                    continue
                }

                val localeFiles = groupFiles.filter { it.normalizedLocaleTag != null }
                if (localeFiles.isEmpty()) {
                    continue
                }

                val localizedMaps = localeFiles.associateWith { localizedFile -> readStringMap(localizedFile.file) }
                for ((key, baseText) in baseMap.toSortedMap()) {
                    checkCanceled(shouldCancel)
                    val localeEntries = localeFiles.mapNotNull { localeFile ->
                        val hasTranslationForKey = localizedMaps[localeFile]?.containsKey(key) == true
                        if (!hasTranslationForKey) {
                            return@mapNotNull null
                        }
                        val localeTag = localeFile.normalizedLocaleTag ?: return@mapNotNull null
                        TranslationDeleteLocaleEntry(
                            localeTag = localeTag,
                            localeQualifierRaw = localeFile.qualifierRaw,
                            localeFilePath = localeFile.file.path,
                        )
                    }.sortedBy { it.localeTag }

                    if (localeEntries.isEmpty()) {
                        continue
                    }

                    targets += TranslationDeleteTarget(
                        id = "${groupKey.resourceRootPath}|${groupKey.moduleName.orEmpty()}|$key",
                        key = key,
                        baseText = baseText,
                        resourceRootPath = groupKey.resourceRootPath,
                        moduleName = groupKey.moduleName,
                        originKind = groupKey.kind,
                        localeEntries = localeEntries,
                    )
                }
            }

            targets.sortedWith(
                compareBy<TranslationDeleteTarget> { it.key }
                    .thenBy { it.moduleName ?: "" }
                    .thenBy { it.resourceRootPath },
            )
        }
    }

    private fun includeByResourceKind(kind: ResourceKind, options: ScanOptions): Boolean {
        return when (kind) {
            ResourceKind.ANDROID_RES -> options.includeAndroidResources
            ResourceKind.COMPOSE_RESOURCES -> options.includeComposeResources
        }
    }

    private fun includeByScope(moduleName: String?, options: ScanOptions): Boolean {
        if (options.scope == ScanScope.WHOLE_PROJECT) {
            return true
        }
        val currentModuleName = options.currentModuleName ?: return true
        return moduleName == currentModuleName
    }

    private fun classify(file: VirtualFile): LocalizedStringsFile? {
        val path = file.path
        val moduleName = ModuleUtilCore.findModuleForFile(file, project)?.name

        ResourcePathClassifier.classify(path)?.let { classified ->
            return LocalizedStringsFile(
                file = file,
                resourceRootPath = classified.resourceRootPath,
                kind = classified.kind,
                folderName = classified.folderName,
                qualifierRaw = classified.qualifierRaw,
                normalizedLocaleTag = classified.normalizedLocaleTag,
                moduleName = moduleName,
            )
        }

        return null
    }

    private fun readStringMap(file: VirtualFile): Map<String, String> {
        val xmlText = runCatching {
            file.inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")
        return StringsXmlValueExtractor.extract(xmlText)
    }

    private data class GroupKey(
        val resourceRootPath: String,
        val kind: ResourceKind,
        val moduleName: String?,
    )

    private data class LocalizedStringsFile(
        val file: VirtualFile,
        val resourceRootPath: String,
        val kind: ResourceKind,
        val folderName: String,
        val qualifierRaw: String,
        val normalizedLocaleTag: String?,
        val moduleName: String?,
    )

    private fun checkCanceled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) {
            throw ProcessCanceledException()
        }
    }
}
