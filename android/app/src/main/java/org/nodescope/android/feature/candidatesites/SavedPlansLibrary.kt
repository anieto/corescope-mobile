package org.nodescope.android.feature.candidatesites

import org.nodescope.android.R
import org.nodescope.android.core.design.UiText
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one owner of the saved-plans file: candidate-site plans and saved studies, as iOS's shared
 * `CandidatePlansModel`. Every screen saves through the same instance, so a second writer never
 * trips the store's changed-on-disk check. Call from the main thread.
 */
class SavedPlansLibrary(private val store: DeploymentPlanStore) {
    var plans by mutableStateOf<List<DeploymentPlan>>(emptyList()); private set
    var studies by mutableStateOf<List<SavedStudy>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<UiText?>(null)
    /** Named study plans (version 3). Membership lives only in each study's planID. */
    var studyPlans by mutableStateOf<List<StudyPlan>>(emptyList()); private set

    suspend fun load() {
        if (busy || loaded) return
        busy = true
        try {
            val document = withContext(Dispatchers.IO) { store.load() }
            apply(document)
            loaded = true
            error = null
        } catch (_: Exception) {
            error = UiText.Resource(R.string.planner_saved_plans_could_not_be_opened_your_files)
        } finally { busy = false }
    }

    suspend fun reload() {
        if (busy) return
        loaded = false
        load()
    }

    suspend fun savePlan(plan: DeploymentPlan): Boolean =
        commit(if (plans.any { it.id == plan.id }) plans.map { if (it.id == plan.id) plan else it } else plans + plan, studies) != null

    suspend fun deletePlan(plan: DeploymentPlan): Boolean = commit(plans.filterNot { it.id == plan.id }, studies) != null

    enum class SaveMode { UPDATE, SAVE_AS_NEW }

    /** Where a save puts the study. Encoded as text so a sheet can keep it through configuration changes. */
    sealed interface Destination {
        data object Standalone : Destination
        data class Plan(val id: String) : Destination
        data object NewPlan : Destination

        fun encoded(): String = when (this) { Standalone -> "standalone"; NewPlan -> "new"; is Plan -> "plan:$id" }
        companion object {
            fun decode(text: String): Destination = when {
                text == "new" -> NewPlan
                text.startsWith("plan:") -> Plan(text.removePrefix("plan:"))
                else -> Standalone
            }
        }
    }

    /**
     * Saves [study] and returns it as stored, with its revision; null if it couldn't be saved. Update
     * refuses a study changed or deleted since it was opened (`study.revision` is the one opened); Save
     * as new always creates a new record. A new plan and its first study are one commit. Saving moves
     * the library to version 3, keeping the original version 1 or 2 file first.
     */
    suspend fun saveStudy(study: SavedStudy, mode: SaveMode, destination: Destination, newPlanName: String = "",
                          inputsOnly: Boolean = false): SavedStudy? {
        val now = System.currentTimeMillis()
        var containers = studyPlans
        val target: String? = when (destination) {
            Destination.Standalone -> null
            is Destination.Plan -> {
                if (containers.none { it.id == destination.id }) { error = UiText.Resource(R.string.planner_that_plan_no_longer_exists_choose_another_place); return null }
                destination.id
            }
            Destination.NewPlan -> {
                val plan = StudyPlan(newStudyID(), newPlanName, null, now, now)
                containers = containers + plan
                plan.id
            }
        }
        val touched = mutableSetOf(target)
        var saving = study.copy(resultRef = null)
        when (mode) {
            SaveMode.UPDATE -> {
                val current = studies.firstOrNull { it.id == study.id }
                    ?: run { error = UiText.Resource(R.string.planner_this_study_was_deleted_since_you_opened_it); return null }
                if (study.revision != null && current.revision != study.revision) {
                    error = UiText.Resource(R.string.planner_this_study_was_changed_elsewhere_since_you_opened)
                    return null
                }
                if (!inputsOnly && dropsUnreadableResult(study)) {
                    error = UiText.Resource(R.string.planner_the_saved_result_can_t_be_shown_on_2)
                    return null
                }
                // The tool says where its inputs came from; it keeps the saved study's source unless they were captured again.
                saving = saving.copy(importOrigin = current.importOrigin)
                touched += current.planID
            }
            SaveMode.SAVE_AS_NEW -> saving = saving.copy(id = newStudyID(), createdAtMilliseconds = now, revision = null, importOrigin = null)
        }
        saving = saving.copy(planID = target, updatedAtMilliseconds = maxOf(saving.createdAtMilliseconds, now))
        val next = if (studies.any { it.id == saving.id }) studies.map { if (it.id == saving.id) saving else it } else studies + saving
        return commit(plans, next, touch(containers, touched, now))?.savedStudies?.firstOrNull { it.id == saving.id }
    }

