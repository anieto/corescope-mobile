package org.nodescope.android.core.network

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.nodescope.android.core.model.*

// Ports of iOS BeaconLiveTransport / BeaconLiveRecovery / BeaconLiveFeed. Like iOS's @MainActor
// classes, each instance must only be touched from one serial dispatcher (see `beaconLiveEvents`).

data class LiveBrowseFilter(val region: String?, val payloadType: Int?)
data class LiveBrowseSnapshot(val filter: LiveBrowseFilter, val rows: List<BeaconLiveRecovery.Row>, val possiblyIncomplete: Boolean)

sealed interface BeaconLiveEvent {
    /** One live observation for the map's route animation (iOS `.packet(observation.liveEnvelope)`). */
    data class Packet(val packet: LivePacket) : BeaconLiveEvent
    data class BrowseSnapshot(val snapshot: LiveBrowseSnapshot) : BeaconLiveEvent
    data class NodeUpdated(val update: BeaconLiveProtocol.NodeUpdate) : BeaconLiveEvent
    data class ObserverStatus(val status: BeaconLiveProtocol.ObserverStatus) : BeaconLiveEvent
    data object MetadataInvalidated : BeaconLiveEvent
    data class NodeCatalog(val nodes: List<BeaconNode>) : BeaconLiveEvent
    data class MetadataRecoveryFailed(val message: String) : BeaconLiveEvent
    data class ObserverCatalog(val observers: List<BeaconObserver>) : BeaconLiveEvent
    data class ObserverRecoveryFailed(val message: String) : BeaconLiveEvent
    data object ObserverCatalogInvalidated : BeaconLiveEvent
    data class BrowseRemoved(val filter: LiveBrowseFilter) : BeaconLiveEvent
    data class RecoveryFailed(val filter: LiveBrowseFilter, val message: String) : BeaconLiveEvent
    data class RecoveryCompleted(val filter: LiveBrowseFilter) : BeaconLiveEvent
    data object Connected : BeaconLiveEvent
    data class Disconnected(val message: String?) : BeaconLiveEvent
    data class DecodingFailed(val message: String) : BeaconLiveEvent
}

interface BeaconSocket {
    val responseCode: Int?
    val retryAfter: String?
    val closeCode: Int?
    suspend fun receive(): String
    suspend fun send(text: String)
    fun close()
}

class OkHttpBeaconSocket(http: OkHttpClient, url: String) : BeaconSocket {
    private val incoming = Channel<String>(Channel.UNLIMITED)
    @Volatile override var responseCode: Int? = null; private set
    @Volatile override var retryAfter: String? = null; private set
    @Volatile override var closeCode: Int? = null; private set
    private val socket: WebSocket = http.newBuilder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).build()
        .newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { incoming.trySend(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { incoming.trySend(bytes.utf8()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closeCode = code
                webSocket.close(1000, null)
                incoming.close(IOException("Connection closed"))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closeCode = code
                incoming.close(IOException("Connection closed"))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                responseCode = response?.code
                retryAfter = response?.header("Retry-After")
                incoming.close(t)
            }
        })

    override suspend fun receive(): String {
        val text = incoming.receive()
        if (text.length > 1_048_576) throw IOException("The live message was too large.")
        return text
    }

    override suspend fun send(text: String) {
        if (!socket.send(text)) throw IOException("The live connection is closed.")
    }

    override fun close() {
        socket.close(1001, null)
        incoming.close(IOException("Connection closed"))
    }
}

