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
    /** Named study plans (version 3); kept through every save even before A2 shows them. */
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

    /**
     * Saves [study] and returns it as stored, with its revision; null if it couldn't be saved. A study
     * changed elsewhere since it was opened isn't overwritten. One deleted since it was opened is saved
     * under a new ID rather than brought back. Saving moves the library to version 3, keeping the
     * original version 1 or 2 file first.
     */
    suspend fun saveStudy(study: SavedStudy): SavedStudy? {
        val current = studies.firstOrNull { it.id == study.id }
        val opened = study.revision
        if (current != null && opened != null && current.revision != opened) {
            error = "This study was changed elsewhere since you opened it. Reopen it to see the latest, or save under a new name."
            return null
        }
        // Keep the plan membership the library has now; an old editor must not undo a later move.
        val saving = when {
            current != null -> study.copy(planID = current.planID, importOrigin = current.importOrigin, origin = current.origin)
            opened != null -> study.copy(id = newStudyID(), revision = null, planID = null, importOrigin = null)
            else -> study
        }
        val document = commit(plans, if (current != null) studies.map { if (it.id == saving.id) saving else it } else studies + saving)
        return document?.savedStudies?.firstOrNull { it.id == saving.id }
    }

    suspend fun deleteStudy(study: SavedStudy): Boolean = commit(plans, studies.filterNot { it.id == study.id }) != null

    private fun apply(document: DeploymentPlanDocument) {
        plans = document.plans
        studies = document.savedStudies
        studyPlans = document.studyPlans.orEmpty()
    }

    private suspend fun commit(nextPlans: List<DeploymentPlan>, nextStudies: List<SavedStudy>): DeploymentPlanDocument? {
        if (!loaded || busy) return null
        busy = true
        try {
            val saved = withContext(Dispatchers.IO) { store.save(DeploymentPlanDocument(3, nextPlans, nextStudies, studyPlans = studyPlans)) }
            apply(saved)
            error = null
            return saved
        } catch (failure: DeploymentPlanStoreException) {
            error = if (failure.reason == DeploymentPlanStoreFailure.STORAGE_FULL)
                "There isn’t enough storage to save this result. Your previous save is unchanged. Remove an older study, or save inputs only."
            else "Saved plans changed outside this editor. Your draft is still open. Close it before reloading the library."
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
    }
}
