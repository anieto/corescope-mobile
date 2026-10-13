package org.nodescope.android.core.network

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.nodescope.android.core.model.*

// Port of iOS BeaconLiveProtocol.swift: frames, live event payloads and the subscription state machine.

object BeaconLiveProtocol {
    const val PING_INTERVAL_MILLIS = 30_000L
    private val DELAYS = longArrayOf(1, 2, 5, 10, 30)

    fun reconnectDelayMillis(attempt: Int): Long = DELAYS[attempt.coerceIn(0, DELAYS.size - 1)] * 1000

    data class Command(
        val type: String,
        val id: String = UUID.randomUUID().toString().uppercase(),
        val scope: Scope? = null,
        val subscriptionId: String? = null,
        val resolvePath: Boolean? = null,
        val includeObserverKey: Boolean? = null,
        val includeRepeats: Boolean? = null,
    ) {
        data class Scope(val iatas: List<String>, val payloadTypes: List<Int>) {
            val events = listOf("packetObservation", "observerStatus", "nodeUpdate")
        }

        /** Sorted keys, like the iOS encoder, so frames are byte-identical across platforms. */
        fun encoded(): String = buildJsonObject {
            put("id", id)
            includeObserverKey?.let { put("includeObserverKey", it) }
            includeRepeats?.let { put("includeRepeats", it) }
            resolvePath?.let { put("resolvePath", it) }
            scope?.let { scope ->
                putJsonObject("scope") {
                    putJsonArray("events") { scope.events.forEach { add(it) } }
                    putJsonArray("iatas") { scope.iatas.forEach { add(it) } }
                    putJsonArray("payloadTypes") { scope.payloadTypes.forEach { add(it) } }
                }
            }
            subscriptionId?.let { put("subscriptionId", it) }
            put("type", type)
            put("v", 1)
        }.toString()

        companion object {
            /** `configure` replaces every flag, so all three are always sent. */
            fun configure(configuration: LiveFeedConfiguration) = Command(
                "configure", resolvePath = configuration.resolvePath,
                includeObserverKey = configuration.includeObserverKey, includeRepeats = configuration.includeRepeats,
            )
            fun ping() = Command("ping")
        }
    }

    sealed interface Incoming {
        data object Hello : Incoming
        data class Subscribed(val requestId: String, val subscriptionId: String) : Incoming
        data class Acknowledged(val requestId: String) : Incoming
        data class Packet(val value: Observation) : Incoming
        data class Observer(val value: ObserverStatus) : Incoming
        data class Node(val value: NodeUpdate) : Incoming
        data object RecoveryRequired : Incoming
        data object Ignored : Incoming
    }

    @Serializable
    data class Observation(val packetHash: String, val packet: Header, val observation: Hearing) {
        @Serializable data class Header(val payloadType: Int, val routeType: Int, val observationCount: Int)
        @Serializable data class Hearing(
            val observerId: String,
            val observerName: String? = null,
            val observerPublicKey: String? = null,
            val iata: String? = null,
            val heardAt: Long,
            val rssi: Double? = null,
            val snr: Double? = null,
            val resolvedPath: List<BeaconResolvedHop>? = null,
        )

        val observationIdentity: String get() = packetHash + "|" + observation.observerId.lowercase()
        val resolvedPath: List<String?>? get() = observation.resolvedPath?.map { it.confirmedKey }
    }