class BeaconLiveTransport(
    private val client: BeaconApiClient,
    verifiedServerInfo: BeaconServerInfo,
    private val scope: CoroutineScope,
    private val makeSocket: (String) -> BeaconSocket,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Event {
        data class Frame(val frame: BeaconLiveProtocol.Incoming) : Event
        data class Disconnected(val message: String) : Event
    }

    val events = Channel<Event>(256)
    val url: String
    private val subscriptions = BeaconLiveProtocol.Subscriptions()
    private var socket: BeaconSocket? = null
    private var loop: Job? = null
    private var timer: Job? = null
    private var sender: Job? = null
    private var cancellationId: UUID? = null
    private var generation = UUID.randomUUID()
    private val awaiting = HashMap<String, Long>()
    private var helloReceived = false
    private var ended = false

    init {
        verifiedServerInfo.requireSupported()
        url = client.origin.replaceFirst("https://", "wss://") + "/ws"
    }

    fun update(subscriptions: Set<LiveFeedSubscription>, configuration: LiveFeedConfiguration) {
        if (ended || !client.sourceSession.isCurrent) return
        enqueue(this.subscriptions.update(subscriptions, configuration))
    }

    fun connect() {
        if (loop != null || ended || !client.sourceSession.isCurrent) return
        cancellationId = client.sourceSession.registerCancellation { scope.launch { disconnect() } }
        loop = scope.launch { run() }
    }

    fun disconnect() {
        if (ended) return
        ended = true
        loop?.cancel()
        loop = null
        stopSocket()
        cancellationId?.let(client.sourceSession::removeCancellation)
        cancellationId = null
        events.close()
    }

    private suspend fun run() {
        var attempt = 0
        while (currentCoroutineContext().isActive && !ended && client.sourceSession.isCurrent) {
            var wait = BeaconLiveProtocol.reconnectDelayMillis(attempt)
            val started = now()
            try {
                client.checkCooldown()
                if (ended) return
                val socket = makeSocket(url)
                this.socket = socket
                val generation = this.generation
                startTimer(generation)
                while (true) {
                    val text = socket.receive()
                    client.sourceSession.check()
                    if (ended || this.generation != generation) return
                    val frame = BeaconLiveProtocol.decode(text)
                    when (frame) {
                        BeaconLiveProtocol.Incoming.Hello -> {
                            if (helloReceived) throw BeaconApiException.InvalidResponse()
                            helloReceived = true
                            enqueue(subscriptions.hello())
                        }
                        is BeaconLiveProtocol.Incoming.Subscribed -> {
                            awaiting.remove(frame.requestId)
                            enqueue(subscriptions.acknowledge(frame.requestId, frame.subscriptionId))
                        }
                        is BeaconLiveProtocol.Incoming.Acknowledged -> awaiting.remove(frame.requestId)
                        else -> if (!helloReceived) throw BeaconApiException.InvalidResponse()
                    }
                    emit(Event.Frame(frame))
                    if (frame == BeaconLiveProtocol.Incoming.Hello) emit(Event.Frame(BeaconLiveProtocol.Incoming.RecoveryRequired))
                }
            } catch (e: CancellationException) {
                return
            } catch (e: Exception) {
                if (ended || !client.sourceSession.isCurrent) return
                if (e is BeaconApiException.RateLimited) wait = maxOf(wait, e.untilMillis - now())
                val failed = socket
                if (failed?.responseCode == 429 || failed?.closeCode == 1013) {
                    val deadline = client.recordCooldown(failed.retryAfter ?: "60")
                    wait = maxOf(wait, deadline - now())
                }
                if (ended || !client.sourceSession.isCurrent) return
                emit(Event.Disconnected(e.message ?: "The live feed disconnected."))
            }
            stopSocket()
            attempt = if (now() - started >= 60_000) 0 else minOf(attempt + 1, 4)
            try { sleep(maxOf(1_000, wait)) } catch (e: CancellationException) { return }
        }
    }

    private fun stopSocket() {
        generation = UUID.randomUUID()
        timer?.cancel()
        timer = null
        sender?.cancel()
        sender = null
        socket?.close()
        socket = null
        awaiting.clear()
        helloReceived = false
        subscriptions.disconnected()
    }

    private fun enqueue(commands: List<BeaconLiveProtocol.Command>) {
        val socket = socket
        if (commands.isEmpty() || socket == null) return
        val previous = sender
        val generation = generation
        sender = scope.launch {
            previous?.join()
            if (ended || this@BeaconLiveTransport.generation != generation) return@launch
            try {
                for (command in commands) {
                    client.sourceSession.checkCurrent()
                    if (this@BeaconLiveTransport.generation != generation) return@launch
                    awaiting[command.id] = now() + 40_000
                    socket.send(command.encoded())
                }
            } catch (e: Exception) {
                if (this@BeaconLiveTransport.generation == generation) socket.close()
            }
        }
    }

    private fun startTimer(generation: UUID) {
        val started = now()
        timer = scope.launch {
            var lastPing = started
            try {
                while (true) {
                    sleep(10_000)
                    if (ended || this@BeaconLiveTransport.generation != generation) return@launch
                    client.sourceSession.checkCurrent()
                    val now = now()
                    if ((!helloReceived && now - started >= 20_000) || awaiting.values.any { it <= now }) {
                        socket?.close()
                        return@launch
                    }
                    if (helloReceived && now - lastPing >= BeaconLiveProtocol.PING_INTERVAL_MILLIS) {
                        lastPing = now
                        enqueue(listOf(BeaconLiveProtocol.Command.ping()))
                    }
                }
            } catch (_: CancellationException) {}
        }
    }

    /** A full buffer drops the oldest frames, so screens are told to recover. */
    private fun emit(event: Event) {
        val result = events.trySend(event)
        if (result.isSuccess || result.isClosed) return
        events.tryReceive()
        events.trySend(event)
        events.tryReceive()
        events.trySend(Event.Frame(BeaconLiveProtocol.Incoming.RecoveryRequired))
    }
}

