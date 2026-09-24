package org.nodescope.android.feature.channels

import java.time.Instant
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.ChannelCrypto
import org.nodescope.android.core.network.ChannelPacket
import org.nodescope.android.core.network.GroupPayload

enum class ChannelSource(val title: String) { ALL("All channels"), MONITORED("Monitored on this device"), SERVER("Server monitored") }
enum class ChannelActivity(val title: String) { ALL("All activity"), LAST_HOUR("Active in one hour") }
enum class ChannelSort(val title: String) { RECENT("Recent activity"), MESSAGES("Message count"), NAME("Name") }

data class ChannelFilters(
    val source: ChannelSource = ChannelSource.ALL,
    val activity: ChannelActivity = ChannelActivity.ALL,
    val sort: ChannelSort = ChannelSort.RECENT,
    val query: String = "",
) {
    val activeCount: Int get() = listOf(source != ChannelSource.ALL, activity != ChannelActivity.ALL, sort != ChannelSort.RECENT).count { it }
}

/** A list row for either a server channel or a channel monitored with a key on this device. */
data class ChannelRow(
    val id: String, val name: String, val lastMessage: String?, val lastSender: String?,
    val messageCount: Int, val lastActivity: Instant?, val monitored: Boolean,
)

data class ChannelSummary(val messageCount: Int, val lastMessage: String?, val lastActivity: Instant?)

data class ChannelSections(val monitored: List<ChannelRow>, val server: List<ChannelRow>) {
    val isEmpty: Boolean get() = monitored.isEmpty() && server.isEmpty()
}

fun channelSections(
    server: List<MeshChannel>, monitored: List<MonitoredChannel>, summaries: Map<String, ChannelSummary>,
    filters: ChannelFilters, now: Long = System.currentTimeMillis(),
): ChannelSections {
    fun matches(row: ChannelRow): Boolean {
        val active = filters.activity == ChannelActivity.ALL || row.lastActivity?.let { now - it.toEpochMilli() < 3_600_000 } == true
        val query = filters.query.trim()
        return active && (query.isEmpty() || listOfNotNull(row.name, row.lastMessage, row.lastSender).any { it.contains(query, ignoreCase = true) })
    }
    val comparator: Comparator<ChannelRow> = when (filters.sort) {
        ChannelSort.RECENT -> compareByDescending<ChannelRow> { it.name.equals("Public", ignoreCase = true) }
            .thenByDescending { it.lastActivity ?: Instant.MIN }
        ChannelSort.MESSAGES -> compareByDescending<ChannelRow> { it.messageCount }.thenByDescending { it.lastActivity ?: Instant.MIN }
        ChannelSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }
    val monitoredRows = monitored.map { channel ->
        val summary = summaries[channel.channelName]
        ChannelRow("user:${channel.channelName}", channel.title, summary?.lastMessage ?: "Monitored locally", null,
            summary?.messageCount ?: 0, summary?.lastActivity, monitored = true)
    }
    val monitoredNames = monitored.map { it.channelName }.toSet()
    val serverRows = server.filter { it.name !in monitoredNames }.map {
        ChannelRow(it.hash, it.name, it.lastMessage, it.lastSender, it.messageCount, parseInstant(it.lastActivity), monitored = false)
    }
    return ChannelSections(
        if (filters.source == ChannelSource.SERVER) emptyList() else monitoredRows.filter(::matches).sortedWith(comparator),
        if (filters.source == ChannelSource.MONITORED) emptyList() else serverRows.filter(::matches).sortedWith(comparator),
    )
}

/** One chat bubble. `hops` is null when the source does not report a path length. */
data class ConversationMessage(
    val id: String, val sender: String, val text: String, val timestamp: Instant?,
    val repeats: Int, val observers: List<String>, val hops: Int?, val snr: Double?, val packetHash: String?,
)

fun ChannelMessage.toConversation(): ConversationMessage = ConversationMessage(
    id = packetId?.toString() ?: packetHash ?: "$timestamp|$sender|$text",
    sender = sender?.takeIf(String::isNotBlank) ?: "Unknown", text = text, timestamp = parseInstant(timestamp),
    repeats = repeats ?: 1, observers = observers, hops = hops, snr = snr, packetHash = packetHash,
)

/**
 * Messages for a locally monitored channel: packets the analyzer already decrypted are matched
 * by channel name; the rest are authenticated and decrypted here with the device-held key.
 */
fun monitoredConversation(packets: List<ChannelPacket>, channel: MonitoredChannel): List<ConversationMessage> {
    val channelHash = ChannelCrypto.channelHash(channel.keyHex) ?: return emptyList()
    return packets.distinctBy { it.hash.ifEmpty { it.id.toString() } }.mapNotNull { packet ->
        val payload = packet.payload
        val (sender, text, hops) = when {
            payload.type == "CHAN" && payload.channel == channel.channelName -> {
                val sender = payload.sender?.takeIf(String::isNotBlank) ?: "Unknown"
                Triple(sender, payload.text.orEmpty().removePrefix("$sender: "), payload.pathLength)
            }
            payload.type == "GRP_TXT" && payload.channelHash == channelHash -> {
                val decrypted = decrypt(payload, channel.keyHex) ?: return@mapNotNull null
                Triple(decrypted.sender, decrypted.text, null)
            }
            else -> return@mapNotNull null
        }
        ConversationMessage(packet.id.toString(), sender, text, parseInstant(packet.firstSeen), packet.observationCount,
            listOfNotNull(packet.observerName), hops, packet.snr, packet.hash.ifEmpty { null })
    }
}

private fun decrypt(payload: GroupPayload, keyHex: String): ChannelCrypto.Decrypted? {
    val mac = payload.mac ?: return null
    val data = payload.encryptedData ?: return null
    return ChannelCrypto.decrypt(keyHex, mac, data)
}

fun summarize(messages: List<ConversationMessage>): ChannelSummary {
    val latest = messages.maxByOrNull { it.timestamp ?: Instant.MIN }
    return ChannelSummary(messages.size, latest?.text, latest?.timestamp)
}

/** Oldest first. Speakers alternate sides; one sender's consecutive run stays on one side. */
fun chronological(messages: List<ConversationMessage>): List<Pair<ConversationMessage, Boolean>> {
    var trailing = false
    var previous: String? = null
    return messages.sortedBy { it.timestamp ?: Instant.MIN }.map { message ->
        if (previous != null && previous != message.sender) trailing = !trailing
        previous = message.sender
        message to trailing
    }
}

/**
 * The messages endpoint has no region parameter, so a region keeps messages heard by that
 * region's observers. Returns null when observer regions are unknown and nothing can be decided.
 */
fun regionFiltered(messages: List<ConversationMessage>, region: String?, observers: List<MeshObserver>): List<ConversationMessage>? {
    region ?: return messages
    if (observers.isEmpty()) return null
    val names = observers.filter { it.iata.equals(region, ignoreCase = true) }.mapNotNull { it.name?.lowercase() }.toSet()
    return messages.filter { message -> message.observers.any { it.lowercase() in names } }
}
