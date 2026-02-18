package de.aarondietz.localizepipe.model

data class TranslationDeleteLocaleEntry(
    val localeTag: String,
    val localeQualifierRaw: String,
    val localeFilePath: String,
)

data class TranslationDeleteTarget(
    val id: String,
    val key: String,
    val baseText: String,
    val resourceRootPath: String,
    val moduleName: String?,
    val originKind: ResourceKind,
    val localeEntries: List<TranslationDeleteLocaleEntry>,
)
