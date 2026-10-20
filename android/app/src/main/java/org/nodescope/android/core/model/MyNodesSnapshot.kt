package org.nodescope.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object MyNodesIdentity {
    fun encode(components: List<String>): String = components.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
}

@Serializable
data class MyNodesNamespace(
    /** Active backend's canonical origin, including port and base path. */
    val origin: String, val backend: String, val selectionScope: String, val policyVersion: Int,
) {
    val id: String get() = MyNodesIdentity.encode(listOf(origin, backend, selectionScope, policyVersion.toString()))
}

@Serializable
data class MyNodesEntity private constructor(val kind: Kind, val value: String) {
    @Serializable enum class Kind {
        @SerialName("node") NODE,
        @SerialName("observer") OBSERVER,
    }
    val id: String get() = MyNodesIdentity.encode(listOf(if (kind == Kind.NODE) "node" else "observer", value))
    companion object {
        fun node(value: String) = MyNodesEntity(Kind.NODE, value.lowercase(java.util.Locale.ROOT))
        fun observer(value: String) = MyNodesEntity(Kind.OBSERVER, value)
    }
}

@Serializable
data class MyNodesMetric(
    val activity: MyNodesActivity? = null,
    val signal: MyNodesSignal.Summary? = null,
    val observer: MyNodesObserverStatus? = null,
) {
    @Serializable enum class Category(val wire: String) {
        @SerialName("activity") ACTIVITY("activity"),
        @SerialName("signal") SIGNAL("signal"),
        @SerialName("observer") OBSERVER("observer"),
    }
    val category: Category get() = if (activity != null) Category.ACTIVITY else if (signal != null) Category.SIGNAL else Category.OBSERVER
    val evaluatedAt: Long get() = activity?.evaluatedAt ?: signal?.evaluatedAt ?: observer?.evaluatedAt ?: Long.MIN_VALUE
    val fetchedAt: Long get() = activity?.fetchedAt ?: signal?.evidence?.fetchedAt ?: observer?.fetchedAt ?: Long.MIN_VALUE
    val measuredAt: Long get() = activity?.activityAt ?: signal?.evidence?.requestedTo ?: observer?.measuredAt ?: Long.MIN_VALUE
    val eligible: Boolean get() = listOfNotNull(activity, signal, observer).size == 1 &&
        (activity?.eligible ?: signal?.eligible ?: observer?.eligible ?: false)

    /** Reject assessments that crossed a recency/freshness boundary before being rendered. */
    fun eligible(now: Long): Boolean {
        if (!eligible) return false
        activity?.let {
            val current = MyNodesActivity.assess(it.activityAt, null, it.fetchedAt, now, true)
            return current.eligible && current.state == it.state
        }
        signal?.let { return it.evidence.permitsHistoryComparison(now) }
        observer?.let {
            val explicit = it.method == MyNodesObserverStatus.Method.EXPLICIT
            val current = MyNodesObserverStatus.assess(
                if (explicit) it.state == MyNodesObserverStatus.State.ACTIVE else null,
                if (explicit) it.measuredAt else null, if (explicit) null else it.measuredAt,
                it.fetchedAt, now, true)
            return current.eligible && current.state == it.state && current.method == it.method
        }
        return false
    }

    fun event(namespace: MyNodesNamespace, entity: MyNodesEntity, baseline: MyNodesBaseline): MyNodesEvent? {
        var delta: Double? = null
        val change = when {
            activity != null && baseline.metric.activity != null -> when (activity.change(baseline.metric.activity)) {
                MyNodesActivityChange.NEWLY_ACTIVE -> "newlyActive"
                MyNodesActivityChange.NO_LONGER_RECENTLY_HEARD -> "noLongerRecentlyHeard"
                null -> null
            }
            signal != null && baseline.metric.signal != null -> {
                val result = signal.visitChange(baseline.metric.signal)
                if (result?.isSignificant == true) {
                    delta = result.decibels
                    if (result.decibels > 0) "snrIncreased" else "snrDecreased"
                } else null
            }
            observer != null && baseline.metric.observer != null && observer.changed(baseline.metric.observer) ->
                if (observer.state == MyNodesObserverStatus.State.ACTIVE) "observerActive" else "observerInactive"
            else -> null
        } ?: return null
        return MyNodesEvent(MyNodesIdentity.encode(listOf(namespace.id, entity.id, category.wire, baseline.revision)),
            category, change, delta, baseline.metric, this)
    }
}

@Serializable data class MyNodesBaseline(val revision: String, val metric: MyNodesMetric)
data class MyNodesEvent(val id: String, val category: MyNodesMetric.Category, val change: String,
                       val snrDelta: Double?, val previous: MyNodesMetric, val current: MyNodesMetric)


/** Unavailable evidence must never be presented as an unchanged comparison. */
data class MyNodesReviewResult(val state: State, val event: MyNodesEvent? = null) {
    enum class State { UNAVAILABLE, FIRST_VISIT, EXPIRED_BASELINE, UNCHANGED, CHANGED }
    companion object { val unavailable = MyNodesReviewResult(State.UNAVAILABLE) }
}

fun MyNodesMetric.review(namespace: MyNodesNamespace, entity: MyNodesEntity,
                        baseline: MyNodesBaseline?, expired: Boolean): MyNodesReviewResult {
    if (!eligible) return MyNodesReviewResult.unavailable
    if (baseline == null) return MyNodesReviewResult(if (expired) MyNodesReviewResult.State.EXPIRED_BASELINE else MyNodesReviewResult.State.FIRST_VISIT)
    val previous = baseline.metric
    val elapsed = runCatching { Math.subtractExact(evaluatedAt, previous.evaluatedAt) }.getOrNull()
    if (!previous.eligible || elapsed == null || elapsed !in 0..604_800_000L ||
        fetchedAt <= previous.evaluatedAt || measuredAt < previous.measuredAt) return MyNodesReviewResult.unavailable
    when {
        signal != null && previous.signal != null -> if (signal.visitChange(previous.signal) == null) return MyNodesReviewResult.unavailable
        observer != null && previous.observer != null -> {
            if (observer.method != previous.observer.method) return MyNodesReviewResult.unavailable
            if (observer.method == MyNodesObserverStatus.Method.EXPLICIT && observer.state != previous.observer.state &&
                measuredAt <= previous.measuredAt) return MyNodesReviewResult.unavailable
        }
        activity == null || previous.activity == null -> return MyNodesReviewResult.unavailable
    }
    val event = event(namespace, entity, baseline)
    return MyNodesReviewResult(if (event == null) MyNodesReviewResult.State.UNCHANGED else MyNodesReviewResult.State.CHANGED, event)
}
