package de.aarondietz.localizepipe.scan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object LocalizePipeSettingsStore {
    const val SETTINGS_FILE_NAME = "localizepipe-settings.json"
    private val json = Json { prettyPrint = true }
    private const val DESCRIPTION_FIELD = "description_of_file"
    private const val DESCRIPTION_TEXT =
        "LocalizePipe per-language translation settings. Checked into version control."

    fun settingsFilePath(resourceRootPath: String): String {
        val parentPath = resourceRootParentPath(resourceRootPath)
        return "$parentPath/$SETTINGS_FILE_NAME"
    }

    fun parse(rawJson: String): LocalizePipeSettings {
        if (rawJson.isBlank()) {
            return LocalizePipeSettings()
        }

        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrElse { return LocalizePipeSettings() }
        val languagesObj = root["languages"] as? JsonObject ?: return LocalizePipeSettings()

        val languagesMap = linkedMapOf<String, LanguageSettings>()
        for ((localeTag, langVal) in languagesObj) {
            val langObj = langVal as? JsonObject ?: continue
            val translationLocaleTag = runCatching { langObj["translationLocaleTag"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
            val disabled = runCatching { langObj["disabled"]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false
            val instructions = runCatching { langObj["instructions"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

            languagesMap[localeTag] = LanguageSettings(
                translationLocaleTag = translationLocaleTag,
                disabled = disabled,
                instructions = instructions,
            )
        }

        return LocalizePipeSettings(languages = languagesMap)
    }

    fun serialize(settings: LocalizePipeSettings): String {
        val jsonObject = buildJsonObject {
            put(DESCRIPTION_FIELD, JsonPrimitive(DESCRIPTION_TEXT))
            put("languages", buildJsonObject {
                settings.languages.toSortedMap().forEach { (localeTag, langSettings) ->
                    put(localeTag, buildJsonObject {
                        if (langSettings.translationLocaleTag != null) {
                            put("translationLocaleTag", JsonPrimitive(langSettings.translationLocaleTag))
                        }
                        if (langSettings.disabled) {
                            put("disabled", JsonPrimitive(true))
                        }
                        if (langSettings.instructions != null) {
                            put("instructions", JsonPrimitive(langSettings.instructions))
                        }
                    })
                }
            })
        }
        return json.encodeToString(JsonObject.serializer(), jsonObject) + "\n"
    }

    fun languageSettingsFor(settings: LocalizePipeSettings, localeTag: String): LanguageSettings? {
        return settings.languages[localeTag]
    }

    private fun resourceRootParentPath(resourceRootPath: String): String {
        val normalizedRoot = resourceRootPath.replace('\\', '/').trimEnd('/')
        return normalizedRoot.substringBeforeLast('/', missingDelimiterValue = normalizedRoot)
    }
}

data class LocalizePipeSettings(
    val languages: Map<String, LanguageSettings> = emptyMap(),
)

data class LanguageSettings(
    val translationLocaleTag: String? = null,
    val disabled: Boolean = false,
    val instructions: String? = null,
)
