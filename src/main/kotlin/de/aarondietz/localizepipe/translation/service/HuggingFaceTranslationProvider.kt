package de.aarondietz.localizepipe.translation.service

import com.intellij.openapi.diagnostic.Logger
import de.aarondietz.localizepipe.settings.TranslationSettingsService
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HuggingFaceTranslationProvider(
    private val settings: TranslationSettingsService,
) : TranslationProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override fun translate(
        request: TranslationRequest,
        onChunk: (partialText: String, tokenSpeed: Float?) -> Unit,
    ): ProviderResult {
        val startTime = System.currentTimeMillis()
        val timerExecutor = Executors.newSingleThreadScheduledExecutor()
        val timerTask = timerExecutor.scheduleAtFixedRate({
            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000
            onChunk("Processing ${elapsedSec}s/${request.timeoutSeconds}s", null)
        }, 0, 1, TimeUnit.SECONDS)

        val payload = buildJsonObject {
            put("inputs", request.prompt)
            put("parameters", buildJsonObject {
                put("return_full_text", JsonPrimitive(false))
            })
        }

        try {
            val response = executePostJson(
                url = "${settings.huggingFaceBaseUrl().trimEnd('/')}/models/${settings.huggingFaceModel()}",
                body = payload.toString(),
                bearerToken = settings.huggingFaceToken().ifBlank { null },
                timeoutSeconds = request.timeoutSeconds,
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
        timeoutSeconds: Long,
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

    private data class HttpCallResult(
        val statusCode: Int?,
        val body: String?,
        val errorMessage: String?,
    )

    private companion object {
        private val LOG = Logger.getInstance(HuggingFaceTranslationProvider::class.java)
    }
}
