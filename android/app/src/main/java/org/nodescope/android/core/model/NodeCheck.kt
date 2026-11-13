package org.nodescope.android.core.model

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

data class NodeCheckFinding(val level: NodeCheckLevel, val title: String, val detail: String, val fix: String? = null)

data class HeardObserver(
    val observerId: String, val name: String, val region: String?, val hops: Int?,
    val snr: Double?, val rssi: Double?, val packets: Int? = null,
)

data class AdvertReach(val sentAt: Long?, val kind: String?, val observers: List<HeardObserver>, val regions: List<String>)

data class NodeCheckReport(
    val node: NodeCheckInput.Node,
    val level: NodeCheckLevel,
    val headline: String,
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
            HeardObserver(it.observerId, it.name ?: "Unnamed observer", input.observerRegions[it.observerId], 0,
                it.averageSnr?.let(::round1), it.averageRssi?.let { value -> value.roundToInt().toDouble() }, it.packets)
        }.sortedByDescending { it.snr ?: -99.0 }

        val lastHeard = input.node.lastHeard ?: input.healthLastHeard ?: input.node.lastSeen
        val quiet = lastHeard?.let { now - it } ?: Long.MAX_VALUE
        val findings = mutableListOf<NodeCheckFinding>()
        fun add(level: NodeCheckLevel, title: String, detail: String, fix: String? = null) { findings += NodeCheckFinding(level, title, detail, fix) }

        // Last heard.
        if (role in infrastructure) {
            val silentAfter = if (role == "room") 3 * DAY else 2 * DAY
            when {
                lastHeard != null && quiet <= DAY ->
                    add(NodeCheckLevel.GOOD, "Heard ${ago(lastHeard, now)}", "Observers are hearing this node regularly.")
                lastHeard != null && quiet <= silentAfter ->
                    add(NodeCheckLevel.WARN, "Quiet: last heard ${ago(lastHeard, now)}",
                        if (role == "room") "A working room server usually sends an advert at least twice a day."
                        else "A working repeater usually turns up at least once a day, from its own adverts or the traffic it relays.",
                        "Check that it still has power and its antenna is connected. If it runs on solar, check the battery.")
                else -> add(NodeCheckLevel.BAD,
                    lastHeard?.let { "Not heard for ${duration(now - it)}" } ?: "No recent traffic",
                    if (role == "room") "Nothing from this node has reached an observer in the last three days."
                    else "Nothing from this node has reached an observer in the last two days.",
                    "Check power, the antenna and the radio settings, then send a flood advert.")
            }
        } else if (lastHeard != null) {
            add(if (quiet <= 14 * DAY) NodeCheckLevel.INFO else NodeCheckLevel.WARN, "Last heard ${ago(lastHeard, now)}",
                "Companions are only heard when they send something, so a quiet spell is normal.",
                if (quiet > 14 * DAY) "Send a flood advert to check that it still reaches the mesh." else null)
        }

        // Reach of the most recent flood advert.
        if (latestFlood != null && latestFlood.observers.isNotEmpty()) {
            val count = latestFlood.observers.size
            val regionText = if (latestFlood.regions.isEmpty()) ""
                else " in ${plural(latestFlood.regions.size, "region", "regions")} (${latestFlood.regions.joinToString(", ")})"
            add(NodeCheckLevel.GOOD, "Last flood advert reached ${plural(count, "observer", "observers")}$regionText",
                latestFlood.sentAt?.let { "Sent ${ago(it, now)}." } ?: "The time it was sent is unknown.",
                if (count < 3) "Only a few observers heard it. That can simply mean there are few observers near you, or that it needs another repeater in range to travel further." else null)
        } else if (input.adverts.isNotEmpty()) {
            add(NodeCheckLevel.INFO, "No recent flood advert",
                "Its recent adverts were zero-hop, which only reach radios in direct range, so they don’t show how far it gets.",
                "Send a flood advert to see how far it travels.")
        } else {
            add(NodeCheckLevel.INFO, "No recent adverts", "The analyzer has no recent adverts from this node.",
                "Send a flood advert to see how far it travels.")
        }

        // Nearest observer.
        val best = direct.firstOrNull()
        if (best != null) {
            val signal = best.snr?.let { " at ${signedDecibels(it)} SNR" }.orEmpty()
            add(NodeCheckLevel.GOOD,
                if (direct.size == 1) "1 observer hears it directly" else "${direct.size} observers hear it directly",
                "The strongest is ${best.name}$signal.")
        } else {
            val nearest = latestFlood?.observers?.firstOrNull()?.hops
            if (nearest != null && nearest > 0) add(NodeCheckLevel.INFO, "Nearest observer is ${plural(nearest, "hop", "hops")} away",
                "No observer hears this node directly, so it reaches the mesh through repeaters. That’s normal unless you expected an observer nearby to hear it.")
        }

        // Repeaters should be relaying.
        if (role == "repeater" && quiet <= 2 * DAY) {
            val lastRelayed = input.node.lastRelayed
            when {
                lastRelayed == null -> add(NodeCheckLevel.WARN, "Not seen relaying",
                    "Observers hear this repeater, but haven’t seen it pass on anyone else’s traffic.",
                    "Check that repeating is turned on. If it is, there may simply be no traffic near it yet.")
                now - lastRelayed > 2 * DAY -> add(NodeCheckLevel.WARN, "Last relayed ${ago(lastRelayed, now)}",
                    "It’s still heard, but hasn’t relayed traffic for a while.",
                    "Check that repeating is still on and nothing changed in its settings.")
                else -> add(NodeCheckLevel.GOOD, "Relaying traffic",
                    "Other nodes’ packets are passing through it. It last relayed one ${ago(lastRelayed, now)}.")
            }
        }

        // Clock.
        input.clock?.let { clock ->
            val fix = "Sync the clock from the MeshCore app, or by running “clock sync” on the node."
            when (clock.severity) {
                "warning", "critical" -> add(NodeCheckLevel.WARN, "Clock is off by ${describeOffset(clock.offsetSeconds)}",
                    "Nodes with the wrong time can have their adverts treated as old.", fix)
                "bimodal_clock" -> add(NodeCheckLevel.WARN, "Clock keeps jumping",
                    "Its clock sometimes reads right and sometimes far off, which usually means it loses time on restart.", fix)
                "absurd", "no_clock" -> add(NodeCheckLevel.WARN, "Clock isn’t set",
                    "Its clock is far from the real time, as if it was never set.", fix)
                "ok" -> if (role in infrastructure) add(NodeCheckLevel.GOOD, "Clock is right", "Its clock is within a few minutes of the real time.")
            }
        }

        // Location, only for nodes meant to be found on a map.
        val hasLocation = input.node.lat != null && input.node.lon != null && !(input.node.lat == 0.0 && input.node.lon == 0.0)
        if (role in infrastructure && !hasLocation) add(NodeCheckLevel.INFO, "No location set",
            "It won’t appear on the live map or the analyzer’s map.",
            "Set a location in the node’s settings if you’re comfortable sharing it.")

        val sorted = findings.sortedBy { it.level.ordinal } // stable: keeps order within a level
        val level = sorted.firstOrNull()?.level ?: NodeCheckLevel.INFO
        val headline = when (level) {
            NodeCheckLevel.BAD -> "This node has gone quiet"
            NodeCheckLevel.WARN -> "Heard, with something to check"
            NodeCheckLevel.GOOD -> "This node is getting out"
            NodeCheckLevel.INFO -> "Heard before, but nothing recent to go on"
        }
        return NodeCheckReport(input.node, level, headline, sorted, latestFlood ?: latestAdvert, direct, lastHeard)
    }

    /** Adverts and everyone who reported them, closest first; each observer once with its shortest path. */
    fun reach(adverts: List<NodeCheckInput.Advert>, regions: Map<String, String>): AdvertReach {
        val seen = LinkedHashMap<String, HeardObserver>()
        for (advert in adverts) for (observation in advert.observations) {
            val entry = HeardObserver(observation.observerId, observation.observerName ?: "Unnamed observer",
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

    fun ago(millis: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - millis).coerceAtLeast(0)
        return when {
            diff < 2 * 60_000 -> "just now"
            diff < HOUR -> "${(diff / 60_000.0).roundToInt()} minutes ago"
            diff < 2 * HOUR -> "an hour ago"
            diff < DAY -> "${(diff.toDouble() / HOUR).roundToInt()} hours ago"
            diff < 2 * DAY -> "a day ago"
            else -> "${(diff.toDouble() / DAY).roundToInt()} days ago"
        }
    }

    private fun duration(millis: Long) = if (millis < 2 * DAY) "a day" else "${(millis.toDouble() / DAY).roundToInt()} days"

    private fun plural(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"

    fun signedDecibels(value: Double): String {
        val text = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
        return if (value > 0) "+$text dB" else "$text dB"
    }

    fun hopsLabel(hops: Int?): String = when (hops) {
        null -> "—"
        0 -> "Direct"
        1 -> "1 hop"
        else -> "$hops hops"
    }

    private fun describeOffset(seconds: Double?): String {
        if (seconds == null || !seconds.isFinite()) return "a while"
        val magnitude = abs(seconds)
        val amount = when {
            magnitude < 3_600 -> plural((magnitude / 60).roundToInt(), "minute", "minutes")
            magnitude < 2 * 86_400 -> plural((magnitude / 3_600).roundToInt(), "hour", "hours")
            else -> plural((magnitude / 86_400).roundToInt(), "day", "days")
        }
        return if (seconds < 0) "$amount behind" else "$amount ahead"
    }

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
