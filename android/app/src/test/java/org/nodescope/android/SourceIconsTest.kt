package org.nodescope.android

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.SourceDocument
import org.nodescope.android.core.network.protocolJson
import org.nodescope.android.core.storage.sourceIconPath

class SourceIconsTest {
    @Test fun onlyRegistryIconPathsAreAccepted() {
        assertEquals("icons/meshtexas.png", sourceIconPath("icons/meshtexas.png"))
        assertEquals("icons/gulf-coast-mesh.png", sourceIconPath(" icons/gulf-coast-mesh.png "))
        listOf(null, "", "https://example.org/logo.png", "//example.org/a.png", "icons/../secret.png", "icons/a/b.png",
            "icons/Logo.png", "icons/logo.svg", "logo.png").forEach { assertNull(it, sourceIconPath(it)) }
    }

    @Test fun everyRegistryIconExistsAndIsAPng() {
        val folder = File("../../CommunitySources")
        val document = protocolJson.decodeFromString<SourceDocument>(File(folder, "us-sources.json").readText())
        assertTrue(document.sources.isNotEmpty())
        document.sources.forEach { source ->
            val path = assertNotNull(source.id, sourceIconPath(source.icon))
            val bytes = File(folder, path!!).readBytes()
            assertArrayEquals(source.id, byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), bytes.copyOf(4))
        }
    }

    @Test fun bundledRegistryMatchesIosAndShipsItsIcons() {
        val android = File("src/main/assets/us-sources.json")
        val ios = File("../../CoreScopeViewer/Resources/us-sources.json")
        assertEquals(protocolJson.parseToJsonElement(ios.readText()), protocolJson.parseToJsonElement(android.readText()))
        protocolJson.decodeFromString<SourceDocument>(android.readText()).sources.forEach { source ->
            val path = sourceIconPath(source.icon) ?: return@forEach
            assertArrayEquals(source.id, File("../../CommunitySources", path).readBytes(), File("src/main/assets", path).readBytes())
        }
    }

    @Test fun airportTableMatchesIos() {
        assertArrayEquals(File("../../CoreScopeViewer/Resources/iata-airports.csv").readBytes(),
            File("src/main/assets/iata-airports.csv").readBytes())
    }

    private fun assertNotNull(message: String, value: String?) = value.also { assertTrue(message, it != null) }
}