class BeaconLiveRecovery(
    private val sourceSession: AnalyzerSourceSession,
    private val region: String?,
    private val payloadType: Int?,
    private val fetch: suspend () -> AnalyzerPage<BeaconPacketSummary>,
) {
    constructor(reader: BeaconPacketReader, region: String?, payloadType: Int?) :
        this(reader.client.sourceSession, region, payloadType, { reader.summaryPage(region, 200, payloadType, null) })

    data class Row(
        val hash: String,
        val firstSeen: Long?,
        val lastHeard: Long,
        val observationCount: Int,
        val payloadType: Int,
        val routeType: Int,
    ) {
        constructor(packet: BeaconPacketSummary) : this(packet.packetHash, packet.firstHeardAt, packet.lastHeardAt,
            packet.observationCount, packet.payloadType, packet.routeType)
        constructor(event: BeaconLiveProtocol.Observation) : this(event.packetHash, null, event.observation.heardAt,
            event.packet.observationCount, event.packet.payloadType, event.packet.routeType)

        fun merge(other: Row) = copy(
            firstSeen = other.firstSeen?.let { date -> firstSeen?.let { minOf(it, date) } ?: date } ?: firstSeen,
            lastHeard = maxOf(lastHeard, other.lastHeard),
            observationCount = maxOf(observationCount, other.observationCount),
        )
    }

    var rows: List<Row> = emptyList(); private set
    var possiblyIncomplete = false; private set
    var recovering = false; private set
    private var refreshRequested = false
    private val maximumRows = 200

    fun receive(event: BeaconLiveProtocol.Observation) {
        sourceSession.checkCurrent()
        if (payloadType != null && event.packet.payloadType != payloadType) return
        if (region != null) {
            val codes = region.split(",").map { it.trim().uppercase() }.toSet()
            val iata = event.observation.iata ?: return
            if (iata.uppercase() !in codes) return
        }
        val row = Row(event)
        val index = rows.indexOfFirst { it.hash == row.hash }
        rows = if (index >= 0) rows.toMutableList().also { it[index] = it[index].merge(row) }
        else (listOf(row) + rows).take(maximumRows)
    }

    suspend fun recover() {
        sourceSession.check()
        // A first page cannot prove that every missed observation has been recovered.
        markGap()
        if (recovering) return
        recovering = true
        try {
            while (refreshRequested) {
                refreshRequested = false
                val page = fetch()
                sourceSession.check()
                merge(page)
            }
        } finally {
            recovering = false
        }
    }

    fun markGap() {
        possiblyIncomplete = true
        refreshRequested = true
    }

    private fun merge(page: AnalyzerPage<BeaconPacketSummary>) {
        val merged = mutableListOf<Row>()
        val positions = HashMap<String, Int>()
        val existing = rows.associateBy { it.hash }
        for (packet in page.items) {
            var row = Row(packet)
            existing[row.hash]?.let { row = row.merge(it) }
            val index = positions[row.hash]
            if (index != null) merged[index] = merged[index].merge(row)
            else { positions[row.hash] = merged.size; merged += row }
        }
        val remaining = rows.filter { it.hash !in positions }
        val newest = page.items.maxOfOrNull { it.lastHeardAt }
        // Keep the server page order; live-only rows received after its snapshot stay ahead.
        val newer = remaining.filter { newest == null || it.lastHeard > newest }
        val older = remaining.filter { newest != null && it.lastHeard <= newest }
        rows = (newer + merged + older).take(maximumRows)
    }
}

