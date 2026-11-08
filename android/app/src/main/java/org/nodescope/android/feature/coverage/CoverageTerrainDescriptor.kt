package org.nodescope.android.feature.coverage

import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.json.*

enum class CoverageTerrainRepositoryState { INVALID_DESCRIPTOR, INVALID_INVENTORY, INVALID_LIMITS, TOO_MANY_TILES }
class CoverageTerrainRepositoryFailure(val state: CoverageTerrainRepositoryState) : Exception(state.name)

/** Pinned SHA-256 identity, or direct AWS HTTPS/ETag identity including its source URL. */
class CoverageTerrainDescriptor(
    val dataset: String, val revision: String, val tile: String, val compressedSHA256: String?,
    val packedBytes: Long, val side: Int, val url: String, val verticalDatum: String,
    val attribution: String, val sourceType: String = "not independently resolved", val originURL: String = "", val sourceETag: String? = null,
) {
    val coordinate: CoverageTerrainTile
    val canonicalIdentity: String
    val cacheKey: String
    val decodedBytes: Long get() = side.toLong() * side * 2

    init {
        if (!Regex("[A-Za-z0-9_.-]{1,48}").matches(dataset) || !Regex("[A-Za-z0-9_.-]{1,48}").matches(revision) ||
            !Regex("[NS][0-9]{2}[EW][0-9]{3}").matches(tile) || !((compressedSHA256?.matches(Regex("[a-f0-9]{64}")) == true && sourceETag == null) ||
                (compressedSHA256 == null && sourceETag?.matches(Regex("\"[A-Za-z0-9-]{1,128}\"")) == true &&
                    url.startsWith("https://elevation-tiles-prod.s3.amazonaws.com/") && originURL == url)) ||
            packedBytes !in 1..40_000_000L || side !in 2..3601 || 3600 % (side - 1) != 0 ||
            listOf(url, verticalDatum, attribution, sourceType).any { it.isEmpty() || it.codePointCount(0, it.length) > 1024 || '\n' in it } ||
            !validURL(url, true) || (originURL.isNotEmpty() && (originURL.codePointCount(0, originURL.length) > 1024 || !validURL(originURL, false)))) {
            throw CoverageTerrainRepositoryFailure(CoverageTerrainRepositoryState.INVALID_DESCRIPTOR)
        }
        val south = tile.substring(1, 3).toInt() * if (tile[0] == 'S') -1 else 1
        val west = tile.substring(4, 7).toInt() * if (tile[3] == 'W') -1 else 1
        if (south !in -90..89 || west !in -180..179) throw CoverageTerrainRepositoryFailure(CoverageTerrainRepositoryState.INVALID_DESCRIPTOR)
        coordinate = CoverageTerrainTile(south, west)
        val identity = buildJsonObject {
            put("dataset", dataset); put("revision", revision); put("tile", tile)
            if (compressedSHA256 != null) put("compressed_sha256", compressedSHA256)
            if (sourceETag != null) {
                put("source_etag", sourceETag); put("source_url", url); put("verification", "https-etag-local-sha256-v1")
            }
             put("packed_bytes", packedBytes); put("side", side)
            put("vertical_datum", verticalDatum); put("attribution", attribution); put("source_type", sourceType)
            put("decoder", "signed-be-hgt-v1"); put("resolution_arcseconds", 3600 / (side - 1)); put("nodata", -32768)
            put("footprint", buildJsonObject { put("east", west + 1); put("north", south + 1); put("south", south); put("west", west) })
        }
        canonicalIdentity = JsonObject(identity.toSortedMap()).toString()
        cacheKey = MessageDigest.getInstance("SHA-256").digest(canonicalIdentity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun validURL(text: String, allowLoopback: Boolean): Boolean = try {
        val uri = URI(text)
        text.none { it.isWhitespace() || it.isISOControl() } && !uri.host.isNullOrEmpty() &&
            uri.userInfo == null && uri.rawFragment == null &&
            (uri.scheme == "https" || (allowLoopback && uri.scheme == "http" && uri.host in listOf("localhost", "127.0.0.1")))
    } catch (_: Exception) { false }
}

/** Estimate snapshot from serialized cache owner; does not prove the cached file is valid. */
data class CoverageTerrainInventoryEntry(val key: String, val decodedBytes: Long, val pinned: Boolean)

data class CoverageTerrainPreflight(
    val tiles: Int, val uncachedTiles: Int, val expectedDownloadBytes: Long, val maximumAttemptBytes: Long,
    val decodedPayloadBytes: Long, val largestStagingReservation: Long, val pinnedSetReservationBytes: Long,
    val worstPinnedStagingBytes: Long, val canPreparePinnedSet: Boolean, val fitsDownloadBudget: Boolean,
    val hasAllCachedTiles: Boolean,
) {
    val cacheValidationPending = true
    companion object {
        fun make(requested: List<CoverageTerrainDescriptor>, inventory: List<CoverageTerrainInventoryEntry>,
                 quota: Long, remainingDownloadBytes: Long, attempts: Int = 2): CoverageTerrainPreflight {
            if (quota !in 8192..2_000_000_000L || remainingDownloadBytes !in 0..512_000_000L || attempts !in 1..3) {
                throw CoverageTerrainRepositoryFailure(CoverageTerrainRepositoryState.INVALID_LIMITS)
            }
            if (requested.size > 256) throw CoverageTerrainRepositoryFailure(CoverageTerrainRepositoryState.TOO_MANY_TILES)
            if (inventory.size > 4096 || inventory.map { it.key }.toSet().size != inventory.size ||
                inventory.any { !Regex("[a-f0-9]{64}").matches(it.key) || it.decodedBytes !in 8..25_934_402L || it.decodedBytes % 2 != 0L }) {
                throw CoverageTerrainRepositoryFailure(CoverageTerrainRepositoryState.INVALID_INVENTORY)
            }
            val unique = requested.distinctBy { it.cacheKey }
            val keys = unique.map { it.cacheKey }.toSet()
            val existing = inventory.associateBy { it.key }
            if (unique.any { existing[it.cacheKey]?.decodedBytes?.let { bytes -> bytes != it.decodedBytes } == true }) {
                throw CoverageTerrainRepositoryFailure(CoverageTerrainRepositoryState.INVALID_INVENTORY)
            }
            val missing = unique.filter { it.cacheKey !in existing }
            val outsidePins = inventory.filter { it.pinned && it.key !in keys }.sumOf { it.decodedBytes + 8192 }
            val decoded = unique.sumOf { it.decodedBytes }
            val pinned = 4096 + outsidePins + decoded + unique.size.toLong() * 8192
            val staging = pinned + (missing.maxOfOrNull { it.packedBytes } ?: 0)
            val expected = missing.sumOf { it.packedBytes }
            return CoverageTerrainPreflight(unique.size, missing.size, expected,
                missing.sumOf { (it.packedBytes + 1) * attempts }, decoded,
                missing.maxOfOrNull { it.packedBytes + it.decodedBytes + 8192 } ?: 0,
                pinned, staging, staging <= quota, expected <= remainingDownloadBytes, missing.isEmpty())
        }
    }
}

/**
 * Conservative logical-byte estimate for one serialized download/decode/publish pipeline.
 * Requires eviction of unrelated unpinned entries and cleanup between tiles and retries.
 * This is not a filesystem free-space check or an acquired reservation.
 */
data class CoverageTerrainPipelinePreflight(
    val cache: CoverageTerrainPreflight,
    val temporaryWorkspaceBytes: Long,
    val peakPipelineBytes: Long,
    val fitsPipelineQuota: Boolean,
) {
    companion object {
        fun make(requested: List<CoverageTerrainDescriptor>, inventory: List<CoverageTerrainInventoryEntry>,
                 quota: Long, remainingDownloadBytes: Long, attempts: Int = 2): CoverageTerrainPipelinePreflight {
            val cache = CoverageTerrainPreflight.make(requested, inventory, quota, remainingDownloadBytes, attempts)
            val existing = inventory.map { it.key }.toSet()
            // Pinned-set bytes already include the final/cache-stage decoded copy.
            val workspace = requested.filter { it.cacheKey !in existing }
                .maxOfOrNull { it.packedBytes + it.decodedBytes + 8192 } ?: 0
            val peak = cache.pinnedSetReservationBytes + workspace
            return CoverageTerrainPipelinePreflight(cache, workspace, peak, peak <= quota)
        }
    }
}
