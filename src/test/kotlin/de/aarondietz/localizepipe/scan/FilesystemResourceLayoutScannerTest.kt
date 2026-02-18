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

    private fun fixturePath(resourcePath: String): Path {
        val uri = checkNotNull(javaClass.classLoader.getResource(resourcePath)) {
            "Missing fixture path: $resourcePath"
        }.toURI()
        return Path.of(uri)
    }
}