class BeaconLiveFeed(
    private val client: BeaconApiClient,
    verifiedServerInfo: BeaconServerInfo,
    private val scope: CoroutineScope,
    transport: BeaconLiveTransport? = null,
    makeSocket: (String) -> BeaconSocket = { error("A socket factory is required.") },
    fetchNodes: (suspend () -> List<BeaconNode>)? = null,
    fetchObservers: (suspend () -> List<BeaconObserver>)? = null,
    observerReader: BeaconObserverReader? = null,
) {
    val events = Channel<BeaconLiveEvent>(Channel.UNLIMITED)
    val observers: BeaconObserverReader
    private val reader = BeaconPacketReader(client, verifiedServerInfo)
    private val transport: BeaconLiveTransport
    private val fetchNodes: suspend () -> List<BeaconNode>
    private val fetchObservers: suspend () -> List<BeaconObserver>
    private var subscriptions: Set<LiveFeedSubscription>? = null
    private var configuration = LiveFeedConfiguration()
    private val projections = LinkedHashMap<LiveBrowseFilter, BeaconLiveRecovery>()
    private val recoveryJobs = HashMap<LiveBrowseFilter, Job>()
    private var eventJob: Job? = null
    private var connected = false
    private var ended = false
    private var nodeRecoveryJob: Job? = null
    private var metadataRevision = 0
    private var needsNodeRecovery = true
    private var observerRecoveryJob: Job? = null
    private var needsObserverRecovery = true
    private var observerRevision = 0

    init {
        if (observerReader != null) {
            if (observerReader.client.sourceSession.id != client.sourceSession.id ||
                observerReader.client.cacheIdentifier != client.cacheIdentifier) throw BeaconApiException.InvalidResponse()
            observers = observerReader
        } else {
            observers = BeaconObserverReader(client, verifiedServerInfo)
        }
        val observerSource = observers
        this.fetchObservers = fetchObservers ?: { observerSource.observers(null) }
        val nodes = BeaconNodeReader(client, verifiedServerInfo)
        this.fetchNodes = fetchNodes ?: { nodes.nodes(null) }
        this.transport = transport ?: BeaconLiveTransport(client, verifiedServerInfo, scope, makeSocket)
    }

    fun subscribe(subscriptions: Set<LiveFeedSubscription>) {
        if (ended || !client.sourceSession.isCurrent || this.subscriptions == subscriptions) return
        val filters = mutableSetOf<LiveBrowseFilter>()
        for (subscription in subscriptions) {
            val codes = subscription.regions.map { it.uppercase() }.sorted()
            val region = if (codes.isEmpty()) null else codes.joinToString(",")
            val types: List<Int?> = if (subscription.payloadTypes.isEmpty()) listOf(null) else subscription.payloadTypes.sorted()
            for (type in types) filters += LiveBrowseFilter(region, type)
        }
        if (filters.size > 20) {
            events.trySend(BeaconLiveEvent.DecodingFailed("Too many live filters; reduce the number of active views."))
            return
        }
        this.subscriptions = subscriptions
        for (filter in projections.keys.toList()) {
            if (filter in filters) continue
            recoveryJobs.remove(filter)?.cancel()
            projections.remove(filter)
            events.trySend(BeaconLiveEvent.BrowseRemoved(filter))
        }
        for (filter in filters) {
            if (filter in projections) continue
            projections[filter] = BeaconLiveRecovery(reader, filter.region, filter.payloadType)
            if (connected) recover(filter)
        }
        transport.update(subscriptions, configuration)
    }

    fun configure(configuration: LiveFeedConfiguration) {
        if (ended || !client.sourceSession.isCurrent) return
        this.configuration = configuration
        transport.update(subscriptions.orEmpty(), configuration)
        recoverNodesIfNeeded()
        recoverObserversIfNeeded()
    }

    suspend fun packetDetail(hash: String): PacketDetail {
        client.sourceSession.check()
        if (ended) throw CancellationException("The live feed ended.")
        val detail = reader.packetDetail(hash)
        client.sourceSession.check()
        if (ended) throw CancellationException("The live feed ended.")
        return detail
    }

    fun refreshObservers() {
        if (ended || !client.sourceSession.isCurrent) return
        observerRevision++
        needsObserverRecovery = true
        events.trySend(BeaconLiveEvent.ObserverCatalogInvalidated)
        recoverObserversIfNeeded()
    }

    suspend fun observerDetail(id: String): MeshObserver {
        client.sourceSession.check()
        if (ended) throw CancellationException("The live feed ended.")
        val detail = observers.observerDetail(id)
        client.sourceSession.check()
        if (ended) throw CancellationException("The live feed ended.")
        return detail.meshObserver()
    }

    fun connect() {
        if (eventJob != null || ended || !client.sourceSession.isCurrent) return
        eventJob = scope.launch {
            for (event in transport.events) {
                if (ended || !client.sourceSession.isCurrent) return@launch
                when (event) {
                    is BeaconLiveTransport.Event.Disconnected -> {
                        connected = false
                        invalidateMetadata()
                        for ((filter, projection) in projections) {
                            projection.markGap()
                            publish(filter, projection)
                        }
                        events.trySend(BeaconLiveEvent.Disconnected(event.message))
                    }
                    is BeaconLiveTransport.Event.Frame -> handle(event.frame)
                }
            }
        }
        transport.connect()
    }

    fun disconnect() {
        if (ended) return
        ended = true
        connected = false
        eventJob?.cancel()
        eventJob = null
        nodeRecoveryJob?.cancel()
        nodeRecoveryJob = null
        observerRecoveryJob?.cancel()
        observerRecoveryJob = null
        recoveryJobs.values.forEach { it.cancel() }
        recoveryJobs.clear()
        projections.clear()
        transport.disconnect()
        events.close()
    }

    private suspend fun handle(frame: BeaconLiveProtocol.Incoming) {
        when (frame) {
            BeaconLiveProtocol.Incoming.Hello -> {
                connected = true
                events.trySend(BeaconLiveEvent.Connected)
            }
            BeaconLiveProtocol.Incoming.RecoveryRequired -> {
                invalidateMetadata()
                recoverNodesIfNeeded()
                recoverObserversIfNeeded()
                projections.keys.toList().forEach(::recover)
            }
            is BeaconLiveProtocol.Incoming.Packet -> {
                for ((filter, projection) in projections.entries.toList()) {
                    try {
                        projection.receive(frame.value)
                        publish(filter, projection)
                    } catch (_: Exception) { return }
                }
                events.trySend(BeaconLiveEvent.Packet(frame.value.livePacket(System.currentTimeMillis())))
                val key = frame.value.observation.observerPublicKey ?: return
                try {
                    observers.learn(frame.value.observation.observerId, key, client.sourceSession.id)
                } catch (_: Exception) {
                    if (ended || !client.sourceSession.isCurrent) return
                    events.trySend(BeaconLiveEvent.DecodingFailed("Invalid observer identity in live event."))
                }
            }
            is BeaconLiveProtocol.Incoming.Node -> events.trySend(BeaconLiveEvent.NodeUpdated(frame.value))
            is BeaconLiveProtocol.Incoming.Observer -> events.trySend(BeaconLiveEvent.ObserverStatus(frame.value))
            is BeaconLiveProtocol.Incoming.Subscribed, is BeaconLiveProtocol.Incoming.Acknowledged,
            BeaconLiveProtocol.Incoming.Ignored -> Unit
        }
    }

    private fun invalidateMetadata() {
        metadataRevision++
        needsNodeRecovery = true
        needsObserverRecovery = true
        observerRevision++
        events.trySend(BeaconLiveEvent.MetadataInvalidated)
    }

    private fun recoverNodesIfNeeded() {
        if (!connected || !configuration.resolvePath || !needsNodeRecovery || nodeRecoveryJob != null ||
            ended || !client.sourceSession.isCurrent) return
        nodeRecoveryJob = scope.launch {
            try {
                while (connected && configuration.resolvePath && needsNodeRecovery) {
                    val revision = metadataRevision
                    try {
                        val nodes = fetchNodes()
                        client.sourceSession.check()
                        if (ended) return@launch
                        if (revision != metadataRevision) continue
                        needsNodeRecovery = false
                        events.trySend(BeaconLiveEvent.NodeCatalog(nodes))
                    } catch (e: CancellationException) {
                        return@launch
                    } catch (e: Exception) {
                        if (ended || !client.sourceSession.isCurrent) return@launch
                        if (revision != metadataRevision) continue
                        events.trySend(BeaconLiveEvent.MetadataRecoveryFailed(e.message ?: "Couldn't load nodes."))
                        return@launch
                    }
                }
            } finally {
                nodeRecoveryJob = null
            }
        }
    }

    private fun recoverObserversIfNeeded() {
        if (!connected || !configuration.recoverObservers || !needsObserverRecovery || observerRecoveryJob != null ||
            ended || !client.sourceSession.isCurrent) return
        observerRecoveryJob = scope.launch {
            try {
                while (connected && configuration.recoverObservers && needsObserverRecovery) {
                    val revision = observerRevision
                    try {
                        val observers = fetchObservers()
                        client.sourceSession.check()
                        if (ended) return@launch
                        if (revision != observerRevision) continue
                        needsObserverRecovery = false
                        events.trySend(BeaconLiveEvent.ObserverCatalog(observers))
                    } catch (e: CancellationException) {
                        return@launch
                    } catch (e: Exception) {
                        if (ended || !client.sourceSession.isCurrent) return@launch
                        if (revision != observerRevision) continue
                        events.trySend(BeaconLiveEvent.ObserverRecoveryFailed(e.message ?: "Couldn't load observers."))
                        return@launch
                    }
                }
            } finally {
                observerRecoveryJob = null
            }
        }
    }

    private fun recover(filter: LiveBrowseFilter) {
        val projection = projections[filter] ?: return
        projection.markGap()
        publish(filter, projection)
        if (recoveryJobs[filter] != null) return
        recoveryJobs[filter] = scope.launch {
            try {
                projection.recover()
                if (ended || !client.sourceSession.isCurrent || projections[filter] !== projection) return@launch
                publish(filter, projection)
                events.trySend(BeaconLiveEvent.RecoveryCompleted(filter))
            } catch (e: CancellationException) {
                return@launch
            } catch (e: Exception) {
                if (ended || !client.sourceSession.isCurrent || projections[filter] !== projection) return@launch
                events.trySend(BeaconLiveEvent.RecoveryFailed(filter, e.message ?: "Couldn't recover packets."))
            } finally {
                if (projections[filter] === projection) recoveryJobs.remove(filter)
            }
        }
    }

    private fun publish(filter: LiveBrowseFilter, projection: BeaconLiveRecovery) {
        events.trySend(BeaconLiveEvent.BrowseSnapshot(LiveBrowseSnapshot(filter, projection.rows, projection.possiblyIncomplete)))
    }
}

