package org.nodescope.android.core.model

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import org.nodescope.android.R
import org.nodescope.android.core.design.UiText
import org.nodescope.android.core.design.formatDisplayDecimal
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.*
import org.nodescope.android.core.network.protocolJson

/** Everything Node Check needs about one node, read from the analyzer. Times are epoch millis. */
data class NodeCheckInput(
    val node: Node,
    /** Newest first; adverts only. */
    val adverts: List<Advert>,
    val directObservers: List<DirectObserver>,
    val healthLastHeard: Long?,
    val clock: Clock?,
    /** Observer id → region code, for reports that don't carry one. */
    val observerRegions: Map<String, String>,
) {
    data class Node(
        val publicKey: String, val name: String?, val role: String, val lat: Double?, val lon: Double?,
        val lastHeard: Long?, val lastSeen: Long?, val lastRelayed: Long?, val firstSeen: Long?,
    )
    data class Observation(val observerId: String, val observerName: String?, val region: String?, val hops: Int?, val snr: Double?, val rssi: Double?)
    /** [kind] is "flood", "zero_hop", or null when the analyzer doesn't say. */
    data class Advert(val id: Long, val sentAt: Long?, val kind: String?, val observations: List<Observation>)
    data class DirectObserver(val observerId: String, val name: String?, val averageSnr: Double?, val averageRssi: Double?, val packets: Int?)
    /** CoreScope's severity (ok, warning, critical, absurd, no_clock, bimodal_clock); offset is ahead (+) or behind (−). */
    data class Clock(val severity: String, val offsetSeconds: Double?)
}

enum class NodeCheckLevel { BAD, WARN, GOOD, INFO }

data class NodeCheckFinding(val level: NodeCheckLevel, val title: UiText, val detail: UiText, val fix: UiText? = null)

data class HeardObserver(
    val observerId: String, val name: String?, val region: String?, val hops: Int?,
    val snr: Double?, val rssi: Double?, val packets: Int? = null,
) {
    val displayName: UiText
        get() = name?.let(UiText::Raw) ?: UiText.Resource(R.string.node_check_rule_unnamed_observer)
}

data class AdvertReach(val sentAt: Long?, val kind: String?, val observers: List<HeardObserver>, val regions: List<String>)

data class NodeCheckReport(
    val node: NodeCheckInput.Node,
    val level: NodeCheckLevel,
    val headline: UiText,
    /** Problems first, then what's working, then notes. */
    val findings: List<NodeCheckFinding>,
    val latestAdvert: AdvertReach?,
    /** Observers that heard the node itself, strongest first. */
    val direct: List<HeardObserver>,
    val lastHeard: Long?,
)

/**
 * Node Check's findings. Mirrors iOS `NodeCheckRules` and the meshtexas.org Node Check rules
 * (functions/_shared/node-check.js in meshtexas-site); change all three together.
 */
object NodeCheckRules {
    private const val HOUR = 3_600_000L
    private const val DAY = 86_400_000L
    private val infrastructure = setOf("repeater", "room")

