package de.aarondietz.localizepipe.scan

import de.aarondietz.localizepipe.model.ResourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class FilesystemResourceLayoutScannerTest {
    private val scanner = FilesystemResourceLayoutScanner()

    @Test
    fun scansAndroidOnlyFixture() {
        val results = scanner.scanRoot(fixturePath("fixtures/android-only"))

        assertEquals(2, results.size)
        assertTrue(results.all { it.kind == ResourceKind.ANDROID_RES })
        assertTrue(results.any { it.folderName == "values" && it.normalizedLocaleTag == null })
        assertTrue(results.any { it.folderName == "values-tr" && it.normalizedLocaleTag == "tr" })
    }

    @Test
    fun scansKmpOnlyFixture() {
        val results = scanner.scanRoot(fixturePath("fixtures/kmp-only"))

        assertEquals(2, results.size)
        assertTrue(results.all { it.kind == ResourceKind.COMPOSE_RESOURCES })
        assertTrue(results.any { it.folderName == "values" && it.normalizedLocaleTag == null })
        assertTrue(results.any { it.folderName == "values-de" && it.normalizedLocaleTag == "de" })
    }

    @Test
    fun scansMixedFixture() {
        val results = scanner.scanRoot(fixturePath("fixtures/mixed"))

        assertEquals(4, results.size)
        assertTrue(results.any { it.kind == ResourceKind.ANDROID_RES && it.normalizedLocaleTag == "fr" })
        assertTrue(results.any { it.kind == ResourceKind.COMPOSE_RESOURCES && it.normalizedLocaleTag == "es" })
    }

    @Test
    fun normalizesAndroidRegionQualifier() {
        assertEquals("pt-BR", LocaleQualifierUtil.qualifierToLocaleTag("pt-rBR"))
        assertEquals("pt-rBR", LocaleQualifierUtil.localeTagToQualifier("pt-BR"))
    }

    @Test
    fun handlesScriptBearingBcp47Qualifiers() {
        assertEquals("sr-Cyrl", LocaleQualifierUtil.qualifierToLocaleTag("b+sr+Cyrl"))
        assertEquals("sr-Latn", LocaleQualifierUtil.qualifierToLocaleTag("b+sr+Latn"))
        assertEquals("zh-Hans", LocaleQualifierUtil.qualifierToLocaleTag("b+zh+Hans"))
        assertEquals("zh-Hant-TW", LocaleQualifierUtil.qualifierToLocaleTag("b+zh+Hant+TW"))
        assertEquals("pa-Arab", LocaleQualifierUtil.qualifierToLocaleTag("b+pa+Arab"))
        assertEquals("sr", LocaleQualifierUtil.qualifierToLocaleTag("sr"))
        assertEquals("sr-RS", LocaleQualifierUtil.qualifierToLocaleTag("sr-rRS"))

        assertEquals("b+sr+Cyrl", LocaleQualifierUtil.localeTagToQualifier("sr-Cyrl"))
        assertEquals("b+sr+Latn", LocaleQualifierUtil.localeTagToQualifier("sr-Latn"))
        assertEquals("b+zh+Hans", LocaleQualifierUtil.localeTagToQualifier("zh-Hans"))
        assertEquals("b+zh+Hant+TW", LocaleQualifierUtil.localeTagToQualifier("zh-Hant-TW"))
        assertEquals("b+pa+Arab", LocaleQualifierUtil.localeTagToQualifier("pa-Arab"))
        assertEquals("pt-rBR", LocaleQualifierUtil.localeTagToQualifier("pt-BR"))
        assertEquals("sr", LocaleQualifierUtil.localeTagToQualifier("sr"))

        assertEquals("b+sr+Cyrl", LocaleQualifierUtil.localeTagToQualifier(LocaleQualifierUtil.qualifierToLocaleTag("b+sr+Cyrl")!!))
    }

    private fun fixturePath(resourcePath: String): Path {
        val uri = checkNotNull(javaClass.classLoader.getResource(resourcePath)) {
            "Missing fixture path: $resourcePath"
        }.toURI()
        return Path.of(uri)
    }
}
