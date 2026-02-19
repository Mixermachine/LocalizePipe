package de.aarondietz.localizepipe.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import de.aarondietz.localizepipe.model.ResourceKind
import de.aarondietz.localizepipe.model.RowStatus
import de.aarondietz.localizepipe.model.StringEntryRow
import de.aarondietz.localizepipe.translation.TranslateGemmaLanguageMapper
import de.aarondietz.localizepipe.translation.service.LocalAiTranslationService
import java.awt.*
import java.util.*
import javax.swing.*
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

class LocalizePipeSettingsConfigurable(private val project: Project) : Configurable {
    companion object {
        private const val DEFAULT_SOURCE_LOCALE_TAG = "en"
    }

    private var panel: JPanel? = null
    private var rootComponent: JComponent? = null

    private lateinit var includeAndroidCheckBox: JCheckBox
    private lateinit var includeComposeCheckBox: JCheckBox
    private lateinit var includeIdenticalToBaseCheckBox: JCheckBox

    private lateinit var providerCombo: ComboBox<TranslationProviderType>
    private lateinit var sourceLocaleCombo: ComboBox<String>
    private lateinit var ollamaBaseUrlField: JBTextField
    private lateinit var ollamaModelCombo: ComboBox<String>
    private lateinit var ollamaRuntimeModeCombo: ComboBox<OllamaRuntimeMode>
    private lateinit var huggingFaceBaseUrlField: JBTextField
    private lateinit var huggingFaceModelCombo: ComboBox<String>
    private lateinit var huggingFaceTokenField: JBTextField
    private lateinit var timeoutSecondsSpinner: JSpinner
    private lateinit var retryCountSpinner: JSpinner
    private lateinit var temperatureSpinner: JSpinner
    private lateinit var removeAddedTrailingPeriodCheckBox: JCheckBox
    private lateinit var ollamaProviderPanel: JPanel
    private lateinit var huggingFaceProviderPanel: JPanel
    private lateinit var modelGuidanceLabel: JLabel
    private lateinit var ollamaModelAvailabilityLabel: JLabel
    private lateinit var ollamaPullModelButton: JButton
    private lateinit var testSourceTextLabel: JLabel
    private lateinit var testSourceTextField: JBTextField
    private lateinit var testTargetLocaleCombo: ComboBox<String>
    private lateinit var testTranslateButton: JButton
    private lateinit var testStatusLabel: JLabel
    private lateinit var testResultArea: JTextArea
    private val sourceLocaleOptionByTag = linkedMapOf<String, String>()
    private val testTargetLocaleOptionByTag = linkedMapOf<String, String>()
    private lateinit var ollamaAvailabilityDebounceTimer: Timer
    private var isTestTranslationRunning: Boolean = false
    private var isOllamaPullRunning: Boolean = false
    private var ollamaAvailabilityCheckGeneration: Int = 0
    private var latestOllamaCheckStatus: OllamaModelCheckStatus? = null
    private var detectedSystemRamGb: Long? = null
    private var detectedAvailableStorageGb: Long? = null
    private var recommendedGemmaSize: TranslateGemmaSize = TranslateGemmaSize.SIZE_4B

    override fun getDisplayName(): String = "LocalizePipe"

