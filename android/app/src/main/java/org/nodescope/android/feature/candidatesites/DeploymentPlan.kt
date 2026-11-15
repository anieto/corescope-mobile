package org.nodescope.android.feature.candidatesites

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Version 1 holds candidate sites; version 2 adds typed studies with optional coverage result
 * snapshots. Version 2 requires `studies`; version 1 may only carry an empty list. Matches iOS.
 */
@Serializable
data class DeploymentPlanDocument(
    val schemaVersion: Int, val plans: List<DeploymentPlan>, val studies: List<SavedStudy>? = null,
    /** Version 3 only (docs/node-planner/saved-plans-v3-schema.md). */
    val documentRevision: Long? = null, val studyPlans: List<StudyPlan>? = null,
) {
    fun validate() {
        if (schemaVersion !in 1..3) throw DeploymentPlanException(DeploymentPlanFailure.UNSUPPORTED_VERSION)
        if (schemaVersion == 3) return validateVersion3()
        valid(documentRevision == null && studyPlans == null && plans.all { it.revision == null } &&
            studies.orEmpty().all { it.revision == null && it.planID == null && it.origin == null && it.importOrigin == null && it.resultRef == null })
        val saved = studies.orEmpty()
        valid(if (schemaVersion == 2) studies != null else saved.isEmpty())
        valid(saved.size <= 100 && saved.map { it.id }.toSet().size == saved.size)
        saved.forEach { it.validate() }
        valid(plans.size <= 100 && plans.map { it.id }.toSet().size == plans.size)
        plans.forEach { it.validate() }
    }

    /** Studies as stored; version 1 has none. */
    val savedStudies: List<SavedStudy> get() = studies.orEmpty()
}

@Serializable
data class DeploymentPlan(
    val id: String, val name: String, val createdAtMilliseconds: Long, val updatedAtMilliseconds: Long,
    val selectedSiteID: String? = null, val sites: List<CandidateSite>,
    /** Version 3 only; the store assigns it. */
    val revision: Long? = null,
) {
    fun validate() {
        valid(validID(id) && validName(name) && createdAtMilliseconds >= 0 &&
            updatedAtMilliseconds >= createdAtMilliseconds && updatedAtMilliseconds <= 253_402_300_799_999 &&
            sites.size <= 100 && sites.map { it.id }.toSet().size == sites.size &&
            (selectedSiteID == null || sites.any { it.id == selectedSiteID }))
        sites.forEach { it.validate() }
    }
}

@Serializable
data class CandidateSite(
    val id: String, val name: String, val latitude: Double, val longitude: Double,
    val assumptions: CandidateSiteAssumptions,
) {
    fun validate() {
        valid(validID(id) && validName(name) && latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude >= -180 && longitude < 180)
        assumptions.validate()
    }
}

/** Null means unspecified, never zero or a radio preset. Bounds are storage guards, not engine limits. */
@Serializable
data class CandidateSiteAssumptions(
    val antennaHeightMeters: Double? = null, val frequencyMHz: Double? = null,
    val transmitPowerDBm: Double? = null, val antennaGainDBi: Double? = null,
    val cableLossDB: Double? = null, val additionalLossDB: Double? = null,
) {
    fun validate() {
        val fields = listOf(antennaHeightMeters to 0.0..10_000.0, frequencyMHz to 0.001..100_000.0,
            transmitPowerDBm to -200.0..100.0, antennaGainDBi to -100.0..100.0,
            cableLossDB to 0.0..200.0, additionalLossDB to 0.0..200.0)
        valid(fields.all { (value, range) -> value == null || (value.isFinite() && value in range) })
    }
}

enum class DeploymentPlanFailure { UNSUPPORTED_VERSION, INVALID_DATA, TOO_LARGE }
/** Decimal input only; a blank optional value must remain unknown, never zero. */
object CandidateSiteInput {
    private val decimal = Regex("""^[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?$""")
    fun make(id: String, name: String, values: List<String>, heightInFeet: Boolean = false): CandidateSite {
        valid(values.size == 8)
        val parsed = values.map { text ->
            val value = text.trim().replace(',', '.')
            if (value.isEmpty()) null else {
                valid(decimal.matches(value))
                val number = value.toDoubleOrNull()
                valid(number != null && number.isFinite())
                number
            }
        }
        valid(parsed[0] != null && parsed[1] != null)
        val longitude = requireNotNull(parsed[1])
        return CandidateSite(id, name, requireNotNull(parsed[0]), if (longitude == 180.0) -180.0 else longitude,
            CandidateSiteAssumptions(parsed[2]?.let { if (heightInFeet) it * 0.3048 else it },
                parsed[3], parsed[4], parsed[5], parsed[6], parsed[7])).also { it.validate() }
    }
}

