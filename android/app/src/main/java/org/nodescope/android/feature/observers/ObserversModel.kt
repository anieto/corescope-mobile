package org.nodescope.android.feature.observers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import org.nodescope.android.core.model.MeshObserver
import org.nodescope.android.core.model.parseInstant
import org.nodescope.android.core.network.BrowseRepository
import org.nodescope.android.core.network.KeyedLoader
import org.nodescope.android.core.network.ResponseCache

enum class ObserverActivity(val title: String) { ALL("All activity"), RECENT("Active in 15 minutes") }
enum class ObserverSort(val title: String) { RECENT("Recently seen"), HOURLY("Packets per hour"), TOTAL("Total packets"), NAME("Name") }

const val ACTIVE_WINDOW_MILLIS = 15 * 60_000L

fun MeshObserver.isActive(now: Long): Boolean = parseInstant(lastSeen)?.let { now - it.toEpochMilli() < ACTIVE_WINDOW_MILLIS } == true

/** Same meanings as iOS: region is the observer's IATA code; activity means seen within 15 minutes. */
fun visibleObservers(
    observers: List<MeshObserver>, region: String?, activity: ObserverActivity, model: String?,
    query: String, sort: ObserverSort, now: Long = System.currentTimeMillis(),
): List<MeshObserver> {
    val text = query.trim()
    val lastSeen = { observer: MeshObserver -> parseInstant(observer.lastSeen) ?: Instant.MIN }
    val comparator: Comparator<MeshObserver> = when (sort) {
        ObserverSort.RECENT -> compareByDescending(lastSeen)
        ObserverSort.HOURLY -> compareByDescending<MeshObserver> { it.packetsLastHour ?: -1L }.thenByDescending(lastSeen)
        ObserverSort.TOTAL -> compareByDescending<MeshObserver> { it.packetCount ?: -1L }.thenByDescending(lastSeen)
        ObserverSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
    }
    return observers.filter { observer ->
        (region == null || observer.iata.equals(region, ignoreCase = true)) &&
            (activity == ObserverActivity.ALL || observer.isActive(now)) &&
            (model == null || observer.model == model) &&
            (text.isEmpty() || listOfNotNull(observer.name, observer.id, observer.iata, observer.model).any { it.contains(text, ignoreCase = true) })
    }.sortedWith(comparator)
}

/** The list and its detail screens share one observer download. */
class ObserversViewModel(repository: BrowseRepository) : ViewModel() {
    val observers = KeyedLoader(viewModelScope, 60_000, restore = { host: String -> repository.saved(ResponseCache.STALE_LIFETIME) { observers(host) } }) { host -> repository.observers(host) }
    val analytics = KeyedLoader(viewModelScope, 5 * 60_000, restore = { key: Pair<String, String> -> repository.saved(ResponseCache.DETAIL_LIFETIME) { observerAnalytics(key.first, key.second) } }) { key -> repository.observerAnalytics(key.first, key.second) }
}
