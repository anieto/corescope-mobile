package org.nodescope.android.core.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString
import org.nodescope.android.core.model.*
import org.nodescope.android.core.storage.PreferencesStore

enum class LiveConnection { CONNECTING, LIVE, RECONNECTING, PAUSED }
data class LiveFeedState(
    val selection: AnalyzerSelection? = null,
    val connection: LiveConnection = LiveConnection.PAUSED,
    val packets: List<LivePacket> = emptyList(),
    val observers: List<MeshObserver> = emptyList(),
    val observersLoaded: Boolean = false,
    val historyError: Boolean = false,
) {
    val visiblePackets: List<LivePacket> get() {
        val region = selection?.region ?: return packets
        val ids = observers.filter { it.iata.equals(region, true) }.map { it.id.lowercase() }.toSet()
        return packets.filter { it.region.equals(region, true) || it.observerId?.lowercase() in ids }
    }
}
sealed interface LiveSignal {
    data class Connection(val value: LiveConnection) : LiveSignal
    data class Packet(val value: LivePacket) : LiveSignal
}
interface PacketSource {
    suspend fun recent(selection: AnalyzerSelection): List<LivePacket>
    suspend fun observers(host: String): List<MeshObserver>
    fun events(host: String): Flow<LiveSignal>
}
class HttpPacketSource(client: OkHttpClient) : PacketSource {
    private val http = client
    private val socketClient = client.newBuilder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    override suspend fun recent(selection: AnalyzerSelection): List<LivePacket> {
        val url = endpoint(selection.host, "api", "packets").newBuilder().addQueryParameter("limit", "200")
            .apply { selection.region?.let { addQueryParameter("region", it) } }.build()
        val root = protocolJson.parseToJsonElement(http.read(url)).jsonObject
        return (root["packets"] as? JsonArray).orEmpty().mapNotNull { value ->
            runCatching { parseLivePacket(value.jsonObject, false) }.getOrNull()
        }
    }
    override suspend fun observers(host: String) = protocolJson.decodeFromString<ObserversResponse>(
        http.read(endpoint(host, "api", "observers"))).observers

    override fun events(host: String): Flow<LiveSignal> = reconnecting(endpoint(host))

    internal fun reconnecting(url: HttpUrl): Flow<LiveSignal> = flow {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            emit(LiveSignal.Connection(if (attempt == 0) LiveConnection.CONNECTING else LiveConnection.RECONNECTING))
            try {
                connection(url).collect {
                    if (it is LiveSignal.Connection && it.value == LiveConnection.LIVE) attempt = 0
                    emit(it)
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* reconnect below */ }
            emit(LiveSignal.Connection(LiveConnection.RECONNECTING))
            delay((1_000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000))
            attempt++
        }
    }

    internal fun connection(url: HttpUrl): Flow<LiveSignal> = callbackFlow {
        val socket = socketClient.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { trySend(LiveSignal.Connection(LiveConnection.LIVE)) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                // Heartbeats, channel messages and malformed/unknown events never masquerade as packets.
                val packet = runCatching {
                    val root = protocolJson.parseToJsonElement(text).jsonObject
                    if (root["type"]?.jsonPrimitive?.content != "packet") null
                    else (root["data"] as? JsonObject)?.let { parseLivePacket(it, true) }
                }.getOrNull()
                packet?.let { trySend(LiveSignal.Packet(it)) }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onMessage(webSocket, bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); close(IOException("Connection closed")) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close(IOException("Connection closed")) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(t) }
        })
        awaitClose { socket.cancel() }
    }.buffer(256, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
}

@OptIn(ExperimentalCoroutinesApi::class)
class LiveFeed(preferences: PreferencesStore, private val source: PacketSource) {
    private val restart = MutableStateFlow(0)
    fun reconnect() { restart.update { it + 1 } }
    val states = combine(preferences.values.map {
        if (it.onboarded) AnalyzerSelection(it.host, it.region) else null
    }.distinctUntilChanged(), restart) { selection, _ -> selection }.flatMapLatest { selection ->
        if (selection == null) flowOf(LiveFeedState()) else session(selection)
    }
    private sealed interface Update {
        data class Signal(val value: LiveSignal) : Update
        data class History(val value: List<LivePacket>?, val observers: List<MeshObserver>?) : Update
    }
    private fun session(selection: AnalyzerSelection): Flow<LiveFeedState> = channelFlow {
        var state = LiveFeedState(selection, LiveConnection.CONNECTING)
        send(state)
        val updates = Channel<Update>(Channel.BUFFERED)
        suspend fun refreshHistory() {
            val packets = try { source.recent(selection) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            val observers = try { source.observers(selection.host) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            updates.send(Update.History(packets, observers))
        }
        var historyJob: Job? = launch { refreshHistory() }
        launch { source.events(selection.host).collect { updates.send(Update.Signal(it)) } }
        try {
            for (update in updates) {
                when (update) {
                    is Update.Signal -> when (val signal = update.value) {
                        is LiveSignal.Connection -> {
                            state = state.copy(connection = signal.value)
                            if (signal.value == LiveConnection.LIVE) {
                                if (historyJob?.isActive != true) historyJob = launch { refreshHistory() }
                            }
                        }
                        is LiveSignal.Packet -> {
                            val previous = state.packets.firstOrNull { it.key == signal.value.key }
                            val packet = if (previous?.isLive == true) signal.value.copy(receivedAt = previous.receivedAt) else signal.value
                            state = state.copy(packets = (listOf(packet) + state.packets.filter { it.key != packet.key }).take(200))
                        }
                    }
                    is Update.History -> {
                        // A slow REST response must never replace packets already received on the socket.
                        val merged = (state.packets + update.value.orEmpty()).distinctBy { it.key }
                            .sortedByDescending { if (it.isLive) it.receivedAt else packetEpoch(it) }.take(200)
                        state = state.copy(packets = merged, observers = update.observers ?: state.observers,
                            observersLoaded = update.observers != null || state.observersLoaded, historyError = update.value == null)
                    }
                }
                send(state)
            }
        } finally { updates.close() }
    }
}