    data class NodeUpdate(
        val nodeId: String,
        val publicKey: String,
        val nodeType: Int,
        val name: String?,
        val isObserver: Boolean?,
        val iatas: List<BeaconNodeRegion>?,
        val position: Position,
    ) {
        sealed interface Position {
            data object Unchanged : Position
            data object Clear : Position
            data class Coordinate(val latitude: Double, val longitude: Double) : Position
        }

        companion object {
            fun decode(data: JsonObject): NodeUpdate {
                fun text(key: String) = data[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.takeIf { it.isString }?.content
                val nodeId = text("nodeId") ?: throw BeaconApiException.InvalidResponse()
                val publicKey = text("publicKey") ?: throw BeaconApiException.InvalidResponse()
                val nodeType = data["nodeType"]?.jsonPrimitive?.intOrNull ?: throw BeaconApiException.InvalidResponse()
                if (!isUuid(nodeId) || !isFullBeaconKey(publicKey)) throw BeaconApiException.InvalidResponse()
                val iatas = data["iatas"]?.takeIf { it !is JsonNull }?.let {
                    protocolJson.decodeFromJsonElement<List<BeaconNodeRegion>>(it)
                }
                val position = when {
                    "lat" !in data && "lng" !in data -> Position.Unchanged
                    "lat" !in data || "lng" !in data -> throw BeaconApiException.InvalidResponse()
                    else -> {
                        val latitude = data["lat"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull
                        val longitude = data["lng"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull
                        if (latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
                            latitude in -90.0..90.0 && longitude in -180.0..180.0 && (latitude != 0.0 || longitude != 0.0)
                        ) Position.Coordinate(latitude, longitude) else Position.Clear
                    }
                }
                return NodeUpdate(nodeId, publicKey, nodeType, text("name"),
                    data["isObserver"]?.jsonPrimitive?.booleanOrNull, iatas, position)
            }
        }
    }

    @Serializable
    data class ObserverStatus(
        val observerId: String,
        val displayName: String? = null,
        val iata: String? = null,
        val online: Boolean,
        val batteryMv: Int? = null,
        val uptimeSeconds: Long? = null,
        val lastStatusAt: Long? = null,
        val radio: String? = null,
    ) {
        /** A full snapshot: missing optional fields clear the old values. */
        fun applying(observer: MeshObserver): MeshObserver {
            if (!observer.id.equals(observerId, ignoreCase = true)) return observer
            return observer.copy(
                name = displayName, iata = iata, radio = radio, batteryMv = batteryMv, uptimeSecs = uptimeSeconds,
                online = online, lastStatusAt = lastStatusAt,
            )
        }
    }

    /** What's known about one node from live adverts (each carries only the regions that heard it). */
    data class NodeState(
        val latest: NodeUpdate,
        val name: String? = null,
        val nameHeardAt: Long? = null,
        val position: NodeUpdate.Position = NodeUpdate.Position.Unchanged,
        val positionHeardAt: Long? = null,
        val regions: Map<String, BeaconNodeRegion> = emptyMap(),
    ) {
        fun merge(update: NodeUpdate): NodeState {
            val heardAt = update.iatas?.mapNotNull { it.lastHeard }?.maxOrNull()
            val previousHeardAt = regions.values.mapNotNull { it.lastHeard }.maxOrNull()
            val isOlder = previousHeardAt != null && heardAt != null && heardAt < previousHeardAt
            var result = this
            if (!isOlder) {
                result = result.copy(latest = update)
                if (update.name != null) result = result.copy(name = update.name, nameHeardAt = heardAt)
                if (update.position != NodeUpdate.Position.Unchanged) {
                    result = result.copy(position = update.position, positionHeardAt = heardAt)
                }
            }
            val merged = result.regions.toMutableMap()
            for (region in update.iatas.orEmpty()) {
                val code = region.iata.uppercase()
                val previous = merged[code]?.lastHeard
                if (previous != null && (region.lastHeard == null || region.lastHeard < previous)) continue
                merged[code] = region
            }
            return result.copy(regions = merged)
        }

        fun applying(existing: MeshNode?): MeshNode {
            val heardAt = regions.values.mapNotNull { it.lastHeard }.maxOrNull()
            val existingActivity = existing?.let { parseInstant(it.lastSeen)?.toEpochMilli() }
            fun olderThanExisting(date: Long?) = existingActivity != null && (date == null || date < existingActivity)
            val older = olderThanExisting(heardAt)
            var latitude = existing?.lat
            var longitude = existing?.lon
            if (!olderThanExisting(positionHeardAt)) when (val position = position) {
                NodeUpdate.Position.Unchanged -> Unit
                NodeUpdate.Position.Clear -> { latitude = null; longitude = null }
                is NodeUpdate.Position.Coordinate -> { latitude = position.latitude; longitude = position.longitude }
            }
            val role = beaconRole(latest.nodeType)
            val lastSeen = listOfNotNull(existingActivity, heardAt).maxOrNull()
            return MeshNode(
                publicKey = existing?.publicKey ?: latest.publicKey.lowercase(),
                name = if (olderThanExisting(nameHeardAt)) existing?.name else name ?: existing?.name,
                role = if (older) existing?.role ?: role else role,
                lat = latitude, lon = longitude,
                lastSeen = isoMillis(lastSeen) ?: existing?.lastSeen.orEmpty(),
                firstSeen = existing?.firstSeen, advertCount = existing?.advertCount,
            )
        }

        companion object {
            fun of(update: NodeUpdate) = NodeState(update).merge(update)
        }
    }

    fun decode(text: String): Incoming {
        val root = protocolJson.parseToJsonElement(text).jsonObject
        fun field(key: String) = root[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
        if (root["v"]?.jsonPrimitive?.intOrNull != 1) throw BeaconApiException.InvalidResponse()
        val type = field("type") ?: throw BeaconApiException.InvalidResponse()
        return when (type) {
            "hello" -> Incoming.Hello
            "subscribed" -> {
                val request = field("id")?.takeIf { it.isNotEmpty() } ?: throw BeaconApiException.InvalidResponse()
                val subscription = field("subscriptionId")?.takeIf { it.isNotEmpty() } ?: throw BeaconApiException.InvalidResponse()
                Incoming.Subscribed(request, subscription)
            }
            "lagged" -> Incoming.RecoveryRequired
            "configured", "unsubscribed", "pong" -> Incoming.Acknowledged(field("id") ?: throw BeaconApiException.InvalidResponse())
            "event" -> {
                val data = { root["data"]?.jsonObject ?: throw BeaconApiException.InvalidResponse() }
                when (field("event")) {
                    "packetObservation" -> {
                        val value = protocolJson.decodeFromJsonElement(Observation.serializer(), data())
                        if (value.packetHash.isEmpty() || value.packet.observationCount < 0 || !isUuid(value.observation.observerId)) {
                            throw BeaconApiException.InvalidResponse()
                        }
                        Incoming.Packet(value)
                    }
                    "observerStatus" -> {
                        val value = protocolJson.decodeFromJsonElement(ObserverStatus.serializer(), data())
                        if (!isUuid(value.observerId)) throw BeaconApiException.InvalidResponse()
                        Incoming.Observer(value)
                    }
                    "nodeUpdate" -> Incoming.Node(NodeUpdate.decode(data()))
                    else -> Incoming.Ignored
                }
            }
            else -> Incoming.Ignored
        }
    }

    /** Which subscriptions the server holds; rebuilt from scratch after every reconnect. */
    class Subscriptions {
        private var desired: Set<LiveFeedSubscription> = emptySet()
        private val pending = LinkedHashMap<String, LiveFeedSubscription>()
        private val active = LinkedHashMap<LiveFeedSubscription, String>()
        private var connected = false
        private var configuration = LiveFeedConfiguration()

        fun update(subscriptions: Set<LiveFeedSubscription>, configuration: LiveFeedConfiguration): List<Command> {
            desired = subscriptions
            val changed = this.configuration != configuration
            this.configuration = configuration
            if (!connected) return emptyList()
            val commands = reconcile()
            if (changed) commands += Command.configure(configuration)
            return commands
        }

        fun hello(): List<Command> {
            connected = true
            pending.clear()
            active.clear()
            return listOf(Command.configure(configuration)) + reconcile()
        }

        fun disconnected() {
            connected = false
            pending.clear()
            active.clear()
        }

        fun acknowledge(requestId: String, subscriptionId: String): List<Command> {
            if (!connected) return emptyList()
            val subscription = pending.remove(requestId) ?: return emptyList()
            if (subscription in desired) {
                active[subscription] = subscriptionId
                return emptyList()
            }
            // A screen can disappear before its subscribe acknowledgement arrives.
            return listOf(Command("unsubscribe", subscriptionId = subscriptionId))
        }

        private fun reconcile(): MutableList<Command> {
            val commands = mutableListOf<Command>()
            for ((subscription, identifier) in active) {
                if (subscription !in desired) commands += Command("unsubscribe", subscriptionId = identifier)
            }
            active.keys.retainAll(desired)
            val awaiting = pending.values.toSet()
            for (subscription in desired) {
                if (subscription in active || subscription in awaiting) continue
                val command = Command("subscribe", scope = Command.Scope(
                    subscription.regions.sorted(), subscription.payloadTypes.sorted(),
                ))
                pending[command.id] = subscription
                commands += command
            }
            return commands
        }
    }
}
