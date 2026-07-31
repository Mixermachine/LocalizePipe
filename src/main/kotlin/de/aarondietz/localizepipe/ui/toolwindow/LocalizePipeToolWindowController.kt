package de.aarondietz.localizepipe.ui.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.concurrency.AppExecutorUtil
import de.aarondietz.localizepipe.apply.TranslationApplier
import de.aarondietz.localizepipe.model.LanguageAddTarget
import de.aarondietz.localizepipe.model.RowStatus
import de.aarondietz.localizepipe.model.ScanOptions
import de.aarondietz.localizepipe.model.ScanScope
import de.aarondietz.localizepipe.model.StringEntryRow
import de.aarondietz.localizepipe.model.TranslationDeleteTarget
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import de.aarondietz.localizepipe.scan.LanguageSettings
import de.aarondietz.localizepipe.scan.LocalizePipeSettings
import de.aarondietz.localizepipe.scan.LocalizePipeSettingsStore
import de.aarondietz.localizepipe.scan.StringsXmlScanner
import de.aarondietz.localizepipe.settings.ProjectScanSettingsService
import de.aarondietz.localizepipe.settings.TranslationSettingsService
import de.aarondietz.localizepipe.translation.service.LocalAiTranslationService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class LocalizePipeToolWindowController(
    private val project: Project,
    private val settings: TranslationSettingsService,
    private val projectScanSettings: ProjectScanSettingsService,
    parentDisposable: Disposable,
) {
    private val scanner = StringsXmlScanner(project)
    private val translationService = LocalAiTranslationService(settings) { projectScanSettings.sourceLocaleTag() }
    private val applier = TranslationApplier(project)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
    private val lock = Any()
    private var scheduledRescan: ScheduledFuture<*>? = null
    private var pendingRescanRequested = false
    private var currentProgressIndicator: ProgressIndicator? = null
    private var cancellationRequested = false

    private var state = ToolWindowUiState(
        includeAndroidResources = projectScanSettings.includeAndroidResources,
        includeComposeResources = projectScanSettings.includeComposeResources,
    )

    init {
        Disposer.register(parentDisposable) {
            cancelScheduledRescan()
        }
        val connection = project.messageBus.connect(parentDisposable)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { shouldTriggerRescan(it.path) }) {
                    scheduleRescan()
                }
            }
        })
    }

    fun addStateListener(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun snapshot(): ToolWindowUiState = synchronized(lock) { state }

    fun cancelCurrentOperation() {
        val indicator = synchronized(lock) {
            if (!state.isBusy) {
                return
            }
            cancellationRequested = true
            currentProgressIndicator
        }
        cancelScheduledRescan()
        indicator?.cancel()
        mutateState {
            copy(lastMessage = "Cancellation requested for ${activeOperation.displayName.lowercase()}")
        }
    }

    fun scheduleRescan(delayMs: Int = 700) {
        synchronized(lock) {
            scheduledRescan?.cancel(false)
            scheduledRescan = scheduler.schedule(
                { rescan() },
                delayMs.toLong(),
                TimeUnit.MILLISECONDS,
            )
        }
    }

    fun rescan() {
        if (isBusy()) {
            LOG.debug("Rescan requested while busy; queueing follow-up rescan")
            queueRescan()
            return
        }
        synchronized(lock) {
            cancellationRequested = false
        }

        val includeAndroidResources = projectScanSettings.includeAndroidResources
        val includeComposeResources = projectScanSettings.includeComposeResources

        val (options, scopeLabel) = synchronized(lock) {
            val currentState = state
            val scopeForScan = currentState.scanScope
            val options = ScanOptions(
                scope = scopeForScan,
                includeAndroidResources = includeAndroidResources,
                includeComposeResources = includeComposeResources,
                includeIdenticalToBase = projectScanSettings.includeIdenticalToBase,
                trackSourceChanges = projectScanSettings.trackSourceChanges,
                currentModuleName = when (scopeForScan) {
                    ScanScope.CURRENT_MODULE -> selectedModuleName()
                    ScanScope.WHOLE_PROJECT -> null
                },
            )
            options to if (scopeForScan == ScanScope.WHOLE_PROJECT) "Project" else "Module"
        }

        mutateState {
            copy(
                statusText = "Scanning",
                isBusy = true,
                activeOperation = UiOperation.SCANNING,
                progressCurrent = 0,
                progressTotal = 0,
                includeAndroidResources = includeAndroidResources,
                includeComposeResources = includeComposeResources,
                lastMessage = "Scanning resource files (scope: $scopeLabel)",
            )
        }
        LOG.info(
            "Starting scan (scope=$scopeLabel, android=$includeAndroidResources, compose=$includeComposeResources, " +
                    "includeIdentical=${options.includeIdenticalToBase})",
        )

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalizePipe scanning", true) {
            override fun run(indicator: ProgressIndicator) {
                setCurrentProgressIndicator(indicator)
                try {
                    checkCanceled(indicator)
                    indicator.isIndeterminate = true
                    val scanResult = scanner.scan(options) {
                        checkCanceled(indicator)
                        false
                    }
                    val deletionTargets = scanner.scanDeletionTargets(options) {
                        checkCanceled(indicator)
                        false
                    }
                    val languageTargets = scanner.scanLanguageTargets(options) {
                        checkCanceled(indicator)
                        false
                    }
                    val oldRowsById = synchronized(lock) { state.rows.associateBy { it.id } }

                    val mergedRows = scanResult.rows.map { scannedRow ->
                        val previousRow = oldRowsById[scannedRow.id]
                        if (previousRow?.proposedText != null && previousRow.proposedText != scannedRow.localizedText) {
                            scannedRow.copy(
                                proposedText = previousRow.proposedText,
                                status = if (previousRow.status in setOf(RowStatus.READY, RowStatus.ERROR)) {
                                    previousRow.status
                                } else {
                                    scannedRow.status
                                },
                                message = previousRow.message,
                            )
                        } else {
                            scannedRow
                        }
                    }

                    mutateState {
                        copy(
                            rows = mergedRows,
                            deleteTargets = deletionTargets,
                            languageTargets = languageTargets,
                            detectedLocales = scanResult.detectedLocales,
                            selectedRowId = selectedRowId?.takeIf { id -> mergedRows.any { it.id == id } }
                                ?: mergedRows.firstOrNull()?.id,
                            statusText = "Idle",
                            isBusy = false,
                            activeOperation = UiOperation.IDLE,
                            progressCurrent = 0,
                            progressTotal = 0,
                            hasCompletedInitialScan = true,
                            lastMessage = if (mergedRows.isEmpty()) {
                                "No untranslated strings found (scope: $scopeLabel)"
                            } else {
                                "Found ${mergedRows.size} candidate strings (scope: $scopeLabel)"
                            },
                        )
                    }
                    LOG.info(
                        "Scan completed (scope=$scopeLabel, rows=${mergedRows.size}, detectedLocales=${scanResult.detectedLocales.size})",
                    )
                } catch (cancelled: ProcessCanceledException) {
                    mutateState {
                        copy(
                            statusText = "Idle",
                            isBusy = false,
                            activeOperation = UiOperation.IDLE,
                            progressCurrent = 0,
                            progressTotal = 0,
                            hasCompletedInitialScan = true,
                            lastMessage = "Scan cancelled",
                        )
                    }
                    LOG.info("Scan cancelled (scope=$scopeLabel)")
                    throw cancelled
                } catch (error: Throwable) {
                    mutateState {
                        copy(
                            statusText = "Errors",
                            isBusy = false,
                            activeOperation = UiOperation.IDLE,
                            progressCurrent = 0,
                            progressTotal = 0,
                            hasCompletedInitialScan = true,
                            lastMessage = "Scan failed (scope: $scopeLabel): ${error.message ?: "unknown error"}",
                        )
                    }
                    LOG.warn("Scan failed (scope=$scopeLabel)", error)
                } finally {
                    clearCurrentProgressIndicator(indicator)
                    runQueuedRescanIfNeeded()
                }
            }
        })
    }

    fun translate() {
        if (isBusy()) {
            LOG.debug("Translate ignored because another operation is in progress")
            mutateState { copy(lastMessage = "Translation already in progress") }
            return
        }
        synchronized(lock) {
            cancellationRequested = false
        }

        val (rowsToTranslate, rowsToWrite) = synchronized(lock) {
            val rows = state.rows
            val translatable = rows.filter { row ->
                row.status == RowStatus.MISSING ||
                        row.status == RowStatus.IDENTICAL ||
                        row.status == RowStatus.SOURCE_CHANGED ||
                        row.status == RowStatus.ERROR
            }
            val writable = rows.filter { row ->
                !row.proposedText.isNullOrBlank() && row.status != RowStatus.ERROR
            }
            translatable to writable
        }

        if (rowsToTranslate.isEmpty()) {
            if (rowsToWrite.isEmpty()) {
                mutateState { copy(lastMessage = "Nothing to translate or write") }
                return
            }

            mutateState {
                copy(
                    statusText = "Writing",
                    isBusy = true,
                    activeOperation = UiOperation.APPLYING,
                    progressCurrent = 0,
                    progressTotal = rowsToWrite.size,
                    lastMessage = "Writing 0 / ${rowsToWrite.size}",
                )
            }
            LOG.info("Writing prepared translations without translation step (rows=${rowsToWrite.size})")

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalizePipe writing", true) {
                override fun run(indicator: ProgressIndicator) {
                    setCurrentProgressIndicator(indicator)
                    try {
                        checkCanceled(indicator)
                        indicator.isIndeterminate = false
                        indicator.fraction = 0.0
                        indicator.text = "LocalizePipe writing"
                        indicator.text2 = "Writing 0 / ${rowsToWrite.size}"
                        val applyResult = applier.apply(
                            rows = rowsToWrite,
                            onProgress = { processedCount, appliedCount ->
                                checkCanceled(indicator)
                                updateProgressIndicator(
                                    indicator = indicator,
                                    phaseTitle = "LocalizePipe writing",
                                    phaseLabel = "Writing",
                                    processedCount = processedCount,
                                    phaseTotal = rowsToWrite.size,
                                )
                                mutateState {
                                    copy(
                                        statusText = "Writing",
                                        isBusy = true,
                                        activeOperation = UiOperation.APPLYING,
                                        progressCurrent = processedCount,
                                        progressTotal = rowsToWrite.size,
                                        lastMessage = "Writing $processedCount / ${rowsToWrite.size} (written $appliedCount)",
                                    )
                                }
                            },
                            shouldCancel = {
                                checkCanceled(indicator)
                                false
                            },
                        )

                        mutateState {
                            copy(
                                statusText = if (applyResult.errors.isEmpty()) "Idle" else "Errors",
                                isBusy = false,
                                activeOperation = UiOperation.IDLE,
                                progressCurrent = 0,
                                progressTotal = 0,
                                lastMessage = if (applyResult.errors.isEmpty()) {
                                    "Write complete: ${applyResult.appliedCount} written"
                                } else {
                                    "Write completed with errors: ${applyResult.appliedCount} written, ${applyResult.errors.size} write errors"
                                },
                            )
                        }
                        scheduleRescan(100)
                        LOG.info(
                            "Write-only run completed (written=${applyResult.appliedCount}, writeErrors=${applyResult.errors.size})",
                        )
                    } catch (cancelled: ProcessCanceledException) {
                        mutateState {
                            copy(
                                statusText = "Idle",
                                isBusy = false,
                                activeOperation = UiOperation.IDLE,
                                progressCurrent = 0,
                                progressTotal = 0,
                                lastMessage = "Write cancelled",
                            )
                        }
                        LOG.info("Write-only run cancelled")
                        throw cancelled
                    } finally {
                        clearCurrentProgressIndicator(indicator)
                        runQueuedRescanIfNeeded()
                    }
                }
            })
            return
        }

        mutateState {
            copy(
                statusText = "Translating",
                isBusy = true,
                activeOperation = UiOperation.TRANSLATING,
                progressCurrent = 0,
                progressTotal = rowsToTranslate.size,
                lastMessage = "Translating 0 / ${rowsToTranslate.size}",
            )
        }
        LOG.info(
            "Starting translation (rows=${rowsToTranslate.size}, provider=${settings.providerType}, model=${settings.activeModel()})",
        )

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalizePipe translating", true) {
            override fun run(indicator: ProgressIndicator) {
                setCurrentProgressIndicator(indicator)
                try {
                    checkCanceled(indicator)
                    indicator.isIndeterminate = false
                    indicator.fraction = 0.0
                    indicator.text = "LocalizePipe translating"
                    indicator.text2 = "Translating 0 / ${rowsToTranslate.size}"

                    val languageSettings = rowsToTranslate
                        .map { it.resourceRootPath }
                        .distinct()
                        .fold(mutableMapOf<String, LanguageSettings>()) { acc, rootPath ->
                            val fileSettings = readLocalizePipeSettings(rootPath)
                            fileSettings.languages.forEach { (localeTag, langSettings) ->
                                acc.putIfAbsent(localeTag, langSettings)
                            }
                            acc
                        }

                    var writtenCount = 0
                    var writeErrors = 0

                    val translatedRows = translationService.translateRows(
                        rows = rowsToTranslate,
                        onProgress = { partialTranslatedRows, processedCount, speed ->
                            checkCanceled(indicator)
                            updateProgressIndicator(
                                indicator = indicator,
                                phaseTitle = "LocalizePipe translating",
                                phaseLabel = "Translating",
                                processedCount = processedCount,
                                phaseTotal = rowsToTranslate.size,
                            )
                            val partialById = partialTranslatedRows.associateBy { it.id }
                            val speedStr = speed?.takeIf { it > 0f }?.let { String.format(java.util.Locale.US, " (%.1f t/s)", it) } ?: ""

                            mutateState {
                                val merged = rows.map { row -> partialById[row.id] ?: row }
                                copy(
                                    rows = merged,
                                    statusText = "Translating",
                                    isBusy = true,
                                    activeOperation = UiOperation.TRANSLATING,
                                    progressCurrent = processedCount,
                                    progressTotal = rowsToTranslate.size,
                                    currentTokenSpeed = speed,
                                    lastMessage = "Translating $processedCount / ${rowsToTranslate.size}$speedStr",
                                )
                            }
                        },
                        onRowTranslated = { translatedRow ->
                            checkCanceled(indicator)
                            val applyResult = applier.apply(
                                rows = listOf(translatedRow),
                                shouldCancel = {
                                    checkCanceled(indicator)
                                    false
                                },
                            )
                            writtenCount += applyResult.appliedCount
                            writeErrors += applyResult.errors.size
                        },
                        shouldCancel = {
                            checkCanceled(indicator)
                            false
                        },
                        languageSettings = languageSettings,
                    )

                    val translatedById = translatedRows.associateBy { it.id }
                    mutateState {
                        copy(
                            rows = rows.map { row -> translatedById[row.id] ?: row },
                            currentTokenSpeed = null,
                        )
                    }

                    val errors = translatedRows.count { it.status == RowStatus.ERROR }
                    indicator.fraction = 1.0

                    mutateState {
                        copy(
                            statusText = if (errors > 0 || writeErrors > 0) "Errors" else "Idle",
                            isBusy = false,
                            activeOperation = UiOperation.IDLE,
                            progressCurrent = 0,
                            progressTotal = 0,
                            lastMessage = "Translation + write complete: $writtenCount written, $errors translation errors, $writeErrors write errors",
                        )
                    }
                    LOG.info(
                        "Translation + write completed (rows=${translatedRows.size}, written=$writtenCount, translationErrors=$errors, writeErrors=$writeErrors)",
                    )
                    scheduleRescan(100)
                } catch (cancelled: ProcessCanceledException) {
                    mutateState {
                        copy(
                            statusText = "Idle",
                            isBusy = false,
                            activeOperation = UiOperation.IDLE,
                            progressCurrent = 0,
                            progressTotal = 0,
                            lastMessage = "Translation cancelled",
                        )
                    }
                    LOG.info("Translation cancelled")
                    scheduleRescan(100)
                    throw cancelled
                } finally {
                    clearCurrentProgressIndicator(indicator)
                    runQueuedRescanIfNeeded()
                }
            }
        })
    }

    fun deleteTranslationsForTarget(target: TranslationDeleteTarget) {
        if (isBusy()) {
            LOG.debug("Delete ignored because another operation is in progress")
            mutateState { copy(lastMessage = "Another operation is already running") }
            return
        }
        synchronized(lock) {
            cancellationRequested = false
        }

        val totalLocales = target.localeEntries.size
        if (totalLocales == 0) {
            mutateState { copy(lastMessage = "No translated locale entries found for key '${target.key}'") }
            return
        }

        mutateState {
            copy(
                statusText = "Deleting",
                isBusy = true,
                activeOperation = UiOperation.APPLYING,
                progressCurrent = 0,
                progressTotal = totalLocales,
                lastMessage = "Deleting translations for '${target.key}' (0 / $totalLocales)",
            )
        }
        LOG.info("Deleting translations for key='${target.key}' across $totalLocales locale files")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalizePipe deleting translations", true) {
            override fun run(indicator: ProgressIndicator) {
                setCurrentProgressIndicator(indicator)
                try {
                    checkCanceled(indicator)
                    indicator.isIndeterminate = false
                    indicator.fraction = 0.0
                    indicator.text = "LocalizePipe deleting translations"
                    indicator.text2 = "Deleting 0 / $totalLocales"

                    val deleteResult = applier.deleteTranslations(
                        target = target,
                        onProgress = { processedCount, deletedCount ->
                            checkCanceled(indicator)
                            updateProgressIndicator(
                                indicator = indicator,
                                phaseTitle = "LocalizePipe deleting translations",
                                phaseLabel = "Deleting",
                                processedCount = processedCount,
                                phaseTotal = totalLocales,
                            )
                            mutateState {
                                copy(
                                    statusText = "Deleting",
                                    isBusy = true,
                                    activeOperation = UiOperation.APPLYING,
                                    progressCurrent = processedCount,
                                    progressTotal = totalLocales,
                                    lastMessage = "Deleting translations for '${target.key}' ($processedCount / $totalLocales, deleted $deletedCount)",
                                )
                            }
                        },
                        shouldCancel = {
                            checkCanceled(indicator)
                            false
                        },
                    )

                    mutateState {
                        copy(
                            statusText = if (deleteResult.errors.isEmpty()) "Idle" else "Errors",
                            isBusy = false,
                            activeOperation = UiOperation.IDLE,
                            progressCurrent = 0,
                            progressTotal = 0,
                            lastMessage = if (deleteResult.errors.isEmpty()) {
                                "Deleted translations for '${target.key}' in ${deleteResult.appliedCount} locale files"
                            } else {
                                "Deleted with errors for '${target.key}': ${deleteResult.appliedCount} deleted, ${deleteResult.errors.size} errors"
                            },
                        )
                    }
                    LOG.info(
                        "Delete completed for key='${target.key}' (deleted=${deleteResult.appliedCount}, errors=${deleteResult.errors.size})",
                    )
                    scheduleRescan(100)
                } catch (cancelled: ProcessCanceledException) {
                    mutateState {
                        copy(
                            statusText = "Idle",
                            isBusy = false,
                            activeOperation = UiOperation.IDLE,
                            progressCurrent = 0,
                            progressTotal = 0,
                            lastMessage = "Delete cancelled",
                        )
                    }
                    LOG.info("Delete cancelled for key='${target.key}'")
                    throw cancelled
                } finally {
                    clearCurrentProgressIndicator(indicator)
                    runQueuedRescanIfNeeded()
                }
            }
        })
    }

    fun addLanguage(localeTag: String, targetIds: Set<String>) {
        if (isBusy()) {
            LOG.debug("Add language ignored because another operation is in progress")
            mutateState { copy(lastMessage = "Another operation is already running") }
            return
        }
        synchronized(lock) {
            cancellationRequested = false
        }

        val normalizedLocaleTag = localeTag.replace('_', '-').trim()
        val targets = synchronized(lock) {
            state.languageTargets.filter { it.id in targetIds }
        }
        if (normalizedLocaleTag.isBlank()) {
            mutateState { copy(lastMessage = "Locale tag must not be empty") }
            return
        }
        if (targets.isEmpty()) {
            mutateState { copy(lastMessage = "No resource roots selected for adding language '$normalizedLocaleTag'") }
            return
        }

        mutateState {
            copy(
                statusText = "Adding language",
                isBusy = true,
                activeOperation = UiOperation.APPLYING,
                progressCurrent = 0,
                progressTotal = targets.size,
                lastMessage = "Adding locale '$normalizedLocaleTag' (0 / ${targets.size})",
            )
        }
        LOG.info("Adding locale='$normalizedLocaleTag' to ${targets.size} resource roots")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalizePipe adding language", true) {
            override fun run(indicator: ProgressIndicator) {
                setCurrentProgressIndicator(indicator)
                try {
                    checkCanceled(indicator)
                    indicator.isIndeterminate = false
                    indicator.fraction = 0.0
                    indicator.text = "LocalizePipe adding language"
                    indicator.text2 = "Adding 0 / ${targets.size}"

                    val addResult = applier.addLanguage(
                        localeTag = normalizedLocaleTag,
                        targets = targets,
                        onProgress = { processedCount, createdCount ->
                            checkCanceled(indicator)
                            updateProgressIndicator(
                                indicator = indicator,
                                phaseTitle = "LocalizePipe adding language",
                                phaseLabel = "Adding",
                                processedCount = processedCount,
                                phaseTotal = targets.size,
                            )
                            mutateState {
                                copy(
                                    statusText = "Adding language",
                                    isBusy = true,
                                    activeOperation = UiOperation.APPLYING,
                                    progressCurrent = processedCount,
                                    progressTotal = targets.size,
                                    lastMessage = "Adding locale '$normalizedLocaleTag' ($processedCount / ${targets.size}, created $createdCount)",
                                )
                            }
                        },
                        shouldCancel = {
                            checkCanceled(indicator)
                            false
                        },
                    )

                    val unchangedCount = addResult.skippedCount

                    mutateState {
                        copy(
                            statusText = if (addResult.errors.isEmpty()) "Idle" else "Errors",
                            isBusy = false,
                            activeOperation = UiOperation.IDLE,
                            progressCurrent = 0,
                            progressTotal = 0,
                            lastMessage = if (addResult.errors.isEmpty()) {
                                "Added locale '$normalizedLocaleTag': ${addResult.createdCount} created, $unchangedCount unchanged"
                            } else {
                                "Added locale '$normalizedLocaleTag' with errors: ${addResult.createdCount} created, $unchangedCount unchanged, ${addResult.errors.size} errors"
                            },
                        )
                    }
                    LOG.info(
                        "Add language completed (locale=$normalizedLocaleTag, created=${addResult.createdCount}, unchanged=$unchangedCount, errors=${addResult.errors.size})",
                    )
                    scheduleRescan(100)
                } catch (cancelled: ProcessCanceledException) {
                    mutateState {
                        copy(
                            statusText = "Idle",
                            isBusy = false,
                            activeOperation = UiOperation.IDLE,
                            progressCurrent = 0,
                            progressTotal = 0,
                            lastMessage = "Add language cancelled",
                        )
                    }
                    LOG.info("Add language cancelled (locale=$normalizedLocaleTag)")
                    throw cancelled
                } finally {
                    clearCurrentProgressIndicator(indicator)
                    runQueuedRescanIfNeeded()
                }
            }
        })
    }

    fun toggleScope() {
        if (preventChangesWhileBusy()) {
            return
        }
        mutateState {
            copy(
                scanScope = if (scanScope == ScanScope.WHOLE_PROJECT) ScanScope.CURRENT_MODULE else ScanScope.WHOLE_PROJECT,
            )
        }
        scheduleRescan()
    }

    fun selectRow(rowId: String) {
        mutateState { copy(selectedRowId = rowId) }
    }

    private fun selectedModuleName(): String? {
        val selectedFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return null
        return ModuleUtilCore.findModuleForFile(selectedFile, project)?.name
    }

    private fun shouldTriggerRescan(path: String): Boolean {
        if (!path.contains("/values")) {
            return false
        }
        return path.endsWith("/strings.xml") || path.contains("/values-")
    }

    private fun isBusy(): Boolean = synchronized(lock) { state.isBusy }

    private fun setCurrentProgressIndicator(indicator: ProgressIndicator) {
        synchronized(lock) {
            currentProgressIndicator = indicator
        }
    }

    private fun clearCurrentProgressIndicator(indicator: ProgressIndicator) {
        synchronized(lock) {
            if (currentProgressIndicator === indicator) {
                currentProgressIndicator = null
            }
            cancellationRequested = false
        }
    }

    private fun checkCanceled(indicator: ProgressIndicator) {
        val isCancelled = synchronized(lock) { cancellationRequested } || indicator.isCanceled
        if (isCancelled) {
            indicator.cancel()
            throw ProcessCanceledException()
        }
    }

    private fun queueRescan() {
        synchronized(lock) {
            pendingRescanRequested = true
        }
        LOG.debug("Queued rescan for execution after current operation")
        mutateState {
            copy(lastMessage = "Rescan queued while ${activeOperation.displayName.lowercase()} is running")
        }
    }

    private fun runQueuedRescanIfNeeded() {
        val shouldRun = synchronized(lock) {
            if (!state.isBusy && pendingRescanRequested) {
                pendingRescanRequested = false
                true
            } else {
                false
            }
        }
        if (shouldRun) {
            LOG.debug("Running queued rescan")
            scheduleRescan(100)
        }
    }

    private fun preventChangesWhileBusy(): Boolean {
        if (!isBusy()) {
            return false
        }
        mutateState {
            copy(lastMessage = "Wait for ${activeOperation.displayName.lowercase()} to finish before changing scope")
        }
        return true
    }

    private fun cancelScheduledRescan() {
        synchronized(lock) {
            scheduledRescan?.cancel(false)
            scheduledRescan = null
        }
    }

    private fun mutateState(update: ToolWindowUiState.() -> ToolWindowUiState) {
        synchronized(lock) {
            state = state.update()
        }
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it.invoke() }
        }
    }

    fun readAllLanguageSettings(): Map<String, LocalizePipeSettings> {
        val targets = snapshot().languageTargets
        return targets.associate { target ->
            target.resourceRootPath to readLocalizePipeSettings(target.resourceRootPath)
        }
    }

    fun writeAllLanguageSettings(settingsByRoot: Map<String, LocalizePipeSettings>) {
        WriteCommandAction.writeCommandAction(project)
            .withName("Write LocalizePipe Language Settings")
            .run<Throwable> {
                for ((resourceRootPath, settings) in settingsByRoot) {
                    val filePath = LocalizePipeSettingsStore.settingsFilePath(resourceRootPath)
                    val file = LocalFileSystem.getInstance().findFileByPath(filePath)

                    val nonDefaultLanguages = settings.languages.filterValues {
                        it.translationLocaleTag != null || it.disabled || it.instructions != null
                    }

                    if (nonDefaultLanguages.isEmpty()) {
                        if (file != null && file.exists()) {
                            file.delete(this)
                        }
                    } else {
                        val directoryPath = filePath.substringBeforeLast('/', missingDelimiterValue = "")
                        val fileName = filePath.substringAfterLast('/')
                        val directory = LocalFileSystem.getInstance().findFileByPath(directoryPath)
                            ?: continue
                        val targetFile = file ?: directory.createChildData(this, fileName)
                        val cleanedSettings = LocalizePipeSettings(languages = nonDefaultLanguages)
                        VfsUtil.saveText(targetFile, LocalizePipeSettingsStore.serialize(cleanedSettings))
                    }
                }
            }
        scheduleRescan(100)
    }

    internal fun readLocalizePipeSettings(resourceRootPath: String): LocalizePipeSettings {
        val settingsFile = LocalFileSystem.getInstance()
            .findFileByPath(LocalizePipeSettingsStore.settingsFilePath(resourceRootPath))
            ?: return LocalizePipeSettings()
        val rawJson = runCatching {
            settingsFile.inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")
        return LocalizePipeSettingsStore.parse(rawJson)
    }

    private fun updateProgressIndicator(
        indicator: ProgressIndicator,
        phaseTitle: String,
        phaseLabel: String,
        processedCount: Int,
        phaseTotal: Int,
        completedWorkBefore: Int = 0,
        totalWork: Int = phaseTotal,
    ) {
        val safePhaseTotal = phaseTotal.coerceAtLeast(1)
        val safeTotalWork = totalWork.coerceAtLeast(1)
        val safeProcessed = processedCount.coerceIn(0, safePhaseTotal)
        val completed = (completedWorkBefore + safeProcessed).coerceAtMost(safeTotalWork)

        indicator.isIndeterminate = false
        indicator.text = phaseTitle
        indicator.text2 = "$phaseLabel $safeProcessed / $phaseTotal"
        indicator.fraction = completed.toDouble() / safeTotalWork.toDouble()
    }
}

data class ToolWindowUiState(
    val scanScope: ScanScope = ScanScope.WHOLE_PROJECT,
    val includeAndroidResources: Boolean = true,
    val includeComposeResources: Boolean = true,
    val deleteTargets: List<TranslationDeleteTarget> = emptyList(),
    val languageTargets: List<LanguageAddTarget> = emptyList(),
    val detectedLocales: Set<String> = emptySet(),
    val rows: List<StringEntryRow> = emptyList(),
    val selectedRowId: String? = null,
    val statusText: String = "Idle",
    val isBusy: Boolean = false,
    val lastMessage: String? = null,
    val activeOperation: UiOperation = UiOperation.IDLE,
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val hasCompletedInitialScan: Boolean = false,
    val currentTokenSpeed: Float? = null,
)

enum class UiOperation(val displayName: String) {
    IDLE("Idle"),
    SCANNING("Scanning"),
    TRANSLATING("Translating"),
    APPLYING("Applying"),
}

private val LOG = Logger.getInstance(LocalizePipeToolWindowController::class.java)
