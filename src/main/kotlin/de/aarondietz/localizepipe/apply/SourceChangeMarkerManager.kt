package de.aarondietz.localizepipe.apply

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import de.aarondietz.localizepipe.scan.ResourcePathClassifier
import de.aarondietz.localizepipe.scan.SourceChangeMarkerSupport
import de.aarondietz.localizepipe.scan.SourceChangeMetadata
import de.aarondietz.localizepipe.scan.SourceChangeMetadataStore
import de.aarondietz.localizepipe.scan.StringResourceValue
import de.aarondietz.localizepipe.scan.StringsXmlValueExtractor

class SourceChangeMarkerManager(private val project: Project) {
    fun populateMarkers(
        onProgress: (processedCount: Int, totalCount: Int, changedEntries: Int) -> Unit = { _, _, _ -> },
        shouldCancel: () -> Boolean = { false },
    ): MarkerMaintenanceResult {
        val plan = buildPopulatePlan(onProgress, shouldCancel)
        return applyPlan("Populate LocalizePipe Source Change Hashes", plan, shouldCancel)
    }

    fun removeMarkers(
        onProgress: (processedCount: Int, totalCount: Int, changedEntries: Int) -> Unit = { _, _, _ -> },
        shouldCancel: () -> Boolean = { false },
    ): MarkerMaintenanceResult {
        val plan = buildRemovePlan(onProgress, shouldCancel)
        return applyPlan("Remove LocalizePipe Source Change Hashes", plan, shouldCancel)
    }

    fun updateHash(resourceRootPath: String, localeTag: String, key: String, baseText: String, localizePipeContext: String?) {
        val sourceHash = SourceChangeMarkerSupport.computeSourceHash(baseText, localizePipeContext)
        val file = getOrCreateMetadataFile(resourceRootPath)
        val currentMetadata = readMetadata(file)
        val updatedMetadata = SourceChangeMetadataStore.upsertHash(currentMetadata, localeTag, key, sourceHash)
        if (updatedMetadata != currentMetadata) {
            VfsUtil.saveText(file, SourceChangeMetadataStore.serialize(updatedMetadata))
        }
    }

    fun removeHash(resourceRootPath: String, localeTag: String, key: String) {
        val file = findMetadataFile(resourceRootPath) ?: return
        val currentMetadata = readMetadata(file)
        val updatedMetadata = SourceChangeMetadataStore.removeHash(currentMetadata, localeTag, key)
        if (updatedMetadata == currentMetadata) {
            return
        }
        if (updatedMetadata.isEmpty()) {
            file.delete(this)
        } else {
            VfsUtil.saveText(file, SourceChangeMetadataStore.serialize(updatedMetadata))
        }
    }

    private fun buildPopulatePlan(
        onProgress: (processedCount: Int, totalCount: Int, changedEntries: Int) -> Unit,
        shouldCancel: () -> Boolean,
    ): MarkerMaintenancePlan {
        return ApplicationManager.getApplication().runReadAction(Computable {
            val localizedFiles = findLocalizedFiles()
            val grouped = localizedFiles.groupBy { GroupKey(it.resourceRootPath, it.moduleName) }
            val localeFiles = localizedFiles.filter { it.normalizedLocaleTag != null }
            val updates = mutableListOf<FileMetadataUpdate>()
            var processedCount = 0
            var changedEntries = 0

            for ((groupKey, groupFiles) in grouped) {
                checkCanceled(shouldCancel)
                val baseFile = groupFiles.firstOrNull { it.folderName == "values" } ?: continue
                val baseEntries = readStringEntries(baseFile.file)
                if (baseEntries.isEmpty()) {
                    continue
                }

                var metadata = readMetadata(groupKey.resourceRootPath)
                val originalMetadata = metadata

                for (localeFile in groupFiles.filter { it.normalizedLocaleTag != null }) {
                    checkCanceled(shouldCancel)
                    val localeTag = localeFile.normalizedLocaleTag ?: continue
                    for ((key, _) in readStringEntries(localeFile.file)) {
                        val baseEntry = baseEntries[key] ?: continue
                        if (SourceChangeMetadataStore.hashFor(metadata, localeTag, key) != null) {
                            continue
                        }
                        metadata = SourceChangeMetadataStore.upsertHash(
                            metadata = metadata,
                            localeTag = localeTag,
                            key = key,
                            hash = SourceChangeMarkerSupport.computeSourceHash(
                                baseText = baseEntry.text,
                                localizePipeContext = baseEntry.localizePipeContext,
                            ),
                        )
                        changedEntries++
                    }

                    processedCount++
                    onProgress(processedCount, localeFiles.size, changedEntries)
                }

                if (metadata != originalMetadata) {
                    updates += FileMetadataUpdate(groupKey.resourceRootPath, metadata)
                }
            }

            MarkerMaintenancePlan(totalFiles = localeFiles.size, updates = updates)
        })
    }

