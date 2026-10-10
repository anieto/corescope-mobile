package org.nodescope.android.core.network

import okhttp3.OkHttpClient
import org.nodescope.android.core.model.RegionCoordinate

enum class BackendCapability {
    NODE_HEALTH, NODE_REACH, NODE_PATHS, NODE_ANALYTICS,
    OBSERVER_ANALYTICS, CHANNEL_DECRYPT_ON_PHONE, SERVER_CHANNELS, MAP_DEFAULTS,
}

interface AnalyzerBackend : AnalyzerRepository, BrowseRepository {
    val capabilities: Set<BackendCapability>
    val liveFeed: PacketSource
}

class CoreScopeBackend(
    analyzer: AnalyzerRepository,
    browse: BrowseRepository,
    override val liveFeed: PacketSource,
) : AnalyzerBackend, AnalyzerRepository by analyzer, BrowseRepository by browse {
    override val capabilities: Set<BackendCapability> = setOf(
        BackendCapability.NODE_HEALTH, BackendCapability.NODE_REACH,
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
    ): AnalyzerBackend = CoreScopeBackend(
        HttpAnalyzerRepository(client, cache, airports),
        HttpBrowseRepository(client, cache),
        HttpPacketSource(client),
    )
}
