package de.aarondietz.localizepipe.settings

import com.intellij.openapi.components.*

enum class TranslationProviderType {
    OLLAMA,
    HUGGING_FACE,
}

enum class OllamaRuntimeMode(val label: String) {
    AUTO("Auto (Ollama decides)"),
    CPU_ONLY("CPU only"),
    GPU_PREFERRED("GPU preferred");

    override fun toString(): String = label
}

@State(
    name = "LocalizePipeTranslationSettings",
    storages = [Storage("localizepipe.xml")],
)
@Service(Service.Level.APP)
class TranslationSettingsService :
    SimplePersistentStateComponent<TranslationSettingsService.TranslationState>(TranslationState()) {
    companion object {
        private const val LEGACY_OLLAMA_DEFAULT_MODEL = "translategemma:4b"

        fun defaultOllamaModelForMachine(): String {
            val totalRamGb = TranslateGemmaSizingGuide.detectTotalSystemRamGb()
            val recommendedSize = TranslateGemmaSizingGuide.recommendedSize(totalRamGb)
            return TranslateGemmaSizingGuide.recommendedModelId(TranslationProviderType.OLLAMA, recommendedSize)
        }
    }

    class TranslationState : BaseState() {
        var providerType by enum(TranslationProviderType.OLLAMA)
        var sourceLocaleTag by string("en")

        // Ollama defaults
        var ollamaBaseUrl by string("http://127.0.0.1:11434")
        var ollamaModel by string()
        var ollamaModelManuallySelected by property(false)
        var ollamaRuntimeMode by enum(OllamaRuntimeMode.AUTO)

        // Hugging Face defaults
        var huggingFaceBaseUrl by string("https://api-inference.huggingface.co")
        var huggingFaceModel by string("google/translategemma-4b-it")
        var huggingFaceApiToken by string("")

        // Shared generation defaults
        var temperature by property(0.1f)
        var timeoutSeconds by property(45L)
        var retryCount by property(1)
        var removeAddedTrailingPeriod by property(true)
    }

    var providerType: TranslationProviderType
        get() = state.providerType
        set(value) {
            state.providerType = value
        }

    var sourceLocaleTag: String
        get() = state.sourceLocaleTag ?: "en"
        set(value) {
            state.sourceLocaleTag = value
        }

    var ollamaBaseUrl: String
        get() = state.ollamaBaseUrl ?: "http://127.0.0.1:11434"
        set(value) {
            state.ollamaBaseUrl = value
        }

    var ollamaModel: String
        get() {
            val storedModel = state.ollamaModel?.trim().orEmpty()
            if (storedModel.isBlank()) {
                return defaultOllamaModelForMachine()
            }
            if (!state.ollamaModelManuallySelected && storedModel == LEGACY_OLLAMA_DEFAULT_MODEL) {
                return defaultOllamaModelForMachine()
            }
            return storedModel
        }
        set(value) {
            state.ollamaModel = value
            state.ollamaModelManuallySelected = true
        }

    var ollamaRuntimeMode: OllamaRuntimeMode
        get() = state.ollamaRuntimeMode
        set(value) {
            state.ollamaRuntimeMode = value
        }

    var huggingFaceBaseUrl: String
        get() = state.huggingFaceBaseUrl ?: "https://api-inference.huggingface.co"
        set(value) {
            state.huggingFaceBaseUrl = value
        }

    var huggingFaceModel: String
        get() = state.huggingFaceModel ?: "google/translategemma-4b-it"
        set(value) {
            state.huggingFaceModel = value
        }

    var huggingFaceToken: String
        get() = state.huggingFaceApiToken ?: ""
        set(value) {
            state.huggingFaceApiToken = value
        }

    var timeoutSecondsConfig: Long
        get() = state.timeoutSeconds
        set(value) {
            state.timeoutSeconds = value
        }

    var retryCountConfig: Int
        get() = state.retryCount
        set(value) {
            state.retryCount = value
        }

    var temperatureConfig: Float
        get() = state.temperature
        set(value) {
            state.temperature = value
        }

    var removeAddedTrailingPeriodConfig: Boolean
        get() = state.removeAddedTrailingPeriod
        set(value) {
            state.removeAddedTrailingPeriod = value
        }

    fun activeModel(): String {
        return when (providerType) {
            TranslationProviderType.OLLAMA -> ollamaModel
            TranslationProviderType.HUGGING_FACE -> state.huggingFaceModel ?: "google/translategemma-4b-it"
        }
    }

    fun activeEndpoint(): String {
        return when (providerType) {
            TranslationProviderType.OLLAMA -> "${state.ollamaBaseUrl ?: "http://127.0.0.1:11434"}/api/generate"
            TranslationProviderType.HUGGING_FACE -> {
                val baseUrl = state.huggingFaceBaseUrl ?: "https://api-inference.huggingface.co"
                val model = state.huggingFaceModel ?: "google/translategemma-4b-it"
                "$baseUrl/models/$model"
            }
        }
    }

    fun toggleProvider() {
        providerType = when (providerType) {
            TranslationProviderType.OLLAMA -> TranslationProviderType.HUGGING_FACE
            TranslationProviderType.HUGGING_FACE -> TranslationProviderType.OLLAMA
        }
    }

    fun hasHuggingFaceToken(): Boolean {
        return !state.huggingFaceApiToken.isNullOrBlank()
    }

    fun sourceLocaleTag(): String {
        return sourceLocaleTag
    }

    fun ollamaBaseUrl(): String {
        return ollamaBaseUrl
    }

    fun ollamaModel(): String {
        return ollamaModel
    }

    fun ollamaRuntimeMode(): OllamaRuntimeMode {
        return ollamaRuntimeMode
    }

    fun huggingFaceBaseUrl(): String {
        return huggingFaceBaseUrl
    }

    fun huggingFaceModel(): String {
        return huggingFaceModel
    }

    fun huggingFaceToken(): String {
        return huggingFaceToken
    }

    fun requestTimeoutSeconds(): Long {
        return timeoutSecondsConfig
    }

    fun retryCount(): Int {
        return retryCountConfig
    }

    fun temperature(): Float {
        return temperatureConfig
    }

    fun removeAddedTrailingPeriod(): Boolean {
        return removeAddedTrailingPeriodConfig
    }
}
