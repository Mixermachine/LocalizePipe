package de.aarondietz.localizepipe.translation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object OllamaGenerateResponseParser {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun extractResponseText(rawGenerateResponseJson: String): String {
        val parsed = json.parseToJsonElement(rawGenerateResponseJson).jsonObject
        val response = parsed["response"]?.jsonPrimitive?.content ?: ""
        return response.trimEnd('\n', '\r')
    }
}
