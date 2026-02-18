package de.aarondietz.localizepipe.model

enum class RowStatus {
    MISSING,
    IDENTICAL,
    READY,
    ERROR,
    UP_TO_DATE,
}

data class StringEntryRow(
    val id: String,
    val key: String,
    val baseText: String,
    val localizedText: String?,
    val proposedText: String?,
    val localeTag: String,
    val localeQualifierRaw: String,
    val localeFilePath: String?,
    val resourceRootPath: String,
    val moduleName: String?,
    val originKind: ResourceKind,
    val status: RowStatus,
    val message: String? = null,
)
