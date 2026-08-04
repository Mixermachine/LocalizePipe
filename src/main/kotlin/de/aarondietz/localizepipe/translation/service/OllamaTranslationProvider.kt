package de.aarondietz.localizepipe.translation.service

import com.intellij.openapi.diagnostic.Logger
import de.aarondietz.localizepipe.settings.OllamaRuntimeMode
import de.aarondietz.localizepipe.settings.TranslationSettingsService
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

class OllamaTranslationProvider(
    private val settings: TranslationSettingsService,
) : TranslationProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override fun translate(
        request: TranslationRequest,
        onChunk: (partialText: String, tokenSpeed: Float?) -> Unit,
    ): ProviderResult {
        val model = settings.ollamaModel()
        val baseUrl = settings.ollamaBaseUrl().trimEnd('/')

        val payload = buildJsonObject {
            put("model", model)
            put("stream", JsonPrimitive(true))
            put("prompt", request.prompt)
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
            timeoutSeconds = request.timeoutSeconds,
            baseTextLength = request.baseTextLength,
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
        onChunk: (String, Float?) -> Unit,
    ): ProviderResult {
        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(minOf(30L, timeoutSeconds)))
                .build()

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

            val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())

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
                        } catch (_: TimeoutException) {
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

    internal companion object {
        private val LOG = Logger.getInstance(OllamaTranslationProvider::class.java)

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
