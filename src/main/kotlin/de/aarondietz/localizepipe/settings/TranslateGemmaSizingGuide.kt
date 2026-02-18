package de.aarondietz.localizepipe.settings

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.ceil

enum class TranslateGemmaSize(
    val shortLabel: String,
    val ollamaModelSizeGb: Double,
    val gemmaQ4MemoryGb: Double,
    val recommendedSystemRamGb: Int,
) {
    // Model sizes are based on Ollama TranslateGemma tags (4b/12b/27b).
    // Q4 memory numbers are based on the Gemma 3 model card memory table.
    SIZE_4B(shortLabel = "4B", ollamaModelSizeGb = 3.3, gemmaQ4MemoryGb = 3.4, recommendedSystemRamGb = 8),
    SIZE_12B(shortLabel = "12B", ollamaModelSizeGb = 8.1, gemmaQ4MemoryGb = 8.7, recommendedSystemRamGb = 16),
    SIZE_27B(shortLabel = "27B", ollamaModelSizeGb = 17.0, gemmaQ4MemoryGb = 21.0, recommendedSystemRamGb = 32),
}

object TranslateGemmaSizingGuide {
    val allSizes: List<TranslateGemmaSize> = listOf(
        TranslateGemmaSize.SIZE_4B,
        TranslateGemmaSize.SIZE_12B,
        TranslateGemmaSize.SIZE_27B,
    )

    fun detectTotalSystemRamGb(): Long? {
        val osBean = ManagementFactory.getOperatingSystemMXBean()
        val totalBytes = readLongMetric(osBean, "getTotalMemorySize")
            ?: readLongMetric(osBean, "getTotalPhysicalMemorySize")
            ?: return null

        if (totalBytes <= 0L) {
            return null
        }
        val gb = ceil(totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)).toLong()
        return gb.coerceAtLeast(1L)
    }

    fun detectAvailableStorageGb(): Long? {
        val preferredPath = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
            ?.let { Paths.get(it) }
        val fallbackPath = Paths.get(".").toAbsolutePath().normalize()
        val usableBytes = sequenceOf(preferredPath, fallbackPath)
            .filterNotNull()
            .mapNotNull { detectUsableSpace(it) }
            .firstOrNull()
            ?: return null
        if (usableBytes <= 0L) {
            return null
        }
        val gb = ceil(usableBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)).toLong()
        return gb.coerceAtLeast(1L)
    }

    fun recommendedSize(totalSystemRamGb: Long?): TranslateGemmaSize {
        val ram = totalSystemRamGb ?: return TranslateGemmaSize.SIZE_4B
        return when {
            ram >= TranslateGemmaSize.SIZE_27B.recommendedSystemRamGb -> TranslateGemmaSize.SIZE_27B
            ram >= TranslateGemmaSize.SIZE_12B.recommendedSystemRamGb -> TranslateGemmaSize.SIZE_12B
            else -> TranslateGemmaSize.SIZE_4B
        }
    }

    fun sizeForModelId(modelId: String?): TranslateGemmaSize? {
        val normalized = modelId?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) {
            return null
        }
        return when {
            Regex("""(^|[-:])27b($|[-:_])""").containsMatchIn(normalized) -> TranslateGemmaSize.SIZE_27B
            Regex("""(^|[-:])12b($|[-:_])""").containsMatchIn(normalized) -> TranslateGemmaSize.SIZE_12B
            Regex("""(^|[-:])4b($|[-:_])""").containsMatchIn(normalized) -> TranslateGemmaSize.SIZE_4B
            else -> null
        }
    }

    fun recommendedModelId(providerType: TranslationProviderType, size: TranslateGemmaSize): String {
        return when (providerType) {
            TranslationProviderType.OLLAMA -> "translategemma:${size.shortLabel.lowercase()}"
            TranslationProviderType.HUGGING_FACE -> "google/translategemma-${size.shortLabel.lowercase()}-it"
        }
    }

    fun guidanceHtml(
        providerType: TranslationProviderType,
        totalSystemRamGb: Long?,
        recommendedSize: TranslateGemmaSize,
        availableStorageGb: Long?,
        selectedModelId: String?,
    ): String {
        val ramText = totalSystemRamGb?.let { "$it GB" } ?: "unknown"
        val storageText = availableStorageGb?.let { "$it GB free" } ?: "unknown"
        val recommendedModel = recommendedModelId(providerType, recommendedSize)
        val selectedSize = sizeForModelId(selectedModelId)
        val warning = buildStorageWarning(providerType, selectedModelId, selectedSize, availableStorageGb)
        val rows = allSizes.joinToString("<br>") { size ->
            "${size.shortLabel}: model storage ~${trimGb(size.ollamaModelSizeGb)} GB, " +
                    "runtime memory ~${trimGb(size.gemmaQ4MemoryGb)} GB (Q4), " +
                    "recommended system RAM >= ${size.recommendedSystemRamGb} GB"
        }
        return buildString {
            append("<html>")
            appendLine("<b>TranslateGemma RAM guidance</b>")
            appendLine("Detected system RAM: $ramText, Detected free storage: $storageText")
            appendLine("<b>Recommended now: $recommendedModel (based on system RAM)</b>")
            if (warning != null) {
                appendLine("<span style='color:#B54708'><b>Warning:</b> $warning</span>")
            }
            appendLine(rows)
            appendLine()
            appendLine("<i>Note: Larger models give you a better results but also consume more RAM, memory and are slower.</i>")
            appendLine("<i>Your mileage may vary, the values above are only estimates.</i>")
            appendLine("<i>If your dev setup already consumes a lot of RAM, please pick a smaller model.</i>")
            appendLine("<i>If you want to run the model on a GPU, pick a size that matches your VRAM.</i>")
            appendLine("</html>")
        }.replace("\n", "<br>")
    }

    private fun trimGb(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            value.toString()
        }
    }

    private fun readLongMetric(instance: Any, methodName: String): Long? {
        return runCatching {
            val method = instance.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 0
            } ?: return null
            (method.invoke(instance) as? Number)?.toLong()
        }.getOrNull()
    }

    private fun detectUsableSpace(path: Path): Long? {
        return runCatching {
            Files.getFileStore(path).usableSpace
        }.getOrNull()
    }

    private fun buildStorageWarning(
        providerType: TranslationProviderType,
        selectedModelId: String?,
        selectedSize: TranslateGemmaSize?,
        availableStorageGb: Long?,
    ): String? {
        val storage = availableStorageGb ?: return null
        val size = selectedSize ?: return null
        val required = ceil(size.ollamaModelSizeGb).toLong()
        if (storage >= required) {
            return null
        }
        val modelLabel = selectedModelId?.takeIf { it.isNotBlank() } ?: recommendedModelId(providerType, size)
        return "Model '$modelLabel' needs about ${trimGb(size.ollamaModelSizeGb)} GB, but only $storage GB is available."
    }
}
