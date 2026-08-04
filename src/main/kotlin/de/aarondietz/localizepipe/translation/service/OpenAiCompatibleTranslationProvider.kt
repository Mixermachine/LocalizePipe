package de.aarondietz.localizepipe.translation.service

import com.intellij.openapi.diagnostic.Logger
import de.aarondietz.localizepipe.settings.Const
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

class OpenAiCompatibleTranslationProvider(
    private val settings: TranslationSettingsService,
) : TranslationProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override fun translate(
        request: TranslationRequest,
        onChunk: (partialText: String, tokenSpeed: Float?) -> Unit,
    ): ProviderResult {
        val model = settings.openAiCompatibleModel()
        val baseUrl = settings.openAiCompatibleBaseUrl()
        val apiKey = settings.openAiCompatibleApiKey().trim()
        val targetUrl = buildEndpointUrl(baseUrl)

        val payload = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", request.prompt)
                })
            })
            put("stream", JsonPrimitive(true))
            put("temperature", settings.temperature().toDouble())
        }

        return executeSsePost(
            url = targetUrl,
            body = payload.toString(),
            apiKey = apiKey.ifBlank { null },
            timeoutSeconds = request.timeoutSeconds,
            baseTextLength = request.baseTextLength,
            baseUrl = baseUrl,
            onChunk = onChunk,
        )
    }

    private fun executeSsePost(
        url: String,
        body: String,
        apiKey: String?,
        timeoutSeconds: Long,
        baseTextLength: Int,
        baseUrl: String,
        onChunk: (String, Float?) -> Unit,
    ): ProviderResult {
        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(minOf(30L, timeoutSeconds)))
                .build()

            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body))

            if (!apiKey.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $apiKey")
            }

            val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())

            if (response.statusCode() !in 200..299) {
                val errorBody = response.body().bufferedReader().use { it.readLine() ?: "" }
                val message = formatErrorMessage(response.statusCode(), errorBody)
                LOG.warn("OpenAI-compatible request failed: $message")
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
                                "OpenAI-compatible generation stalled: no token received for ${tokenIdleTimeoutSeconds}s (received $tokenCount tokens)."
                            } else {
                                "OpenAI-compatible request timed out after ${timeoutSeconds}s (text length: $baseTextLength chars). Increase request timeout in Settings -> LocalizePipe."
                            }
                            LOG.warn(msg)
                            return ProviderResult.Failure(msg)
                        }
                        if (line == null) break

                        val trimmedLine = line.trim()
                        if (trimmedLine.startsWith("data:")) {
                            val dataPayload = trimmedLine.substring(5).trim()
                            if (dataPayload == "[DONE]") {
                                break
                            }
                            if (dataPayload.isNotEmpty()) {
                                try {
                                    val chunkText = extractChunkContent(dataPayload)
                                    if (!chunkText.isNullOrEmpty()) {
                                        accumulatedText.append(chunkText)
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
                                    }
                                } catch (_: Throwable) {
                                    // ignore malformed line
                                }
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
                ProviderResult.Failure("OpenAI-compatible provider returned an empty response")
            } else {
                ProviderResult.Success(resultText)
            }
        } catch (error: HttpTimeoutException) {
            val msg = "OpenAI-compatible request timed out after ${timeoutSeconds}s (text length: $baseTextLength chars). Increase request timeout in Settings -> LocalizePipe."
            LOG.warn(msg, error)
            ProviderResult.Failure(msg)
        } catch (error: Throwable) {
            val detail = error.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
            LOG.warn("OpenAI-compatible request failed at $baseUrl$detail", error)
            ProviderResult.Failure("Could not reach OpenAI-compatible endpoint at $baseUrl$detail")
        }
    }

    internal companion object {
        private val LOG = Logger.getInstance(OpenAiCompatibleTranslationProvider::class.java)

        internal fun buildEndpointUrl(baseUrl: String): String {
            val cleaned = baseUrl.trim().ifBlank { Const.OPENAI_COMPATIBLE_BASE_URL }.trimEnd('/')
            return when {
                cleaned.endsWith("/v1/chat/completions") -> cleaned
                cleaned.endsWith("/v1") -> "$cleaned/chat/completions"
                else -> "$cleaned/v1/chat/completions"
            }
        }

        internal fun extractChunkContent(jsonString: String): String? {
            val element = Json { ignoreUnknownKeys = true }.parseToJsonElement(jsonString).jsonObject
            val choices = element["choices"]?.jsonArray ?: return null
            if (choices.isEmpty()) return null
            val choice = choices[0].jsonObject
            val delta = choice["delta"]?.jsonObject
            return delta?.get("content")?.jsonPrimitive?.contentOrNull
                ?: delta?.get("text")?.jsonPrimitive?.contentOrNull
                ?: choice["text"]?.jsonPrimitive?.contentOrNull
        }

        internal fun formatErrorMessage(statusCode: Int, body: String?): String {
            val remoteError = extractJsonErrorMessage(body)
            if (!remoteError.isNullOrBlank()) {
                return "OpenAI-compatible request failed (HTTP $statusCode): $remoteError"
            }
            return "OpenAI-compatible request failed (HTTP $statusCode)"
        }

        private fun extractJsonErrorMessage(body: String?): String? {
            if (body.isNullOrBlank()) {
                return null
            }
            return runCatching {
                val parsed = Json { ignoreUnknownKeys = true }.parseToJsonElement(body)
                parsed.jsonObject["error"]?.let { err ->
                    when (err) {
                        is JsonObject -> err["message"]?.jsonPrimitive?.content?.trim()
                        is JsonPrimitive -> err.content.trim()
                        else -> null
                    }
                }
            }.getOrNull()
        }
    }
}
