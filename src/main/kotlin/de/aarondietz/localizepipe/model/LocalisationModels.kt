package de.aarondietz.localizepipe.model

enum class ResourceKind {
    ANDROID_RES,
    COMPOSE_RESOURCES,
}

enum class ScanScope {
    WHOLE_PROJECT,
    CURRENT_MODULE,
}

enum class RowStatus {
    MISSING,
    IDENTICAL,
    READY,
    ERROR,
    UP_TO_DATE,
}

data class ScanOptions(
    val scope: ScanScope,
    val includeAndroidResources: Boolean,
    val includeComposeResources: Boolean,
    val includeIdenticalToBase: Boolean,
    val currentModuleName: String?,
)

data class LocaleFolder(
    val qualifierRaw: String,
    val normalizedLocaleTag: String,
    val filePath: String?,
)

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

data class ScanResult(
    val rows: List<StringEntryRow>,
    val detectedLocales: Set<String>,
)
