package org.nodescope.android.core.network

import java.time.Instant
import kotlinx.serialization.Serializable
import org.nodescope.android.core.model.*

/**
 * Port of iOS BeaconChannelReader: channels whose keys the server knows, decrypted server-side.
 * The list walks the opaque `nextPageCursor` (at most ten pages); messages read one page of 20,
 * the measured initial-view budget. Beacon reports no observers, hops or SNR per message, so
 * those stay unknown rather than invented.
 */
class BeaconChannelReader(val client: BeaconApiClient, verifiedServerInfo: BeaconServerInfo) {
    init { verifiedServerInfo.requireSupported() }

    @Serializable private data class Summary(val id: Int, val name: String, val channelHash: String, val lastSeen: Long,
        val isHashtag: Boolean, val keyKnown: Boolean)
    @Serializable private data class Page(val items: List<Summary> = emptyList(), val hasMore: Boolean, val nextPageCursor: String? = null)
    @Serializable private data class Message(val id: Long, val packetHash: String, val senderName: String, val content: String,
        val sentAt: Long, val observationCount: Int)
    @Serializable private data class MessagePage(val items: List<Message> = emptyList(), val nextCursor: Long? = null, val hasMore: Boolean)

    suspend fun channels(pageSize: Int = 200): List<MeshChannel> {
        if (pageSize <= 0) throw AnalyzerPagingException.InvalidPageSize()
        client.sourceSession.check()
        val channels = mutableListOf<MeshChannel>()
        var cursor: String? = null
        val seenCursors = HashSet<String>()
        val seenIds = HashSet<Int>()
        repeat(10) {
            val query = mutableListOf("limit" to minOf(pageSize, 200).toString(), "keyKnown" to "true")
                .apply { cursor?.let { add("pageCursor" to it) } }
            val page = client.get(Page.serializer(), listOf("channels"), query)
            client.sourceSession.check()
            for (item in page.items) {
                if (item.id <= 0 || !item.keyKnown || item.name.isBlank() || !seenIds.add(item.id)) throw BeaconApiException.InvalidResponse()
                channels += MeshChannel(hash = item.channelHash, name = item.name, lastActivity = Instant.ofEpochMilli(item.lastSeen).toString(),
                    serverId = item.id, isHashtag = item.isHashtag)
            }
            if (!page.hasMore) return channels
            val next = page.nextPageCursor
            if (page.items.isEmpty() || next.isNullOrEmpty() || !seenCursors.add(next)) throw BeaconApiException.MalformedPage()
            cursor = next
        }
        throw BeaconApiException.MalformedPage()
    }

    /** [channelId] is the channel's `beacon:<id>` identity or its bare integer id. */
    suspend fun messages(channelId: String): List<ChannelMessage> {
        val id = channelId.removePrefix("beacon:").toIntOrNull()?.takeIf { it > 0 } ?: throw BeaconApiException.InvalidResponse()
        client.sourceSession.check()
        val page = client.get(MessagePage.serializer(), listOf("channels", id.toString(), "messages"), listOf("limit" to "20"))
        client.sourceSession.check()
        if (page.items.map { it.id }.toSet().size != page.items.size ||
            !page.items.all { it.id > 0 && it.packetHash.isNotEmpty() && it.observationCount >= 0 }) {
            throw BeaconApiException.InvalidResponse()
        }
        // A page that says there is more must say where it continues.
        if (page.hasMore && (page.items.isEmpty() || page.nextCursor == null)) throw BeaconApiException.MalformedPage()
        return page.items.map {
            ChannelMessage(sender = it.senderName, text = it.content, timestamp = Instant.ofEpochMilli(it.sentAt).toString(),
                packetId = it.id, packetHash = it.packetHash, repeats = it.observationCount)
        }
    }
}

/** A saved Beacon channel that is gone and can't be safely re-matched after a server reset. */
class BeaconChannelUnavailableException : IllegalStateException("This saved channel is no longer available on this Beacon server.")
