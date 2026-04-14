package de.aarondietz.localizepipe.scan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object SourceChangeMetadataStore {
    private val json = Json { prettyPrint = true }
    private const val DESCRIPTION_FIELD = "description_of_file"
    private const val DESCRIPTION_TEXT =
        "LocalizePipe source hashes for detecting outdated translations. Hashes are constructed from string source + localizePipeContext field (if provided)."

    fun metadataFilePath(resourceRootPath: String): String {
        val parentPath = resourceRootParentPath(resourceRootPath)
        return "$parentPath/${SourceChangeMarkerSupport.METADATA_FILE_NAME}"
    }

    fun parse(rawJson: String): SourceChangeMetadata {
        if (rawJson.isBlank()) {
            return SourceChangeMetadata()
        }

        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrElse { return SourceChangeMetadata() }
        val localeHashes = linkedMapOf<String, Map<String, String>>()
        for ((localeTag, localeValue) in root) {
            val localeObject = localeValue as? JsonObject ?: continue
            val keyHashes = linkedMapOf<String, String>()
            for ((key, hashValue) in localeObject) {
                val hash = runCatching { hashValue.jsonPrimitive.content.trim() }.getOrNull().orEmpty()
                if (key.isBlank() || hash.isBlank()) {
                    continue
                }
                keyHashes[key] = hash
            }
            if (keyHashes.isNotEmpty()) {
                localeHashes[localeTag] = keyHashes
            }
        }
        return SourceChangeMetadata(localeHashes)
    }

    fun serialize(metadata: SourceChangeMetadata): String {
        val jsonObject = buildJsonObject {
            put(DESCRIPTION_FIELD, JsonPrimitive(DESCRIPTION_TEXT))
            metadata.localeHashes.toSortedMap().forEach { (localeTag, keyHashes) ->
                put(localeTag, buildJsonObject {
                    keyHashes.toSortedMap().forEach { (key, hash) ->
                        put(key, JsonPrimitive(hash))
                    }
                })
            }
        }
        return json.encodeToString(JsonObject.serializer(), jsonObject) + "\n"
    }

    fun upsertHash(metadata: SourceChangeMetadata, localeTag: String, key: String, hash: String): SourceChangeMetadata {
        val mutable = metadata.localeHashes.mapValuesTo(linkedMapOf()) { (_, keyHashes) -> keyHashes.toMutableMap() }
        val localeHashes = mutable.getOrPut(localeTag) { linkedMapOf() }
        localeHashes[key] = hash
        return SourceChangeMetadata(mutable)
    }

    fun removeHash(metadata: SourceChangeMetadata, localeTag: String, key: String): SourceChangeMetadata {
        val mutable = metadata.localeHashes.mapValuesTo(linkedMapOf()) { (_, keyHashes) -> keyHashes.toMutableMap() }
        val localeHashes = mutable[localeTag] ?: return metadata
        localeHashes.remove(key)
        if (localeHashes.isEmpty()) {
            mutable.remove(localeTag)
        }
        return SourceChangeMetadata(mutable)
    }

    fun hashFor(metadata: SourceChangeMetadata, localeTag: String, key: String): String? {
        return metadata.localeHashes[localeTag]?.get(key)
    }

    private fun resourceRootParentPath(resourceRootPath: String): String {
        val normalizedRoot = normalizeResourceRootPath(resourceRootPath)
        return normalizedRoot.substringBeforeLast('/', missingDelimiterValue = normalizedRoot)
    }

    private fun normalizeResourceRootPath(resourceRootPath: String): String {
        return resourceRootPath.replace('\\', '/').trimEnd('/')
    }
}

data class SourceChangeMetadata(
    val localeHashes: Map<String, Map<String, String>> = emptyMap(),
) {
    fun isEmpty(): Boolean = localeHashes.isEmpty()
}