    /**
     * Whether updating [study] would detach a saved result this device can't show (missing, unverified or
     * another format): its calculation inputs changed and it brings no new result. A result can't stay
     * attached to different inputs, so Update then saves inputs only, once confirmed ([saveStudy]'s inputsOnly).
     */
    fun dropsUnreadableResult(study: SavedStudy): Boolean {
        val current = studies.firstOrNull { it.id == study.id } ?: return false
        if (current.resultRef == null || current.resultStatus == SavedResultStatus.AVAILABLE ||
            study.coverage?.calculatedResult != null || study.linkProfileResult != null) return false
        return !DeploymentPlanStore.sameInputs(current, study)
    }

    /** What moving every candidate-site plan to Saved Plans would create, and what can't carry over exactly. */
    data class CandidateConversion(val plans: List<StudyPlan>, val studies: List<SavedStudy>, val notes: List<String>)

    /**
     * Each candidate-site plan becomes a study plan of the same name; each site an inputs-only Estimated
     * Coverage study at its location with its equipment, and the tool's defaults for what a site doesn't
     * hold. Nothing is calculated. Sites Coverage can't place (beyond 85° latitude) are listed, not moved.
     */
    fun candidateConversion(heightInFeet: Boolean): CandidateConversion {
        val defaults = org.nodescope.android.feature.coverage.EstimatedCoverageViewModel.defaultValues(heightInFeet)
        fun default(key: String) = defaults.getValue(key).toDouble()
        fun defaultHeight(key: String) = default(key).let { if (heightInFeet) it * 0.3048 else it }
        // The tool's starting study area (EstimatedCoverageViewModel).
        val radius = if (heightInFeet) 16_093 else 20_000
        val spacing = if (heightInFeet) 1609 else 1000
        val now = System.currentTimeMillis()
        val containers = mutableListOf<StudyPlan>()
        val converted = mutableListOf<SavedStudy>()
        val notes = mutableListOf<String>()
        plans.forEachIndexed { planIndex, plan ->
            val created = now - planIndex
            val container = StudyPlan(newStudyID(), plan.name, null, created, created)
            containers += container
            plan.sites.forEachIndexed { siteIndex, site ->
                val label = "${plan.name} › ${site.name}"
                if (site.latitude !in -85.0..85.0) {
                    notes += "$label: too close to a pole for Coverage, so it isn't moved."
                    return@forEachIndexed
                }
                val assumptions = site.assumptions
                fun value(saved: Double?, fallback: Double, range: ClosedFloatingPointRange<Double>, what: String): Double {
                    if (saved == null) return fallback
                    if (saved in range) return saved
                    notes += "$label: $what is outside what Coverage accepts; the default is used."
                    return fallback
                }
                val loss = if (assumptions.cableLossDB == null && assumptions.additionalLossDB == null) default("txLoss")
                    else (assumptions.cableLossDB ?: 0.0) + (assumptions.additionalLossDB ?: 0.0)
                val coverage = SavedCoverage(
                    SavedEndpoint(site.name, site.latitude, site.longitude, SavedAntenna(
                        value(assumptions.antennaHeightMeters, defaultHeight("txHeight"), 1.0..3000.0, "antenna height"),
                        assumptions.antennaGainDBi ?: default("txGain"), loss)),
                    SavedAntenna(defaultHeight("rxHeight"), default("rxGain"), default("rxLoss")),
                    value(assumptions.frequencyMHz, default("frequency"), 30.0..6000.0, "frequency"),
                    assumptions.transmitPowerDBm ?: default("power"), default("sensitivity"), default("reserve"),
                    radius, spacing, emptyList(), false)
                // Later sites slightly older, so the plan lists them in their original order.
                val time = created - siteIndex
                val study = SavedStudy(newStudyID(), site.name, time, time, SavedStudy.Kind.COVERAGE, coverage = coverage, planID = container.id)
                if (runCatching { study.validate() }.isFailure) notes += "$label: its values can't be used for Coverage, so it isn't moved."
                else converted += study
            }
        }
        return CandidateConversion(containers, converted, notes)
    }

