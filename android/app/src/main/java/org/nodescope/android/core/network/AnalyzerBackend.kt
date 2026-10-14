package org.nodescope.android.core.network

import okhttp3.OkHttpClient
import org.nodescope.android.core.model.RegionCoordinate
import org.nodescope.android.core.model.AnalyzerSelection

enum class BackendCapability {
    NEIGHBORS, NODE_HEALTH, NODE_REACH, NODE_PATHS, NODE_ANALYTICS,
    OBSERVER_ANALYTICS, CHANNEL_DECRYPT_ON_PHONE, SERVER_CHANNELS, MAP_DEFAULTS,
}

interface AnalyzerBackend : AnalyzerRepository, BrowseRepository {
    val capabilities: Set<BackendCapability>
    val liveFeed: PacketSource
}

class CapabilityBrowseRepository(
    private val delegate: BrowseRepository,
    private val capabilities: () -> Set<BackendCapability>,
) : BrowseRepository by delegate {
    /** Fails before any request, with the feature's explanation (iOS `beaconExplanation`). */
    private fun requireCapability(capability: BackendCapability) {
        if (capability !in capabilities()) throw BeaconUnsupportedException(capability)
    }

    override suspend fun channels(selection: AnalyzerSelection) =
        requireCapability(BackendCapability.SERVER_CHANNELS).let { delegate.channels(selection) }
    override suspend fun channelMessages(host: String, hash: String) =
        requireCapability(BackendCapability.SERVER_CHANNELS).let { delegate.channelMessages(host, hash) }
    override suspend fun channelPackets(selection: AnalyzerSelection) =
        requireCapability(BackendCapability.CHANNEL_DECRYPT_ON_PHONE).let { delegate.channelPackets(selection) }
    override suspend fun observerAnalytics(host: String, id: String) =
        requireCapability(BackendCapability.OBSERVER_ANALYTICS).let { delegate.observerAnalytics(host, id) }
    override suspend fun nodeHealth(host: String, publicKey: String) =
        requireCapability(BackendCapability.NODE_HEALTH).let { delegate.nodeHealth(host, publicKey) }
    override suspend fun nodePaths(host: String, publicKey: String) =
        requireCapability(BackendCapability.NODE_PATHS).let { delegate.nodePaths(host, publicKey) }
    override suspend fun nodeReach(host: String, publicKey: String) =
        requireCapability(BackendCapability.NODE_REACH).let { delegate.nodeReach(host, publicKey) }
    override suspend fun neighbors(host: String, publicKey: String) =
        requireCapability(BackendCapability.NEIGHBORS).let { delegate.neighbors(host, publicKey) }
    override suspend fun nodeAnalytics(host: String, publicKey: String, days: Int) =
        requireCapability(BackendCapability.NODE_ANALYTICS).let { delegate.nodeAnalytics(host, publicKey, days) }

    override suspend fun <T> saved(maxAgeMillis: Long, block: suspend BrowseRepository.() -> T): Cached<T>? =
        delegate.saved(maxAgeMillis) { block(CapabilityBrowseRepository(this, capabilities)) }
}

class CoreScopeBackend(
    analyzer: AnalyzerRepository,
    browse: BrowseRepository,
    override val liveFeed: PacketSource,
) : AnalyzerBackend, AnalyzerRepository by analyzer, BrowseRepository by browse {
    override val capabilities: Set<BackendCapability> = setOf(
        BackendCapability.NEIGHBORS, BackendCapability.NODE_HEALTH, BackendCapability.NODE_REACH,
        BackendCapability.NODE_PATHS, BackendCapability.NODE_ANALYTICS,
        BackendCapability.OBSERVER_ANALYTICS, BackendCapability.CHANNEL_DECRYPT_ON_PHONE,
        BackendCapability.SERVER_CHANNELS, BackendCapability.MAP_DEFAULTS,
    )
}

object AnalyzerBackendFactory {
    fun create(
        client: OkHttpClient,
        cache: ResponseCache? = null,
        airports: () -> Map<String, RegionCoordinate> = { emptyMap() },
    ): AnalyzerBackend = createCoreScope(client, cache, airports)

    fun createCoreScope(
        client: OkHttpClient,
        cache: ResponseCache? = null,
        airports: () -> Map<String, RegionCoordinate> = { emptyMap() },
    ): CoreScopeBackend = CoreScopeBackend(
        HttpAnalyzerRepository(client, cache, airports),
        HttpBrowseRepository(client, cache),
        HttpPacketSource(client),
    )
}
