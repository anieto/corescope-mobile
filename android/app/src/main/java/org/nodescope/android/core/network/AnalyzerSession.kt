package org.nodescope.android.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.nodescope.android.core.model.*
import org.nodescope.android.core.storage.PreferencesStore

data class SessionState(
    val selection: AnalyzerSelection? = null,
    val snapshot: AnalyzerSnapshot? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** Switching source/region cancels old work; new state never contains old-source data. */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyzerSession(preferences: PreferencesStore, repository: AnalyzerRepository) {
    private val refreshSequence = MutableStateFlow(0L)
    fun refresh() { refreshSequence.update { it + 1 } }
    val states: Flow<SessionState> = preferences.values.map {
        if (it.onboarded) AnalyzerSelection(it.host, it.region) else null
    }.distinctUntilChanged().flatMapLatest { selection ->
        if (selection == null) flowOf(SessionState()) else {
            var lastSnapshot: AnalyzerSnapshot? = null
            refreshSequence.flatMapLatest {
                flow {
                    emit(SessionState(selection, lastSnapshot, loading = true))
                    // The last saved snapshot keeps the map usable while the analyzer loads or is unreachable.
                    if (lastSnapshot == null) repository.saved(selection)?.let {
                        lastSnapshot = it.value
                        emit(SessionState(selection, it.value, loading = true))
                    }
                    try {
                        val fresh = repository.load(selection)
                        lastSnapshot = fresh
                        emit(SessionState(selection, fresh))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emit(SessionState(selection, lastSnapshot, error = e.message ?: "Unable to connect to analyzer."))
                    }
                }
            }
        }
    }
}
