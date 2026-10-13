package org.nodescope.android.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import org.nodescope.android.core.model.*

/**
 * Which backend serves the selected source (the resolution half of iOS `AnalyzerSettings`).
 * Android repositories take a host per call, so this decides per host: the selected host goes
 * to its verified Beacon backend, to CoreScope once resolved as CoreScope, and nowhere while
 * still being identified — a Beacon source never falls back to CoreScope.
 */
class AnalyzerSourceRouter(
    private val http: OkHttpClient,
    val catalog: BeaconServerCatalog,
    /** A positive CoreScope check, used only when the host has no Beacon `/api/v1/info`. */
    private val coreScopeCheck: suspend (host: String, session: AnalyzerSourceSession) -> Unit = { host, session ->
        protocolJson.decodeFromString<MapDefaults>(http.read(endpoint(host, "api", "config", "map")))
        session.check()
    },
) {
    data class State(
        val host: String? = null,
        val session: AnalyzerSourceSession = AnalyzerSourceSession(),
        val beacon: BeaconBackend? = null,
        val ready: Boolean = false,
        val error: String? = null,
        val resolutionId: Long = 0,
    ) {
        val backendType: String get() = if (beacon != null) "Beacon" else "CoreScope"
    }

    sealed interface Route {
        data object CoreScope : Route
        data class Beacon(val backend: BeaconBackend) : Route
        data object NotReady : Route
    }

    private val lock = Any()
    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private var resolvingId: Long? = null

    /** A different host drops the Beacon selection and starts a new source session. */
    fun setHost(host: String?) {
        val normalized = host?.let { runCatching { normalizeHost(it).lowercase() }.getOrNull() }
        synchronized(lock) {
            val current = mutableState.value
            if (current.host == normalized) return
            current.session.invalidate()
            mutableState.value = State(host = normalized, resolutionId = current.resolutionId + 1)
        }
    }

    fun requestResolution() = synchronized(lock) {
        mutableState.value = mutableState.value.let { it.copy(ready = false, error = null, resolutionId = it.resolutionId + 1) }
    }

    suspend fun resolve(knownCoreScope: Boolean, requiresBeacon: Boolean = false) {
        val start = mutableState.value
        val host = start.host ?: return
        val resolutionId = start.resolutionId
        synchronized(lock) {
            if (start.ready || resolvingId == resolutionId) return
            resolvingId = resolutionId
        }
        val captured = start.session
        try {
            val client = BeaconApiClient("https://$host", captured, http)
            val remembered = catalog.knownInfo(client)
            captured.check()
            if (mutableState.value.resolutionId != resolutionId) return
            val selected = mutableState.value.beacon
            when {
                remembered != null -> selectBeacon(remembered, captured.id)
                selected != null -> selected.serverInfo.requireSupported()
                requiresBeacon || !knownCoreScope -> try {
                    val info = catalog.resolve(client)
                    captured.check()
                    if (mutableState.value.resolutionId != resolutionId) return
                    selectBeacon(info, captured.id)
                } catch (e: BeaconApiException.Server) {
                    // Only an absent Beacon endpoint permits a positive CoreScope compatibility check.
                    if (e.status != 404 || requiresBeacon) throw e
                    coreScopeCheck(host, captured)
                }
            }
            val active = currentCoroutineContext().isActive
            synchronized(lock) {
                val now = mutableState.value
                if (now.resolutionId != resolutionId || !now.session.isCurrent || !active) return
                mutableState.value = now.copy(error = null, ready = true)
            }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            fail(resolutionId, captured, "Source detection was interrupted. Retry to continue.")
        } catch (e: Exception) {
            fail(resolutionId, captured, e.message ?: "Couldn't identify this server.")
        } finally {
            synchronized(lock) { if (resolvingId == resolutionId) resolvingId = null }
        }
    }

    private fun fail(resolutionId: Long, captured: AnalyzerSourceSession, message: String) = synchronized(lock) {
        val now = mutableState.value
        if (now.resolutionId == resolutionId && now.session.id == captured.id) mutableState.value = now.copy(error = message)
    }

    fun selectBeacon(verifiedServerInfo: BeaconServerInfo, expectedSessionId: java.util.UUID) = synchronized(lock) {
        val current = mutableState.value
        current.session.checkCurrent()
        if (current.session.id != expectedSessionId) throw CancellationException("The analyzer source changed.")
        verifiedServerInfo.requireSupported()
        if (current.beacon?.serverInfo == verifiedServerInfo) return
        val host = checkNotNull(current.host)
        val nextSession = AnalyzerSourceSession()
        val backend = BeaconBackend(BeaconApiClient("https://$host", nextSession, http), verifiedServerInfo)
        current.session.invalidate()
        mutableState.value = current.copy(beacon = backend, error = null, session = nextSession)
    }

    fun route(host: String): Route {
        val requested = runCatching { normalizeHost(host).lowercase() }.getOrNull()
        val current = mutableState.value
        if (requested == null || requested != current.host) return Route.CoreScope
        current.beacon?.let { return Route.Beacon(it) }
        return if (current.ready) Route.CoreScope else Route.NotReady
    }

    val currentBeacon: BeaconBackend? get() = mutableState.value.beacon

    fun makeSocket(url: String): BeaconSocket = OkHttpBeaconSocket(http, url)

    /** Readiness for [LiveFeed]: a new key whenever the backend or source session changes. */
    val liveReadiness: kotlinx.coroutines.flow.Flow<LiveSourceReadiness> = state.map {
        // No host yet means nothing has been selected through the router, so nothing is gated.
        LiveSourceReadiness(it.host, it.ready || it.host == null, it.session.id)
    }.distinctUntilChanged()
}

