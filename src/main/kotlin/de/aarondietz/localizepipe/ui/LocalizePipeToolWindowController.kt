package de.aarondietz.localizepipe.ui

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
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import de.aarondietz.localizepipe.apply.TranslationApplier
import de.aarondietz.localizepipe.model.RowStatus
import de.aarondietz.localizepipe.model.ScanOptions
import de.aarondietz.localizepipe.model.ScanScope
import de.aarondietz.localizepipe.model.StringEntryRow
import de.aarondietz.localizepipe.scan.StringsXmlScanner
import de.aarondietz.localizepipe.settings.ProjectScanSettingsService
import de.aarondietz.localizepipe.settings.TranslationSettingsService
import de.aarondietz.localizepipe.translation.service.LocalAiTranslationService
import java.util.concurrent.CopyOnWriteArrayList

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
    private val rescanAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parentDisposable)
    private val lock = Any()
    private var pendingRescanRequested = false
    private var currentProgressIndicator: ProgressIndicator? = null
    private var cancellationRequested = false

    private var state = ToolWindowUiState(
        includeAndroidResources = projectScanSettings.includeAndroidResources,
        includeComposeResources = projectScanSettings.includeComposeResources,
    )

    init {
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
        rescanAlarm.cancelAllRequests()
        indicator?.cancel()
        mutateState {
            copy(lastMessage = "Cancellation requested for ${activeOperation.displayName.lowercase()}")
        }
    }

    fun scheduleRescan(delayMs: Int = 700) {
        rescanAlarm.cancelAllRequests()
        rescanAlarm.addRequest({ rescan() }, delayMs)
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

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalizePipe: Scanning", false) {
            override fun run(indicator: ProgressIndicator) {
                setCurrentProgressIndicator(indicator)
                try {
                    checkCanceled(indicator)
                    indicator.isIndeterminate = true
                    val scanResult = scanner.scan(options) {
                        checkCanceled(indicator)
                        false
                    }
                    val oldRowsById = synchronized(lock) { state.rows.associateBy { it.id } }

                    val mergedRows = scanResult.rows.map { scannedRow ->
                        val previousRow = oldRowsById[scannedRow.id]
                        if (previousRow?.proposedText != null) {
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
                } catch (_: ProcessCanceledException) {
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
                }
                runQueuedRescanIfNeeded()
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
                row.status == RowStatus.MISSING || row.status == RowStatus.IDENTICAL
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

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalizePipe: Writing", false) {
                override fun run(indicator: ProgressIndicator) {
                    setCurrentProgressIndicator(indicator)
                    try {
                        checkCanceled(indicator)
                        indicator.isIndeterminate = false
                        indicator.fraction = 0.0
                        indicator.text = "LocalizePipe: Writing"
                        indicator.text2 = "Writing 0 / ${rowsToWrite.size}"
                        val applyResult = applier.apply(
                            rows = rowsToWrite,
                            onProgress = { processedCount, appliedCount ->
                                checkCanceled(indicator)
                                updateProgressIndicator(
                                    indicator = indicator,
                                    phaseTitle = "LocalizePipe: Writing",
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
                    } catch (_: ProcessCanceledException) {
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
                    } finally {
                        clearCurrentProgressIndicator(indicator)
                    }
                    runQueuedRescanIfNeeded()
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
        val expectedRowsToApplyCount = (rowsToTranslate.asSequence() + rowsToWrite.asSequence())
            .map { it.id }
            .toSet()
            .size
        val estimatedTotalWork = (rowsToTranslate.size + expectedRowsToApplyCount).coerceAtLeast(1)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalizePipe: Translating", false) {
            override fun run(indicator: ProgressIndicator) {
                setCurrentProgressIndicator(indicator)
                try {
                    checkCanceled(indicator)
                    indicator.isIndeterminate = false
                    indicator.fraction = 0.0
                    indicator.text = "LocalizePipe: Translating"
                    indicator.text2 = "Translating 0 / ${rowsToTranslate.size}"

                    val translatedRows = translationService.translateRows(
                        rows = rowsToTranslate,
                        onProgress = { partialTranslatedRows, processedCount ->
                            checkCanceled(indicator)
                            updateProgressIndicator(
                                indicator = indicator,
                                phaseTitle = "LocalizePipe: Translating",
                                phaseLabel = "Translating",
                                processedCount = processedCount,
                                phaseTotal = rowsToTranslate.size,
                                completedWorkBefore = 0,
                                totalWork = estimatedTotalWork,
                            )
                            val partialById = partialTranslatedRows.associateBy { it.id }

                            mutateState {
                                val merged = rows.map { row -> partialById[row.id] ?: row }
                                copy(
                                    rows = merged,
                                    statusText = "Translating",
                                    isBusy = true,
                                    activeOperation = UiOperation.TRANSLATING,
                                    progressCurrent = processedCount,
                                    progressTotal = rowsToTranslate.size,
                                    lastMessage = "Translating $processedCount / ${rowsToTranslate.size}",
                                )
                            }
                        },
                        shouldCancel = {
                            checkCanceled(indicator)
                            false
                        },
                    )

                    val translatedById = translatedRows.associateBy { it.id }
                    mutateState {
                        copy(rows = rows.map { row -> translatedById[row.id] ?: row })
                    }

                    val errors = translatedRows.count { it.status == RowStatus.ERROR }
                    val rowsToApply = synchronized(lock) {
                        state.rows.filter { row ->
                            !row.proposedText.isNullOrBlank() && row.status != RowStatus.ERROR
                        }
                    }

                    if (rowsToApply.isEmpty()) {
                        indicator.fraction = 1.0
                        indicator.text2 = "Translating ${rowsToTranslate.size} / ${rowsToTranslate.size}"
                        mutateState {
                            copy(
                                statusText = if (errors > 0) "Errors" else "Idle",
                                isBusy = false,
                                activeOperation = UiOperation.IDLE,
                                progressCurrent = 0,
                                progressTotal = 0,
                                lastMessage = "Translation complete: ${translatedRows.size - errors} ok, $errors errors, 0 written",
                            )
                        }
                        LOG.info(
                            "Translation completed with nothing to write (rows=${translatedRows.size}, errors=$errors)",
                        )
                        runQueuedRescanIfNeeded()
                        return
                    }

                    checkCanceled(indicator)
                    updateProgressIndicator(
                        indicator = indicator,
                        phaseTitle = "LocalizePipe: Writing",
                        phaseLabel = "Writing",
                        processedCount = 0,
                        phaseTotal = rowsToApply.size,
                        completedWorkBefore = rowsToTranslate.size,
                        totalWork = estimatedTotalWork,
                    )
                    mutateState {
                        copy(
                            statusText = "Writing",
                            isBusy = true,
                            activeOperation = UiOperation.APPLYING,
                            progressCurrent = 0,
                            progressTotal = rowsToApply.size,
                            lastMessage = "Writing 0 / ${rowsToApply.size}",
                        )
                    }
                    LOG.info("Writing translated strings immediately (rows=${rowsToApply.size})")
                    val applyResult = applier.apply(
                        rows = rowsToApply,
                        onProgress = { processedCount, appliedCount ->
                            checkCanceled(indicator)
                            updateProgressIndicator(
                                indicator = indicator,
                                phaseTitle = "LocalizePipe: Writing",
                                phaseLabel = "Writing",
                                processedCount = processedCount,
                                phaseTotal = rowsToApply.size,
                                completedWorkBefore = rowsToTranslate.size,
                                totalWork = estimatedTotalWork,
                            )
                            mutateState {
                                copy(
                                    statusText = "Writing",
                                    isBusy = true,
                                    activeOperation = UiOperation.APPLYING,
                                    progressCurrent = processedCount,
                                    progressTotal = rowsToApply.size,
                                    lastMessage = "Writing $processedCount / ${rowsToApply.size} (written $appliedCount)",
                                )
                            }
                        },
                        shouldCancel = {
                            checkCanceled(indicator)
                            false
                        },
                    )
                    val writeErrors = applyResult.errors.size
                    indicator.fraction = 1.0

                    mutateState {
                        copy(
                            statusText = if (errors > 0 || writeErrors > 0) "Errors" else "Idle",
                            isBusy = false,
                            activeOperation = UiOperation.IDLE,
                            progressCurrent = 0,
                            progressTotal = 0,
                            lastMessage = "Translation + write complete: ${applyResult.appliedCount} written, $errors translation errors, $writeErrors write errors",
                        )
                    }
                    LOG.info(
                        "Translation + write completed (rows=${translatedRows.size}, written=${applyResult.appliedCount}, translationErrors=$errors, writeErrors=$writeErrors)",
                    )
                    scheduleRescan(100)
                } catch (_: ProcessCanceledException) {
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
                } finally {
                    clearCurrentProgressIndicator(indicator)
                }
                runQueuedRescanIfNeeded()
            }
        })
    }

    fun apply() {
        if (isBusy()) {
            LOG.debug("Apply ignored because another operation is in progress")
            mutateState { copy(lastMessage = "Operation already in progress") }
            return
        }
        synchronized(lock) {
            cancellationRequested = false
        }

        val rowsToApply = synchronized(lock) {
            state.rows.filter { row ->
                !row.proposedText.isNullOrBlank() && row.status != RowStatus.ERROR
            }
        }

        if (rowsToApply.isEmpty()) {
            mutateState { copy(lastMessage = "No valid proposed translations to apply") }
            return
        }

        mutateState {
            copy(
                statusText = "Applying",
                isBusy = true,
                activeOperation = UiOperation.APPLYING,
                progressCurrent = 0,
                progressTotal = rowsToApply.size,
                lastMessage = "Applying 0 / ${rowsToApply.size}",
            )
        }
        LOG.info("Applying translated strings (rows=${rowsToApply.size})")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalizePipe: Applying", false) {
            override fun run(indicator: ProgressIndicator) {
                setCurrentProgressIndicator(indicator)
                try {
                    checkCanceled(indicator)
                    indicator.isIndeterminate = false
                    indicator.fraction = 0.0
                    indicator.text = "LocalizePipe: Applying"
                    indicator.text2 = "Applying 0 / ${rowsToApply.size}"
                    val applyResult = applier.apply(
                        rows = rowsToApply,
                        onProgress = { processedCount, appliedCount ->
                            checkCanceled(indicator)
                            updateProgressIndicator(
                                indicator = indicator,
                                phaseTitle = "LocalizePipe: Applying",
                                phaseLabel = "Applying",
                                processedCount = processedCount,
                                phaseTotal = rowsToApply.size,
                            )
                            mutateState {
                                copy(
                                    statusText = "Applying",
                                    isBusy = true,
                                    activeOperation = UiOperation.APPLYING,
                                    progressCurrent = processedCount,
                                    progressTotal = rowsToApply.size,
                                    lastMessage = "Applying $processedCount / ${rowsToApply.size} (applied $appliedCount)",
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
                                "Applied ${applyResult.appliedCount} strings"
                            } else {
                                "Applied ${applyResult.appliedCount} with ${applyResult.errors.size} errors"
                            },
                        )
                    }
                    scheduleRescan(100)
                    LOG.info("Apply completed (applied=${applyResult.appliedCount}, errors=${applyResult.errors.size})")
                } catch (_: ProcessCanceledException) {
                    mutateState {
                        copy(
                            statusText = "Idle",
                            isBusy = false,
                            activeOperation = UiOperation.IDLE,
                            progressCurrent = 0,
                            progressTotal = 0,
                            lastMessage = "Apply cancelled",
                        )
                    }
                    LOG.info("Apply cancelled")
                } finally {
                    clearCurrentProgressIndicator(indicator)
                }
                runQueuedRescanIfNeeded()
            }
        })
    }

    fun toggleScope() {
        if (preventChangesWhileBusy("change scope")) {
            return
        }
        mutateState {
            copy(
                scanScope = if (scanScope == ScanScope.WHOLE_PROJECT) ScanScope.CURRENT_MODULE else ScanScope.WHOLE_PROJECT,
            )
        }
        scheduleRescan()
    }

    fun toggleAndroidResources() {
        projectScanSettings.includeAndroidResources = !projectScanSettings.includeAndroidResources
        mutateState { copy(includeAndroidResources = projectScanSettings.includeAndroidResources) }
        scheduleRescan()
    }

    fun toggleComposeResources() {
        projectScanSettings.includeComposeResources = !projectScanSettings.includeComposeResources
        mutateState { copy(includeComposeResources = projectScanSettings.includeComposeResources) }
        scheduleRescan()
    }

    fun selectRow(rowId: String) {
        mutateState { copy(selectedRowId = rowId) }
    }

    fun updateSelectedRowProposedText(value: String) {
        mutateState {
            val selected = selectedRowId ?: return@mutateState this
            copy(
                rows = rows.map { row ->
                    if (row.id == selected) {
                        row.copy(
                            proposedText = value,
                            status = if (value.isBlank()) row.status else RowStatus.READY,
                            message = null,
                        )
                    } else {
                        row
                    }
                },
            )
        }
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

    private fun preventChangesWhileBusy(action: String): Boolean {
        if (!isBusy()) {
            return false
        }
        mutateState {
            copy(lastMessage = "Wait for ${activeOperation.displayName.lowercase()} to finish before you $action")
        }
        return true
    }

    private fun mutateState(update: ToolWindowUiState.() -> ToolWindowUiState) {
        synchronized(lock) {
            state = state.update()
        }
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it.invoke() }
        }
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
)

enum class UiOperation(val displayName: String) {
    IDLE("Idle"),
    SCANNING("Scanning"),
    TRANSLATING("Translating"),
    APPLYING("Applying"),
}

private val LOG = Logger.getInstance(LocalizePipeToolWindowController::class.java)