    fun report(input: NodeCheckInput, now: Long = System.currentTimeMillis()): NodeCheckReport {
        val role = input.node.role.lowercase()
        val latestAdvert = input.adverts.firstOrNull()?.let { reach(listOf(it), input.observerRegions) }
        val latestFlood = input.adverts.firstOrNull { it.kind == "flood" }?.let { reach(listOf(it), input.observerRegions) }
        val direct = input.directObservers.map {
            HeardObserver(it.observerId, it.name, input.observerRegions[it.observerId], 0,
                it.averageSnr?.let(::round1), it.averageRssi?.let { value -> value.roundToInt().toDouble() }, it.packets)
        }.sortedByDescending { it.snr ?: -99.0 }

        val lastHeard = input.node.lastHeard ?: input.healthLastHeard ?: input.node.lastSeen
        val quiet = lastHeard?.let { now - it } ?: Long.MAX_VALUE
        val findings = mutableListOf<NodeCheckFinding>()
        fun add(level: NodeCheckLevel, title: UiText, detail: UiText, fix: UiText? = null) { findings += NodeCheckFinding(level, title, detail, fix) }

        // Last heard.
        if (role in infrastructure) {
            val silentAfter = if (role == "room") 3 * DAY else 2 * DAY
            when {
                lastHeard != null && quiet <= DAY ->
                    add(NodeCheckLevel.GOOD, text(R.string.node_check_rule_heard, ago(lastHeard, now)), text(R.string.node_check_rule_regularly))
                lastHeard != null && quiet <= silentAfter ->
                    add(NodeCheckLevel.WARN, text(R.string.node_check_rule_quiet, ago(lastHeard, now)),
                        if (role == "room") text(R.string.node_check_rule_room_advert_cadence)
                        else text(R.string.node_check_rule_repeater_advert_cadence),
                        text(R.string.node_check_rule_fix_quiet))
                else -> add(NodeCheckLevel.BAD,
                    lastHeard?.let { text(R.string.node_check_rule_not_heard_for, duration(now - it)) } ?: text(R.string.node_check_rule_no_recent_traffic),
                    if (role == "room") text(R.string.node_check_rule_room_silent)
                    else text(R.string.node_check_rule_repeater_silent),
                    text(R.string.node_check_rule_fix_silent))
            }
        } else if (lastHeard != null) {
            add(if (quiet <= 14 * DAY) NodeCheckLevel.INFO else NodeCheckLevel.WARN, text(R.string.node_check_rule_last_heard, ago(lastHeard, now)),
                text(R.string.node_check_rule_companion_quiet),
                if (quiet > 14 * DAY) text(R.string.node_check_rule_fix_companion_quiet) else null)
        }

        // Reach of the most recent flood advert.
        if (latestFlood != null && latestFlood.observers.isNotEmpty()) {
            val count = latestFlood.observers.size
            val observerCount = countText(R.plurals.node_check_observer_count, count)
            val title = if (latestFlood.regions.isEmpty()) text(R.string.node_check_rule_flood_reached, observerCount)
                else text(R.string.node_check_rule_flood_reached_regions, observerCount,
                    countText(R.plurals.node_check_region_count, latestFlood.regions.size), latestFlood.regions.joinToString(", "))
            add(NodeCheckLevel.GOOD, title,
                latestFlood.sentAt?.let { text(R.string.node_check_rule_advert_sent, ago(it, now)) } ?: text(R.string.node_check_rule_advert_time_unknown),
                if (count < 3) text(R.string.node_check_rule_few_observers) else null)
        } else if (input.adverts.isNotEmpty()) {
            add(NodeCheckLevel.INFO, text(R.string.node_check_rule_no_recent_flood),
                text(R.string.node_check_rule_zero_hop_reach),
                text(R.string.node_check_rule_fix_send_flood))
        } else {
            add(NodeCheckLevel.INFO, text(R.string.node_check_rule_no_recent_adverts), text(R.string.node_check_rule_analyzer_has_no_recent_adverts),
                text(R.string.node_check_rule_fix_send_flood))
        }

        // Nearest observer.
        val best = direct.firstOrNull()
        if (best != null) {
            val detail = best.snr?.let { text(R.string.node_check_rule_strongest_with_snr, best.displayName, signedDecibels(it)) }
                ?: text(R.string.node_check_rule_strongest, best.displayName)
            add(NodeCheckLevel.GOOD,
                countText(R.plurals.node_check_rule_direct_observers, direct.size), detail)
        } else {
            val nearest = latestFlood?.observers?.firstOrNull()?.hops
            if (nearest != null && nearest > 0) add(NodeCheckLevel.INFO, text(R.string.node_check_rule_nearest_observer, countText(R.plurals.node_check_rule_hops, nearest)),
                text(R.string.node_check_rule_repeater_path))
        }

        // Repeaters should be relaying.
        if (role == "repeater" && quiet <= 2 * DAY) {
            val lastRelayed = input.node.lastRelayed
            when {
                lastRelayed == null -> add(NodeCheckLevel.WARN, text(R.string.node_check_rule_not_relaying),
                    text(R.string.node_check_rule_not_relaying_detail),
                    text(R.string.node_check_rule_fix_enable_repeating))
                now - lastRelayed > 2 * DAY -> add(NodeCheckLevel.WARN, text(R.string.node_check_rule_last_relayed, ago(lastRelayed, now)),
                    text(R.string.node_check_rule_hasnt_relayed),
                    text(R.string.node_check_rule_fix_check_repeating))
                else -> add(NodeCheckLevel.GOOD, text(R.string.node_check_rule_relaying),
                    text(R.string.node_check_rule_relaying_detail, ago(lastRelayed, now)))
            }
        }

        // Clock.
        input.clock?.let { clock ->
            val fix = text(R.string.node_check_rule_fix_clock)
            when (clock.severity) {
                "warning", "critical" -> add(NodeCheckLevel.WARN, text(R.string.node_check_rule_clock_off, describeOffset(clock.offsetSeconds)),
                    text(R.string.node_check_rule_adverts_treated_as_old), fix)
                "bimodal_clock" -> add(NodeCheckLevel.WARN, text(R.string.node_check_rule_clock_jumps),
                    text(R.string.node_check_rule_clock_jumps_detail), fix)
                "absurd", "no_clock" -> add(NodeCheckLevel.WARN, text(R.string.node_check_rule_clock_not_set),
                    text(R.string.node_check_rule_clock_not_set_detail), fix)
                "ok" -> if (role in infrastructure) add(NodeCheckLevel.GOOD, text(R.string.node_check_rule_clock_right), text(R.string.node_check_rule_clock_right_detail))
            }
        }

        // Location, only for nodes meant to be found on a map.
        val hasLocation = input.node.lat != null && input.node.lon != null && !(input.node.lat == 0.0 && input.node.lon == 0.0)
        if (role in infrastructure && !hasLocation) add(NodeCheckLevel.INFO, text(R.string.node_check_rule_no_location),
            text(R.string.node_check_rule_no_location_detail),
            text(R.string.node_check_rule_fix_location))

        val sorted = findings.sortedBy { it.level.ordinal } // stable: keeps order within a level
        val level = sorted.firstOrNull()?.level ?: NodeCheckLevel.INFO
        val headline = when (level) {
            NodeCheckLevel.BAD -> text(R.string.node_check_rule_headline_bad)
            NodeCheckLevel.WARN -> text(R.string.node_check_rule_headline_warn)
            NodeCheckLevel.GOOD -> text(R.string.node_check_rule_headline_good)
            NodeCheckLevel.INFO -> text(R.string.node_check_rule_headline_info)
        }
        return NodeCheckReport(input.node, level, headline, sorted, latestFlood ?: latestAdvert, direct, lastHeard)
    }

