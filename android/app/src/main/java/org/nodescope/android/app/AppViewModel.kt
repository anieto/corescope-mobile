package org.nodescope.android.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import org.nodescope.android.core.storage.*

data class PendingLink(val id: Long, val link: NodeScopeLink)

class AppViewModel(private val container: AppContainer) : ViewModel() {
    val preferences = container.preferences.values.map<AppPreferences, AppPreferences?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val session = AnalyzerSession(container.preferences, container.repository, container.sourceRouter.liveReadiness)
    /** Whether the selected analyzer has been identified as CoreScope or Beacon (iOS `sourceIsReady`). */
    val sourceStatus: StateFlow<AnalyzerSourceRouter.State> get() = container.sourceRouter.state
    fun retrySourceDetection() = container.sourceRouter.requestResolution()
    /** iOS "Choose Another Source": back to the source list without forgetting the current one. */
    fun chooseAnotherSource() = persist { container.preferences.setOnboarded(false) }
    val state = session.states.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionState())
    private val feed = LiveFeed(container.preferences, container.packetSource, container.sourceRouter.liveReadiness)
    // Packet bookkeeping runs off the main thread, which only receives each finished state; on a
    // busy network it otherwise competed with the map's animation for every frame. The socket
    // outlives a quick app switch or rotation, then closes in the background (Beacon's guidance).
    val live = feed.states.flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(10_000, 0), LiveFeedState())
    fun reconnectLive() = feed.reconnect()
    fun refreshBeaconObservers() = feed.refreshObservers()
    fun retryBeaconMetadata() = feed.retryMetadataRecovery()
    fun setLiveFeedDemand(consumer: java.util.UUID, host: String, demand: LiveFeedDemand?) =
        feed.setDemand(consumer, host, demand)
    val browse: BrowseRepository get() = container.browse
    val monitoredChannels: MonitoredChannelStore get() = container.monitoredChannels
    val diagnostics: AnalyzerDiagnostics get() = container.diagnostics
    val cacheStorage: CacheStorage get() = container.cacheStorage
    val myNodesSnapshots get() = container.myNodesStorage.snapshots
    fun myNodesRepositories(): MyNodesRepositories {
        val selected = sourceStatus.value
        check(selected.ready)
        selected.session.checkCurrent()
        return selected.beacon?.let { container.myNodesStorage.beacon(it.client, it.serverInfo) }
            ?: container.myNodesStorage.coreScope(checkNotNull(selected.host), container.client, selected.session)
    }
    val sourceIcons: org.nodescope.android.core.storage.SourceIcons get() = container.sourceIcons
    private val linkState = MutableStateFlow<PendingLink?>(null)
    /** A `nodescope://` link waiting to be opened; kept until the app shell is ready (after onboarding). */
    val pendingLink = linkState.asStateFlow()
    fun openLink(data: String?) { NodeScopeLink.parse(data)?.let { linkState.value = PendingLink(System.nanoTime(), it) } }
    fun linkHandled(id: Long) { if (linkState.value?.id == id) linkState.value = null }
    private val sourceChangeState = MutableStateFlow<Long?>(null)
    /** When the analyzer was last changed from Settings; drives the connection banner. */
    val sourceChangedAt = sourceChangeState.asStateFlow()
    private val sourceState = MutableStateFlow(container.bundledSources.sources)
    val sources = sourceState.asStateFlow()
    private val savingState = MutableStateFlow(false)
    val saving = savingState.asStateFlow()
    private val writeErrorState = MutableStateFlow<String?>(null)
    val writeError = writeErrorState.asStateFlow()

    init {
        viewModelScope.launch { container.responses.prune() }
        // Identify the source whenever it changes or a retry is requested (iOS app `.task(id:)`).
        viewModelScope.launch {
            container.preferences.values.map { it.onboarded to it.host }.distinctUntilChanged().collectLatest { (onboarded, host) ->
                if (!onboarded) return@collectLatest
                container.sourceRouter.setHost(host)
                container.sourceRouter.state.map { it.resolutionId }.distinctUntilChanged().collectLatest {
                    val normalized = runCatching { normalizeHost(host).lowercase() }.getOrDefault(host.lowercase())
                    val listed = sourceState.value.firstOrNull {
                        runCatching { normalizeHost(it.host).lowercase() }.getOrNull() == normalized
                    }
                    val requiresBeacon = listed?.backendType == AnalyzerSourceType.BEACON
                    val knownCoreScope = !requiresBeacon &&
                        (normalized == DEFAULT_HOST || listed?.backendType == AnalyzerSourceType.CORESCOPE)
                    container.sourceRouter.resolve(knownCoreScope, requiresBeacon)
                }
            }
        }
        viewModelScope.launch {
            val cached = container.preferences.values.first().registry
            cached?.let { parseSources(it)?.let { sourceState.value = it.sources } }
            try {
                val raw = container.client.read(SOURCE_REGISTRY_URL.toHttpUrl())
                parseSources(raw)?.let {
                    sourceState.value = it.sources
                    container.preferences.cacheRegistry(raw)
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                // The bundled/cached registry stays usable while offline.
            }
        }
    }
    private fun parseSources(raw: String): SourceDocument? = runCatching {
        decodeSourceRegistryV2(raw).let { document ->
            document.copy(sources = document.sources.filter {
                it.name.isNotBlank() && runCatching { normalizeHost(it.host) }.isSuccess
            }).takeIf { it.sources.isNotEmpty() }
        }
    }.getOrNull()

    fun selectSource(input: String, onSaved: () -> Unit = {}) {
        if (savingState.value) return
        savingState.value = true
        viewModelScope.launch {
            writeErrorState.value = null
            try {
                val host = normalizeHost(input)
                val sameHost = container.sourceRouter.state.value.host == host.lowercase()
                container.preferences.selectSource(host)
                // Re-choosing the current source re-identifies it, as on iOS.
                if (sameHost) container.sourceRouter.requestResolution()
                sourceChangeState.value = System.currentTimeMillis()
                onSaved()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                writeErrorState.value = e.message ?: "Unable to save analyzer."
            } finally { savingState.value = false }
        }
    }
    fun setAppearance(mode: Appearance) = persist { container.preferences.setAppearance(mode) }
    fun setBackgroundTexture(enabled: Boolean) = persist { container.preferences.setBackgroundTexture(enabled) }
    fun setDistanceUnit(unit: org.nodescope.android.core.storage.DistanceUnit) = persist { container.preferences.setDistanceUnit(unit) }
    fun setRegion(region: String?) = persist { container.preferences.setRegion(region) }
    fun selectDestination(destination: String) = persist { container.preferences.setDestination(destination) }
    fun refresh() = session.refresh()
    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block(); writeErrorState.value = null }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { writeErrorState.value = "Unable to save settings. Please try again." }
        }
    }
    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
        }
    }
}
