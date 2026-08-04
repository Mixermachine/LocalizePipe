package de.aarondietz.localizepipe.settings

import com.intellij.openapi.components.*

enum class TranslationProviderType {
    OLLAMA,
    HUGGING_FACE,
    OPENAI_COMPATIBLE,
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
        var sourceLocaleTag by string(Const.SOURCE_LOCALE_TAG)

        // Ollama defaults
        var ollamaBaseUrl by string(Const.OLLAMA_BASE_URL)
        var ollamaModel by string()
        var ollamaModelManuallySelected by property(false)
        var ollamaRuntimeMode by enum(OllamaRuntimeMode.AUTO)

        // Hugging Face defaults
        var huggingFaceBaseUrl by string(Const.HUGGING_FACE_BASE_URL)
        var huggingFaceModel by string(Const.HUGGING_FACE_DEFAULT_MODEL)
        var huggingFaceApiToken by string("")

        // OpenAI-compatible defaults
        var openAiCompatibleBaseUrl by string(Const.OPENAI_COMPATIBLE_BASE_URL)
        var openAiCompatibleModel by string(Const.OPENAI_COMPATIBLE_DEFAULT_MODEL)
        var openAiCompatibleApiKey by string("")

        // Shared generation defaults
        var temperature by property(Const.TEMPERATURE)
        var timeoutSeconds by property(Const.TIMEOUT_SECONDS)
        var retryCount by property(Const.RETRY_COUNT)
        var removeAddedTrailingPeriod by property(Const.REMOVE_ADDED_TRAILING_PERIOD)
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
        get() = state.ollamaBaseUrl ?: Const.OLLAMA_BASE_URL
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
        get() = state.huggingFaceBaseUrl ?: Const.HUGGING_FACE_BASE_URL
        set(value) {
            state.huggingFaceBaseUrl = value
        }

    var huggingFaceModel: String
        get() = state.huggingFaceModel ?: Const.HUGGING_FACE_DEFAULT_MODEL
        set(value) {
            state.huggingFaceModel = value
        }

    var huggingFaceToken: String
        get() = state.huggingFaceApiToken ?: ""
        set(value) {
            state.huggingFaceApiToken = value
        }

    var openAiCompatibleBaseUrl: String
        get() = state.openAiCompatibleBaseUrl ?: Const.OPENAI_COMPATIBLE_BASE_URL
        set(value) {
            state.openAiCompatibleBaseUrl = value
        }

    var openAiCompatibleModel: String
        get() = state.openAiCompatibleModel?.takeIf { it.isNotBlank() } ?: Const.OPENAI_COMPATIBLE_DEFAULT_MODEL
        set(value) {
            state.openAiCompatibleModel = value
        }

    var openAiCompatibleApiKey: String
        get() = state.openAiCompatibleApiKey ?: ""
        set(value) {
            state.openAiCompatibleApiKey = value
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
            TranslationProviderType.HUGGING_FACE -> state.huggingFaceModel ?: Const.HUGGING_FACE_DEFAULT_MODEL
            TranslationProviderType.OPENAI_COMPATIBLE -> state.openAiCompatibleModel ?: Const.OPENAI_COMPATIBLE_DEFAULT_MODEL
        }
    }

    fun activeEndpoint(): String {
        return when (providerType) {
            TranslationProviderType.OLLAMA -> "${state.ollamaBaseUrl ?: Const.OLLAMA_BASE_URL}/api/generate"
            TranslationProviderType.HUGGING_FACE -> {
                val baseUrl = state.huggingFaceBaseUrl ?: Const.HUGGING_FACE_BASE_URL
                val model = state.huggingFaceModel ?: Const.HUGGING_FACE_DEFAULT_MODEL
                "$baseUrl/models/$model"
            }
            TranslationProviderType.OPENAI_COMPATIBLE -> {
                val baseUrl = (state.openAiCompatibleBaseUrl ?: Const.OPENAI_COMPATIBLE_BASE_URL).trimEnd('/')
                when {
                    baseUrl.endsWith("/v1/chat/completions") -> baseUrl
                    baseUrl.endsWith("/v1") -> "$baseUrl/chat/completions"
                    else -> "$baseUrl/v1/chat/completions"
                }
            }
        }
    }

    fun toggleProvider() {
        providerType = when (providerType) {
            TranslationProviderType.OLLAMA -> TranslationProviderType.HUGGING_FACE
            TranslationProviderType.HUGGING_FACE -> TranslationProviderType.OPENAI_COMPATIBLE
            TranslationProviderType.OPENAI_COMPATIBLE -> TranslationProviderType.OLLAMA
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

    fun openAiCompatibleBaseUrl(): String {
        return openAiCompatibleBaseUrl
    }

    fun openAiCompatibleModel(): String {
        return openAiCompatibleModel
    }

    fun openAiCompatibleApiKey(): String {
        return openAiCompatibleApiKey
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
