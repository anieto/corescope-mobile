package org.nodescope.android.core.model

import java.time.Instant
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Public community-maintained metadata from CoreScope's separate repeater catalog. */
data class RepeaterMetadata(
    val publicKey: String,
    val heightMeters: Double?,
    val gainDbi: Double?,
    val status: String?,
    val power: String?,
    val notes: String?,
    val claimers: List<Claim>,
    val updatedAt: Instant?,
    val updatedBy: String?,
) {
    data class Claim(val displayName: String, val claimedAt: Instant?)

    fun applicableHeightMeters(minimum: Double = 0.0): Double? =
        heightMeters?.takeIf { claimers.isNotEmpty() && it in minimum..300.0 }

    // Upstream uses zero even when the edit omitted gain.
    val applicableGainDbi: Double? get() = gainDbi?.takeUnless { it == 0.0 }

    val hasDetails: Boolean get() = heightMeters != null || gainDbi != null || status != null ||
        power != null || notes != null || claimers.isNotEmpty() || updatedAt != null || updatedBy != null
}

fun decodeRepeaterCatalog(root: JsonObject): Map<String, RepeaterMetadata> =
    requireNotNull(root["repeaters"] as? JsonArray) { "Expected a repeater catalog" }
        .mapNotNull { (it as? JsonObject)?.repeaterMetadata() }
        .filter(RepeaterMetadata::hasDetails)
        .associateBy(RepeaterMetadata::publicKey)

private fun JsonObject.repeaterMetadata(): RepeaterMetadata? {
    val key = text("pubkey")?.lowercase(Locale.ROOT)?.takeIf { it.matches(fullRepeaterKey) } ?: return null
    val updatedAt = instant("updatedAt")
    return RepeaterMetadata(
        publicKey = key,
        heightMeters = number("heightM")?.takeIf { it in 0.0..300.0 },
        gainDbi = number("gainDbi")?.takeIf { it in -10.0..30.0 && (it != 0.0 || updatedAt != null) },
        status = text("status")?.takeIf { it in setOf("active", "maintenance", "decommissioned") },
        power = text("power")?.takeIf { it in setOf("mains", "battery", "solar") },
        notes = text("notes")?.let(::cleanText),
        claimers = (this["claimers"] as? JsonArray).orEmpty().mapNotNull {
            val claim = it as? JsonObject ?: return@mapNotNull null
            val name = claim.text("displayName")?.let(::cleanText) ?: return@mapNotNull null
            RepeaterMetadata.Claim(name, claim.instant("claimedAt"))
        },
        updatedAt = updatedAt,
        updatedBy = text("updatedBy")?.let(::cleanText),
    )
}

private val fullRepeaterKey = Regex("[0-9a-f]{64}")
private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
private fun JsonObject.instant(key: String) = text(key)?.let {
    runCatching { Instant.ofEpochMilli(Instant.parse(it).toEpochMilli()) }.getOrNull()
}
private fun cleanText(value: String) = value.filter {
    (it == '\n' || !it.isISOControl()) && it !in '\u202A'..'\u202E' && it !in '\u2066'..'\u2069'
}.let { text ->
    text.substring(0, text.offsetByCodePoints(0, minOf(500, text.codePointCount(0, text.length))))
}.trim().takeIf { it.isNotEmpty() }


@Serializable
data class ReportedRepeaterSelection(val analyzerHost: String, val publicKey: String)

@Serializable
data class ReportedAntennaSource(
    val analyzerHost: String,
    val publicKey: String,
    val updatedAtMilliseconds: Long? = null,
    val updatedBy: String? = null,
) {
    fun isFromDifferentRepeater(previous: ReportedAntennaSource?) =
        previous?.analyzerHost != analyzerHost || previous?.publicKey != publicKey

    fun draftValue() = Json.encodeToString(serializer(), this)

    companion object {
        fun fromDraft(value: String?) = value?.let {
            runCatching { Json.decodeFromString(serializer(), it) }.getOrNull()
        }
    }
}

fun RepeaterMetadata.antennaSource(host: String) =
    ReportedAntennaSource(org.nodescope.android.core.network.normalizeHost(host), publicKey, updatedAt?.toEpochMilli(), updatedBy)
