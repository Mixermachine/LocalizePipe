package de.aarondietz.localizepipe.ui.toolwindow

import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import de.aarondietz.localizepipe.settings.ProjectScanSettingsService
import de.aarondietz.localizepipe.settings.TranslationSettingsService
import de.aarondietz.localizepipe.ui.swing.LocalizePipeSwingPanel

class LocalizePipeToolWindowFactory : ToolWindowFactory, DumbAware {
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

        val panel = LocalizePipeSwingPanel(project, controller, controllerDisposable)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