    /** Adverts and everyone who reported them, closest first; each observer once with its shortest path. */
    fun reach(adverts: List<NodeCheckInput.Advert>, regions: Map<String, String>): AdvertReach {
        val seen = LinkedHashMap<String, HeardObserver>()
        for (advert in adverts) for (observation in advert.observations) {
            val entry = HeardObserver(observation.observerId, observation.observerName,
                observation.region ?: regions[observation.observerId], observation.hops, observation.snr?.let(::round1), observation.rssi)
            val previous = seen[observation.observerId]
            if (previous != null && (previous.hops ?: 99) <= (entry.hops ?: 99)) continue
            seen[observation.observerId] = entry
        }
        val observers = sortClosest(seen.values)
        return AdvertReach(adverts.firstOrNull()?.sentAt, adverts.firstOrNull()?.kind, observers,
            observers.mapNotNull { it.region }.distinct().sorted())
    }

    fun sortClosest(observers: Collection<HeardObserver>): List<HeardObserver> =
        observers.sortedWith(compareBy<HeardObserver> { it.hops ?: 99 }.thenByDescending { it.snr ?: -99.0 })

    fun ago(millis: Long, now: Long = System.currentTimeMillis()): UiText {
        val diff = (now - millis).coerceAtLeast(0)
        return when {
            diff < 2 * 60_000 -> text(R.string.node_check_rule_ago_now)
            diff < HOUR -> countText(R.plurals.node_check_rule_ago_minutes, (diff / 60_000.0).roundToInt())
            diff < 2 * HOUR -> text(R.string.node_check_rule_ago_an_hour)
            diff < DAY -> countText(R.plurals.node_check_rule_ago_hours, (diff.toDouble() / HOUR).roundToInt())
            diff < 2 * DAY -> text(R.string.node_check_rule_ago_a_day)
            else -> countText(R.plurals.node_check_rule_ago_days, (diff.toDouble() / DAY).roundToInt())
        }
    }

    private fun duration(millis: Long): UiText =
        if (millis < 2 * DAY) text(R.string.node_check_rule_duration_a_day)
        else countText(R.plurals.node_check_rule_days, (millis.toDouble() / DAY).roundToInt())

    fun signedDecibels(value: Double): UiText {
        val number = formatDisplayDecimal(value, 1)
        return if (value > 0) text(R.string.node_check_rule_positive_decibels, number)
        else text(R.string.node_check_rule_decibels, number)
    }

    fun hopsLabel(hops: Int?): UiText = when (hops) {
        null -> UiText.Raw("—")
        0 -> text(R.string.node_check_rule_direct)
        else -> countText(R.plurals.node_check_rule_hops, hops)
    }