    override fun createComponent(): JComponent {
        if (rootComponent != null) {
            return rootComponent!!
        }

        includeAndroidCheckBox = JCheckBox("Scan Android resources (res/values*)")
        includeComposeCheckBox = JCheckBox("Scan Compose resources (composeResources/values*)")
        includeIdenticalToBaseCheckBox = JCheckBox("Include values identical to source text")

        providerCombo = ComboBox(TranslationProviderType.entries.toTypedArray())
        sourceLocaleCombo = ComboBox<String>().apply {
            maximumRowCount = 18
        }
        refreshSourceLocaleOptions()
        ollamaBaseUrlField = JBTextField()
        huggingFaceBaseUrlField = JBTextField()
        huggingFaceTokenField = JBTextField()
        timeoutSecondsSpinner = JSpinner(SpinnerNumberModel(45L, 5L, 600L, 1L))
        retryCountSpinner = JSpinner(SpinnerNumberModel(1, 0, 10, 1))
        temperatureSpinner = JSpinner(SpinnerNumberModel(0.1, 0.0, 2.0, 0.1))
        removeAddedTrailingPeriodCheckBox =
            JCheckBox("Remove trailing '.' when source text has no trailing '.'").apply {
                toolTipText = "Prevents model-added final dots when the original text does not end with a dot."
            }
        detectedSystemRamGb = TranslateGemmaSizingGuide.detectTotalSystemRamGb()
        detectedAvailableStorageGb = TranslateGemmaSizingGuide.detectAvailableStorageGb()
        recommendedGemmaSize = TranslateGemmaSizingGuide.recommendedSize(detectedSystemRamGb)
        ollamaModelCombo = editableModelCombo(
            "translategemma:4b",
            "translategemma:12b",
            "translategemma:27b",
        )
        ollamaRuntimeModeCombo = ComboBox(OllamaRuntimeMode.entries.toTypedArray())
        huggingFaceModelCombo = editableModelCombo(
            "google/translategemma-4b-it",
            "google/translategemma-12b-it",
            "google/translategemma-27b-it",
        )
        testSourceTextLabel = JLabel()
        testSourceTextField = JBTextField("Settings")
        testTargetLocaleCombo = ComboBox<String>().apply {
            maximumRowCount = 18
        }
        refreshTestTargetLocaleOptions()
        testTranslateButton = JButton("Test translation")
        testStatusLabel = JLabel("Run a test translation with the current provider settings.")
        testResultArea = JTextArea(1, 20).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        ollamaModelAvailabilityLabel = JLabel("Checking Ollama model availability...")
        ollamaPullModelButton = JButton("Pull model from Ollama").apply {
            isEnabled = false
        }
        ollamaAvailabilityDebounceTimer = Timer(350) { runOllamaModelAvailabilityCheck() }.apply {
            isRepeats = false
        }
        applyRecommendationRenderer(ollamaModelCombo)
        applyRecommendationRenderer(huggingFaceModelCombo)

        val projectPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Source locale tag", sourceLocaleCombo)
            .addComponent(includeAndroidCheckBox)
            .addComponent(includeComposeCheckBox)
            .addComponent(includeIdenticalToBaseCheckBox)
            .addComponent(
                JLabel(
                    "<html><i>" +
                            "When enabled, locale strings that are identical to the source language are listed as translation candidates. <br>" +
                            "Default is OFF to only focus on truly missing entries." +
                            "</i></html>",
                ),
            )
            .panel.apply {
                border = BorderFactory.createTitledBorder("Project-specific (saved per project)")
            }

        ollamaProviderPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Ollama base URL", ollamaBaseUrlField)
            .addLabeledComponent("Ollama model", ollamaModelCombo)
            .addLabeledComponent("Ollama runtime", ollamaRuntimeModeCombo)
            .addComponent(ollamaModelAvailabilityLabel)
            .addComponent(ollamaPullModelButton)
            .panel

        huggingFaceProviderPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Hugging Face base URL", huggingFaceBaseUrlField)
            .addLabeledComponent("Hugging Face model", huggingFaceModelCombo)
            .addLabeledComponent("Hugging Face token", huggingFaceTokenField)
            .panel

        modelGuidanceLabel = JLabel().apply {
            horizontalAlignment = JLabel.LEFT
            verticalAlignment = JLabel.TOP
        }
        val modelGuidancePanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(4, 0, 6, 0)
            add(modelGuidanceLabel, BorderLayout.CENTER)
        }
        val testResultScroll = JBScrollPane(testResultArea).apply {
            preferredSize = Dimension(560, 52)
        }
        val testActionRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(testTranslateButton)
            add(testStatusLabel)
        }
        val testSourceTextPanel = JPanel(BorderLayout(8, 0)).apply {
            add(testSourceTextLabel, BorderLayout.WEST)
            add(testSourceTextField, BorderLayout.CENTER)
        }
        val translationTestPanel = FormBuilder.createFormBuilder()
            .addSeparator()
            .addComponent(testSourceTextPanel)
            .addLabeledComponent("Test target locale", testTargetLocaleCombo)
            .addComponent(testActionRow)
            .addLabeledComponent("Test result", testResultScroll)
            .panel

        val pluginPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createTitledBorder("Plugin-wide (shared across all projects)")
            add(
                FormBuilder.createFormBuilder()
                    .addLabeledComponent("Provider", providerCombo)
                    .panel,
            )
            add(ollamaProviderPanel)
            add(huggingFaceProviderPanel)
            add(modelGuidancePanel)
            add(translationTestPanel)
            add(
                FormBuilder.createFormBuilder()
                    .addSeparator()
                    .addComponent(removeAddedTrailingPeriodCheckBox)
                    .addLabeledComponent("Request timeout (seconds)", timeoutSecondsSpinner)
                    .addLabeledComponent("Retry count", retryCountSpinner)
                    .addLabeledComponent("Temperature", temperatureSpinner)
                    .panel,
            )
        }

        panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(projectPanel)
            add(pluginPanel)
        }
        panel?.let { content ->
            content.components.forEach { component ->
                if (component is JComponent) {
                    component.setAlignmentX(Component.LEFT_ALIGNMENT)
                }
            }
        }

        providerCombo.addActionListener { updateProviderSpecificVisibility() }
        testTranslateButton.addActionListener { runTestTranslation() }
        ollamaPullModelButton.addActionListener { pullSelectedOllamaModel() }
        ollamaModelCombo.addActionListener {
            scheduleOllamaModelAvailabilityCheck()
            updateModelGuidance()
        }
        huggingFaceModelCombo.addActionListener { updateModelGuidance() }
        addTextChangeListener(ollamaBaseUrlField) { scheduleOllamaModelAvailabilityCheck() }
        sourceLocaleCombo.addActionListener { updateTestSourceLabel() }
        (ollamaModelCombo.editor.editorComponent as? JTextComponent)?.let { editor ->
            addTextChangeListener(editor) {
                scheduleOllamaModelAvailabilityCheck()
                updateModelGuidance()
            }
        }
        (huggingFaceModelCombo.editor.editorComponent as? JTextComponent)?.let { editor ->
            addTextChangeListener(editor) { updateModelGuidance() }
        }

        reset()
        updateProviderSpecificVisibility()
        rootComponent = JBScrollPane(panel).apply {
            border = BorderFactory.createEmptyBorder()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.unitIncrement = 16
        }
        return rootComponent!!
    }

    override fun isModified(): Boolean {
        val appSettings = service<TranslationSettingsService>()
        val projectSettings = project.service<ProjectScanSettingsService>()
        return includeAndroidCheckBox.isSelected != projectSettings.includeAndroidResources ||
                includeComposeCheckBox.isSelected != projectSettings.includeComposeResources ||
                includeIdenticalToBaseCheckBox.isSelected != projectSettings.includeIdenticalToBase ||
                selectedSourceLocaleTag() != projectSettings.sourceLocaleTag() ||
                providerCombo.selectedItem != appSettings.providerType ||
                ollamaBaseUrlField.text.trim() != appSettings.ollamaBaseUrl() ||
                selectedModel(ollamaModelCombo) != appSettings.ollamaModel() ||
                ollamaRuntimeModeCombo.selectedItem != appSettings.ollamaRuntimeMode() ||
                huggingFaceBaseUrlField.text.trim() != appSettings.huggingFaceBaseUrl() ||
                selectedModel(huggingFaceModelCombo) != appSettings.huggingFaceModel() ||
                huggingFaceTokenField.text.trim() != appSettings.huggingFaceToken() ||
                removeAddedTrailingPeriodCheckBox.isSelected != appSettings.removeAddedTrailingPeriod() ||
                (timeoutSecondsSpinner.value as Number).toLong() != appSettings.requestTimeoutSeconds() ||
                (retryCountSpinner.value as Number).toInt() != appSettings.retryCount() ||
                (temperatureSpinner.value as Number).toFloat() != appSettings.temperature()
    }

    override fun apply() {
        val appSettings = service<TranslationSettingsService>()
        val projectSettings = project.service<ProjectScanSettingsService>()

        projectSettings.includeAndroidResources = includeAndroidCheckBox.isSelected
        projectSettings.includeComposeResources = includeComposeCheckBox.isSelected
        projectSettings.includeIdenticalToBase = includeIdenticalToBaseCheckBox.isSelected
        projectSettings.sourceLocaleTag = selectedSourceLocaleTag()

        appSettings.providerType = providerCombo.selectedItem as TranslationProviderType
        appSettings.ollamaBaseUrl = ollamaBaseUrlField.text.trim()
        appSettings.ollamaModel = selectedModel(ollamaModelCombo)
            .ifBlank { TranslationSettingsService.defaultOllamaModelForMachine() }
        appSettings.ollamaRuntimeMode =
            ollamaRuntimeModeCombo.selectedItem as? OllamaRuntimeMode ?: OllamaRuntimeMode.AUTO
        appSettings.huggingFaceBaseUrl = huggingFaceBaseUrlField.text.trim()
        appSettings.huggingFaceModel = selectedModel(huggingFaceModelCombo).ifBlank { "google/translategemma-4b-it" }
        appSettings.huggingFaceToken = huggingFaceTokenField.text.trim()
        appSettings.removeAddedTrailingPeriodConfig = removeAddedTrailingPeriodCheckBox.isSelected
        appSettings.timeoutSecondsConfig = (timeoutSecondsSpinner.value as Number).toLong()
        appSettings.retryCountConfig = (retryCountSpinner.value as Number).toInt()
        appSettings.temperatureConfig = (temperatureSpinner.value as Number).toFloat()
    }

    override fun reset() {
        val appSettings = service<TranslationSettingsService>()
        val projectSettings = project.service<ProjectScanSettingsService>()

        includeAndroidCheckBox.isSelected = projectSettings.includeAndroidResources
        includeComposeCheckBox.isSelected = projectSettings.includeComposeResources
        includeIdenticalToBaseCheckBox.isSelected = projectSettings.includeIdenticalToBase
        selectSourceLocale(projectSettings.sourceLocaleTag())

        providerCombo.selectedItem = appSettings.providerType
        ollamaBaseUrlField.text = appSettings.ollamaBaseUrl()
        ollamaModelCombo.selectedItem = appSettings.ollamaModel()
        ollamaRuntimeModeCombo.selectedItem = appSettings.ollamaRuntimeMode()
        huggingFaceBaseUrlField.text = appSettings.huggingFaceBaseUrl()
        huggingFaceModelCombo.selectedItem = appSettings.huggingFaceModel()
        huggingFaceTokenField.text = appSettings.huggingFaceToken()
        removeAddedTrailingPeriodCheckBox.isSelected = appSettings.removeAddedTrailingPeriod()
        timeoutSecondsSpinner.value = appSettings.requestTimeoutSeconds()
        retryCountSpinner.value = appSettings.retryCount()
        temperatureSpinner.value = appSettings.temperature().toDouble()
        updateTestSourceLabel()
        testSourceTextField.text = "Settings"
        selectTestTargetLocale(defaultTestTargetLocaleTag())
        testResultArea.text = ""
        testStatusLabel.foreground = JBColor.foreground()
        testStatusLabel.text = "Run a test translation with the current provider settings."
        setTestUiBusy(false)
        isOllamaPullRunning = false
        latestOllamaCheckStatus = null
        scheduleOllamaModelAvailabilityCheck()
        updateProviderSpecificVisibility()
    }

    override fun disposeUIResources() {
        if (::ollamaAvailabilityDebounceTimer.isInitialized) {
            ollamaAvailabilityDebounceTimer.stop()
        }
        panel = null
        rootComponent = null
    }

    private fun editableModelCombo(vararg options: String): ComboBox<String> {
        return ComboBox(options).apply {
            isEditable = true
        }
    }

    private fun selectedModel(comboBox: ComboBox<String>): String {
        val value = comboBox.editor.item?.toString()?.trim().orEmpty()
        return if (value.isBlank()) comboBox.selectedItem?.toString()?.trim().orEmpty() else value
    }

    private fun updateProviderSpecificVisibility() {
        val provider = providerCombo.selectedItem as? TranslationProviderType ?: TranslationProviderType.OLLAMA
        ollamaProviderPanel.isVisible = provider == TranslationProviderType.OLLAMA
        huggingFaceProviderPanel.isVisible = provider == TranslationProviderType.HUGGING_FACE
        updateModelGuidance()
        if (provider == TranslationProviderType.OLLAMA) {
            scheduleOllamaModelAvailabilityCheck()
        }
        updateOllamaPullButtonEnabled()
        panel?.revalidate()
        panel?.repaint()
    }

    private fun updateModelGuidance() {
        val provider = providerCombo.selectedItem as? TranslationProviderType ?: TranslationProviderType.OLLAMA
        val selectedModel = when (provider) {
            TranslationProviderType.OLLAMA -> selectedModel(ollamaModelCombo)
            TranslationProviderType.HUGGING_FACE -> selectedModel(huggingFaceModelCombo)
        }
        modelGuidanceLabel.text = TranslateGemmaSizingGuide.guidanceHtml(
            providerType = provider,
            totalSystemRamGb = detectedSystemRamGb,
            recommendedSize = recommendedGemmaSize,
            availableStorageGb = detectedAvailableStorageGb,
            selectedModelId = selectedModel,
        )
    }

    private fun applyRecommendationRenderer(comboBox: ComboBox<String>) {
        comboBox.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                val rawValue = value?.toString().orEmpty()
                val valueSize = TranslateGemmaSizingGuide.sizeForModelId(rawValue)
                if (valueSize == recommendedGemmaSize && rawValue.isNotBlank()) {
                    label.text = "$rawValue  ★ Recommended for ${recommendedGemmaSize.recommendedSystemRamGb}+ GB RAM"
                    if (!isSelected) {
                        label.foreground = JBColor(0x1F7A1F, 0x7ACF7A)
                    }
                    label.font = label.font.deriveFont(label.font.style or Font.BOLD)
                }
                return label
            }
        }
    }

    private fun runTestTranslation() {
        if (isTestTranslationRunning) {
            return
        }
        val sourceText = testSourceTextField.text.trim()
        if (sourceText.isBlank()) {
            testStatusLabel.foreground = JBColor.RED
            testStatusLabel.text = "Enter a test source text first."
            return
        }
        val targetLocale = selectedTestTargetLocaleTag()
        val snapshot = settingsSnapshotFromUi()

        isTestTranslationRunning = true
        setTestUiBusy(true)
        testStatusLabel.foreground = JBColor.foreground()
        testStatusLabel.text = "Testing translation in background..."
        testResultArea.text = ""

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                executeTestTranslation(snapshot, sourceText, targetLocale)
            }.getOrElse { error ->
                TestTranslationResult(
                    statusText = "Test failed: ${error.message ?: "unknown error"}",
                    outputText = "",
                    isError = true,
                )
            }

            SwingUtilities.invokeLater {
                testStatusLabel.foreground = if (result.isError) JBColor.RED else JBColor.foreground()
                testStatusLabel.text = result.statusText
                testResultArea.text = result.outputText
                isTestTranslationRunning = false
                setTestUiBusy(false)
            }
        }
    }

    private fun settingsSnapshotFromUi(): SettingsSnapshot {
        return SettingsSnapshot(
            providerType = providerCombo.selectedItem as? TranslationProviderType ?: TranslationProviderType.OLLAMA,
            sourceLocaleTag = selectedSourceLocaleTag(),
            ollamaBaseUrl = ollamaBaseUrlField.text.trim().ifBlank { "http://127.0.0.1:11434" },
            ollamaModel = selectedModel(ollamaModelCombo)
                .ifBlank { TranslationSettingsService.defaultOllamaModelForMachine() },
            ollamaRuntimeMode = ollamaRuntimeModeCombo.selectedItem as? OllamaRuntimeMode ?: OllamaRuntimeMode.AUTO,
            huggingFaceBaseUrl = huggingFaceBaseUrlField.text.trim().ifBlank { "https://api-inference.huggingface.co" },
            huggingFaceModel = selectedModel(huggingFaceModelCombo).ifBlank { "google/translategemma-4b-it" },
            huggingFaceToken = huggingFaceTokenField.text.trim(),
            removeAddedTrailingPeriod = removeAddedTrailingPeriodCheckBox.isSelected,
            timeoutSeconds = (timeoutSecondsSpinner.value as Number).toLong(),
            retryCount = (retryCountSpinner.value as Number).toInt(),
            temperature = (temperatureSpinner.value as Number).toFloat(),
        )
    }

    private fun executeTestTranslation(
        snapshot: SettingsSnapshot,
        sourceText: String,
        targetLocale: String,
    ): TestTranslationResult {
        val tempSettings = TranslationSettingsService().apply {
            providerType = snapshot.providerType
            ollamaBaseUrl = snapshot.ollamaBaseUrl
            ollamaModel = snapshot.ollamaModel
            ollamaRuntimeMode = snapshot.ollamaRuntimeMode
            huggingFaceBaseUrl = snapshot.huggingFaceBaseUrl
            huggingFaceModel = snapshot.huggingFaceModel
            huggingFaceToken = snapshot.huggingFaceToken
            removeAddedTrailingPeriodConfig = snapshot.removeAddedTrailingPeriod
            timeoutSecondsConfig = snapshot.timeoutSeconds
            retryCountConfig = snapshot.retryCount
            temperatureConfig = snapshot.temperature
        }

        val probeRow = StringEntryRow(
            id = "__test_translation__",
            key = "__test_translation__",
            baseText = sourceText,
            localizedText = null,
            proposedText = null,
            localeTag = targetLocale,
            localeQualifierRaw = targetLocale,
            localeFilePath = null,
            resourceRootPath = "",
            moduleName = null,
            originKind = ResourceKind.ANDROID_RES,
            status = RowStatus.MISSING,
        )
        val translatedRow = LocalAiTranslationService(tempSettings) { snapshot.sourceLocaleTag }
            .translateRows(
                rows = listOf(probeRow),
                onProgress = { _, _ -> },
            )
            .first()

        if (translatedRow.status == RowStatus.ERROR) {
            return TestTranslationResult(
                statusText = "Test failed: ${translatedRow.message ?: "translation error"}",
                outputText = "",
                isError = true,
            )
        }

        val output = translatedRow.proposedText?.trimEnd('\n', '\r').orEmpty()
        if (output.isBlank()) {
            return TestTranslationResult(
                statusText = "Test completed, but no translation text was returned.",
                outputText = "",
                isError = true,
            )
        }

        return TestTranslationResult(
            statusText = "Test succeeded",
            outputText = output,
            isError = false,
        )
    }

    private fun setTestUiBusy(isBusy: Boolean) {
        testTranslateButton.isEnabled = !isBusy
    }

    private fun scheduleOllamaModelAvailabilityCheck() {
        if (!::ollamaAvailabilityDebounceTimer.isInitialized) {
            return
        }
        if ((providerCombo.selectedItem as? TranslationProviderType) != TranslationProviderType.OLLAMA) {
            return
        }
        latestOllamaCheckStatus = null
        updateOllamaPullButtonEnabled()
        ollamaModelAvailabilityLabel.foreground = JBColor.foreground()
        ollamaModelAvailabilityLabel.text = "Checking Ollama model availability..."
        ollamaAvailabilityDebounceTimer.restart()
    }

    private fun runOllamaModelAvailabilityCheck() {
        if ((providerCombo.selectedItem as? TranslationProviderType) != TranslationProviderType.OLLAMA) {
            return
        }
        val baseUrl = ollamaBaseUrlField.text.trim().ifBlank { "http://127.0.0.1:11434" }
        val model = selectedModel(ollamaModelCombo)
        val timeoutSeconds = (timeoutSecondsSpinner.value as Number).toLong()
        val generation = ++ollamaAvailabilityCheckGeneration

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = OllamaModelAvailabilityChecker.check(
                baseUrl = baseUrl,
                requestedModel = model,
                timeoutSeconds = timeoutSeconds,
            )
            SwingUtilities.invokeLater {
                if (generation != ollamaAvailabilityCheckGeneration) {
                    return@invokeLater
                }
                latestOllamaCheckStatus = result.status
                when (result.status) {
                    OllamaModelCheckStatus.AVAILABLE -> ollamaModelAvailabilityLabel.foreground =
                        JBColor(0x1F7A1F, 0x7ACF7A)

                    OllamaModelCheckStatus.MISSING -> ollamaModelAvailabilityLabel.foreground =
                        JBColor(0xAD6800, 0xE6B450)

                    OllamaModelCheckStatus.UNREACHABLE,
                    OllamaModelCheckStatus.PARSE_ERROR,
                    OllamaModelCheckStatus.INVALID_INPUT -> ollamaModelAvailabilityLabel.foreground = JBColor.RED
                }
                ollamaModelAvailabilityLabel.text = result.message
                updateOllamaPullButtonEnabled()
            }
        }
    }

    private fun pullSelectedOllamaModel() {
        if (isOllamaPullRunning) {
            return
        }
        if ((providerCombo.selectedItem as? TranslationProviderType) != TranslationProviderType.OLLAMA) {
            return
        }
        val model = selectedModel(ollamaModelCombo).trim()
        if (model.isBlank()) {
            ollamaModelAvailabilityLabel.foreground = JBColor.RED
            ollamaModelAvailabilityLabel.text = "Enter an Ollama model before pulling."
            return
        }
        val baseUrl = ollamaBaseUrlField.text.trim().ifBlank { "http://127.0.0.1:11434" }
        val timeoutSeconds = (timeoutSecondsSpinner.value as Number).toLong()
        val generation = ++ollamaAvailabilityCheckGeneration

        isOllamaPullRunning = true
        updateOllamaPullButtonEnabled()
        ollamaModelAvailabilityLabel.foreground = JBColor.foreground()
        ollamaModelAvailabilityLabel.text = "Pulling '$model' from Ollama..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "LocalizePipe: Pull Ollama Model ($model)",
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Pulling $model from Ollama"
                indicator.text2 = "Connecting..."

                val result = OllamaModelAvailabilityChecker.pullWithProgress(
                    baseUrl = baseUrl,
                    requestedModel = model,
                    timeoutSeconds = timeoutSeconds,
                    onProgress = { update ->
                        if (indicator.isCanceled) {
                            return@pullWithProgress false
                        }
                        indicator.text = "Pulling $model from Ollama"
                        indicator.text2 = update.status
                        val fraction = update.fraction
                        if (fraction != null) {
                            indicator.isIndeterminate = false
                            indicator.fraction = fraction
                        } else {
                            indicator.isIndeterminate = true
                        }
                        !indicator.isCanceled
                    },
                )

                SwingUtilities.invokeLater {
                    if (generation != ollamaAvailabilityCheckGeneration) {
                        return@invokeLater
                    }
                    isOllamaPullRunning = false
                    when (result.status) {
                        OllamaModelPullStatus.PULLED -> {
                            ollamaModelAvailabilityLabel.foreground = JBColor(0x1F7A1F, 0x7ACF7A)
                            ollamaModelAvailabilityLabel.text = result.message
                            scheduleOllamaModelAvailabilityCheck()
                        }

                        OllamaModelPullStatus.FAILED -> {
                            ollamaModelAvailabilityLabel.foreground = JBColor(0xAD6800, 0xE6B450)
                            ollamaModelAvailabilityLabel.text = result.message
                        }

                        OllamaModelPullStatus.CANCELLED -> {
                            ollamaModelAvailabilityLabel.foreground = JBColor(0xAD6800, 0xE6B450)
                            ollamaModelAvailabilityLabel.text = "Pull cancelled."
                        }

                        OllamaModelPullStatus.UNREACHABLE,
                        OllamaModelPullStatus.INVALID_INPUT -> {
                            ollamaModelAvailabilityLabel.foreground = JBColor.RED
                            ollamaModelAvailabilityLabel.text = result.message
                        }
                    }
                    updateOllamaPullButtonEnabled()
                }
            }
        })
    }

    private fun updateOllamaPullButtonEnabled() {
        if (!::ollamaPullModelButton.isInitialized) {
            return
        }
        val isOllamaProvider =
            (providerCombo.selectedItem as? TranslationProviderType) == TranslationProviderType.OLLAMA
        val modelIsMissing = latestOllamaCheckStatus == OllamaModelCheckStatus.MISSING
        ollamaPullModelButton.isEnabled = isOllamaProvider && modelIsMissing && !isOllamaPullRunning
    }

    private fun addTextChangeListener(textComponent: JTextComponent, onChange: () -> Unit) {
        textComponent.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onChange()
            override fun removeUpdate(e: DocumentEvent?) = onChange()
            override fun changedUpdate(e: DocumentEvent?) = onChange()
        })
    }

    private fun updateTestSourceLabel() {
        if (!::testSourceTextLabel.isInitialized || !::sourceLocaleCombo.isInitialized) {
            return
        }
        val sourceTag = selectedSourceLocaleTag()
        val sourceLanguage = Locale.forLanguageTag(sourceTag)
            .getDisplayLanguage(Locale.ENGLISH)
            .replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.ENGLISH) else char.toString() }
            .ifBlank { "Source" }
        testSourceTextLabel.text = "$sourceLanguage test source text:"
    }

    private fun refreshSourceLocaleOptions() {
        if (!::sourceLocaleCombo.isInitialized) {
            return
        }
        val previousTag = selectedSourceLocaleTagOrNull()
        sourceLocaleOptionByTag.clear()
        val supportedTags = TranslateGemmaLanguageMapper.supportedLocaleTagsForUi()
        val orderedTags = if (supportedTags.contains(DEFAULT_SOURCE_LOCALE_TAG)) {
            listOf(DEFAULT_SOURCE_LOCALE_TAG) + supportedTags.filterNot { it == DEFAULT_SOURCE_LOCALE_TAG }
        } else {
            supportedTags
        }
        orderedTags.forEach { localeTag ->
            sourceLocaleOptionByTag[localeTag] = localeDisplayLabel(localeTag)
        }

        sourceLocaleCombo.removeAllItems()
        sourceLocaleOptionByTag.values.forEach { label ->
            sourceLocaleCombo.addItem(label)
        }

        selectSourceLocale(previousTag ?: DEFAULT_SOURCE_LOCALE_TAG)
    }

    private fun selectSourceLocale(localeTag: String) {
        val label = sourceLocaleOptionByTag[localeTag]
            ?: sourceLocaleOptionByTag[DEFAULT_SOURCE_LOCALE_TAG]
            ?: return
        sourceLocaleCombo.selectedItem = label
    }

    private fun selectedSourceLocaleTag(): String {
        return selectedSourceLocaleTagOrNull() ?: DEFAULT_SOURCE_LOCALE_TAG
    }

    private fun selectedSourceLocaleTagOrNull(): String? {
        val selectedLabel = sourceLocaleCombo.selectedItem?.toString()?.trim().orEmpty()
        return sourceLocaleOptionByTag.entries.firstOrNull { it.value == selectedLabel }?.key
    }

    private fun refreshTestTargetLocaleOptions() {
        if (!::testTargetLocaleCombo.isInitialized) {
            return
        }
        val previousTag = selectedTestTargetLocaleTagOrNull()
        testTargetLocaleOptionByTag.clear()
        TranslateGemmaLanguageMapper.supportedLocaleTagsForUi().forEach { localeTag ->
            testTargetLocaleOptionByTag[localeTag] = localeDisplayLabel(localeTag)
        }

        testTargetLocaleCombo.removeAllItems()
        testTargetLocaleOptionByTag.values.forEach { label ->
            testTargetLocaleCombo.addItem(label)
        }

        val targetToSelect = previousTag?.takeIf { testTargetLocaleOptionByTag.containsKey(it) }
            ?: defaultTestTargetLocaleTag()
        selectTestTargetLocale(targetToSelect)
    }

    private fun selectTestTargetLocale(localeTag: String) {
        val label = testTargetLocaleOptionByTag[localeTag]
            ?: testTargetLocaleOptionByTag[defaultTestTargetLocaleTag()]
            ?: return
        testTargetLocaleCombo.selectedItem = label
    }

    private fun selectedTestTargetLocaleTag(): String {
        return selectedTestTargetLocaleTagOrNull() ?: defaultTestTargetLocaleTag()
    }

    private fun selectedTestTargetLocaleTagOrNull(): String? {
        val selectedLabel = testTargetLocaleCombo.selectedItem?.toString()?.trim().orEmpty()
        return testTargetLocaleOptionByTag.entries.firstOrNull { it.value == selectedLabel }?.key
    }

    private fun defaultTestTargetLocaleTag(): String {
        val systemLanguage = Locale.getDefault().language.lowercase().ifBlank { "en" }
        val preferred = if (systemLanguage == "en") "de" else systemLanguage
        return if (TranslateGemmaLanguageMapper.toGemmaCode(preferred) != null) preferred else "de"
    }

    private fun localeDisplayLabel(localeTag: String): String {
        val locale = Locale.forLanguageTag(localeTag)
        val displayName = locale.getDisplayName(Locale.ENGLISH)
            .replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.ENGLISH) else char.toString() }
            .takeIf { it.isNotBlank() && it != localeTag }
            ?: localeTag
        return "$displayName ($localeTag)"
    }

    private data class SettingsSnapshot(
        val providerType: TranslationProviderType,
        val sourceLocaleTag: String,
        val ollamaBaseUrl: String,
        val ollamaModel: String,
        val ollamaRuntimeMode: OllamaRuntimeMode,
        val huggingFaceBaseUrl: String,
        val huggingFaceModel: String,
        val huggingFaceToken: String,
        val removeAddedTrailingPeriod: Boolean,
        val timeoutSeconds: Long,
        val retryCount: Int,
        val temperature: Float,
    )

    private data class TestTranslationResult(
        val statusText: String,
        val outputText: String,
        val isError: Boolean,
    )
}
