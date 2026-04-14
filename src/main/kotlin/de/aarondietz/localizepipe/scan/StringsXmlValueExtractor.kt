package de.aarondietz.localizepipe.scan

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

data class StringResourceValue(
    val text: String,
    val localizePipeContext: String?,
)

object StringsXmlValueExtractor {
    fun extract(xmlText: String): Map<String, String> {
        return extractEntries(xmlText).mapValues { it.value.text }
    }

    fun extractEntries(xmlText: String): Map<String, StringResourceValue> {
        if (xmlText.isBlank()) {
            return emptyMap()
        }

        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false

            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xmlText)))
            val nodes = document.getElementsByTagName("string")

            val values = linkedMapOf<String, StringResourceValue>()
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as? Element ?: continue
                val key = element.getAttribute("name").trim()
                if (key.isBlank()) {
                    continue
                }
                if (element.getAttribute("translatable").equals("false", ignoreCase = true)) {
                    continue
                }
                values[key] = StringResourceValue(
                    text = element.textContent ?: "",
                    localizePipeContext = element.getAttribute("localizePipeContext").trim().ifBlank { null },
                )
            }
            values
        } catch (_: Throwable) {
            emptyMap()
        }
    }
}
