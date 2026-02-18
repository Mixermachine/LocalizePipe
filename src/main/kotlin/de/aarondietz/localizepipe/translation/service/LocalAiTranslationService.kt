package de.aarondietz.localizepipe.translation.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import de.aarondietz.localizepipe.model.RowStatus
import de.aarondietz.localizepipe.model.StringEntryRow
import de.aarondietz.localizepipe.settings.OllamaRuntimeMode
import de.aarondietz.localizepipe.settings.TranslationProviderType
import de.aarondietz.localizepipe.settings.TranslationSettingsService
import de.aarondietz.localizepipe.translation.OllamaGenerateResponseParser
import de.aarondietz.localizepipe.translation.TranslateGemmaLanguageMapper
import de.aarondietz.localizepipe.translation.TranslationOutputValidator
import de.aarondietz.localizepipe.translation.ValidationError
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class LocalAiTranslationService(
    private val settings: TranslationSettingsService,
    private val sourceLocaleTagProvider: () -> String = { settings.sourceLocaleTag() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun translateRows(
        rows: List<StringEntryRow>,
        onProgress: (translatedRows: List<StringEntryRow>, processedCount: Int) -> Unit,
        shouldCancel: () -> Boolean = { false },
    ): List<StringEntryRow> {
        checkCanceled(shouldCancel)
        val sourceLangCode = TranslateGemmaLanguageMapper.toGemmaCode(sourceLocaleTagProvider()) ?: "eng_Latn"
        val mutableRows = rows.toMutableList()
        LOG.info(
            "Translate request started (rows=${rows.size}, provider=${settings.providerType}, model=${settings.activeModel()}, source=$sourceLangCode)",
        )

        for ((index, row) in rows.withIndex()) {
            checkCanceled(shouldCancel)
            val targetLangCode = TranslateGemmaLanguageMapper.toGemmaCode(row.localeTag)
            if (targetLangCode == null) {
                LOG.warn("Unsupported locale mapping for target locale '${row.localeTag}'")
                mutableRows[index] = row.copy(
                    status = RowStatus.ERROR,
                    message = "Unsupported locale mapping for ${row.localeTag}",
                )
                onProgress(mutableRows.toList(), index + 1)
                continue
            }

            val translatedRow = translateAndValidateRow(row, sourceLangCode, targetLangCode)
            mutableRows[index] = translatedRow

            if (translatedRow.status == RowStatus.ERROR && shouldAbortRemainingRows(translatedRow.message)) {
                LOG.warn("Aborting remaining rows due to fatal provider error: ${translatedRow.message}")
                for (remaining in index + 1 until rows.size) {
                    mutableRows[remaining] = rows[remaining].copy(
                        status = RowStatus.ERROR,
                        message = translatedRow.message,
                    )
                }
                onProgress(mutableRows.toList(), rows.size)
                break
            } else {
                onProgress(mutableRows.toList(), index + 1)
            }
        }

        return mutableRows
    }

    private fun checkCanceled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) {
            throw ProcessCanceledException()
        }
    }

    private fun translateAndValidateRow(
        row: StringEntryRow,
        sourceLangCode: String,
        targetLangCode: String,
    ): StringEntryRow {
        var latestText: String? = null
        var latestValidationErrors: Set<ValidationError> = emptySet()

        repeat(2) { validationAttempt ->
            val translated = translateWithRetry(
                baseText = row.baseText,
                sourceLangCode = sourceLangCode,
                targetLangCode = targetLangCode,
            )

            if (translated.errorMessage != null) {
                return row.copy(
                    status = RowStatus.ERROR,
                    message = translated.errorMessage,
                )
            }

            val translatedText = translated.text
            if (translatedText == null) {
                return row.copy(
                    status = RowStatus.ERROR,
                    message = "Unknown translation error",
                )
            }

            latestText = translatedText
            val validation = TranslationOutputValidator.validate(
                baseText = row.baseText,
                translatedText = translatedText,
            )
            if (validation.isValid) {
                return row.copy(
                    proposedText = translatedText,
                    status = RowStatus.READY,
                    message = null,
                )
            }

            latestValidationErrors = validation.errors
            if (validationAttempt == 0) {
                // Retry once when validation fails before giving up.
                return@repeat
            }
        }

        return row.copy(
            proposedText = latestText,
            status = RowStatus.ERROR,
            message = "Validation failed: ${latestValidationErrors.joinToString(", ")}",
        )
    }

    private fun translateWithRetry(
        baseText: String,
        sourceLangCode: String,
        targetLangCode: String,
    ): TranslationOutcome {
        val attempts = (settings.retryCount() + 1).coerceAtLeast(1)
        var lastError: String? = null

        repeat(attempts) {
            val providerResult = when (settings.providerType) {
                TranslationProviderType.OLLAMA -> requestOllama(baseText, sourceLangCode, targetLangCode)
                TranslationProviderType.HUGGING_FACE -> requestHuggingFace(baseText, sourceLangCode, targetLangCode)
            }

            when (providerResult) {
                is ProviderResult.Success -> return TranslationOutcome(text = providerResult.text, errorMessage = null)
                is ProviderResult.Failure -> lastError = providerResult.message
            }
        }

        return TranslationOutcome(text = null, errorMessage = lastError ?: "Translation failed")
    }

    private fun requestOllama(baseText: String, sourceLangCode: String, targetLangCode: String): ProviderResult {
        val model = settings.ollamaModel()
        val baseUrl = settings.ollamaBaseUrl().trimEnd('/')
        val payload = buildJsonObject {
            put("model", model)
            put("stream", JsonPrimitive(false))
            put("prompt", buildPrompt(baseText, sourceLangCode, targetLangCode))
            put(
                "options",
                buildOllamaOptions(
                    temperature = settings.temperature(),
                    runtimeMode = settings.ollamaRuntimeMode(),
                ),
            )
        }

        val response = executePostJson(
            url = "$baseUrl/api/generate",
            body = payload.toString(),
            bearerToken = null,
        )

        if (response.statusCode == null) {
            val detail = response.errorMessage?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
            LOG.warn("Ollama is unreachable at $baseUrl$detail")
            return ProviderResult.Failure("Could not reach Ollama at $baseUrl$detail")
        }
        if (response.statusCode !in 200..299) {
            val message = formatOllamaFailureMessage(model, response.statusCode, response.body)
            LOG.warn("Ollama request failed: $message")
            return ProviderResult.Failure(message)
        }
        val responseBody = response.body ?: return ProviderResult.Failure("Ollama returned an empty response body")

        return try {
            ProviderResult.Success(OllamaGenerateResponseParser.extractResponseText(responseBody))
        } catch (error: Throwable) {
            LOG.warn("Failed to parse Ollama response", error)
            ProviderResult.Failure("Failed to parse Ollama response: ${error.message}")
        }
    }

    private fun requestHuggingFace(baseText: String, sourceLangCode: String, targetLangCode: String): ProviderResult {
        val payload = buildJsonObject {
            put("inputs", buildPrompt(baseText, sourceLangCode, targetLangCode))
            put("parameters", buildJsonObject {
                put("return_full_text", JsonPrimitive(false))
            })
        }

        val response = executePostJson(
            url = "${settings.huggingFaceBaseUrl().trimEnd('/')}/models/${settings.huggingFaceModel()}",
            body = payload.toString(),
            bearerToken = settings.huggingFaceToken().ifBlank { null },
        )

        if (response.statusCode == null) {
            val detail = response.errorMessage?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
            LOG.warn("Hugging Face is unreachable$detail")
            return ProviderResult.Failure("Could not reach Hugging Face: $detail".removeSuffix(": "))
        }
        val responseBody =
            response.body ?: return ProviderResult.Failure("Hugging Face returned an empty response body")
        if (response.statusCode !in 200..299) {
            LOG.warn("Hugging Face request failed with status ${response.statusCode}")
            return parseHuggingFaceResponse(responseBody)
        }

        return parseHuggingFaceResponse(responseBody)
    }

    private fun parseHuggingFaceResponse(rawResponse: String): ProviderResult {
        return try {
            val element = json.parseToJsonElement(rawResponse)
            when {
                element is JsonArray && element.isNotEmpty() -> {
                    val first = element.firstOrNull()?.jsonObject
                    val text = first?.get("generated_text")?.jsonPrimitive?.content
                    if (text.isNullOrBlank()) {
                        ProviderResult.Failure("Unexpected Hugging Face array response")
                    } else {
                        ProviderResult.Success(text)
                    }
                }

                element is JsonObject -> {
                    val error = element["error"]?.jsonPrimitive?.content
                    if (!error.isNullOrBlank()) {
                        ProviderResult.Failure(error)
                    } else {
                        val generated = element["generated_text"]?.jsonPrimitive?.content
                            ?: element["translation_text"]?.jsonPrimitive?.content
                        if (generated.isNullOrBlank()) {
                            ProviderResult.Failure("Unexpected Hugging Face response format")
                        } else {
                            ProviderResult.Success(generated)
                        }
                    }
                }

                else -> ProviderResult.Failure("Unexpected Hugging Face response type")
            }
        } catch (error: Throwable) {
            LOG.warn("Failed to parse Hugging Face response", error)
            ProviderResult.Failure("Failed to parse Hugging Face response: ${error.message}")
        }
    }

    private fun executePostJson(url: String, body: String, bearerToken: String?): HttpCallResult {
        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(settings.requestTimeoutSeconds()))
                .build()

            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(settings.requestTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(body))

            if (!bearerToken.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $bearerToken")
            }

            val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            HttpCallResult(
                statusCode = response.statusCode(),
                body = response.body(),
                errorMessage = null,
            )
        } catch (error: Throwable) {
            HttpCallResult(
                statusCode = null,
                body = null,
                errorMessage = error.message,
            )
        }
    }

    private fun shouldAbortRemainingRows(message: String?): Boolean {
        if (message.isNullOrBlank()) {
            return false
        }
        return message.contains("Run `ollama pull", ignoreCase = true)
    }

    private fun buildPrompt(baseText: String, sourceLangCode: String, targetLangCode: String): String {
        return buildString {
            appendLine("Translate from $sourceLangCode to $targetLangCode.")
            appendLine("Return only translated text.")
            appendLine("Preserve placeholders exactly (e.g. %1\$s, %d, {name}).")
            appendLine("Preserve XML tags exactly.")
            append("Text: $baseText")
        }
    }

    private sealed interface ProviderResult {
        data class Success(val text: String) : ProviderResult
        data class Failure(val message: String) : ProviderResult
    }

    private data class HttpCallResult(
        val statusCode: Int?,
        val body: String?,
        val errorMessage: String?,
    )

    private data class TranslationOutcome(
        val text: String?,
        val errorMessage: String?,
    )

    internal companion object {
        internal fun buildOllamaOptions(temperature: Float, runtimeMode: OllamaRuntimeMode): JsonObject {
            return buildJsonObject {
                put("temperature", temperature.toDouble())
                when (runtimeMode) {
                    OllamaRuntimeMode.AUTO -> Unit
                    OllamaRuntimeMode.CPU_ONLY -> put("num_gpu", 0)
                    OllamaRuntimeMode.GPU_PREFERRED -> put("num_gpu", 999)
                }
            }
        }

        internal fun formatOllamaFailureMessage(model: String, statusCode: Int, body: String?): String {
            val remoteError = extractJsonErrorMessage(body)
            val normalized = remoteError?.lowercase().orEmpty()
            val looksLikeMissingModel = statusCode == 404 ||
                    (normalized.contains("model") && normalized.contains("not found")) ||
                    normalized.contains("try pulling it first")

            if (looksLikeMissingModel) {
                val suffix = remoteError?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                return "Ollama model '$model' is not available locally. Run `ollama pull $model` and retry$suffix"
            }

            if (!remoteError.isNullOrBlank()) {
                return "Ollama request failed (HTTP $statusCode): $remoteError"
            }
            return "Ollama request failed (HTTP $statusCode)"
        }

        private fun extractJsonErrorMessage(body: String?): String? {
            if (body.isNullOrBlank()) {
                return null
            }
            return runCatching {
                val parsed = Json.parseToJsonElement(body)
                parsed.jsonObject["error"]?.jsonPrimitive?.content?.trim()
            }.getOrNull()
        }
    }
}

private val LOG = Logger.getInstance(LocalAiTranslationService::class.java)
