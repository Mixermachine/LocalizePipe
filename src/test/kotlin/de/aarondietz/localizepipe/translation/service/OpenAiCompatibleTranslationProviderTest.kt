package de.aarondietz.localizepipe.translation.service

import com.sun.net.httpserver.HttpServer
import de.aarondietz.localizepipe.settings.TranslationProviderType
import de.aarondietz.localizepipe.settings.TranslationSettingsService
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class OpenAiCompatibleTranslationProviderTest {

    @Test
    fun buildsEndpointUrlCorrectly() {
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            OpenAiCompatibleTranslationProvider.buildEndpointUrl("http://127.0.0.1:8080"),
        )
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            OpenAiCompatibleTranslationProvider.buildEndpointUrl("http://127.0.0.1:8080/"),
        )
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            OpenAiCompatibleTranslationProvider.buildEndpointUrl("http://127.0.0.1:8080/v1"),
        )
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            OpenAiCompatibleTranslationProvider.buildEndpointUrl("http://127.0.0.1:8080/v1/chat/completions"),
        )
    }

    @Test
    fun extractsChunkContentFromStandardSseDelta() {
        val sseJson = """{"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"Hallo"},"finish_reason":null}]}"""
        val chunk = OpenAiCompatibleTranslationProvider.extractChunkContent(sseJson)

        assertEquals("Hallo", chunk)
    }

    @Test
    fun extractsChunkContentFromAlternativeDeltaText() {
        val sseJson = """{"id":"chatcmpl-1","choices":[{"index":0,"delta":{"text":"Welt"}}]}"""
        val chunk = OpenAiCompatibleTranslationProvider.extractChunkContent(sseJson)

        assertEquals("Welt", chunk)
    }

    @Test
    fun extractsChunkContentFromChoiceTextFallback() {
        val sseJson = """{"id":"chatcmpl-1","choices":[{"text":"!"}]}"""
        val chunk = OpenAiCompatibleTranslationProvider.extractChunkContent(sseJson)

        assertEquals("!", chunk)
    }

    @Test
    fun returnsNullForEmptyChoicesOrNoContent() {
        val sseJson = """{"id":"chatcmpl-1","choices":[{"index":0,"delta":{}}]}"""
        val chunk = OpenAiCompatibleTranslationProvider.extractChunkContent(sseJson)

        assertNull(chunk)
    }

    @Test
    fun formatsErrorMessageWithRemoteMessage() {
        val errorJson = """{"error":{"message":"Invalid API Key","type":"invalid_request_error"}}"""
        val msg = OpenAiCompatibleTranslationProvider.formatErrorMessage(401, errorJson)

        assertEquals("OpenAI-compatible request failed (HTTP 401): Invalid API Key", msg)
    }

    @Test
    fun formatsErrorMessageWithoutBody() {
        val msg = OpenAiCompatibleTranslationProvider.formatErrorMessage(503, null)

        assertEquals("OpenAI-compatible request failed (HTTP 503)", msg)
    }

    @Test
    fun streamsResponseFromLocalMockHttpServer() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var receivedAuthHeader: String? = null
        server.createContext("/v1/chat/completions") { exchange ->
            receivedAuthHeader = exchange.requestHeaders.getFirst("Authorization")
            val sseData = """
                data: {"choices":[{"delta":{"content":"Hallo "}}]}

                data: {"choices":[{"delta":{"content":"Welt"}}]}

                data: [DONE]

            """.trimIndent()
            val bytes = sseData.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        server.start()

        try {
            val serverPort = server.address.port
            val settings = TranslationSettingsService().apply {
                providerType = TranslationProviderType.OPENAI_COMPATIBLE
                openAiCompatibleBaseUrl = "http://127.0.0.1:$serverPort"
                openAiCompatibleModel = "translategemma-4b"
                openAiCompatibleApiKey = "test-api-key"
                timeoutSecondsConfig = 5L
            }

            val provider = OpenAiCompatibleTranslationProvider(settings)
            val request = TranslationRequest(
                prompt = "Translate Hello World to German",
                baseTextLength = 11,
                timeoutSeconds = 5L,
            )

            val chunks = mutableListOf<String>()
            val result = provider.translate(request) { text, _ ->
                chunks.add(text)
            }

            assertTrue(result is ProviderResult.Success)
            assertEquals("Hallo Welt", (result as ProviderResult.Success).text)
            assertEquals("Bearer test-api-key", receivedAuthHeader)

            val tokenChunks = chunks.filterNot { it.startsWith("Startup") }
            assertTrue(tokenChunks.isNotEmpty())
            assertEquals("Hallo Welt", tokenChunks.last())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun handlesErrorStatusCodeFromLocalMockHttpServer() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val errorPayload = """{"error":{"message":"Invalid API key provided"}}"""
            val bytes = errorPayload.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(401, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        server.start()

        try {
            val serverPort = server.address.port
            val settings = TranslationSettingsService().apply {
                providerType = TranslationProviderType.OPENAI_COMPATIBLE
                openAiCompatibleBaseUrl = "http://127.0.0.1:$serverPort"
                openAiCompatibleApiKey = "invalid-key"
                timeoutSecondsConfig = 5L
            }

            val provider = OpenAiCompatibleTranslationProvider(settings)
            val request = TranslationRequest(
                prompt = "Test",
                baseTextLength = 4,
                timeoutSeconds = 5L,
            )

            val result = provider.translate(request) { _, _ -> }

            assertTrue(result is ProviderResult.Failure)
            val failure = result as ProviderResult.Failure
            assertTrue(failure.message.contains("401"))
            assertTrue(failure.message.contains("Invalid API key provided"))
        } finally {
            server.stop(0)
        }
    }
}
