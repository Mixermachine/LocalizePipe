package de.aarondietz.localizepipe.ui.dialog

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import de.aarondietz.localizepipe.model.LanguageRecommendation
import de.aarondietz.localizepipe.model.RecommendationSeverity
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JPanel

internal class LanguageRecommendationPanel(
    private val preferredContentWidth: Int? = null,
    private val showActionLink: Boolean = true,
) : JBPanel<LanguageRecommendationPanel>(BorderLayout(8, 0)) {

    private val iconLabel = JBLabel()
    private val messageLabel = JBLabel()
    private val actionLink: ActionLink
    private var currentRecommendation: LanguageRecommendation? = null
    private var onApplyRecommendation: ((LanguageRecommendation) -> Unit)? = null

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(0xD0D7DE, 0x43454A), 1, true),
            JBUI.Borders.empty(6, 10),
        )
        background = JBColor(0xF6F8FA, 0x2B2D30)
        isOpaque = true
        isVisible = false

        actionLink = ActionLink("") {
            currentRecommendation?.let { rec ->
                onApplyRecommendation?.invoke(rec)
            }
        }.apply {
            isVisible = false
        }

        val leftPanel = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            add(iconLabel, BorderLayout.WEST)
            add(messageLabel, BorderLayout.CENTER)
        }
        add(leftPanel, BorderLayout.CENTER)

        if (showActionLink) {
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(actionLink)
            }
            add(rightPanel, BorderLayout.EAST)
        }
    }

    fun setOnApplyRecommendation(callback: (LanguageRecommendation) -> Unit) {
        this.onApplyRecommendation = callback
    }

    fun setRecommendation(recommendation: LanguageRecommendation?, currentOverride: String? = null) {
        currentRecommendation = recommendation
        if (recommendation == null) {
            isVisible = false
            return
        }

        val overrideTag = recommendation.recommendedOverrideTag
        val instructions = recommendation.recommendedInstructions
        val isApplied = (overrideTag != null && overrideTag.equals(currentOverride?.trim(), ignoreCase = true)) ||
                (overrideTag == null && instructions != null && !currentOverride.isNullOrBlank())

        if (isApplied) {
            iconLabel.icon = null
            iconLabel.text = "✅"
        } else {
            iconLabel.text = null
            iconLabel.icon = when (recommendation.severity) {
                RecommendationSeverity.WARNING -> AllIcons.General.Warning
                RecommendationSeverity.INFO,
                RecommendationSeverity.RECOMMENDATION -> AllIcons.General.Information
            }
        }

        val escaped = recommendation.infoMessage
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "&#39;")
            .replace("&#39;([a-zA-Z0-9+_\\-]+)&#39;".toRegex(), "<b>$1</b>")

        val prefix = when {
            isApplied -> "<b>Applied:</b> "
            recommendation.severity == RecommendationSeverity.WARNING -> "<b>Warning:</b> "
            recommendation.severity == RecommendationSeverity.RECOMMENDATION -> "<b>Recommendation:</b> "
            else -> "<b>Note:</b> "
        }

        val widthStyle = if (preferredContentWidth != null) "style='width: ${preferredContentWidth}px;'" else ""
        messageLabel.text = "<html><body $widthStyle>$prefix$escaped</body></html>"

        val shouldShowAction = showActionLink && !isApplied && (
            (overrideTag != null && !overrideTag.equals(currentOverride?.trim(), ignoreCase = true)) ||
            (instructions != null && currentOverride.isNullOrBlank())
        )

        if (shouldShowAction) {
            actionLink.text = when {
                overrideTag != null -> "Use override $overrideTag"
                else -> "Apply recommendation"
            }
            actionLink.isVisible = true
        } else {
            actionLink.isVisible = false
        }

        isVisible = true
    }
}