    /** Adds the conversion's plans and studies and removes every candidate-site plan, in one save. */
    suspend fun moveCandidateSites(conversion: CandidateConversion): Boolean =
        commit(emptyList(), studies + conversion.studies, studyPlans + conversion.plans) != null

    suspend fun createStudyPlan(name: String, notes: String?): StudyPlan? {
        val now = System.currentTimeMillis()
        val plan = StudyPlan(newStudyID(), name, cleanNotes(notes), now, now)
        return commit(plans, studies, studyPlans + plan)?.studyPlans?.firstOrNull { it.id == plan.id }
    }

    suspend fun editStudyPlan(id: String, name: String, notes: String?): Boolean {
        if (studyPlans.none { it.id == id }) return missingPlan()
        val now = System.currentTimeMillis()
        return commit(plans, studies, studyPlans.map {
            if (it.id == id) it.copy(name = name, notes = cleanNotes(notes), updatedAtMilliseconds = maxOf(it.createdAtMilliseconds, now)) else it
        }) != null
    }

    /** Removes the plan and keeps its studies as standalone studies, in one commit. */
    suspend fun deleteStudyPlan(id: String): Boolean =
        commit(plans, studies.map { if (it.planID == id) it.copy(planID = null) else it }, studyPlans.filterNot { it.id == id }) != null

    suspend fun renameStudy(id: String, name: String): Boolean = changeStudy(id) { it.copy(name = name) }

    /** Changes membership only; never touches inputs or results. */
    suspend fun moveStudy(id: String, planID: String?): Boolean {
        if (planID != null && studyPlans.none { it.id == planID }) return missingPlan()
        return changeStudy(id) { it.copy(planID = planID) }
    }

    /** A new independent record with the same inputs and result, in the same place. */
    suspend fun duplicateStudy(id: String): SavedStudy? {
        val original = studies.firstOrNull { it.id == id } ?: run { missingStudy(); return null }
        val now = System.currentTimeMillis()
        val copy = original.copy(id = newStudyID(), name = uniqueName(copyName(original.name)), createdAtMilliseconds = now,
            updatedAtMilliseconds = now, revision = null, importOrigin = null, resultStatus = SavedResultStatus.NONE)
        return commit(plans, studies + copy, touch(studyPlans, setOf(copy.planID), now))?.savedStudies?.firstOrNull { it.id == copy.id }
    }

    /** Appends whole records in one save (an import). Nothing changes if it fails. */
    suspend fun addRecords(newPlans: List<DeploymentPlan>, newContainers: List<StudyPlan>, newStudies: List<SavedStudy>): Boolean =
        commit(plans + newPlans, studies + newStudies, studyPlans + newContainers) != null

    suspend fun deleteStudy(id: String): Boolean {
        val planID = studies.firstOrNull { it.id == id }?.planID
        return commit(plans, studies.filterNot { it.id == id }, touch(studyPlans, setOf(planID), System.currentTimeMillis())) != null
    }

    fun plan(id: String?): StudyPlan? = id?.let { wanted -> studyPlans.firstOrNull { it.id == wanted } }

    /** [base], or `base 2`, `base 3`… if a study already has that name. */
    fun uniqueName(base: String): String = uniqueName(base, studies.map { it.name }.toSet())

