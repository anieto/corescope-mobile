package org.nodescope.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Explicit online status and inferred recency are intentionally separate methods. */
@Serializable
data class MyNodesObserverStatus(
    val method: Method,
    val state: State,
    val measuredAt: Long?,
    val fetchedAt: Long,
    val evaluatedAt: Long,
    val eligible: Boolean,
) {
    @Serializable enum class Method {
        @SerialName("explicit") EXPLICIT,
        @SerialName("recentlySeen") RECENTLY_SEEN,
    }
    @Serializable enum class State {
        @SerialName("unknown") UNKNOWN,
        @SerialName("active") ACTIVE,
        @SerialName("inactive") INACTIVE,
    }

    fun changed(baseline: MyNodesObserverStatus): Boolean {
        val elapsed = difference(evaluatedAt, baseline.evaluatedAt) ?: return false
        if (!eligible || !baseline.eligible || method != baseline.method ||
            state == State.UNKNOWN || baseline.state == State.UNKNOWN || state == baseline.state ||
            elapsed !in 0..604_800_000L || fetchedAt <= baseline.evaluatedAt ||
            measuredAt == null || baseline.measuredAt == null || measuredAt < baseline.measuredAt) return false
        return method == Method.RECENTLY_SEEN && state == State.INACTIVE || measuredAt > baseline.measuredAt
    }

    companion object {
        private fun difference(later: Long, earlier: Long): Long? =
            runCatching { Math.subtractExact(later, earlier) }.getOrNull()

        fun assess(explicitOnline: Boolean?, statusAt: Long?, lastSeen: Long?, fetchedAt: Long,
                   evaluatedAt: Long, successfulRecord: Boolean): MyNodesObserverStatus {
            if (explicitOnline != null && statusAt != null) {
                val fetchAge = difference(evaluatedAt, fetchedAt)
                val statusAge = difference(evaluatedAt, statusAt)
                val valid = successfulRecord && fetchAge != null && fetchAge >= 0 &&
                    statusAge != null && statusAge >= 0
                val state = if (!valid) State.UNKNOWN else if (explicitOnline) State.ACTIVE else State.INACTIVE
                return MyNodesObserverStatus(Method.EXPLICIT, state, statusAt, fetchedAt, evaluatedAt,
                    valid && fetchAge <= 300_000 && statusAge <= 300_000)
            }
            val activity = MyNodesActivity.assess(lastSeen, null, fetchedAt, evaluatedAt, successfulRecord)
            val state = when (activity.state) {
                MyNodesActivityState.UNKNOWN -> State.UNKNOWN
                MyNodesActivityState.RECENTLY_HEARD -> State.ACTIVE
                MyNodesActivityState.NOT_RECENTLY_HEARD -> State.INACTIVE
            }
            return MyNodesObserverStatus(Method.RECENTLY_SEEN, state, activity.activityAt,
                fetchedAt, evaluatedAt, activity.eligible)
        }
    }
}
