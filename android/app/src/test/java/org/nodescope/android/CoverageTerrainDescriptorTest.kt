package org.nodescope.android

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageTerrainDescriptorTest {
    @Test fun pipelineAccountsForBothDecodedCopiesAndWorkspace() {
        val tile = descriptor(base())
        val pinned = 4096 + tile.decodedBytes + 8192
        val peak = pinned + tile.packedBytes + tile.decodedBytes + 8192
        for (quota in listOf(peak - 1, peak)) {
            val result = CoverageTerrainPipelinePreflight.make(listOf(tile, tile), emptyList(), quota, 512_000_000)
            assertEquals(peak, result.peakPipelineBytes)
            assertEquals(quota == peak, result.fitsPipelineQuota)
            assertTrue(result.cache.canPreparePinnedSet)
        }
        val cached = CoverageTerrainInventoryEntry(tile.cacheKey, tile.decodedBytes, true)
        val offline = CoverageTerrainPipelinePreflight.make(listOf(tile), listOf(cached), pinned, 0)
        assertEquals(0L, offline.temporaryWorkspaceBytes)
        assertEquals(pinned, offline.peakPipelineBytes)
        assertTrue(offline.fitsPipelineQuota)
        val outside = CoverageTerrainInventoryEntry("f".repeat(64), 18, true)
        val protected = CoverageTerrainPipelinePreflight.make(listOf(tile), listOf(outside), peak, 512_000_000)
        assertEquals(peak + 18 + 8192, protected.peakPipelineBytes)
        assertFalse(protected.fitsPipelineQuota)
    }

    private fun fixture(name: String) = Json.parseToJsonElement(File("../../shared/fixtures/coverage/$name.json").readText())
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean
    private fun descriptor(spec: JsonObject) = CoverageTerrainDescriptor(spec.text("dataset"), spec.text("revision"), spec.text("tile"),
        spec.text("compressed_sha256"), spec.long("packed_bytes"), spec.int("side"), spec.text("url"),
        spec.text("vertical_datum"), spec.text("attribution"), spec.text("source_type"), spec.text("origin_url"))
    private fun base() = fixture("cache-identity").jsonObject.getValue("vectors").jsonArray.first().jsonObject.getValue("spec").jsonObject
    private fun failure(state: CoverageTerrainRepositoryState, block: () -> Unit) {
        assertEquals(state, assertThrows(CoverageTerrainRepositoryFailure::class.java, block).state)
    }

    @Test fun canonicalKeysMatchIndependentVectors() {
        val vectors = fixture("cache-identity").jsonObject.getValue("vectors").jsonArray
        assertEquals(9, vectors.size)
        for (entry in vectors) {
            val vector = entry.jsonObject
            val actual = descriptor(vector.getValue("spec").jsonObject)
            assertEquals(vector.text("name"), vector.text("canonical_utf8_json"), actual.canonicalIdentity)
            assertEquals(vector.text("name"), vector.text("sha256_key"), actual.cacheKey)
        }
    }

    @Test fun completePinnedSetPreflightMatchesRepositoryContract() {
        val cases = fixture("cache-preflight").jsonArray
        assertEquals(13, cases.size)
        for (entry in cases) {
            val item = entry.jsonObject
            val expected = item.getValue("expected").jsonObject
            val actual = CoverageTerrainPreflight.make(item.getValue("requested").jsonArray.map { descriptor(it.jsonObject) },
                item.getValue("inventory").jsonArray.map { row -> row.jsonObject.let {
                    CoverageTerrainInventoryEntry(it.text("key"), it.long("decoded_bytes"), it.bool("pinned"))
                } }, item.long("quota"), item.long("remaining_download_bytes"), item.int("attempts"))
            val result = CoverageTerrainPreflight(expected.int("tiles"), expected.int("uncached_tiles"),
                expected.long("expected_download_bytes"), expected.long("maximum_attempt_bytes"), expected.long("decoded_payload_bytes"),
                expected.long("largest_staging_reservation"), expected.long("pinned_set_reservation_bytes"), expected.long("worst_pinned_staging_bytes"),
                expected.bool("can_prepare_pinned_set"), expected.bool("fits_download_budget"), expected.bool("has_all_cached_tiles"))
            assertEquals(item.text("name"), result, actual)
            assertEquals(expected.bool("cache_validation_pending"), actual.cacheValidationPending)
        }
    }

    @Test fun invalidDescriptorsCannotReachHashOrSizeArithmetic() {
        val invalid = listOf(
            "dataset" to JsonPrimitive("../terrain"), "revision" to JsonPrimitive("r1\n"),
            "tile" to JsonPrimitive("N90E000"), "tile" to JsonPrimitive("N00E180"), "tile" to JsonPrimitive("N0E000"),
            "side" to JsonPrimitive(Int.MAX_VALUE), "side" to JsonPrimitive(1), "side" to JsonPrimitive(8),
            "packed_bytes" to JsonPrimitive(Long.MAX_VALUE), "packed_bytes" to JsonPrimitive(0),
            "compressed_sha256" to JsonPrimitive("A".repeat(64)), "compressed_sha256" to JsonPrimitive("0".repeat(64) + "\n"),
            "attribution" to JsonPrimitive(""), "vertical_datum" to JsonPrimitive("EGM\n96"),
            "url" to JsonPrimitive("http://example.com/tile"), "url" to JsonPrimitive("https://user:secret@example.com/tile"),
            "url" to JsonPrimitive("https://example.com/tile#fragment"), "url" to JsonPrimitive("file:///tile"),
            "url" to JsonPrimitive("https://example.com/a b"), "origin_url" to JsonPrimitive("http://localhost/tile"))
        for ((key, value) in invalid) {
            failure(CoverageTerrainRepositoryState.INVALID_DESCRIPTOR) { descriptor(JsonObject(base() + (key to value))) }
        }
    }

    @Test fun sourceAndTransportChangesHaveDifferentIdentitySemantics() {
        val base = base()
        val original = descriptor(base).cacheKey
        val mirrored = JsonObject(base + mapOf("url" to JsonPrimitive("http://127.0.0.1:8080/replay"), "origin_url" to JsonPrimitive("https://origin.example.com/source")))
        assertEquals(original, descriptor(mirrored).cacheKey)
        assertNotEquals(original, descriptor(JsonObject(mirrored + ("compressed_sha256" to JsonPrimitive("1".repeat(64))))).cacheKey)
    }

    @Test fun limitsAndContradictoryInventoryFailClosed() {
        val descriptor = descriptor(base())
        for ((quota, budget, attempts) in listOf(Triple(Long.MAX_VALUE, 0L, 2), Triple(8191L, 0L, 2),
            Triple(100000L, -1L, 2), Triple(100000L, 512000001L, 2), Triple(100000L, 0L, 0))) {
            failure(CoverageTerrainRepositoryState.INVALID_LIMITS) {
                CoverageTerrainPreflight.make(emptyList(), emptyList(), quota, budget, attempts)
            }
        }
        failure(CoverageTerrainRepositoryState.TOO_MANY_TILES) {
            CoverageTerrainPreflight.make(List(257) { descriptor }, emptyList(), 100000, 1000)
        }
        val entry = CoverageTerrainInventoryEntry(descriptor.cacheKey, descriptor.decodedBytes, true)
        for (inventory in listOf(listOf(entry, entry), listOf(CoverageTerrainInventoryEntry("bad", 50, false)),
            listOf(CoverageTerrainInventoryEntry(descriptor.cacheKey, Long.MAX_VALUE, true)),
            listOf(CoverageTerrainInventoryEntry(descriptor.cacheKey, 18, true)))) {
            failure(CoverageTerrainRepositoryState.INVALID_INVENTORY) {
                CoverageTerrainPreflight.make(listOf(descriptor), inventory, 100000, 1000)
            }
        }
    }
}