class AnalyzerSourceNotReadyException : IllegalStateException("Checking analyzer source…")

/** The app's single backend: each call goes to the backend the router picked for its host. */
class RoutingAnalyzerBackend(
    private val coreScope: CoreScopeBackend,
    private val router: AnalyzerSourceRouter,
) : AnalyzerBackend {
    override val capabilities: Set<BackendCapability> get() = router.currentBeacon?.capabilities ?: coreScope.capabilities
    override val liveFeed: PacketSource = RoutingPacketSource(coreScope.liveFeed, router)

    private inline fun <T> on(host: String, beacon: (BeaconBackend) -> T, coreScope: () -> T): T =
        when (val route = router.route(host)) {
            is AnalyzerSourceRouter.Route.Beacon -> beacon(route.backend)
            AnalyzerSourceRouter.Route.CoreScope -> coreScope()
            AnalyzerSourceRouter.Route.NotReady -> throw AnalyzerSourceNotReadyException()
        }

    override suspend fun load(selection: AnalyzerSelection) =
        on(selection.host, { it.load(selection) }) { coreScope.load(selection) }
    override suspend fun saved(selection: AnalyzerSelection): Cached<AnalyzerSnapshot>? =
        on(selection.host, { null }) { coreScope.saved(selection) }

    override suspend fun channels(selection: AnalyzerSelection) =
        on(selection.host, { it.channels(selection) }) { coreScope.channels(selection) }
    override suspend fun channelMessages(host: String, hash: String) =
        on(host, { it.channelMessages(host, hash) }) { coreScope.channelMessages(host, hash) }
    override suspend fun channelPackets(selection: AnalyzerSelection) =
        on(selection.host, { it.channelPackets(selection) }) { coreScope.channelPackets(selection) }
    override suspend fun observers(host: String) = on(host, { it.observers(host) }) { coreScope.observers(host) }
    override suspend fun observerAnalytics(host: String, id: String) =
        on(host, { it.observerAnalytics(host, id) }) { coreScope.observerAnalytics(host, id) }
    override suspend fun nodeHealth(host: String, publicKey: String) =
        on(host, { it.nodeHealth(host, publicKey) }) { coreScope.nodeHealth(host, publicKey) }
    override suspend fun nodePaths(host: String, publicKey: String) =
        on(host, { it.nodePaths(host, publicKey) }) { coreScope.nodePaths(host, publicKey) }
    override suspend fun nodeReach(host: String, publicKey: String) =
        on(host, { it.nodeReach(host, publicKey) }) { coreScope.nodeReach(host, publicKey) }
    override suspend fun nodeAnalytics(host: String, publicKey: String, days: Int) =
        on(host, { it.nodeAnalytics(host, publicKey, days) }) { coreScope.nodeAnalytics(host, publicKey, days) }
    override suspend fun packetDetail(host: String, hash: String) =
        on(host, { it.packetDetail(host, hash) }) { coreScope.packetDetail(host, hash) }
    override suspend fun allNodes(host: String) = on(host, { it.allNodes(host) }) { coreScope.allNodes(host) }
    override suspend fun observerDetail(host: String, id: String) =
        on(host, { it.observerDetail(host, id) }) { coreScope.observerDetail(host, id) }
    override suspend fun recentPackets(host: String, limit: Int) =
        on(host, { it.recentPackets(host, limit) }) { coreScope.recentPackets(host, limit) }
    override suspend fun packetPage(host: String, size: Int, cursor: AnalyzerCursor?) =
        on(host, { it.packetPage(host, size, cursor) }) { coreScope.packetPage(host, size, cursor) }

    /** Saved (offline) responses exist only for CoreScope; a Beacon source has none. */
    override suspend fun <T> saved(maxAgeMillis: Long, block: suspend BrowseRepository.() -> T): Cached<T>? =
        if (router.currentBeacon != null) null else coreScope.saved(maxAgeMillis, block)
}