/** What the app keeps from a Beacon live feed (the Beacon half of iOS `LiveFeedService`). */
data class BeaconLiveState(
    val browseSnapshots: Map<LiveBrowseFilter, LiveBrowseSnapshot> = emptyMap(),
    val recoveryErrors: Map<LiveBrowseFilter, String> = emptyMap(),
    /** Insertion order is recency; at most [MAX_METADATA_ENTRIES]. */
    val nodes: Map<String, BeaconLiveProtocol.NodeState> = emptyMap(),
    val observers: Map<String, BeaconLiveProtocol.ObserverStatus> = emptyMap(),
    val nodeSequence: Int = 0,
    val observerSequence: Int = 0,
    val nodeCatalog: List<BeaconNode>? = null,
    val nodeCatalogId: Long? = null,
    val metadataRecoveryError: String? = null,
    val metadataNeedsRecovery: Boolean = false,
    val observerNeedsRecovery: Boolean = false,
    val observerRecoveryError: String? = null,
    val observerCatalog: List<BeaconObserver>? = null,
    val lastError: String? = null,
) {
    fun reduce(event: BeaconLiveEvent): BeaconLiveState = when (event) {
        BeaconLiveEvent.ObserverCatalogInvalidated ->
            copy(observers = emptyMap(), observerNeedsRecovery = true, observerSequence = observerSequence + 1)
        BeaconLiveEvent.MetadataInvalidated -> copy(
            nodes = emptyMap(), observers = emptyMap(), observerSequence = observerSequence + 1,
            metadataNeedsRecovery = true, observerNeedsRecovery = true, nodeSequence = nodeSequence + 1,
        )
        is BeaconLiveEvent.NodeCatalog -> copy(
            nodeCatalog = event.nodes, nodeCatalogId = (nodeCatalogId ?: 0) + 1, metadataNeedsRecovery = false,
            metadataRecoveryError = null, nodeSequence = nodeSequence + 1,
        )
        is BeaconLiveEvent.MetadataRecoveryFailed -> copy(metadataRecoveryError = event.message)
        is BeaconLiveEvent.ObserverCatalog -> copy(
            observerCatalog = event.observers, observerNeedsRecovery = false, observerRecoveryError = null,
            observerSequence = observerSequence + 1,
        )
        is BeaconLiveEvent.ObserverRecoveryFailed -> copy(observerRecoveryError = event.message)
        is BeaconLiveEvent.NodeUpdated -> {
            val key = event.update.publicKey.lowercase()
            val state = (nodes[key] ?: BeaconLiveProtocol.NodeState(event.update)).merge(event.update)
            copy(nodes = recent(nodes, key, state), nodeSequence = nodeSequence + 1)
        }
        // Status is a full snapshot: missing optional fields must clear old values.
        is BeaconLiveEvent.ObserverStatus -> copy(
            observers = recent(observers, event.status.observerId.lowercase(), event.status),
            observerSequence = observerSequence + 1,
        )
        is BeaconLiveEvent.BrowseSnapshot -> copy(browseSnapshots = browseSnapshots + (event.snapshot.filter to event.snapshot))
        is BeaconLiveEvent.BrowseRemoved ->
            copy(browseSnapshots = browseSnapshots - event.filter, recoveryErrors = recoveryErrors - event.filter)
        is BeaconLiveEvent.RecoveryFailed -> copy(recoveryErrors = recoveryErrors + (event.filter to event.message))
        is BeaconLiveEvent.RecoveryCompleted -> copy(recoveryErrors = recoveryErrors - event.filter)
        is BeaconLiveEvent.Packet -> this
        BeaconLiveEvent.Connected -> copy(lastError = null)
        is BeaconLiveEvent.Disconnected -> copy(lastError = event.message)
        is BeaconLiveEvent.DecodingFailed -> copy(lastError = event.message)
    }

    /** The recovered catalog; null until it has loaded. Screens apply live [observers] statuses. */
    fun recoveredObservers(): List<MeshObserver>? = observerCatalog?.map { record ->
        val observer = record.meshObserver()
        if (observerNeedsRecovery) observer else when (record.status) {
            "online" -> observer.copy(online = true)
            "offline" -> observer.copy(online = false)
            else -> observer
        }
    }

    companion object {
        const val MAX_METADATA_ENTRIES = 1_000

        private fun <V> recent(map: Map<String, V>, key: String, value: V): Map<String, V> {
            val result = LinkedHashMap(map)
            result.remove(key)
            result[key] = value
            while (result.size > MAX_METADATA_ENTRIES) result.remove(result.keys.first())
            return result
        }
    }
}

