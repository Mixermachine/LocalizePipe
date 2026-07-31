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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class LocalAiTranslationService(
    private val settings: TranslationSettingsService,
    private val sourceLocaleTagProvider: () -> String = { settings.sourceLocaleTag() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun translateRows(
        rows: List<StringEntryRow>,
        onProgress: (translatedRows: List<StringEntryRow>, processedCount: Int, tokenSpeed: Float?) -> Unit = { _, _, _ -> },
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
                onProgress(mutableRows.toList(), index + 1, null)
                continue
            }

            val translatedRow = translateAndValidateRow(
                row = row,
                sourceLangCode = sourceLangCode,
                targetLangCode = targetLangCode,
                onChunk = { partialText, speed ->
                    mutableRows[index] = row.copy(proposedText = partialText)
                    onProgress(mutableRows.toList(), index, speed)
                },
            )
            mutableRows[index] = translatedRow

            if (translatedRow.status == RowStatus.ERROR && shouldAbortRemainingRows(translatedRow.message)) {
                LOG.warn("Aborting remaining rows due to fatal provider error: ${translatedRow.message}")
                for (remaining in index + 1 until rows.size) {
                    mutableRows[remaining] = rows[remaining].copy(
                        status = RowStatus.ERROR,
                        message = translatedRow.message,
                    )
                }
                onProgress(mutableRows.toList(), rows.size, null)
                break
            } else {
                onProgress(mutableRows.toList(), index + 1, null)
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
        onChunk: (String, Float?) -> Unit = { _, _ -> },
    ): StringEntryRow {
        var latestText: String? = null
        var latestValidationErrors: Set<ValidationError> = emptySet()
        var latestValidationDetail: String? = null

        repeat(2) { validationAttempt ->
            val translated = translateWithRetry(
                baseText = row.baseText,
                translationContext = row.translationContext,
                sourceLangCode = sourceLangCode,
                targetLangCode = targetLangCode,
                onChunk = onChunk,
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
            val normalizedTranslatedText = normalizeTranslationToSource(
                baseText = row.baseText,
                translatedText = translatedText,
                removeAddedTrailingPeriod = settings.removeAddedTrailingPeriod(),
            )

            latestText = normalizedTranslatedText
            val validation = TranslationOutputValidator.validate(
                baseText = row.baseText,
                translatedText = normalizedTranslatedText,
            )
            if (validation.isValid) {
                return row.copy(
                    proposedText = normalizedTranslatedText,
                    status = RowStatus.READY,
                    message = null,
                )
            }

            latestValidationErrors = validation.errors
            latestValidationDetail = validation.detailMessage
            if (validationAttempt == 0) {
                // Retry once when validation fails before giving up.
                return@repeat
            }
        }

        val detailSuffix = latestValidationDetail?.let { " ($it)" } ?: ""
        return row.copy(
            proposedText = latestText,
            status = RowStatus.ERROR,
            message = "Validation failed: ${latestValidationErrors.joinToString(", ")}$detailSuffix",
        )
    }

    private fun translateWithRetry(
        baseText: String,
        translationContext: String?,
        sourceLangCode: String,
        targetLangCode: String,
        onChunk: (String, Float?) -> Unit = { _, _ -> },
    ): TranslationOutcome {
        val attempts = (settings.retryCount() + 1).coerceAtLeast(1)
        var lastError: String? = null

        repeat(attempts) {
            val providerResult = when (settings.providerType) {
                TranslationProviderType.OLLAMA -> requestOllama(
                    baseText,
                    translationContext,
                    sourceLangCode,
                    targetLangCode,
                    onChunk = onChunk,
                )
                TranslationProviderType.HUGGING_FACE -> requestHuggingFace(
                    baseText,
                    translationContext,
                    sourceLangCode,
                    targetLangCode,
                )
            }

            when (providerResult) {
                is ProviderResult.Success -> return TranslationOutcome(text = providerResult.text, errorMessage = null)
                is ProviderResult.Failure -> lastError = providerResult.message
            }
        }

        return TranslationOutcome(text = null, errorMessage = lastError ?: "Translation failed")
    }

    private fun requestOllama(
        baseText: String,
        translationContext: String?,
        sourceLangCode: String,
        targetLangCode: String,
        onChunk: (String, Float?) -> Unit = { _, _ -> },
    ): ProviderResult {
        val model = settings.ollamaModel()
        val baseUrl = settings.ollamaBaseUrl().trimEnd('/')
        val baseTimeout = settings.requestTimeoutSeconds()
        val effectiveTimeoutSeconds = maxOf(baseTimeout, baseTimeout + (baseText.length / 10).toLong())

        val payload = buildJsonObject {
            put("model", model)
            put("stream", JsonPrimitive(true))
            put("prompt", buildPrompt(baseText, sourceLangCode, targetLangCode, translationContext))
            put(
                "options",
                buildOllamaOptions(
                    temperature = settings.temperature(),
                    runtimeMode = settings.ollamaRuntimeMode(),
                ),
            )
        }

        return executeOllamaStreamPost(
            url = "$baseUrl/api/generate",
            body = payload.toString(),
            timeoutSeconds = effectiveTimeoutSeconds,
            baseTextLength = baseText.length,
            model = model,
            baseUrl = baseUrl,
            onChunk = onChunk,
        )
    }

    private fun executeOllamaStreamPost(
        url: String,
        body: String,
        timeoutSeconds: Long,
        baseTextLength: Int,
        model: String,
        baseUrl: String,
        onChunk: (String, Float?) -> Unit = { _, _ -> },
    ): ProviderResult {
        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(minOf(30L, timeoutSeconds)))
                .build()

            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

            if (response.statusCode() !in 200..299) {
                val errorBody = response.body().bufferedReader().use { it.readLine() ?: "" }
                val message = formatOllamaFailureMessage(model, response.statusCode(), errorBody)
                LOG.warn("Ollama request failed: $message")
                return ProviderResult.Failure(message)
            }

            val accumulatedText = StringBuilder()
            var tokenCount = 0
            var firstTokenReceived = false
            var firstTokenTimeMs = 0L
            val tokenIdleTimeoutSeconds = 30L

            val startTime = System.currentTimeMillis()
            val timerExecutor = Executors.newSingleThreadScheduledExecutor()
            val timerTask = timerExecutor.scheduleAtFixedRate({
                if (!firstTokenReceived) {
                    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000
                    onChunk("Startup ${elapsedSec}s/${timeoutSeconds}s", null)
                }
            }, 0, 1, TimeUnit.SECONDS)

            val executor = Executors.newSingleThreadExecutor()
            try {
                BufferedReader(InputStreamReader(response.body(), Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val future = executor.submit(Callable { reader.readLine() })
                        val currentTimeout = if (firstTokenReceived) tokenIdleTimeoutSeconds else timeoutSeconds
                        val line = try {
                            future.get(currentTimeout, TimeUnit.SECONDS)
                        } catch (e: TimeoutException) {
                            future.cancel(true)
                            val msg = if (firstTokenReceived) {
                                "Ollama generation stalled: no token received for ${tokenIdleTimeoutSeconds}s (received $tokenCount tokens)."
                            } else {
                                "Ollama request timed out after ${timeoutSeconds}s (text length: $baseTextLength chars). Increase request timeout in Settings -> LocalizePipe."
                            }
                            LOG.warn(msg)
                            return ProviderResult.Failure(msg)
                        }
                        if (line == null) break

                        if (line.isNotBlank()) {
                            try {
                                val element = json.parseToJsonElement(line).jsonObject
                                val chunk = element["response"]?.jsonPrimitive?.content
                                val done = element["done"]?.jsonPrimitive?.booleanOrNull ?: false
                                val evalCount = element["eval_count"]?.jsonPrimitive?.longOrNull
                                val evalDuration = element["eval_duration"]?.jsonPrimitive?.longOrNull

                                if (!chunk.isNullOrEmpty()) {
                                    accumulatedText.append(chunk)
                                    tokenCount++
                                    val now = System.currentTimeMillis()
                                    if (!firstTokenReceived) {
                                        firstTokenReceived = true
                                        firstTokenTimeMs = now
                                        timerTask.cancel(true)
                                    }
                                    val elapsedMs = now - firstTokenTimeMs
                                    val speed = if (elapsedMs > 50) {
                                        (tokenCount.toDouble() / (elapsedMs / 1000.0)).toFloat()
                                    } else null

                                    onChunk(accumulatedText.toString(), speed)
                                } else if (done && evalCount != null && evalDuration != null && evalDuration > 0) {
                                    val speed = (evalCount.toDouble() / (evalDuration.toDouble() / 1e9)).toFloat()
                                    onChunk(accumulatedText.toString(), speed)
                                }
                            } catch (_: Throwable) {
                                // ignore malformed line
                            }
                        }
                    }
                }
            } finally {
                timerTask.cancel(true)
                timerExecutor.shutdownNow()
                executor.shutdownNow()
            }

            val resultText = accumulatedText.toString().trimEnd('\n', '\r')
            if (resultText.isBlank()) {
                ProviderResult.Failure("Ollama returned an empty response")
            } else {
                ProviderResult.Success(resultText)
            }
        } catch (error: HttpTimeoutException) {
            val msg = "Ollama request timed out after ${timeoutSeconds}s (text length: $baseTextLength chars). Increase request timeout in Settings -> LocalizePipe."
            LOG.warn(msg, error)
            ProviderResult.Failure(msg)
        } catch (error: Throwable) {
            val detail = error.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
            LOG.warn("Ollama request failed at $baseUrl$detail", error)
            ProviderResult.Failure("Could not reach Ollama at $baseUrl$detail")
        }
    }

    private fun requestHuggingFace(
        baseText: String,
        translationContext: String?,
        sourceLangCode: String,
        targetLangCode: String,
        onChunk: (String) -> Unit = {},
    ): ProviderResult {
        val baseTimeout = settings.requestTimeoutSeconds()
        val effectiveTimeoutSeconds = maxOf(baseTimeout, baseTimeout + (baseText.length / 10).toLong())
        val startTime = System.currentTimeMillis()
        val timerExecutor = Executors.newSingleThreadScheduledExecutor()
        val timerTask = timerExecutor.scheduleAtFixedRate({
            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000
            onChunk("Processing ${elapsedSec}s/${effectiveTimeoutSeconds}s")
        }, 0, 1, TimeUnit.SECONDS)

        val payload = buildJsonObject {
            put("inputs", buildPrompt(baseText, sourceLangCode, targetLangCode, translationContext))
            put("parameters", buildJsonObject {
                put("return_full_text", JsonPrimitive(false))
            })
        }

        try {
            val response = executePostJson(
                url = "${settings.huggingFaceBaseUrl().trimEnd('/')}/models/${settings.huggingFaceModel()}",
                body = payload.toString(),
                bearerToken = settings.huggingFaceToken().ifBlank { null },
                timeoutSeconds = effectiveTimeoutSeconds,
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
        } finally {
            timerTask.cancel(true)
            timerExecutor.shutdownNow()
        }
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

    private fun executePostJson(
        url: String,
        body: String,
        bearerToken: String?,
        timeoutSeconds: Long = settings.requestTimeoutSeconds(),
    ): HttpCallResult {
        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build()

            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
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
        internal fun buildPrompt(
            baseText: String,
            sourceLangCode: String,
            targetLangCode: String,
            translationContext: String? = null,
        ): String {
            val normalizedContext = translationContext?.trim()?.takeIf { it.isNotEmpty() }
            return buildString {
                appendLine("Translate from $sourceLangCode to $targetLangCode.")
                appendLine("Return only translated text.")
                appendLine("Preserve placeholders exactly (e.g. %1\$s, %d, {name}).")
                appendLine("Preserve XML tags exactly.")
                if (normalizedContext != null) {
                    appendLine("Use additional context only to disambiguate the translation. Do not mention it in the output.")
                    appendLine("Additional context: $normalizedContext")
                }
                append("Text: $baseText")
            }
        }

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

        internal fun alignTrailingPeriodToSource(
            baseText: String,
            translatedText: String,
            removeAddedTrailingPeriod: Boolean,
        ): String {
            if (!removeAddedTrailingPeriod) {
                return translatedText
            }
            val baseTrimmed = baseText.trimEnd()
            if (baseTrimmed.endsWith(".")) {
                return translatedText
            }

            val translatedTrimmed = translatedText.trimEnd()
            if (!translatedTrimmed.endsWith(".") || translatedTrimmed.endsWith("...")) {
                return translatedText
            }

            val trailingWhitespace = translatedText.substring(translatedTrimmed.length)
            return translatedTrimmed.dropLast(1) + trailingWhitespace
        }

        internal fun normalizeEdgeEllipsisToSource(baseText: String, translatedText: String): String {
            var normalizedText = translatedText
            val baseTrimmed = baseText.trim()
            if (baseTrimmed.startsWith("...") || baseTrimmed.endsWith("...")) {
                return translatedText
            }

            if (baseTrimmed.startsWith("…")) {
                val leadingWhitespaceLength = normalizedText.indexOfFirst { !it.isWhitespace() }
                    .let { if (it == -1) normalizedText.length else it }
                val content = normalizedText.substring(leadingWhitespaceLength)
                if (content.startsWith("...")) {
                    normalizedText = normalizedText.substring(0, leadingWhitespaceLength) + "…" + content.drop(3)
                }
            }

            if (baseTrimmed.endsWith("…")) {
                val translatedTrimmedEnd = normalizedText.trimEnd()
                if (translatedTrimmedEnd.endsWith("...")) {
                    val trailingWhitespace = normalizedText.substring(translatedTrimmedEnd.length)
                    normalizedText = translatedTrimmedEnd.dropLast(3) + "…" + trailingWhitespace
                }
            }

            return normalizedText
        }

        internal fun normalizeTranslationToSource(
            baseText: String,
            translatedText: String,
            removeAddedTrailingPeriod: Boolean,
        ): String {
            val periodAligned = alignTrailingPeriodToSource(
                baseText = baseText,
                translatedText = translatedText,
                removeAddedTrailingPeriod = removeAddedTrailingPeriod,
            )
            return normalizeEdgeEllipsisToSource(
                baseText = baseText,
                translatedText = periodAligned,
            )
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
