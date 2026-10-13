package org.nodescope.android

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.AnalyzerSourceType
import org.nodescope.android.core.network.decodeSourceRegistry
import org.nodescope.android.core.network.decodeSourceRegistryV2

/** Mirrors the iOS registry tests in AnalyzerSourceLogoTests.swift. */
class SourceRegistryTest {
    @Test fun versionTwoFiltersUnknownAndMissingTypes() {
        val document = decodeSourceRegistry("""{"version":2,"sources":[{"id":"a","type":"beacon","name":"A","host":"a.example","subtitle":"","isDefault":false},{"type":"future"},{"id":"missing","name":"Missing","host":"missing.example","subtitle":"","isDefault":false}]}""")
        assertEquals(1, document.sources.size)
        assertEquals(AnalyzerSourceType.BEACON, document.sources.first().backendType)
    }

    @Test fun legacyRegistryRemainsCoreScopeButIsNotAcceptedAsV2() {
        val legacy = """{"version":1,"sources":[{"id":"a","name":"A","host":"a.example","subtitle":"","isDefault":true}]}"""
        assertEquals(AnalyzerSourceType.CORESCOPE, decodeSourceRegistry(legacy).sources.first().backendType)
        assertThrows(IllegalArgumentException::class.java) { decodeSourceRegistryV2(legacy) }
    }

    @Test fun unusableRegistriesAreRejected() {
        for (json in listOf(
            """{"version":3,"sources":[]}""",
            """{"version":2,"sources":[{"type":"future"}]}""",
            """{"version":2,"sources":[{"id":"a","type":"beacon","name":"A","host":"  ","subtitle":"","isDefault":false}]}""",
            // Matches iOS for now: one malformed supported entry fails the whole document.
            """{"version":2,"sources":[{"id":"a","type":"beacon","name":"A","host":"a.example","subtitle":"","isDefault":false},{"id":"b","type":"corescope"}]}""",
            "invalid JSON",
        )) {
            assertThrows(json, Exception::class.java) { decodeSourceRegistry(json) }
        }
    }

    @Test fun duplicateIdentifiersKeepTheFirstEntry() {
        val document = decodeSourceRegistry("""{"version":2,"sources":[{"id":"a","type":"corescope","name":"A","host":"a.example","subtitle":""},{"id":"a","type":"beacon","name":"B","host":"b.example","subtitle":""}]}""")
        assertEquals(listOf("a.example"), document.sources.map { it.host })
    }

    @Test fun bundledV2MatchesIosAndHasACoreScopeDefault() {
        val android = File("src/main/assets/sources-v2.json")
        val ios = File("../../CoreScopeViewer/Resources/sources-v2.json")
        assertEquals(ios.readText(), android.readText())
        val default = decodeSourceRegistryV2(android.readText()).sources.first { it.isDefault }
        assertEquals("meshtexas", default.id)
        assertEquals(AnalyzerSourceType.CORESCOPE, default.backendType)
    }
}
