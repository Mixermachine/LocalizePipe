package de.aarondietz.localizepipe

import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import de.aarondietz.localizepipe.settings.ProjectScanSettingsService
import de.aarondietz.localizepipe.settings.TranslationSettingsService
import de.aarondietz.localizepipe.ui.LocalizePipeToolWindowContent
import de.aarondietz.localizepipe.ui.LocalizePipeToolWindowController
import org.jetbrains.jewel.bridge.addComposeTab

class MyToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val controllerDisposable = Disposer.newDisposable("LocalizePipeToolWindow")
        Disposer.register(toolWindow.contentManager, controllerDisposable)

        val settings = service<TranslationSettingsService>()
        val projectScanSettings = project.service<ProjectScanSettingsService>()
        val controller = LocalizePipeToolWindowController(
            project = project,
            settings = settings,
            projectScanSettings = projectScanSettings,
            parentDisposable = controllerDisposable,
        )

        toolWindow.addComposeTab("LocalizePipe", focusOnClickInside = true) {
            LocalizePipeToolWindowContent(
                project = project,
                controller = controller,
                disposable = controllerDisposable,
            )
        }
    }
}
