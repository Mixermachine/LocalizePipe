package de.aarondietz.localizepipe.model

data class LanguageAddTarget(
    val id: String,
    val resourceRootPath: String,
    val moduleName: String?,
    val originKind: ResourceKind,
    val existingLocaleTags: List<String>,
)
