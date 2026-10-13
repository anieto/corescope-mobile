package org.nodescope.android.core.network

import kotlinx.coroutines.CancellationException
import org.nodescope.android.core.model.*

// Port of iOS BeaconBackend (AnalyzerBackend.swift): browse-only, no optional capabilities.

/** The explanation a screen shows when a CoreScope-only feature is opened on a Beacon server. */
val BackendCapability.beaconExplanation: String get() = when (this) {
    BackendCapability.NODE_ANALYTICS ->
        "Node analytics for Beacon servers aren’t supported in this version of NodeScope. Support is planned for a future update."
    BackendCapability.OBSERVER_ANALYTICS ->
        "Observer analytics for Beacon servers aren’t supported in this version of NodeScope. Activity and telemetry support is planned for a future update."
    BackendCapability.SERVER_CHANNELS ->
        "Channel messages from Beacon servers aren’t supported in this version of NodeScope. Support for server-decrypted channels is planned for a future update."
    BackendCapability.CHANNEL_DECRYPT_ON_PHONE ->
        "On-device channel decryption isn’t currently supported for Beacon sources. Future support depends on changes to Beacon’s API."
    BackendCapability.NODE_HEALTH, BackendCapability.NODE_REACH, BackendCapability.NODE_PATHS ->
        "These detailed statistics are currently available only with CoreScope servers. Node analytics and a neighbors view for Beacon servers are planned for a future update."
    BackendCapability.MAP_DEFAULTS ->
        "Beacon servers don’t provide map defaults. NodeScope uses region coordinates to frame the map instead."
}

class BeaconUnsupportedException(val capability: BackendCapability) : IllegalStateException(capability.beaconExplanation)
class BeaconNodeUnavailableException : IllegalStateException("This node is not available on this server.")

class BeaconBackend(val client: BeaconApiClient, val serverInfo: BeaconServerInfo) : AnalyzerRepository, BrowseRepository {
    val nodeReader: BeaconNodeReader
    val observerReader: BeaconObserverReader
    val packetReader: BeaconPacketReader
    val regionReader: BeaconRegionReader

    val sourceSession: AnalyzerSourceSession get() = client.sourceSession
    val cacheIdentifier: String get() = client.cacheIdentifier
    val capabilities: Set<BackendCapability> = emptySet()

    init {
        serverInfo.requireSupported()
        nodeReader = BeaconNodeReader(client, serverInfo)
        observerReader = BeaconObserverReader(client, serverInfo)
        packetReader = BeaconPacketReader(client, serverInfo)
        regionReader = BeaconRegionReader(client, serverInfo)
    }

    /** Android repositories take the host on every call; a different host is a stale caller. */
    private fun requireHost(host: String) {
        val requested = runCatching { normalizeHost(host).lowercase() }.getOrNull()
        if ("https://$requested" != client.origin) throw CancellationException("The analyzer source changed.")
    }

    override suspend fun load(selection: AnalyzerSelection): AnalyzerSnapshot {
        requireHost(selection.host)
        val nodes = nodeReader.nodes(selection.region).map { it.meshNode() }
            .distinctBy { it.publicKey }.sortedBy { it.displayName.lowercase() }
        val catalog = regionReader.catalog()
        sourceSession.check()
        return AnalyzerSnapshot(nodes, nodes.size, catalog.names, mapDefaults = null,
            configurationIncomplete = false, regionCoordinates = catalog.coordinates)
    }

    suspend fun nodeDetail(host: String, publicKey: String): MeshNode {
        requireHost(host)
        val node = nodeReader.nodeDetail(publicKey) ?: throw BeaconNodeUnavailableException()
        sourceSession.check()
        return node.meshNode()
    }

    override suspend fun observers(host: String): List<MeshObserver> {
        requireHost(host)
        val observers = observerReader.observers(null).map { it.meshObserver() }
        sourceSession.check()
        return observers
    }

    override suspend fun observerDetail(host: String, id: String): MeshObserver {
        requireHost(host)
        val detail = observerReader.observerDetail(id).meshObserver()
        sourceSession.check()
        return detail
    }

    override suspend fun allNodes(host: String): List<MeshNode> {
        requireHost(host)
        return nodeReader.nodes(null).map { it.meshNode() }.distinctBy { it.publicKey }
    }

    override suspend fun recentPackets(host: String, limit: Int): List<LivePacket> {
        sourceSession.check()
        val packets = collectAnalyzerPages(limit, identity = { it.hash }) { cursor, size -> packetPage(host, size, cursor) }
        sourceSession.check()
        return packets
    }

    override suspend fun packetPage(host: String, size: Int, cursor: AnalyzerCursor?): AnalyzerPage<LivePacket> {
        requireHost(host)
        return packetReader.packetPage(null, size, null, cursor)
    }

    override suspend fun packetDetail(host: String, hash: String): PacketDetail {
        requireHost(host)
        return packetReader.packetDetail(hash)
    }

    private suspend fun unavailable(capability: BackendCapability): Nothing {
        sourceSession.check()
        throw BeaconUnsupportedException(capability)
    }

    override suspend fun channels(selection: AnalyzerSelection): List<MeshChannel> = unavailable(BackendCapability.SERVER_CHANNELS)
    override suspend fun channelMessages(host: String, hash: String): List<ChannelMessage> = unavailable(BackendCapability.SERVER_CHANNELS)
    override suspend fun channelPackets(selection: AnalyzerSelection): List<ChannelPacket> = unavailable(BackendCapability.CHANNEL_DECRYPT_ON_PHONE)
    override suspend fun observerAnalytics(host: String, id: String): ObserverAnalytics = unavailable(BackendCapability.OBSERVER_ANALYTICS)
    override suspend fun nodeHealth(host: String, publicKey: String): NodeHealth = unavailable(BackendCapability.NODE_HEALTH)
    override suspend fun nodePaths(host: String, publicKey: String): NodePaths = unavailable(BackendCapability.NODE_PATHS)
    override suspend fun nodeReach(host: String, publicKey: String): NodeReach = unavailable(BackendCapability.NODE_REACH)
    override suspend fun nodeAnalytics(host: String, publicKey: String, days: Int): NodeAnalytics = unavailable(BackendCapability.NODE_ANALYTICS)
}