class RoutingPacketSource(private val coreScope: PacketSource, private val router: AnalyzerSourceRouter) : PacketSource {
    private inline fun <T> on(host: String, beacon: (BeaconBackend) -> T, coreScope: () -> T): T =
        when (val route = router.route(host)) {
            is AnalyzerSourceRouter.Route.Beacon -> beacon(route.backend)
            AnalyzerSourceRouter.Route.CoreScope -> coreScope()
            AnalyzerSourceRouter.Route.NotReady -> throw AnalyzerSourceNotReadyException()
        }

    override suspend fun recent(selection: AnalyzerSelection) =
        on(selection.host, { beaconRecent(it, selection) }) { coreScope.recent(selection) }
    override suspend fun observers(host: String) = on(host, { it.observers(host) }) { coreScope.observers(host) }
    override fun events(host: String): Flow<LiveSignal> = events(host, kotlinx.coroutines.flow.flowOf(LiveFeedControls(emptySet(), LiveFeedConfiguration())))
    override fun events(host: String, controls: Flow<LiveFeedControls>): Flow<LiveSignal> =
        on(host, { beaconEvents(it, controls) }) { coreScope.events(host, controls) }
    override fun usesBrowseSnapshots(host: String) = router.route(host) is AnalyzerSourceRouter.Route.Beacon

    private suspend fun beaconRecent(backend: BeaconBackend, selection: AnalyzerSelection): List<LivePacket> =
        collectAnalyzerPages(200, identity = { it.hash }) { cursor, size ->
            backend.packetReader.packetPage(selection.region, size, null, cursor)
        }

    /**
     * One Beacon live feed per collection, confined to a serial dispatcher like iOS's @MainActor
     * feed; demands are re-applied on every hello, as iOS `LiveFeedService` does.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun beaconEvents(backend: BeaconBackend, controls: Flow<LiveFeedControls>): Flow<LiveSignal> =
        kotlinx.coroutines.flow.channelFlow {
            val feed = BeaconLiveFeed(backend.client, backend.serverInfo, this, makeSocket = router::makeSocket,
                observerReader = backend.observerReader)
            var latest: LiveFeedControls? = null
            fun apply() = latest?.let { feed.subscribe(it.subscriptions); feed.configure(it.configuration) }
            send(LiveSignal.Connection(LiveConnection.CONNECTING))
            launch {
                controls.collect { next ->
                    val refreshed = latest != null && latest?.observerRefresh != next.observerRefresh
                    latest = next
                    apply()
                    if (refreshed) feed.refreshObservers()
                }
            }
            feed.connect()
            try {
                for (event in feed.events) {
                    when (event) {
                        BeaconLiveEvent.Connected -> { apply(); send(LiveSignal.Connection(LiveConnection.LIVE)) }
                        is BeaconLiveEvent.Disconnected -> send(LiveSignal.Connection(LiveConnection.RECONNECTING))
                        else -> Unit
                    }
                    send(LiveSignal.Beacon(event))
                }
            } finally {
                feed.disconnect()
            }
        }.flowOn(BEACON_LIVE_DISPATCHER)

    companion object {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private val BEACON_LIVE_DISPATCHER = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)
    }
}