    private fun describeOffset(seconds: Double?): UiText {
        if (seconds == null || !seconds.isFinite()) return text(R.string.node_check_rule_offset_unknown)
        val magnitude = abs(seconds)
        val amount = when {
            magnitude < 3_600 -> countText(R.plurals.node_check_rule_minutes, (magnitude / 60).roundToInt())
            magnitude < 2 * 86_400 -> countText(R.plurals.node_check_rule_hours, (magnitude / 3_600).roundToInt())
            else -> countText(R.plurals.node_check_rule_days, (magnitude / 86_400).roundToInt())
        }
        return if (seconds < 0) text(R.string.node_check_rule_offset_behind, amount)
        else text(R.string.node_check_rule_offset_ahead, amount)
    }

    private fun text(@StringRes id: Int, vararg args: Any): UiText = UiText.Resource(id, *args)

    private fun countText(@PluralsRes id: Int, count: Int): UiText = UiText.Plural(id, count, count)

    private fun round1(value: Double) = (value * 10).roundToInt() / 10.0
}

/** A name, a key (8 or more hex characters), or a contact link shared from the MeshCore app. */
fun nodeCheckKey(text: String): String? {
    Regex("public_key=([0-9A-Fa-f]{64})").find(text)?.let { return it.groupValues[1].lowercase() }
    val compact = text.filterNot(Char::isWhitespace)
    return compact.takeIf { it.length in 8..64 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }?.lowercase()
}

// CoreScope responses. Read leniently: one odd field leaves that value out rather than failing the check.

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun time(text: String?): Long? = text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/** The node and its recent adverts with every observer's report, from `GET /api/nodes/{key}`. */
fun parseNodeCheckDetail(body: String, observerRegions: Map<String, String>): Pair<NodeCheckInput.Node, List<NodeCheckInput.Advert>> {
    val root = protocolJson.parseToJsonElement(body).jsonObject
    val node = root["node"]!!.jsonObject
    val parsedNode = NodeCheckInput.Node(
        publicKey = node.str("public_key")!!.lowercase(), name = node.str("name"), role = node.str("role") ?: "unknown",
        lat = node.num("lat"), lon = node.num("lon"), lastHeard = time(node.str("last_heard")), lastSeen = time(node.str("last_seen")),
        lastRelayed = time(node.str("last_relayed")), firstSeen = time(node.str("first_seen")),
    )
    val adverts = (root["recentAdverts"] as? JsonArray).orEmpty().mapNotNull { element ->
        val transmission = element as? JsonObject ?: return@mapNotNull null
        if (transmission.num("payload_type")?.toInt() != 4) return@mapNotNull null
        NodeCheckInput.Advert(
            id = transmission.num("id")?.toLong() ?: 0,
            sentAt = time(transmission.str("timestamp")) ?: time(transmission.str("first_seen")),
            kind = transmission.str("advert_kind"),
            observations = (transmission["observations"] as? JsonArray).orEmpty().mapNotNull { item ->
                val observation = item as? JsonObject ?: return@mapNotNull null
                val id = observation.str("observer_id") ?: return@mapNotNull null
                NodeCheckInput.Observation(id, observation.str("observer_name"),
                    observation.str("observer_iata") ?: observerRegions[id], hopCount(observation.str("path_json")),
                    observation.num("snr"), observation.num("rssi"))
            },
        )
    }
    return parsedNode to adverts
}

/** Direct-RF observers and the last time the node was heard, from `/api/nodes/{key}/health`. */
fun parseNodeCheckHealth(body: String): Pair<List<NodeCheckInput.DirectObserver>, Long?> {
    val root = protocolJson.parseToJsonElement(body).jsonObject
    val observers = (root["observers"] as? JsonArray).orEmpty().mapNotNull { element ->
        val observer = element as? JsonObject ?: return@mapNotNull null
        val id = observer.str("observer_id") ?: return@mapNotNull null
        NodeCheckInput.DirectObserver(id, observer.str("observer_name"), observer.num("avgSnr"), observer.num("avgRssi"),
            observer.num("packetCount")?.toInt())
    }
    return observers to time((root["stats"] as? JsonObject)?.str("lastHeard"))
}

/** `/api/nodes/{key}/clock-skew`; null when the analyzer has no clock data for the node. */
fun parseNodeCheckClock(body: String): NodeCheckInput.Clock? {
    val root = protocolJson.parseToJsonElement(body) as? JsonObject ?: return null
    val severity = root.str("severity") ?: return null
    return NodeCheckInput.Clock(severity, root.num("recentMedianSkewSec") ?: root.num("medianSkewSec"))
}

/** Repeaters a copy passed through; 0 means the observer heard the node itself. */
fun hopCount(pathJson: String?): Int? = pathJson?.let {
    runCatching { (protocolJson.parseToJsonElement(it) as? JsonArray)?.size }.getOrNull()
}
