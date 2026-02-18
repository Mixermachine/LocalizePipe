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
        var includeAndroidResources by property(true)
        var includeComposeResources by property(true)
        var includeIdenticalToBase by property(false)
        var sourceLocaleTag by string("en")
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

    var sourceLocaleTag: String
        get() = state.sourceLocaleTag ?: "en"
        set(value) {
            state.sourceLocaleTag = value
        }

    fun sourceLocaleTag(): String {
        return sourceLocaleTag
    }
}
