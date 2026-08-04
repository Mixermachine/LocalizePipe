package de.aarondietz.localizepipe.translation.service

sealed interface ProviderResult {
    data class Success(val text: String) : ProviderResult
    data class Failure(val message: String) : ProviderResult
}

data class TranslationRequest(
    val prompt: String,
    val baseTextLength: Int,
    val timeoutSeconds: Long,
)

interface TranslationProvider {
    fun translate(
        request: TranslationRequest,
        onChunk: (partialText: String, tokenSpeed: Float?) -> Unit,
    ): ProviderResult
}
