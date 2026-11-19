package org.nodescope.android

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.decodeRepeaterCatalog
import org.nodescope.android.core.network.BodySource
import org.nodescope.android.core.network.HttpBrowseRepository
import org.nodescope.android.core.network.HttpFailure
import org.nodescope.android.core.storage.DistanceUnit
import org.nodescope.android.feature.linkprofile.profileDisplayHeight

class RepeaterMetadataTest {
    private val key = "A".repeat(64)
    private val claimedOnly = "b".repeat(64)
    private val catalog = """
        {"repeaters":[null, {"pubkey":"abc"}, {"pubkey":"$key","heightM":0,"gainDbi":"7",
          "status":"relocating","power":"solar","notes":"<b>plain</b>",
          "claimers":[null, {"displayName":"Operator","claimedAt":"bad date"}]},
          {"pubkey":"$claimedOnly","gainDbi":0,"claimers":[{"displayName":"Other"}]}]}
    """.trimIndent()

    @Test fun partialRecordsAndClaimOnlyRowsDecodeIndependently() {
        val reports = decodeRepeaterCatalog(Json.parseToJsonElement(catalog).jsonObject)
        val report = reports.getValue(key.lowercase())
        assertEquals(0.0, report.heightMeters!!, 0.0)
        assertNull(report.applicableGainDbi)
        assertEquals(0.0, report.applicableHeightMeters()!!, 0.0)
        assertNull(report.applicableHeightMeters(minimum = 1.0))
        assertNull(report.copy(heightMeters = 7.6, claimers = emptyList()).applicableHeightMeters())
        assertNull(report.copy(heightMeters = 301.0).applicableHeightMeters())
        assertNull(report.status)
        assertEquals("solar", report.power)
        assertEquals("<b>plain</b>", report.notes)
        assertEquals("Operator", report.claimers.single().displayName)
        assertNull(report.claimers.single().claimedAt)
        assertNull(reports.getValue(claimedOnly).gainDbi)
        assertEquals(2, reports.size)
        assertEquals(10.0, profileDisplayHeight(3.048, DistanceUnit.IMPERIAL), 1e-9)
    }

    @Test fun claimActionRequiresAnExplicitEmptyClaimListForTheSelectedRepeater() = runTest {
        val repository = HttpBrowseRepository(BodySource { url ->
            assertEquals("/api/repeaters/$key/info", url.encodedPath)
            when (url.host) {
                "unclaimed.invalid" -> """{"pubkey":"${key.lowercase()}","claimers":[]}"""
                "claimed.invalid" -> """{"pubkey":"$key","claimers":[{"displayName":"Operator"}]}"""
                "partial.invalid" -> """{"pubkey":"$key"}"""
                "wrong-node.invalid" -> """{"pubkey":"$claimedOnly","claimers":[]}"""
                else -> throw HttpFailure(404)
            }
        })
        assertTrue(repository.repeaterIsUnclaimed("unclaimed.invalid", key))
        for (host in listOf("claimed.invalid", "partial.invalid", "wrong-node.invalid", "legacy.invalid")) {
            assertFalse(host, repository.repeaterIsUnclaimed(host, key))
        }
    }

    @Test fun bulkCatalogIsSharedAndScopedToAnalyzer() = runTest {
        val calls = mutableListOf<String>()
        val repository = HttpBrowseRepository(BodySource { url ->
            calls += url.host
            assertEquals("/api/repeaters/info", url.encodedPath)
            if (url.host == "legacy.invalid") throw HttpFailure(404)
            catalog
        })
        assertEquals(2, repository.repeaterMetadata("new.invalid").size)
        assertEquals(2, repository.repeaterMetadata("new.invalid").size)
        assertTrue(repository.repeaterMetadata("legacy.invalid").isEmpty())
        assertTrue(repository.repeaterMetadata("legacy.invalid").isEmpty())
        assertEquals(listOf("new.invalid", "legacy.invalid"), calls)
    }
}
