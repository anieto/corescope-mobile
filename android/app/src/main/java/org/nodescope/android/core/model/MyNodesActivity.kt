package org.nodescope.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MyNodesActivityState {
    @SerialName("unknown") UNKNOWN,
    @SerialName("recentlyHeard") RECENTLY_HEARD,
    @SerialName("notRecentlyHeard") NOT_RECENTLY_HEARD,
}

@Serializable
enum class MyNodesActivityChange {
    @SerialName("newlyActive") NEWLY_ACTIVE,
    @SerialName("noLongerRecentlyHeard") NO_LONGER_RECENTLY_HEARD,
}

/** Captured assessment; epoch milliseconds match the shared fixtures. */
@Serializable
data class MyNodesActivity(
    val state: MyNodesActivityState,
    val activityAt: Long?,
    val fetchedAt: Long,
    val evaluatedAt: Long,
    val eligible: Boolean,
) {
    fun change(baseline: MyNodesActivity): MyNodesActivityChange? {
        val baselineAge = difference(evaluatedAt, baseline.evaluatedAt) ?: return null
        if (!eligible || !baseline.eligible || baselineAge !in 0..7 * 24 * 60 * 60 * 1_000L ||
            fetchedAt <= baseline.evaluatedAt || activityAt == null || baseline.activityAt == null ||
            activityAt < baseline.activityAt) return null
        return when {
            baseline.state == MyNodesActivityState.NOT_RECENTLY_HEARD &&
                state == MyNodesActivityState.RECENTLY_HEARD && activityAt > baseline.activityAt ->
                MyNodesActivityChange.NEWLY_ACTIVE
            baseline.state == MyNodesActivityState.RECENTLY_HEARD &&
                state == MyNodesActivityState.NOT_RECENTLY_HEARD -> MyNodesActivityChange.NO_LONGER_RECENTLY_HEARD
            else -> null
        }
    }

    companion object {
        private fun difference(later: Long, earlier: Long): Long? =
            runCatching { Math.subtractExact(later, earlier) }.getOrNull()

        fun assess(lastSeen: Long?, lastHeard: Long?, fetchedAt: Long, evaluatedAt: Long,
                   successfulRecord: Boolean): MyNodesActivity {
            val activityAt = listOfNotNull(lastSeen, lastHeard).maxOrNull()
            val fetchAge = difference(evaluatedAt, fetchedAt)
            val activityAge = activityAt?.let { difference(evaluatedAt, it) }
            val valid = successfulRecord && fetchAge != null && fetchAge >= 0 &&
                activityAge != null && activityAge >= -300_000
            val state = when {
                !valid -> MyNodesActivityState.UNKNOWN
                activityAge!! < 900_000 -> MyNodesActivityState.RECENTLY_HEARD
                else -> MyNodesActivityState.NOT_RECENTLY_HEARD
            }
            return MyNodesActivity(state, activityAt, fetchedAt, evaluatedAt,
                valid && fetchAge!! <= 300_000)
        }
    }
}