/**
 * Map nodes for a Beacon source (iOS `MapViewModel.applyBeaconNodes`): the recovered catalog when
 * it's current (keeping a newer REST record), otherwise the loaded nodes, then live adverts on top.
 * A live-only node joins only when one of its hearing regions is selected.
 */
fun beaconMapNodes(loaded: List<MeshNode>, live: BeaconLiveState?, region: String?): List<MeshNode> {
    live ?: return loaded
    val selected = region?.split(",")?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    val catalog = live.nodeCatalog?.takeIf { !live.metadataNeedsRecovery }
    val baseline = if (catalog == null) loaded else {
        val existing = loaded.associateBy { it.publicKey.lowercase() }
        catalog.filter { node -> selected.isEmpty() || node.iatas.orEmpty().any { it.iata.uppercase() in selected } }.map { record ->
            val node = record.meshNode()
            val previous = existing[node.publicKey]
            val previousDate = previous?.let { parseInstant(it.lastSeen) }
            val date = parseInstant(node.lastSeen)
            if (previous != null && previousDate != null && date != null && previousDate > date) previous else node
        }
    }
    return mergingBeaconNodes(baseline, live.nodes, region)
}

fun mergingBeaconNodes(nodes: List<MeshNode>, states: Map<String, BeaconLiveProtocol.NodeState>, region: String?): List<MeshNode> {
    val result = nodes.map { node -> states[node.publicKey.lowercase()]?.applying(node) ?: node }.toMutableList()
    val existing = nodes.mapTo(HashSet()) { it.publicKey.lowercase() }
    val selected = region?.split(",")?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    for (key in states.keys.sorted()) {
        if (key in existing) continue
        val state = states.getValue(key)
        if (selected.isNotEmpty() && selected.none { it in state.regions.keys }) continue
        result += state.applying(null)
    }
    return result
}

/** A live Beacon observation as a live packet: confirmed hops only, its region from the hearing. */
fun BeaconLiveProtocol.Observation.livePacket(receivedAt: Long) = LivePacket(
    id = null, hash = packetHash, payloadType = packet.payloadType, typeName = payloadTypeName(packet.payloadType),
    observerId = observation.observerId.lowercase(), observerName = observation.observerName, region = observation.iata,
    snr = observation.snr, rssi = observation.rssi, hops = emptyList(), resolvedPath = resolvedPath.orEmpty(),
    timestamp = isoMillis(observation.heardAt), receivedAt = receivedAt, isLive = true, observations = packet.observationCount,
)
