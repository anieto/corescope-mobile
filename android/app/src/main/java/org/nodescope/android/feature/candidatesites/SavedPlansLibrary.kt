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
    private var schemaVersion = 1

    suspend fun load() {
        if (busy || loaded) return
        busy = true
        try {
            val document = withContext(Dispatchers.IO) { store.load() }
            plans = document.plans
            studies = document.savedStudies
            schemaVersion = document.schemaVersion
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
        commit(if (plans.any { it.id == plan.id }) plans.map { if (it.id == plan.id) plan else it } else plans + plan, null)

    suspend fun deletePlan(plan: DeploymentPlan): Boolean = commit(plans.filterNot { it.id == plan.id }, null)

    /** Saving a study moves the file to version 2, backing up a version-1 original first. */
    suspend fun saveStudy(study: SavedStudy): Boolean =
        commit(plans, if (studies.any { it.id == study.id }) studies.map { if (it.id == study.id) study else it } else studies + study)

    suspend fun deleteStudy(study: SavedStudy): Boolean = commit(plans, studies.filterNot { it.id == study.id })

    private suspend fun commit(nextPlans: List<DeploymentPlan>, nextStudies: List<SavedStudy>?): Boolean {
        if (!loaded || busy) return false
        busy = true
        try {
            val version = if (nextStudies == null) schemaVersion else 2
            val document = DeploymentPlanDocument(version, nextPlans, if (version == 2) nextStudies ?: studies else null)
            withContext(Dispatchers.IO) { store.save(document) }
            plans = document.plans
            studies = document.savedStudies
            schemaVersion = version
            error = null
            return true
        } catch (_: DeploymentPlanStoreException) {
            error = "Saved plans changed outside this editor. Your draft is still open. Close it before reloading the library."
        } catch (_: Exception) {
            error = "The plan could not be saved. Your draft is still open and the previous save is unchanged."
        } finally { busy = false }
        return false
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