class DeploymentPlanException(val reason: DeploymentPlanFailure) : Exception(reason.name)

internal fun planValid(condition: Boolean) = valid(condition)
internal fun validPlanID(value: String) = validID(value)
internal fun validPlanName(value: String) = validName(value)

private fun valid(condition: Boolean) {
    if (!condition) throw DeploymentPlanException(DeploymentPlanFailure.INVALID_DATA)
}
private fun validID(value: String): Boolean = try {
    UUID.fromString(value).toString() == value
} catch (_: IllegalArgumentException) { false }
private fun validName(value: String): Boolean = value.trim(' ', '\t', '\r', '\n').isNotEmpty() &&
    value.toByteArray(Charsets.UTF_8).size <= 160 && value.none { it.code < 32 || it.code == 127 }

/** Use at every disk boundary; deserialization alone does not validate stored input. */
object DeploymentPlanCodec {
    // Matches iOS: a saved coverage result can reach 8 MiB on its own inside a v2 file.
    const val MAXIMUM_BYTES = 32 * 1024 * 1024
    /** Version 3 keeps results in their own files, so its manifest has a smaller cap. */
    const val MAXIMUM_VERSION3_BYTES = 16 * 1024 * 1024
    // Absent optionals are omitted, as iOS writes them; version 1 never writes `studies`.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    // Version 3 is strict: unknown fields are refused and nulls are written out.
    private val strict = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = true }

    fun decode(bytes: ByteArray): DeploymentPlanDocument {
        if (bytes.size > MAXIMUM_BYTES) throw DeploymentPlanException(DeploymentPlanFailure.TOO_LARGE)
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        val element = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) {
            throw DeploymentPlanException(DeploymentPlanFailure.INVALID_DATA)
        }
        val version = (element["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: throw DeploymentPlanException(DeploymentPlanFailure.INVALID_DATA)
        if (version !in 1..3) throw DeploymentPlanException(DeploymentPlanFailure.UNSUPPORTED_VERSION)
        if (version == 3) {
            if (bytes.size > MAXIMUM_VERSION3_BYTES) throw DeploymentPlanException(DeploymentPlanFailure.TOO_LARGE)
            // Results live in result files from version 3 on; an inline one means the file is malformed.
            val inline = element["studies"]?.jsonArray?.any { (it.jsonObject["coverage"] as? JsonObject)?.containsKey("calculatedResult") == true }
            if (inline == true) throw DeploymentPlanException(DeploymentPlanFailure.INVALID_DATA)
        }
        val document = try { (if (version == 3) strict else json).decodeFromJsonElement<DeploymentPlanDocument>(element) }
            catch (error: DeploymentPlanException) { throw error }
            catch (_: Exception) { throw DeploymentPlanException(DeploymentPlanFailure.INVALID_DATA) }
        return document.also { it.validate() }
    }

    fun encode(document: DeploymentPlanDocument): ByteArray {
        document.validate()
        if (document.schemaVersion == 3) {
            val element = strict.encodeToJsonElement(document).jsonObject
            val studies = element.getValue("studies").jsonArray.map { study ->
                val coverage = study.jsonObject["coverage"] as? JsonObject ?: return@map study
                // In memory, coverage results ride in calculatedResult; on disk they're result files.
                JsonObject(study.jsonObject + ("coverage" to JsonObject(coverage - "calculatedResult")))
            }
            return Json.encodeToString(JsonObject.serializer(), JsonObject(element + ("studies" to JsonArray(studies))))
                .toByteArray(Charsets.UTF_8).also {
                    if (it.size > MAXIMUM_VERSION3_BYTES) throw DeploymentPlanException(DeploymentPlanFailure.TOO_LARGE)
                }
        }
        val stored = if (document.schemaVersion == 1) document.copy(studies = null) else document
        return json.encodeToString(stored).toByteArray(Charsets.UTF_8).also {
            if (it.size > MAXIMUM_BYTES) throw DeploymentPlanException(DeploymentPlanFailure.TOO_LARGE)
        }
    }
}
