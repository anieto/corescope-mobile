package org.nodescope.android.core.model

/**
 * Beacon's node health (iOS `BeaconNodeHealth`): hourly totals over the last seven days and the regions
 * that heard it. Beacon does not say which observer heard a packet, so there is no per-observer list.
 */
data class BeaconNodeHealth(
    /** Null on Beacon servers older than 2.2.0, which have no activity totals. */
    val observationsDay: Int?,
    val observationsWeek: Int?,
    val avgSnr: Double?,
    val avgRssi: Double?,
    val avgHops: Double?,
    /** Epoch milliseconds. */
    val lastHeard: Long?,
    val heardIn: List<HeardIn>,
) {
    data class HeardIn(val iata: String, val name: String?, val lastHeard: Long?)

    val hasActivity: Boolean get() = observationsWeek != null
}
