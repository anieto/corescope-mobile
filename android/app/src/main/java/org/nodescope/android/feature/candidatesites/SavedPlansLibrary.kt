package org.nodescope.android.feature.candidatesites

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
    var error by mutableStateOf<String?>(null)
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
            error = "Saved plans could not be opened. Your files have not been changed. Try again when storage is available."
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
    suspend fun saveStudy(study: SavedStudy, mode: SaveMode, destination: Destination, newPlanName: String = ""): SavedStudy? {
        val now = System.currentTimeMillis()
        var containers = studyPlans
        val target: String? = when (destination) {
            Destination.Standalone -> null
            is Destination.Plan -> {
                if (containers.none { it.id == destination.id }) { error = "That plan no longer exists. Choose another place to save."; return null }
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
                    ?: run { error = "This study was deleted since you opened it. Save it as new to keep it."; return null }
                if (study.revision != null && current.revision != study.revision) {
                    error = "This study was changed elsewhere since you opened it. Save as new to keep your version."
                    return null
                }
                saving = saving.copy(origin = current.origin, importOrigin = current.importOrigin)
                touched += current.planID
            }
            SaveMode.SAVE_AS_NEW -> saving = saving.copy(id = newStudyID(), createdAtMilliseconds = now, revision = null, importOrigin = null)
        }
        saving = saving.copy(planID = target, updatedAtMilliseconds = maxOf(saving.createdAtMilliseconds, now))
        val next = if (studies.any { it.id == saving.id }) studies.map { if (it.id == saving.id) saving else it } else studies + saving
        return commit(plans, next, touch(containers, touched, now))?.savedStudies?.firstOrNull { it.id == saving.id }
    }

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

    suspend fun deleteStudy(id: String): Boolean {
        val planID = studies.firstOrNull { it.id == id }?.planID
        return commit(plans, studies.filterNot { it.id == id }, touch(studyPlans, setOf(planID), System.currentTimeMillis())) != null
    }

    fun plan(id: String?): StudyPlan? = id?.let { wanted -> studyPlans.firstOrNull { it.id == wanted } }

    /** [base], or `base 2`, `base 3`… if a study already has that name. */
    fun uniqueName(base: String): String {
        val names = studies.map { it.name }.toSet()
        if (base !in names) return base
        return generateSequence(2) { it + 1 }.map { fit(base, " $it") }.first { it !in names }
    }

    private suspend fun changeStudy(id: String, change: (SavedStudy) -> SavedStudy): Boolean {
        val before = studies.firstOrNull { it.id == id } ?: return missingStudy()
        val now = System.currentTimeMillis()
        val after = change(before).let { it.copy(updatedAtMilliseconds = maxOf(it.createdAtMilliseconds, now)) }
        return commit(plans, studies.map { if (it.id == id) after else it }, touch(studyPlans, setOf(before.planID, after.planID), now)) != null
    }

    private fun missingStudy(): Boolean { error = "That study is no longer in Saved Plans."; return false }
    private fun missingPlan(): Boolean { error = "That plan no longer exists."; return false }

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
                "There isn’t enough storage to save this result. Your previous save is unchanged. Remove an older study, or save inputs only."
            else "Saved plans changed outside this editor. Your draft is still open. Close it before reloading the library."
        } catch (failure: DeploymentPlanException) {
            error = if (failure.reason == DeploymentPlanFailure.INVALID_DATA)
                "This change couldn’t be saved: a name may be empty or too long, or the library is full (100 plans or 100 studies)."
            else "The plan could not be saved. Your draft is still open and the previous save is unchanged."
        } catch (_: Exception) {
            error = "The plan could not be saved. Your draft is still open and the previous save is unchanged."
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
