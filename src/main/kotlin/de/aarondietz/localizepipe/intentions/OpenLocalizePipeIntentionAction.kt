package de.aarondietz.localizepipe.intentions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

class OpenLocalizePipeIntentionAction : IntentionAction, DumbAware {
    override fun getText(): String = "Open LocalizePipe"

    override fun getFamilyName(): String = "LocalizePipe"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file == null || !file.isValid || !isStringsXml(file)) {
            return false
        }

        val element = file.findElementAt(editor.caretModel.offset) ?: return false
        val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java) ?: return false
        return tag.name == "string" && !tag.getAttributeValue("name").isNullOrBlank()
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        ApplicationManager.getApplication().invokeLater {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return@invokeLater
            toolWindow.show {
                toolWindow.activate(null)
            }
        }
    }

    override fun startInWriteAction(): Boolean = false

    private fun isStringsXml(file: PsiFile): Boolean {
        if (file.name != "strings.xml") {
            return false
        }
        val path = file.virtualFile?.path?.replace('\\', '/') ?: return false
        return path.contains("/res/values") || path.contains("/composeResources/values")
    }

    private companion object {
        private const val TOOL_WINDOW_ID = "LocalizePipe"
    }
}
