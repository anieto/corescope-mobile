package org.nodescope.android.feature.candidatesites

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CandidatePlansViewModel(private val store: DeploymentPlanStore) : ViewModel() {
    var plans by mutableStateOf<List<DeploymentPlan>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var editing by mutableStateOf<DeploymentPlan?>(null); private set
    /** The library as loaded; saves replace only its plans, keeping its version and saved studies. */
    private var document = DeploymentPlanDocument(1, emptyList())

    init { reload() }

    fun reload() {
        if (busy || editing != null) return
        busy = true
        loaded = false
        viewModelScope.launch {
            try {
                document = withContext(Dispatchers.IO) { store.load() }
                plans = document.plans
                loaded = true
                error = null
            } catch (_: Exception) {
                error = "Saved plans could not be opened. Your files have not been changed. Try again when storage is available."
            } finally { busy = false }
        }
    }

    fun open(plan: DeploymentPlan) { if (!busy) { editing = plan; error = null } }
    fun closeEditor() { if (!busy) editing = null }
    fun save(plan: DeploymentPlan) {
        val saved = plan.copy(updatedAtMilliseconds = maxOf(plan.createdAtMilliseconds, System.currentTimeMillis()))
        val next = if (plans.any { it.id == plan.id }) plans.map { if (it.id == plan.id) saved else it } else plans + saved
        commit(next, true)
    }
    fun delete(plan: DeploymentPlan) = commit(plans.filterNot { it.id == plan.id }, false)

    private fun commit(next: List<DeploymentPlan>, closeEditor: Boolean) {
        if (!loaded || busy) return
        busy = true
        viewModelScope.launch {
            try {
                val updated = document.copy(plans = next)
                withContext(Dispatchers.IO) { store.save(updated) }
                document = updated
                plans = next
                error = null
                if (closeEditor) editing = null
            } catch (_: DeploymentPlanStoreException) {
                error = "Saved plans changed outside this editor. Your draft is still open. Close it before reloading the library."
            } catch (_: Exception) {
                error = "The plan could not be saved. Your draft is still open and the previous save is unchanged."
            } finally { busy = false }
        }
    }

    companion object {
        fun newPlan(): DeploymentPlan {
            val now = System.currentTimeMillis()
            return DeploymentPlan(UUID.randomUUID().toString(), "", now, now, sites = emptyList())
        }
    }
}
