package org.nodescope.android.core.network

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One analyzer source selection (iOS `AnalyzerSourceSession`). Switching host or backend
 * invalidates it: in-flight requests registered here are cancelled, and every later [check]
 * fails, so a late response can never reach the newly selected source's state.
 */
class AnalyzerSourceSession {
    val id: UUID = UUID.randomUUID()
    private val lock = Any()
    private var valid = true
    private val cancellations = LinkedHashMap<UUID, () -> Unit>()

    val isCurrent: Boolean get() = synchronized(lock) { valid }

    fun invalidate() {
        val callbacks = synchronized(lock) {
            valid = false
            cancellations.values.toList().also { cancellations.clear() }
        }
        callbacks.forEach { it() }
    }

    /** Runs [cancel] when the session is invalidated (at once if it already is). */
    fun registerCancellation(cancel: () -> Unit): UUID {
        val identifier = UUID.randomUUID()
        val alreadyInvalid = synchronized(lock) {
            if (valid) cancellations[identifier] = cancel
            !valid
        }
        if (alreadyInvalid) cancel()
        return identifier
    }

    fun removeCancellation(identifier: UUID) {
        synchronized(lock) { cancellations.remove(identifier) }
    }

    /** Throws when the caller was cancelled or this source is no longer selected. */
    suspend fun check() {
        currentCoroutineContext().ensureActive()
        checkCurrent()
    }

    fun checkCurrent() {
        if (!isCurrent) throw CancellationException("The analyzer source changed.")
    }
}
