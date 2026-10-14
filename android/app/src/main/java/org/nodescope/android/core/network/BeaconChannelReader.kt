package org.nodescope.android.core.network

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import org.nodescope.android.core.model.*

/**
 * Port of iOS BeaconChannelReader: channels whose keys the server knows, decrypted server-side.
 * The list walks the opaque `nextPageCursor` (at most ten pages); messages read pages of 20,
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

    /** Channel id → (loaded offset → the server cursor that continues from it), so "older" is one request. */
    private val messageCursors = ConcurrentHashMap<Int, MutableMap<Int, Long>>()

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

    /**
     * Port of iOS `messages(channelID:offset:)`: [offset] counts messages already shown, newest
     * first. Offset 0 starts over; a later offset continues from the cursor saved when it was
     * reached, or walks at most ten pages when none was saved. [channelId] is `beacon:<id>` or the id.
     */
    suspend fun messages(channelId: String, offset: Int = 0): ChannelMessagesResponse {
        val id = channelId.removePrefix("beacon:").toIntOrNull()?.takeIf { it > 0 } ?: throw BeaconApiException.InvalidResponse()
        if (offset < 0) throw BeaconApiException.InvalidResponse()
        client.sourceSession.check()
        var cursor: Long? = null
        var consumed = 0
        val seenCursors = HashSet<Long>()
        if (offset == 0) messageCursors.remove(id)
        else messageCursors[id]?.get(offset)?.let { cursor = it; consumed = offset; seenCursors += it }

        repeat(10) {
            val query = mutableListOf("limit" to "20").apply { cursor?.let { add("cursor" to it.toString()) } }
            val page = client.get(MessagePage.serializer(), listOf("channels", id.toString(), "messages"), query)
            client.sourceSession.check()
            if (page.items.map { it.id }.toSet().size != page.items.size ||
                !page.items.all { it.id > 0 && it.packetHash.isNotEmpty() && it.observationCount >= 0 }) {
                throw BeaconApiException.InvalidResponse()
            }
            // A page that says there is more must say where it continues, and never repeat itself.
            val next = if (page.hasMore) {
                val value = page.nextCursor
                if (page.items.isEmpty() || value == null || !seenCursors.add(value)) throw BeaconApiException.MalformedPage()
                value
            } else null

            val nextConsumed = consumed + page.items.size
            if (offset < nextConsumed || (offset == consumed && page.items.isEmpty())) {
                val messages = page.items.drop(maxOf(0, offset - consumed)).map {
                    ChannelMessage(sender = it.senderName, text = it.content, timestamp = Instant.ofEpochMilli(it.sentAt).toString(),
                        packetId = it.id, packetHash = it.packetHash, repeats = it.observationCount)
                }
                next?.let { messageCursors.getOrPut(id) { ConcurrentHashMap() }[offset + messages.size] = it }
                return ChannelMessagesResponse(messages, offset + messages.size + if (page.hasMore) 1 else 0)
            }
            if (next == null) return ChannelMessagesResponse(emptyList(), consumed)
            consumed = nextConsumed
            cursor = next
        }
        throw BeaconApiException.MalformedPage()
    }
}

/** A saved Beacon channel that is gone and can't be safely re-matched after a server reset. */
class BeaconChannelUnavailableException : IllegalStateException("This saved channel is no longer available on this Beacon server.")
