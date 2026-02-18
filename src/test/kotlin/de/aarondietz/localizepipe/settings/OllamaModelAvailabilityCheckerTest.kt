package de.aarondietz.localizepipe.settings

import org.junit.Assert.*
import org.junit.Test

class OllamaModelAvailabilityCheckerTest {
    @Test
    fun parsesModelNamesFromTagsResponse() {
        val raw = """
            {
              "models": [
                {"name":"translategemma:4b"},
                {"name":"translategemma:12b"},
                {"name":"gemma3:latest"}
              ]
            }
        """.trimIndent()

        val names = OllamaModelAvailabilityChecker.parseModelNames(raw)
        assertTrue(names.contains("translategemma:4b"))
        assertTrue(names.contains("translategemma:12b"))
        assertTrue(names.contains("gemma3:latest"))
    }

    @Test
    fun acceptsExactAndUntypedModelNames() {
        val available = setOf("translategemma:4b", "translategemma:12b")
        assertTrue(OllamaModelAvailabilityChecker.isModelAvailable("translategemma:4b", available))
        assertTrue(OllamaModelAvailabilityChecker.isModelAvailable("translategemma", available))
        assertFalse(OllamaModelAvailabilityChecker.isModelAvailable("translategemma:27b", available))
    }

    @Test
    fun pullRejectsBlankModelInput() {
        val result = OllamaModelAvailabilityChecker.pull(
            baseUrl = "http://127.0.0.1:11434",
            requestedModel = "   ",
            timeoutSeconds = 5,
        )

        assertEquals(OllamaModelPullStatus.INVALID_INPUT, result.status)
    }

    @Test
    fun parsesPullProgressLineWithFraction() {
        val parsed = OllamaModelAvailabilityChecker.parsePullProgressLine(
            """{"status":"downloading","completed":50,"total":100}""",
        )

        assertEquals("downloading", parsed?.status)
        assertEquals(0.5, parsed?.fraction ?: 0.0, 0.0001)
        assertNull(parsed?.errorMessage)
    }

    @Test
    fun parsesPullProgressErrorLine() {
        val parsed = OllamaModelAvailabilityChecker.parsePullProgressLine(
            """{"error":"model not found"}""",
        )

        assertEquals("model not found", parsed?.errorMessage)
    }
}
