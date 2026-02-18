package de.aarondietz.localizepipe.model

enum class ResourceKind {
    ANDROID_RES,
    COMPOSE_RESOURCES,
}

enum class ScanScope {
    WHOLE_PROJECT,
    CURRENT_MODULE,
}

data class ScanOptions(
    val scope: ScanScope,
    val includeAndroidResources: Boolean,
    val includeComposeResources: Boolean,
    val includeIdenticalToBase: Boolean,
    val currentModuleName: String?,
)

data class ScanResult(
    val rows: List<StringEntryRow>,
    val detectedLocales: Set<String>,
)
