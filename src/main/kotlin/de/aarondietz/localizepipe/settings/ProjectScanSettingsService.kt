package de.aarondietz.localizepipe.settings

import com.intellij.openapi.components.*

@State(
    name = "LocalizePipeProjectScanSettings",
    storages = [Storage("localizepipe.xml")],
)
@Service(Service.Level.PROJECT)
class ProjectScanSettingsService :
    SimplePersistentStateComponent<ProjectScanSettingsService.ProjectScanState>(ProjectScanState()) {
    class ProjectScanState : BaseState() {
        var includeAndroidResources by property(Const.INCLUDE_ANDROID_RESOURCES)
        var includeComposeResources by property(Const.INCLUDE_COMPOSE_RESOURCES)
        var includeIdenticalToBase by property(Const.INCLUDE_IDENTICAL_TO_BASE)
        var trackSourceChanges by property(Const.TRACK_SOURCE_CHANGES)
        var sourceLocaleTag by string(Const.SOURCE_LOCALE_TAG)
    }

    var includeAndroidResources: Boolean
        get() = state.includeAndroidResources
        set(value) {
            state.includeAndroidResources = value
        }

    var includeComposeResources: Boolean
        get() = state.includeComposeResources
        set(value) {
            state.includeComposeResources = value
        }

    var includeIdenticalToBase: Boolean
        get() = state.includeIdenticalToBase
        set(value) {
            state.includeIdenticalToBase = value
        }

    var trackSourceChanges: Boolean
        get() = state.trackSourceChanges
        set(value) {
            state.trackSourceChanges = value
        }

    var sourceLocaleTag: String
        get() = state.sourceLocaleTag ?: "en"
        set(value) {
            state.sourceLocaleTag = value
        }

    fun sourceLocaleTag(): String {
        return sourceLocaleTag
    }
}
