package de.aarondietz.localizepipe.ui.dialog

import de.aarondietz.localizepipe.model.LanguageRecommendation
import de.aarondietz.localizepipe.model.RecommendationSeverity
import org.junit.Assert.*
import org.junit.Test
import javax.swing.JLabel

class LanguageRecommendationPanelTest {

    @Test
    fun unappliedRecommendationShowsIconAndActionLink() {
        val panel = LanguageRecommendationPanel(preferredContentWidth = null, showActionLink = true)
        val rec = LanguageRecommendation(
            localeTag = "cnr",
            recommendedOverrideTag = "cnr-Latn",
            infoMessage = "Requires 'cnr-Latn'.",
            severity = RecommendationSeverity.RECOMMENDATION,
        )

        panel.setRecommendation(rec, currentOverride = null)

        assertTrue(panel.isVisible)
        val messageLabel = findComponent(panel, JLabel::class.java) { it.text?.contains("Requires") == true }
        assertNotNull(messageLabel)
        assertTrue(messageLabel!!.text.contains("Recommendation:"))

        val iconLabel = findComponent(panel, JLabel::class.java) { it.icon != null }
        assertNotNull(iconLabel)
        assertTrue(iconLabel!!.text.isNullOrEmpty())
    }

    @Test
    fun appliedRecommendationSwitchesToGreenCheckAndHidesAction() {
        val panel = LanguageRecommendationPanel(preferredContentWidth = null, showActionLink = true)
        val rec = LanguageRecommendation(
            localeTag = "cnr",
            recommendedOverrideTag = "cnr-Latn",
            infoMessage = "Requires 'cnr-Latn'.",
            severity = RecommendationSeverity.RECOMMENDATION,
        )

        // When override matches recommendation
        panel.setRecommendation(rec, currentOverride = "cnr-Latn")

        assertTrue(panel.isVisible)
        val checkLabel = findComponent(panel, JLabel::class.java) { it.text == "✅" }
        assertNotNull("Should display green check emoji when applied", checkLabel)
        assertNull("Icon should be null when check emoji is used", checkLabel!!.icon)

        val messageLabel = findComponent(panel, JLabel::class.java) { it.text?.contains("Applied:") == true }
        assertNotNull("Label should have Applied: prefix", messageLabel)
    }

    @Test
    fun nullRecommendationHidesPanel() {
        val panel = LanguageRecommendationPanel(preferredContentWidth = null, showActionLink = true)
        panel.setRecommendation(null)
        assertFalse(panel.isVisible)
    }

    @Test
    fun escapesApostropheSafelyUsingHtmlEntity() {
        val panel = LanguageRecommendationPanel(preferredContentWidth = null, showActionLink = false)
        val rec = LanguageRecommendation(
            localeTag = "test",
            infoMessage = "Requires 'tag' and user's input.",
        )

        panel.setRecommendation(rec)
        val label = findComponent(panel, JLabel::class.java) { it.text?.contains("user&#39;s") == true }
        assertNotNull("Should safely escape apostrophes to &#39;", label)
        assertTrue(label!!.text.contains("<b>tag</b>"))
    }

    private fun <T : java.awt.Component> findComponent(
        container: java.awt.Container,
        clazz: Class<T>,
        predicate: (T) -> Boolean,
    ): T? {
        val queue = ArrayDeque<java.awt.Container>()
        queue.add(container)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (comp in current.components) {
                if (clazz.isInstance(comp) && predicate(clazz.cast(comp))) {
                    return clazz.cast(comp)
                }
                if (comp is java.awt.Container) {
                    queue.add(comp)
                }
            }
        }
        return null
    }
}