    private suspend fun changeStudy(id: String, change: (SavedStudy) -> SavedStudy): Boolean {
        val before = studies.firstOrNull { it.id == id } ?: return missingStudy()
        val now = System.currentTimeMillis()
        val after = change(before).let { it.copy(updatedAtMilliseconds = maxOf(it.createdAtMilliseconds, now)) }
        return commit(plans, studies.map { if (it.id == id) after else it }, touch(studyPlans, setOf(before.planID, after.planID), now)) != null
    }

    private fun missingStudy(): Boolean { error = UiText.Resource(R.string.planner_that_study_is_no_longer_in_saved_plans); return false }
    private fun missingPlan(): Boolean { error = UiText.Resource(R.string.planner_that_plan_no_longer_exists); return false }

    private fun apply(document: DeploymentPlanDocument) {
        plans = document.plans
        studies = document.savedStudies
        studyPlans = document.studyPlans.orEmpty()
    }

    private suspend fun commit(nextPlans: List<DeploymentPlan>, nextStudies: List<SavedStudy>,
                               nextContainers: List<StudyPlan> = studyPlans): DeploymentPlanDocument? {
        if (!loaded || busy) return null
        busy = true
        try {
            val saved = withContext(Dispatchers.IO) { store.save(DeploymentPlanDocument(3, nextPlans, nextStudies, studyPlans = nextContainers)) }
            apply(saved)
            error = null
            return saved
        } catch (failure: DeploymentPlanStoreException) {
            error = if (failure.reason == DeploymentPlanStoreFailure.STORAGE_FULL)
                UiText.Resource(R.string.planner_there_isn_t_enough_storage_to_save_this)
            else UiText.Resource(R.string.planner_saved_plans_changed_outside_this_editor_your_draft)
        } catch (failure: DeploymentPlanException) {
            error = if (failure.reason == DeploymentPlanFailure.INVALID_DATA)
                UiText.Resource(R.string.planner_this_change_couldn_t_be_saved_a_name)
            else UiText.Resource(R.string.planner_the_plan_could_not_be_saved_your_draft)
        } catch (_: Exception) {
            error = UiText.Resource(R.string.planner_the_plan_could_not_be_saved_your_draft)
        } finally { busy = false }
        return null
    }

    companion object {
        @Volatile private var instance: SavedPlansLibrary? = null

        /** The app's library, at filesDir/CandidateSites/plans.json (never the cache). */
        fun shared(context: Context): SavedPlansLibrary = instance ?: synchronized(this) {
            instance ?: SavedPlansLibrary(DeploymentPlanStore(File(context.applicationContext.filesDir, "CandidateSites/plans.json")))
                .also { instance = it }
        }

        fun newStudyID(): String = UUID.randomUUID().toString()

        fun copyName(name: String): String = fit(name, " copy")

        /** `base`, or `base 2`, `base 3`… if [names] already has that name. Imports name their copies this way too. */
        fun uniqueName(base: String, names: Set<String>): String {
            if (base !in names) return base
            return generateSequence(2) { it + 1 }.map { fit(base, " $it") }.first { it !in names }
        }

        /** Most recently updated first; the ID breaks ties so the order is stable. */
        fun <T> sorted(items: List<T>, id: (T) -> String, updated: (T) -> Long): List<T> =
            items.sortedWith(compareByDescending(updated).thenBy(id))

        private fun touch(containers: List<StudyPlan>, ids: Set<String?>, now: Long) =
            containers.map { if (it.id in ids) it.copy(updatedAtMilliseconds = maxOf(it.createdAtMilliseconds, now)) else it }

        private fun cleanNotes(notes: String?) = notes?.takeIf { it.isNotBlank() }

        /** Appends [suffix], shortening [base] by whole code points to stay within the 160-byte name limit. */
        private fun fit(base: String, suffix: String): String {
            var trimmed = base
            while ((trimmed + suffix).toByteArray(Charsets.UTF_8).size > 160 && trimmed.isNotEmpty())
                trimmed = trimmed.substring(0, trimmed.offsetByCodePoints(trimmed.length, -1))
            return trimmed + suffix
        }
    }
}