    private fun buildRemovePlan(
        onProgress: (processedCount: Int, totalCount: Int, changedEntries: Int) -> Unit,
        shouldCancel: () -> Boolean,
    ): MarkerMaintenancePlan {
        return ApplicationManager.getApplication().runReadAction(Computable {
            val localeFiles = findLocalizedFiles().filter { it.normalizedLocaleTag != null }
            val resourceRoots = localeFiles.map { it.resourceRootPath }.distinct().sorted()
            val updates = mutableListOf<FileMetadataUpdate>()
            var processedCount = 0
            var changedEntries = 0

            for (resourceRootPath in resourceRoots) {
                checkCanceled(shouldCancel)
                val metadata = readMetadata(resourceRootPath)
                if (!metadata.isEmpty()) {
                    changedEntries += metadata.localeHashes.values.sumOf { it.size }
                    updates += FileMetadataUpdate(resourceRootPath, SourceChangeMetadata())
                }
                processedCount++
                onProgress(processedCount, resourceRoots.size, changedEntries)
            }

            MarkerMaintenancePlan(totalFiles = resourceRoots.size, updates = updates)
        })
    }

    private fun applyPlan(
        commandName: String,
        plan: MarkerMaintenancePlan,
        shouldCancel: () -> Boolean,
    ): MarkerMaintenanceResult {
        val errors = mutableListOf<String>()
        var updatedFiles = 0
        var updatedEntries = 0

        WriteCommandAction.writeCommandAction(project)
            .withName(commandName)
            .run<Throwable> {
                for (update in plan.updates) {
                    checkCanceled(shouldCancel)
                    try {
                        val persistResult = persistMetadata(update.resourceRootPath, update.metadata)
                        if (persistResult.fileTouched) {
                            updatedFiles++
                            updatedEntries += persistResult.updatedEntries
                        }
                    } catch (error: Throwable) {
                        val metadataPath = SourceChangeMetadataStore.metadataFilePath(update.resourceRootPath)
                        errors += "$metadataPath: ${error.message}"
                    }
                }
            }

        return MarkerMaintenanceResult(
            processedFiles = plan.totalFiles,
            updatedFiles = updatedFiles,
            updatedEntries = updatedEntries,
            errors = errors,
        )
    }

    private fun persistMetadata(resourceRootPath: String, metadata: SourceChangeMetadata): PersistResult {
        val changedEntries = metadata.localeHashes.values.sumOf { it.size }
        val file = findMetadataFile(resourceRootPath)
        if (metadata.isEmpty()) {
            if (file != null) {
                file.delete(this)
                return PersistResult(updatedEntries = 0, fileTouched = true)
            }
            return PersistResult(updatedEntries = 0, fileTouched = false)
        }

        val target = file ?: getOrCreateMetadataFile(resourceRootPath)
        VfsUtil.saveText(target, SourceChangeMetadataStore.serialize(metadata))
        return PersistResult(updatedEntries = changedEntries, fileTouched = true)
    }

    private fun getOrCreateMetadataFile(resourceRootPath: String): VirtualFile {
        val root = LocalFileSystem.getInstance().findFileByPath(resourceRootPath)
            ?: error("Resource root not found: $resourceRootPath")
        return root.findChild(SourceChangeMarkerSupport.METADATA_FILE_NAME)
            ?: root.createChildData(this, SourceChangeMarkerSupport.METADATA_FILE_NAME)
    }

    private fun findMetadataFile(resourceRootPath: String): VirtualFile? {
        return LocalFileSystem.getInstance().findFileByPath(SourceChangeMetadataStore.metadataFilePath(resourceRootPath))
    }

    private fun readMetadata(resourceRootPath: String): SourceChangeMetadata {
        return readMetadata(findMetadataFile(resourceRootPath))
    }

    private fun readMetadata(file: VirtualFile?): SourceChangeMetadata {
        if (file == null) {
            return SourceChangeMetadata()
        }
        val rawJson = runCatching {
            file.inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")
        return SourceChangeMetadataStore.parse(rawJson)
    }

    private fun findLocalizedFiles(): List<LocalizedStringsFile> {
        return FilenameIndex.getVirtualFilesByName(
            "strings.xml",
            GlobalSearchScope.projectScope(project),
        ).mapNotNull { file ->
            val moduleName = ModuleUtilCore.findModuleForFile(file, project)?.name
            ResourcePathClassifier.classify(file.path)?.let { classified ->
                LocalizedStringsFile(
                    file = file,
                    resourceRootPath = classified.resourceRootPath,
                    folderName = classified.folderName,
                    normalizedLocaleTag = classified.normalizedLocaleTag,
                    moduleName = moduleName,
                )
            }
        }
    }

    private fun readStringEntries(file: VirtualFile): Map<String, StringResourceValue> {
        val xmlText = runCatching {
            file.inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")
        return StringsXmlValueExtractor.extractEntries(xmlText)
    }

    private fun checkCanceled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) {
            throw ProcessCanceledException()
        }
    }

    private data class GroupKey(
        val resourceRootPath: String,
        val moduleName: String?,
    )

    private data class LocalizedStringsFile(
        val file: VirtualFile,
        val resourceRootPath: String,
        val folderName: String,
        val normalizedLocaleTag: String?,
        val moduleName: String?,
    )

    private data class FileMetadataUpdate(
        val resourceRootPath: String,
        val metadata: SourceChangeMetadata,
    )

    private data class MarkerMaintenancePlan(
        val totalFiles: Int,
        val updates: List<FileMetadataUpdate>,
    )

    private data class PersistResult(
        val updatedEntries: Int,
        val fileTouched: Boolean,
    )
}

data class MarkerMaintenanceResult(
    val processedFiles: Int,
    val updatedFiles: Int,
    val updatedEntries: Int,
    val errors: List<String>,
)
