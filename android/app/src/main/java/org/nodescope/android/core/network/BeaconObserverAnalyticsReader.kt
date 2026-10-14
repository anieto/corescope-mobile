package org.nodescope.android.core.network

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import org.nodescope.android.core.model.*

/**
 * Port of iOS BeaconObserverAnalyticsReader: the 24-hour activity and telemetry endpoints, fetched
 * together (two requests). Activity counts are observations; telemetry fields stay null when omitted,
 * and empty activity or telemetry is a successful empty result.
 */
/** Below this a `batteryMv` value is not a plausible cell voltage. */
const val MINIMUM_BATTERY_MILLIVOLTS = 1_000

class BeaconObserverAnalyticsReader(val client: BeaconApiClient, verifiedServerInfo: BeaconServerInfo) {
    init { verifiedServerInfo.requireSupported() }

    @Serializable private data class Summary(val recordedPackets: Int)
    @Serializable private data class PayloadCount(val payloadType: Int, val count: Long)
    @Serializable
    private data class ActivityPoint(val t: Long, val observations: Long, val airtimeMs: Double? = null,
        val snrAvg: Double? = null, val snrMin: Double? = null, val rssiAvg: Double? = null)
    @Serializable
    private data class Activity(val windowStart: Long, val windowEnd: Long, val generatedAt: Long, val source: String,
        val summary: Summary, val rolledUntil: Long? = null, val rawFrom: Long? = null, val range: String, val interval: String,
        val payloadTypes: List<PayloadCount> = emptyList(), val points: List<ActivityPoint> = emptyList())
    @Serializable
    // pyMC observers report airtime as fractional seconds (e.g. 13761.747).
    private data class TelemetryPoint(val t: Long, val batteryMv: Int? = null, val airtimeTxSecs: Double? = null,
        val airtimeRxSecs: Double? = null, val noiseFloorDb: Double? = null, val uptimeSeconds: Long? = null,
        val queueLength: Int? = null, val receiveErrors: Long? = null)
    @Serializable private data class Telemetry(val range: String, val interval: String, val points: List<TelemetryPoint> = emptyList())

    suspend fun analytics(observerId: String): ObserverAnalytics {
        if (!isUuid(observerId)) throw BeaconApiException.InvalidResponse()
        val id = observerId.lowercase()
        val (activity, telemetry) = coroutineScope {
            val activity = async {
                client.get(Activity.serializer(), listOf("observers", id, "activity"), listOf("range" to "24h", "interval" to "15m"))
            }
            val telemetry = async {
                client.get(Telemetry.serializer(), listOf("observers", id, "telemetry"), listOf("range" to "24h", "interval" to "1h"))
            }
            activity.await() to telemetry.await()
        }
        validate(activity)
        validate(telemetry)
        client.sourceSession.check()

        val label = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())
        return ObserverAnalytics(
            context = AnalyticsContext(
                AnalyticsCompleteness.COMPLETE, AnalyticsCountUnit.OBSERVATIONS, AnalyticsProvenance.SERVER,
                activity.windowStart, activity.windowEnd, activity.windowStart, activity.windowEnd,
                "Beacon reports observer activity as observations. Unique-node history, recent packet rows, and a true SNR distribution aren’t available from this endpoint.",
            ),
            timeline = activity.points.map { LabeledCount(label.format(Instant.ofEpochMilli(it.t)), it.observations) },
            packetTypes = activity.payloadTypes.associate { it.payloadType.toString() to it.count },
            telemetry = telemetry.points.map {
                // Mains-powered observers report 0 mV (no battery), and some send a percentage in
                // this millivolt field (98 seen live). Neither is a voltage.
                // A real noise floor is well below zero; 0 dB means not reported.
                ObserverTelemetryPoint(it.t, it.batteryMv?.takeIf { mv -> mv >= MINIMUM_BATTERY_MILLIVOLTS }, it.airtimeTxSecs?.roundToLong(),
                    it.airtimeRxSecs?.roundToLong(), it.noiseFloorDb?.takeIf { db -> db != 0.0 },
                    it.uptimeSeconds, it.queueLength, it.receiveErrors)
            },
        )
    }

    private fun validate(activity: Activity) {
        val valid = activity.windowStart <= activity.windowEnd && activity.generatedAt >= activity.windowStart &&
            activity.range == "24h" && activity.interval == "15m" && activity.summary.recordedPackets >= 0 &&
            activity.payloadTypes.all { it.payloadType >= 0 && it.count >= 0 } &&
            activity.points.all { point ->
                point.t in activity.windowStart..activity.windowEnd && point.observations >= 0 &&
                    point.airtimeMs?.let { it.isFinite() && it >= 0 } != false &&
                    point.snrAvg?.isFinite() != false && point.snrMin?.isFinite() != false && point.rssiAvg?.isFinite() != false
            }
        if (!valid) throw BeaconApiException.InvalidResponse()
    }

    private fun validate(telemetry: Telemetry) {
        val valid = telemetry.range == "24h" && telemetry.interval == "1h" && telemetry.points.all { point ->
            (point.batteryMv ?: 0) >= 0 && point.airtimeTxSecs?.let { it.isFinite() && it >= 0 } != false &&
                point.airtimeRxSecs?.let { it.isFinite() && it >= 0 } != false &&
                point.noiseFloorDb?.isFinite() != false && (point.uptimeSeconds ?: 0) >= 0 &&
                (point.queueLength ?: 0) >= 0 && (point.receiveErrors ?: 0) >= 0
        }
        if (!valid) throw BeaconApiException.InvalidResponse()
    }
}
