package org.nodescope.android.feature.candidatesites

import kotlinx.serialization.Serializable

/** A named collection of studies (version 3). Membership lives only in SavedStudy.planID. */
@Serializable
data class StudyPlan(val id: String, val name: String, val notes: String? = null,
    val createdAtMilliseconds: Long, val updatedAtMilliseconds: Long, val revision: Long? = null)

/** Where a study's inputs were captured; null fields are unknown. A null origin is "Unknown source". */
@Serializable
data class StudyOrigin(val host: String?, val region: String?, val capturedAtMilliseconds: Long?)

/** The immediate source of an imported independent copy, not an ancestry chain. */
@Serializable
data class ImportOrigin(val studyID: String, val revision: Long)

/** An immutable result file, named by the SHA-256 of its bytes under CandidateSites/results/. */
@Serializable
data class ResultRef(val format: String, val version: Int, val byteLength: Int, val sha256: String,
    val inputFingerprint: String? = null) {
    companion object {
        const val ANDROID_LEGACY = "legacy.android-json-coverage"
        const val IOS_LEGACY = "legacy.ios-plist-coverage"
        const val PORTABLE_COVERAGE = "nodescope.coverage-result"
        const val PORTABLE_LINK_PROFILE = "nodescope.link-profile-result"
    }
}

/** What happened when the store looked for a study's result file. */
enum class SavedResultStatus {
    /** No result was saved. */
    NONE,
    /** Read and verified; for coverage, its bytes are in calculatedResult. */
    AVAILABLE,
    /** Saved result not on this device (for example, after a cloud restore). */
    MISSING,
    /** The file's length or hash doesn't match its reference. */
    UNVERIFIED,
    /** Verified, but in a format this version can't show: another platform's or a newer one. */
    UNSUPPORTED,
}

internal const val MAXIMUM_REVISION = Long.MAX_VALUE
private val SHA256 = Regex("[0-9a-f]{64}")

internal fun DeploymentPlanDocument.validateVersion3() {
    planValid((documentRevision ?: 0) >= 1)
    val containers = studyPlans ?: throw DeploymentPlanException(DeploymentPlanFailure.INVALID_DATA)
    val saved = studies ?: throw DeploymentPlanException(DeploymentPlanFailure.INVALID_DATA)
    planValid(plans.size <= 100 && containers.size <= 100 && saved.size <= 100)
    planValid(plans.map { it.id }.toSet().size == plans.size && containers.map { it.id }.toSet().size == containers.size &&
        saved.map { it.id }.toSet().size == saved.size)
    plans.forEach { planValid((it.revision ?: 0) >= 1); it.validate() }
    containers.forEach { plan ->
        planValid(validPlanID(plan.id) && validPlanName(plan.name) && (plan.revision ?: 0) >= 1 &&
            (plan.notes == null || plan.notes.toByteArray(Charsets.UTF_8).size <= 4096) &&
            plan.createdAtMilliseconds >= 0 && plan.updatedAtMilliseconds >= plan.createdAtMilliseconds &&
            plan.updatedAtMilliseconds <= 253_402_300_799_999)
    }
    val planIDs = containers.map { it.id }.toSet()
    saved.forEach { study ->
        study.validate()
        planValid((study.revision ?: 0) >= 1 && (study.planID == null || study.planID in planIDs))
        study.origin?.let { origin ->
            planValid((origin.host == null || origin.host.toByteArray(Charsets.UTF_8).size <= 253) &&
                (origin.region == null || origin.region.toByteArray(Charsets.UTF_8).size <= 64) &&
                (origin.capturedAtMilliseconds == null || origin.capturedAtMilliseconds in 0..253_402_300_799_999))
        }
        study.importOrigin?.let { planValid(validPlanID(it.studyID) && it.revision >= 1) }
        study.resultRef?.let { ref ->
            val formats = if (study.kind == SavedStudy.Kind.COVERAGE)
                setOf(ResultRef.ANDROID_LEGACY, ResultRef.IOS_LEGACY, ResultRef.PORTABLE_COVERAGE) else setOf(ResultRef.PORTABLE_LINK_PROFILE)
            val portable = ref.format.startsWith("nodescope.")
            planValid(ref.format in formats && ref.version == 1 && ref.byteLength in 1..SavedCoverage.MAXIMUM_RESULT_BYTES &&
                SHA256.matches(ref.sha256) && portable == (ref.inputFingerprint != null) &&
                (ref.inputFingerprint == null || SHA256.matches(ref.inputFingerprint)))
        }
    }
}
