package de.aarondietz.localizepipe.settings

import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal object OllamaModelAvailabilityChecker {
    private val json = Json { ignoreUnknownKeys = true }

    internal fun check(baseUrl: String, requestedModel: String, timeoutSeconds: Long): OllamaModelCheckResult {
        val trimmedModel = requestedModel.trim()
        if (trimmedModel.isBlank()) {
            return OllamaModelCheckResult(
                status = OllamaModelCheckStatus.INVALID_INPUT,
                message = "Enter an Ollama model to check availability.",
            )
        }

        val normalizedBaseUrl = baseUrl.trim().ifBlank { "http://127.0.0.1:11434" }.trimEnd('/')
        val timeout = timeoutSeconds.coerceIn(3L, 30L)
        val response = executeRequest(
            url = "$normalizedBaseUrl/api/tags",
            timeoutSeconds = timeout,
            postJsonBody = null,
        ).getOrElse { error ->
            return OllamaModelCheckResult(
                status = OllamaModelCheckStatus.UNREACHABLE,
                message = "Could not reach Ollama at $normalizedBaseUrl (${error.message ?: "network error"}).",
            )
        }

        if (response.statusCode() !in 200..299) {
            return OllamaModelCheckResult(
                status = OllamaModelCheckStatus.UNREACHABLE,
                message = "Ollama check failed (HTTP ${response.statusCode()}) at $normalizedBaseUrl.",
            )
        }

        val parsedTags = parseTagsPayload(response.body())
        if (!parsedTags.validShape) {
            return OllamaModelCheckResult(
                status = OllamaModelCheckStatus.PARSE_ERROR,
                message = "Ollama returned an unexpected /api/tags response payload.",
            )
        }
        if (parsedTags.modelNames.isEmpty()) {
            return OllamaModelCheckResult(
                status = OllamaModelCheckStatus.MISSING,
                message = "No local Ollama models were found. Run `ollama pull $trimmedModel`.",
            )
        }

        return if (isModelAvailable(trimmedModel, parsedTags.modelNames)) {
            OllamaModelCheckResult(
                status = OllamaModelCheckStatus.AVAILABLE,
                message = "Model '$trimmedModel' is available locally in Ollama.",
            )
        } else {
            OllamaModelCheckResult(
                status = OllamaModelCheckStatus.MISSING,
                message = "Model '$trimmedModel' is not local. Run `ollama pull $trimmedModel`.",
            )
        }
    }

    internal fun pull(baseUrl: String, requestedModel: String, timeoutSeconds: Long): OllamaModelPullResult {
        return pullWithProgress(
            baseUrl = baseUrl,
            requestedModel = requestedModel,
            timeoutSeconds = timeoutSeconds,
            onProgress = { true },
        )
    }

    internal fun pullWithProgress(
        baseUrl: String,
        requestedModel: String,
        timeoutSeconds: Long,
        onProgress: (OllamaPullProgressUpdate) -> Boolean,
    ): OllamaModelPullResult {
        val trimmedModel = requestedModel.trim()
        if (trimmedModel.isBlank()) {
            return OllamaModelPullResult(
                status = OllamaModelPullStatus.INVALID_INPUT,
                message = "Enter an Ollama model before pulling.",
            )
        }

        val normalizedBaseUrl = baseUrl.trim().ifBlank { "http://127.0.0.1:11434" }.trimEnd('/')
        val timeout = timeoutSeconds.coerceIn(5L, 600L)
        val payload = buildJsonObject {
            put("name", trimmedModel)
            put("stream", JsonPrimitive(true))
        }.toString()

        val response = executeStreamingRequest(
            url = "$normalizedBaseUrl/api/pull",
            timeoutSeconds = timeout,
            postJsonBody = payload,
        ).getOrElse { error ->
            return OllamaModelPullResult(
                status = OllamaModelPullStatus.UNREACHABLE,
                message = "Could not reach Ollama at $normalizedBaseUrl (${error.message ?: "network error"}).",
            )
        }

        response.body().bufferedReader().use { reader ->
            if (response.statusCode() !in 200..299) {
                val remoteError = extractJsonErrorMessage(reader.readText())
                val suffix = remoteError?.let { ": $it" } ?: ""
                return OllamaModelPullResult(
                    status = OllamaModelPullStatus.FAILED,
                    message = "Ollama pull failed (HTTP ${response.statusCode()})$suffix",
                )
            }

            val streamed = streamPullProgress(reader, onProgress)
            if (streamed.status != OllamaModelPullStatus.PULLED) {
                return OllamaModelPullResult(
                    status = streamed.status,
                    message = streamed.message ?: "Ollama pull failed.",
                )
            }
            val finalStatus = streamed.lastStatus.takeIf { it.isNotBlank() } ?: "completed"
            return OllamaModelPullResult(
                status = OllamaModelPullStatus.PULLED,
                message = "Model '$trimmedModel' pull request completed successfully ($finalStatus).",
            )
        }
    }

    internal fun parseModelNames(rawJson: String): Set<String> {
        return parseTagsPayload(rawJson).modelNames
    }

    internal fun isModelAvailable(requestedModel: String, availableModels: Set<String>): Boolean {
        val requested = requestedModel.trim().lowercase()
        if (requested.isBlank()) {
            return false
        }
        val normalizedAvailable = availableModels.map { it.trim().lowercase() }.toSet()
        if (requested in normalizedAvailable) {
            return true
        }
        // If user enters model without tag, accept any local tagged variant.
        if (!requested.contains(':')) {
            return normalizedAvailable.any { it == requested || it.startsWith("$requested:") }
        }
        return false
    }

    private fun executeRequest(
        url: String,
        timeoutSeconds: Long,
        postJsonBody: String?,
    ): Result<HttpResponse<String>> {
        return runCatching {
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))

            if (postJsonBody == null) {
                requestBuilder.GET()
            } else {
                requestBuilder
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(postJsonBody))
            }

            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build()
                .send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        }
    }

    private fun parseTagsPayload(rawJson: String): ParsedTagsPayload {
        return runCatching {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val modelsArray = root["models"] as? JsonArray
                ?: return ParsedTagsPayload(validShape = false, modelNames = emptySet())

            val modelNames = modelsArray
                .mapNotNull { element -> element.jsonObject["name"]?.jsonPrimitive?.content?.trim() }
                .filter { it.isNotBlank() }
                .toSet()

            ParsedTagsPayload(validShape = true, modelNames = modelNames)
        }.getOrDefault(ParsedTagsPayload(validShape = false, modelNames = emptySet()))
    }

    private fun executeStreamingRequest(
        url: String,
        timeoutSeconds: Long,
        postJsonBody: String,
    ): Result<HttpResponse<java.io.InputStream>> {
        return runCatching {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(postJsonBody))
                .build()

            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds.coerceIn(3L, 30L)))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofInputStream())
        }
    }

    private fun streamPullProgress(
        reader: BufferedReader,
        onProgress: (OllamaPullProgressUpdate) -> Boolean,
    ): StreamPullResult {
        var lastStatus = ""
        for (line in reader.lineSequence().filter { it.isNotBlank() }) {
            val parsed = parsePullProgressLine(line) ?: continue
            if (parsed.errorMessage != null) {
                return StreamPullResult(
                    status = OllamaModelPullStatus.FAILED,
                    message = parsed.errorMessage,
                    lastStatus = lastStatus,
                )
            }
            lastStatus = parsed.status
            val shouldContinue = onProgress(
                OllamaPullProgressUpdate(
                    status = parsed.status.ifBlank { "Downloading..." },
                    fraction = parsed.fraction,
                ),
            )
            if (!shouldContinue) {
                return StreamPullResult(
                    status = OllamaModelPullStatus.CANCELLED,
                    message = "Pull cancelled.",
                    lastStatus = lastStatus,
                )
            }
        }
        return StreamPullResult(
            status = OllamaModelPullStatus.PULLED,
            message = null,
            lastStatus = lastStatus,
        )
    }

    internal fun parsePullProgressLine(rawLine: String): ParsedPullProgressLine? {
        return runCatching {
            val obj = json.parseToJsonElement(rawLine).jsonObject
            val errorMessage = obj["error"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            val status = obj["status"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val completed = obj["completed"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val total = obj["total"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val fraction = if (completed != null && total != null && total > 0L) {
                (completed.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
            } else {
                null
            }
            ParsedPullProgressLine(
                status = status,
                fraction = fraction,
                errorMessage = errorMessage,
            )
        }.getOrNull()
    }

    private fun extractJsonErrorMessage(body: String?): String? {
        if (body.isNullOrBlank()) {
            return null
        }
        return runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content?.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

internal data class OllamaModelCheckResult(
    val status: OllamaModelCheckStatus,
    val message: String,
)

internal enum class OllamaModelCheckStatus {
    AVAILABLE,
    MISSING,
    UNREACHABLE,
    PARSE_ERROR,
    INVALID_INPUT,
}

internal data class OllamaModelPullResult(
    val status: OllamaModelPullStatus,
    val message: String,
)

internal enum class OllamaModelPullStatus {
    PULLED,
    FAILED,
    UNREACHABLE,
    INVALID_INPUT,
    CANCELLED,
}

internal data class OllamaPullProgressUpdate(
    val status: String,
    val fraction: Double?,
)

private data class ParsedTagsPayload(
    val validShape: Boolean,
    val modelNames: Set<String>,
)

internal data class ParsedPullProgressLine(
    val status: String,
    val fraction: Double?,
    val errorMessage: String?,
)

private data class StreamPullResult(
    val status: OllamaModelPullStatus,
    val message: String?,
    val lastStatus: String,
)
