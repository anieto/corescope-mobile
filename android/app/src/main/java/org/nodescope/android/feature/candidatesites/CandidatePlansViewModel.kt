package org.nodescope.android.feature.candidatesites

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Candidate Sites' editor state over the shared [SavedPlansLibrary], which owns the file, so plan
 * edits and saved studies never overwrite one another.
 */
class CandidatePlansViewModel(private val library: SavedPlansLibrary) : ViewModel() {
    val plans: List<DeploymentPlan> get() = library.plans
    val loaded: Boolean get() = library.loaded
    val busy: Boolean get() = library.busy
    val error: String? get() = library.error
    var editing by mutableStateOf<DeploymentPlan?>(null); private set

    init { viewModelScope.launch { library.load() } }

    fun reload() {
        if (busy || editing != null) return
        viewModelScope.launch { library.reload() }
    }

    fun open(plan: DeploymentPlan) { if (!busy) { editing = plan; library.error = null } }
    fun closeEditor() { if (!busy) editing = null }
    fun save(plan: DeploymentPlan) {
        val saved = plan.copy(updatedAtMilliseconds = maxOf(plan.createdAtMilliseconds, System.currentTimeMillis()))
        viewModelScope.launch { if (library.savePlan(saved)) editing = null }
    }
    fun delete(plan: DeploymentPlan) { viewModelScope.launch { library.deletePlan(plan) } }

    companion object {
        fun newPlan(): DeploymentPlan {
            val now = System.currentTimeMillis()
            return DeploymentPlan(UUID.randomUUID().toString(), "", now, now, sites = emptyList())
        }
    }
}
