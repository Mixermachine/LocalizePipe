package de.aarondietz.localizepipe.translation.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import de.aarondietz.localizepipe.model.RowStatus
import de.aarondietz.localizepipe.model.StringEntryRow
import de.aarondietz.localizepipe.scan.LanguageSettings
import de.aarondietz.localizepipe.settings.OllamaRuntimeMode
import de.aarondietz.localizepipe.settings.TranslationProviderType
import de.aarondietz.localizepipe.settings.TranslationSettingsService
import de.aarondietz.localizepipe.translation.TranslateGemmaLanguageMapper
import de.aarondietz.localizepipe.translation.TranslationOutputValidator
import de.aarondietz.localizepipe.translation.ValidationError
import kotlinx.serialization.json.JsonObject

class LocalAiTranslationService(
    private val settings: TranslationSettingsService,
    private val sourceLocaleTagProvider: () -> String = { settings.sourceLocaleTag() },
) {
    fun translateRows(
        rows: List<StringEntryRow>,
        onProgress: (translatedRows: List<StringEntryRow>, processedCount: Int, tokenSpeed: Float?) -> Unit = { _, _, _ -> },
        onRowTranslated: (translatedRow: StringEntryRow) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
        languageSettings: Map<String, LanguageSettings> = emptyMap(),
    ): List<StringEntryRow> {
        checkCanceled(shouldCancel)
        val sourceLangCode = TranslateGemmaLanguageMapper.toGemmaCode(sourceLocaleTagProvider()) ?: "eng_Latn"
        val mutableRows = rows.toMutableList()
        LOG.info(
            "Translate request started (rows=${rows.size}, provider=${settings.providerType}, model=${settings.activeModel()}, source=$sourceLangCode)",
        )

        for ((index, row) in rows.withIndex()) {
            checkCanceled(shouldCancel)

            val langSettings = languageSettings[row.localeTag]
            if (langSettings?.disabled == true) {
                onProgress(mutableRows.toList(), index + 1, null)
                continue
            }

            val effectiveLocaleTag = langSettings?.translationLocaleTag ?: row.localeTag
            val targetLangCode = TranslateGemmaLanguageMapper.toGemmaCode(effectiveLocaleTag)
            if (targetLangCode == null) {
                LOG.warn("Unsupported locale mapping for target locale '${row.localeTag}' (effective tag: '$effectiveLocaleTag')")
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
                languageInstructions = langSettings?.instructions,
                onChunk = { partialText, speed ->
                    mutableRows[index] = row.copy(proposedText = partialText)
                    onProgress(mutableRows.toList(), index, speed)
                },
            )
            mutableRows[index] = translatedRow

            if (translatedRow.status == RowStatus.READY && !translatedRow.proposedText.isNullOrBlank()) {
                try {
                    onRowTranslated(translatedRow)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Throwable) {
                    LOG.warn("Error in onRowTranslated callback for key='${translatedRow.key}'", e)
                }
            }

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
        languageInstructions: String? = null,
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
                languageInstructions = languageInstructions,
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
        languageInstructions: String? = null,
        onChunk: (String, Float?) -> Unit = { _, _ -> },
    ): TranslationOutcome {
        val attempts = (settings.retryCount() + 1).coerceAtLeast(1)
        var lastError: String? = null

        val prompt = buildPrompt(
            baseText = baseText,
            sourceLangCode = sourceLangCode,
            targetLangCode = targetLangCode,
            translationContext = translationContext,
            languageInstructions = languageInstructions,
        )
        val baseTimeout = settings.requestTimeoutSeconds()
        val effectiveTimeoutSeconds = maxOf(baseTimeout, baseTimeout + (baseText.length / 10).toLong())
        val request = TranslationRequest(
            prompt = prompt,
            baseTextLength = baseText.length,
            timeoutSeconds = effectiveTimeoutSeconds,
        )

        repeat(attempts) {
            val provider = resolveProvider(settings.providerType)
            val providerResult = provider.translate(request, onChunk)

            when (providerResult) {
                is ProviderResult.Success -> return TranslationOutcome(text = providerResult.text, errorMessage = null)
                is ProviderResult.Failure -> lastError = providerResult.message
            }
        }

        return TranslationOutcome(text = null, errorMessage = lastError ?: "Translation failed")
    }

    internal fun resolveProvider(providerType: TranslationProviderType): TranslationProvider {
        return when (providerType) {
            TranslationProviderType.OLLAMA -> OllamaTranslationProvider(settings)
            TranslationProviderType.HUGGING_FACE -> HuggingFaceTranslationProvider(settings)
            TranslationProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleTranslationProvider(settings)
        }
    }

    private fun shouldAbortRemainingRows(message: String?): Boolean {
        if (message.isNullOrBlank()) {
            return false
        }
        return message.contains("Run `ollama pull", ignoreCase = true) ||
                message.contains("unauthorized", ignoreCase = true) ||
                message.contains("invalid api key", ignoreCase = true) ||
                message.contains("invalid_api_key", ignoreCase = true)
    }

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
            languageInstructions: String? = null,
        ): String {
            val normalizedContext = translationContext?.trim()?.takeIf { it.isNotEmpty() }
            val normalizedLanguageInstructions = languageInstructions?.trim()?.takeIf { it.isNotEmpty() }
            return buildString {
                appendLine("Translate from $sourceLangCode to $targetLangCode.")
                appendLine("Return only translated text.")
                appendLine("Preserve placeholders exactly (e.g. %1\$s, %d, {name}).")
                appendLine("Preserve XML tags exactly.")
                if (normalizedLanguageInstructions != null) {
                    appendLine("Language-specific instructions: $normalizedLanguageInstructions")
                }
                if (normalizedContext != null) {
                    appendLine("Use additional context only to disambiguate the translation. Do not mention it in the output.")
                    appendLine("Additional context: $normalizedContext")
                }
                append("Text: $baseText")
            }
        }

        internal fun buildOllamaOptions(temperature: Float, runtimeMode: OllamaRuntimeMode): JsonObject {
            return OllamaTranslationProvider.buildOllamaOptions(temperature, runtimeMode)
        }

        internal fun formatOllamaFailureMessage(model: String, statusCode: Int, body: String?): String {
            return OllamaTranslationProvider.formatOllamaFailureMessage(model, statusCode, body)
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
    }
}

private val LOG = Logger.getInstance(LocalAiTranslationService::class.java)
