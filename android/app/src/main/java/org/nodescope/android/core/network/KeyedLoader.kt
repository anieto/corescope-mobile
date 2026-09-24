package org.nodescope.android.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Loaded<K, T>(
    val key: K? = null,
    val value: T? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val updatedAt: Long? = null,
)

/**
 * One request stream keyed by source/entity. A new key cancels the previous request and
 * never exposes the previous key's value; a failed refresh of the same key keeps its data.
 */
class KeyedLoader<K, T>(
    private val scope: CoroutineScope,
    private val maxAgeMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Saved data shown while a new key downloads; a restore younger than [maxAgeMillis] skips the download. */
    private val restore: (suspend (K) -> Cached<T>?)? = null,
    private val fetch: suspend (K) -> T,
) {
    private val mutable = MutableStateFlow(Loaded<K, T>())
    val state: StateFlow<Loaded<K, T>> = mutable.asStateFlow()
    private var job: Job? = null

    fun load(key: K, force: Boolean = false) {
        val current = mutable.value
        val sameKey = current.key == key
        if (!force && sameKey && (current.loading ||
                current.error == null && current.updatedAt?.let { clock() - it < maxAgeMillis } == true)) return
        job?.cancel()
        var retained = if (sameKey) current else Loaded(key)
        mutable.value = retained.copy(loading = true, error = null)
        job = scope.launch {
            if (retained.value == null) restore?.invoke(key)?.let { saved ->
                retained = Loaded(key, saved.value, updatedAt = saved.savedAt)
                val fresh = !force && clock() - saved.savedAt < maxAgeMillis
                mutable.value = retained.copy(loading = !fresh)
                if (fresh) return@launch
            }
            try {
                val value = fetch(key)
                mutable.value = Loaded(key, value, updatedAt = clock())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = retained.copy(loading = false, error = e.message ?: "Unable to reach the analyzer.")
            }
        }
    }
}
