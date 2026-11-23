package org.nodescope.android

import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.AnalyzerSource
import org.nodescope.android.core.model.AnalyzerSourceChoices
import org.nodescope.android.core.model.AnalyzerSourceType
import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.core.model.TexasBoundary
import org.nodescope.android.core.network.decodeSourceRegistry
import org.nodescope.android.core.network.decodeSourceRegistryV2

/** Mirrors the iOS registry tests in AnalyzerSourceLogoTests.swift. */
class SourceRegistryTest {
    @Test fun versionTwoFiltersUnknownAndMalformedEntriesAndDefaultsMissingType() {
        val document = decodeSourceRegistry("""{"version":2,"sources":[{"id":"a","type":"beacon","name":"A","host":"a.example","subtitle":"","isDefault":false},{"type":"future"},{"id":"broken","type":"beacon","name":7},{"id":"missing","name":"Missing","host":"missing.example","subtitle":"","isDefault":false}]}""")
        assertEquals(listOf("a", "missing"), document.sources.map { it.id })
        assertEquals(AnalyzerSourceType.BEACON, document.sources.first().backendType)
        assertEquals(AnalyzerSourceType.CORESCOPE, document.sources.last().backendType)
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
            """{"version":2,"sources":[{"id":"b","type":"corescope"}]}""",
            "invalid JSON",
        )) {
            assertThrows(json, Exception::class.java) { decodeSourceRegistry(json) }
        }
    }

    @Test fun duplicateIdentifiersKeepTheFirstEntry() {
        val document = decodeSourceRegistry("""{"version":2,"sources":[{"id":"a","type":"corescope","name":"A","host":"a.example","subtitle":""},{"id":"a","type":"beacon","name":"B","host":"b.example","subtitle":""}]}""")
        assertEquals(listOf("a.example"), document.sources.map { it.host })
    }

    @Test fun countrySectionsPutTheDeviceCountryFirstAndKeepFileOrder() {
        val sources = listOf(source("br1", "BR"), source("other"), source("us1", "US"),
            source("ca", "CA"), source("br2", "BR"), source("us2", "US"))
        val local = AnalyzerSourceChoices(sources, "US", Locale.US)
        assertFalse(local.customFirst)
        assertEquals(listOf("US", "BR", "CA", null), local.countrySections().map { it.country })
        assertEquals(listOf(listOf("us1", "us2"), listOf("br1", "br2"), listOf("ca"), listOf("other")),
            local.countrySections().map { section -> section.sources.map { it.id } })
        val visitor = AnalyzerSourceChoices(sources, "MX", Locale.US)
        assertTrue(visitor.customFirst)
        assertEquals(listOf("BR", "CA", "US", null), visitor.countrySections().map { it.country })
    }

    @Test fun nearestSortKeepsTiesAndUnlocatedEntriesInFileOrder() {
        val sources = listOf(source("missing"), source("far", location = listOf(0.0, 40.0)),
            source("invalid", location = listOf(100.0, 0.0)), source("near1", location = listOf(0.0, 1.0)),
            source("near2", location = listOf(0.0, -1.0)), source("incomplete", location = listOf(1.0)),
            source("nonfinite", location = listOf(Double.POSITIVE_INFINITY, 0.0)))
        assertEquals(listOf("near1", "near2", "far", "missing", "invalid", "incomplete", "nonfinite"),
            AnalyzerSourceChoices(sources).nearest(Coordinate(0.0, 0.0)).map { it.id })
    }

    @Test fun nearestSortPrefersMeshTexasOnlyInsideTexas() {
        val boundary = TexasBoundary(File("../../shared/analyzer-sources/texas-boundary.json").readText())
        val sources = listOf(source("meshtexas", "US", listOf(30.6, -96.9)),
            source("comchan", "US", listOf(30.27, -97.74)),
            source("nearby", "US", listOf(30.28, -97.74)),
            source("arkansas", "US", listOf(33.45, -94.02)))
        val choices = AnalyzerSourceChoices(sources)
        assertEquals(listOf("meshtexas", "comchan", "nearby", "arkansas"),
            choices.nearest(Coordinate(30.27, -97.74), boundary).map { it.id })
        assertEquals("arkansas", choices.nearest(Coordinate(33.45, -94.02), boundary).first().id)
        assertEquals(listOf("comchan", "nearby", "arkansas"),
            AnalyzerSourceChoices(sources.drop(1)).nearest(Coordinate(30.27, -97.74), boundary).map { it.id })
    }

    @Test fun onboardingRespectsCountryDefaultsAndSavedHosts() {
        val sources = listOf(source("us", "US", isDefault = true), source("ca1", "CA"),
            source("ca2", "CA", isDefault = true), source("br1", "BR"), source("br2", "BR"))
        val canada = AnalyzerSourceChoices(sources, "CA")
        assertEquals("ca2.example", canada.onboardingHost(null, false))
        assertEquals("br1.example", AnalyzerSourceChoices(sources, "BR").onboardingHost(null, false))
        assertNull(AnalyzerSourceChoices(sources, "MX").onboardingHost(null, false))
        assertEquals("saved.example", canada.onboardingHost("saved.example", false))
        assertEquals("saved.example", canada.onboardingHost("saved.example", true))
        assertNull(canada.onboardingHost(null, true))
    }

    @Test fun newMetadataIsOptionalAndMalformedValuesDoNotDropEntries() {
        val document = decodeSourceRegistry("""
            {"version":2,"sources":[
              {"id":"old","name":"Old","host":"old.example","subtitle":"","isDefault":false},
              {"id":"bad","name":"Bad","host":"bad.example","subtitle":"","isDefault":false,
               "country":42,"location":[10,"20"]},
              {"id":"new","name":"New","host":"new.example","subtitle":"","isDefault":true,
               "country":"us","location":[10,20],"mapCenter":[30,40],"mapRadiusKm":100}
            ]}
        """.trimIndent())
        assertEquals(listOf("old", "bad", "new"), document.sources.map { it.id })
        for (source in document.sources.take(2)) {
            assertNull(source.country)
            assertNull(source.locationCoordinate)
        }
        val valid = document.sources.last()
        assertEquals("US", valid.country)
        assertEquals(Coordinate(10.0, 20.0), valid.locationCoordinate)
        assertEquals(40.0, valid.viewport?.lon)
        assertEquals(100.0, valid.mapRadiusKm)
    }

    private fun source(id: String, country: String? = null, location: List<Double>? = null,
                       isDefault: Boolean = false) =
        AnalyzerSource(id, id, "$id.example", "", isDefault, country = country, location = location)

    @Test fun bundledV2MatchesIosAndHasACoreScopeDefault() {
        val android = File("src/main/assets/sources-v2.json")
        val ios = File("../../CoreScopeViewer/Resources/sources-v2.json")
        assertEquals(ios.readText(), android.readText())
        val default = decodeSourceRegistryV2(android.readText()).sources.first { it.isDefault }
        assertEquals("meshtexas", default.id)
        assertEquals(AnalyzerSourceType.CORESCOPE, default.backendType)
        val bundled = listOf(default)
        assertEquals(default.host, AnalyzerSourceChoices(bundled, "US").onboardingHost(null, false))
        assertNull(AnalyzerSourceChoices(bundled, "MX").onboardingHost(null, false))
    }
}
