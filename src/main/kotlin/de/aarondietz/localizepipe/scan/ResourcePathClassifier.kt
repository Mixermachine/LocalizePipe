package de.aarondietz.localizepipe.scan

import de.aarondietz.localizepipe.model.ResourceKind

object ResourcePathClassifier {
    private val androidPathRegex = Regex("(.*/src/[^/]+/res)/(values(?:-[^/]+)?)/strings\\.xml$")
    private val composePathRegex = Regex("(.*/src/commonMain/composeResources)/(values(?:-[^/]+)?)/strings\\.xml$")

    fun classify(path: String): ClassifiedResourcePath? {
        androidPathRegex.matchEntire(path)?.let { match ->
            val folderName = match.groupValues[2]
            val qualifierRaw = folderName.removePrefix("values").removePrefix("-")
            return ClassifiedResourcePath(
                resourceRootPath = match.groupValues[1],
                kind = ResourceKind.ANDROID_RES,
                folderName = folderName,
                qualifierRaw = qualifierRaw,
                normalizedLocaleTag = LocaleQualifierUtil.qualifierToLocaleTag(qualifierRaw),
            )
        }

        composePathRegex.matchEntire(path)?.let { match ->
            val folderName = match.groupValues[2]
            val qualifierRaw = folderName.removePrefix("values").removePrefix("-")
            return ClassifiedResourcePath(
                resourceRootPath = match.groupValues[1],
                kind = ResourceKind.COMPOSE_RESOURCES,
                folderName = folderName,
                qualifierRaw = qualifierRaw,
                normalizedLocaleTag = LocaleQualifierUtil.qualifierToLocaleTag(qualifierRaw),
            )
        }

        return null
    }
}

data class ClassifiedResourcePath(
    val resourceRootPath: String,
    val kind: ResourceKind,
    val folderName: String,
    val qualifierRaw: String,
    val normalizedLocaleTag: String?,
)
