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
    private val session = AnalyzerSession(container.preferences, container.repository)
    val state = session.states.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionState())
    private val feed = LiveFeed(container.preferences, container.packetSource)
    val live = feed.states.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), LiveFeedState())
    fun reconnectLive() = feed.reconnect()
    val browse: BrowseRepository get() = container.browse
    val monitoredChannels: MonitoredChannelStore get() = container.monitoredChannels
    val diagnostics: AnalyzerDiagnostics get() = container.diagnostics
    val cacheStorage: CacheStorage get() = container.cacheStorage
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
        viewModelScope.launch {
            val cached = container.preferences.values.first().registry
            cached?.let { parseSources(it)?.let { sourceState.value = it.sources } }
            try {
                val raw = container.client.read("https://raw.githubusercontent.com/anieto/corescope-mobile/main/CommunitySources/us-sources.json".toHttpUrl())
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
        protocolJson.decodeFromString<SourceDocument>(raw).let { document ->
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
                container.preferences.selectSource(host)
                sourceChangeState.value = System.currentTimeMillis()
                onSaved()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                writeErrorState.value = e.message ?: "Unable to save analyzer."
            } finally { savingState.value = false }
        }
    }
    fun setAppearance(mode: Appearance) = persist { container.preferences.setAppearance(mode) }
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
