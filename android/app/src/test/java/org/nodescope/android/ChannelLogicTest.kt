package org.nodescope.android

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import org.nodescope.android.feature.channels.*

class ChannelLogicTest {
    private val now = Instant.parse("2026-09-23T12:05:00Z").toEpochMilli()
    private val server = protocolJson.decodeFromString<ChannelsResponse>(fixture("channels.json")).channels
    private val packets = parseChannelPackets(fixture("channel-packets.json"))
    private val fixtureChannel = MonitoredChannel("#nodescope-test", ChannelCrypto.keyHexFor("#nodescope-test"))

    @Test fun decodesChannelsAndMessagesWithMissingFields() {
        assertEquals(listOf("Public", "#test/one", "#nodescope-test"), server.map { it.hash })
        assertNull(server[1].lastMessage)
        val messages = protocolJson.decodeFromString<ChannelMessagesResponse>(fixture("channel-messages.json")).messages
        val bare = messages[1].toConversation()
        assertEquals(1, bare.repeats)
        assertNull(bare.hops)
        assertNull(bare.snr)
    }

    @Test fun urlsEncodeIdentifiersAndUseTheWorkingTypeFilter() {
        assertEquals("/api/channels/%23test%2Fone/messages", channelMessagesUrl("example.org", "#test/one").encodedPath)
        val url = channelPacketsUrl(AnalyzerSelection("example.org", "DFW"))
        assertEquals("5", url.queryParameter("type"))
        assertEquals("1000", url.queryParameter("limit"))
        assertEquals("DFW", url.queryParameter("region"))
        assertEquals("DFW", channelsUrl(AnalyzerSelection("example.org", "DFW")).queryParameter("region"))
        assertNull(channelsUrl(AnalyzerSelection("example.org", null)).queryParameter("region"))
        assertEquals("/api/observers/OBS%2F1/analytics", observerAnalyticsUrl("example.org", "OBS/1").encodedPath)
    }

    @Test fun packetParsingKeepsOnlyGroupTraffic() {
        assertEquals(listOf(501L, 502L, 503L), packets.map { it.id })
        assertEquals(69, packets[1].payload.channelHash) // numeric strings are decimal
    }

    @Test fun monitoredConversationUsesServerDecryptionAndLocalDecryption() {
        val messages = monitoredConversation(packets, fixtureChannel).associateBy { it.id }
        assertEquals(setOf("501", "502"), messages.keys) // 503 has a bad MAC
        assertEquals("Test Node", messages.getValue("501").sender)
        assertEquals("Hello from a fixture", messages.getValue("501").text)
        assertNull(messages.getValue("501").hops)
        assertEquals("decrypted by analyzer", messages.getValue("502").text) // sender prefix removed
        assertEquals(3, messages.getValue("502").hops)
        assertTrue(monitoredConversation(packets, MonitoredChannel("psk:00112233", "00112233445566778899aabbccddeeff")).isEmpty())
    }

    @Test fun sectionsSeparateMonitoredChannelsAndSortPublicFirst() {
        val summaries = mapOf(fixtureChannel.channelName to summarize(monitoredConversation(packets, fixtureChannel)))
        val sections = channelSections(server, listOf(fixtureChannel), summaries, ChannelFilters(), now)
        assertEquals(listOf("user:#nodescope-test"), sections.monitored.map { it.id })
        assertEquals(2, sections.monitored.single().messageCount)
        assertEquals(listOf("Public", "#test/one"), sections.server.map { it.id }) // monitored name is not duplicated
        val byCount = channelSections(server, emptyList(), emptyMap(), ChannelFilters(sort = ChannelSort.MESSAGES), now)
        assertEquals(listOf(120, 9, 3), byCount.server.map { it.messageCount })
        val byName = channelSections(server, emptyList(), emptyMap(), ChannelFilters(sort = ChannelSort.NAME), now)
        assertEquals(listOf("#nodescope-test", "#test/one", "Public"), byName.server.map { it.name })
    }

    @Test fun sectionFiltersMatchIosMeanings() {
        val recent = channelSections(server, emptyList(), emptyMap(), ChannelFilters(activity = ChannelActivity.LAST_HOUR), now)
        assertEquals(listOf("Public", "#test/one", "#nodescope-test").sorted(), recent.server.map { it.id }.sorted())
        val later = Instant.parse("2026-09-23T12:45:00Z").toEpochMilli()
        assertEquals(listOf("Public", "#nodescope-test"),
            channelSections(server, emptyList(), emptyMap(), ChannelFilters(activity = ChannelActivity.LAST_HOUR), later).server.map { it.id })
        assertEquals(listOf("Public"), channelSections(server, emptyList(), emptyMap(), ChannelFilters(query = "relay"), now).server.map { it.id })
        val onlyServer = channelSections(server, listOf(fixtureChannel), emptyMap(), ChannelFilters(source = ChannelSource.SERVER), now)
        assertTrue(onlyServer.monitored.isEmpty())
        val onlyMonitored = channelSections(server, listOf(fixtureChannel), emptyMap(), ChannelFilters(source = ChannelSource.MONITORED), now)
        assertTrue(onlyMonitored.server.isEmpty())
    }

    @Test fun conversationAlternatesSpeakersOldestFirst() {
        fun message(id: String, sender: String, minute: Int) = ConversationMessage(id, sender, "hi",
            Instant.parse("2026-09-23T12:%02d:00Z".format(minute)), 1, emptyList(), null, null, null)
        val ordered = chronological(listOf(message("c", "B", 3), message("a", "A", 1), message("b", "A", 2), message("d", "A", 4)))
        assertEquals(listOf("a" to false, "b" to false, "c" to true, "d" to false), ordered.map { it.first.id to it.second })
    }

    @Test fun regionFilterUsesObserverNamesAndReportsUnknownRegions() {
        val messages = protocolJson.decodeFromString<ChannelMessagesResponse>(fixture("channel-messages.json")).messages.map { it.toConversation() }
        val observers = protocolJson.decodeFromString<ObserversResponse>(fixture("observers.json")).observers
        assertEquals(listOf("10"), regionFiltered(messages, "dfw", observers)?.map { it.id })
        assertEquals(messages, regionFiltered(messages, null, emptyList()))
        assertNull(regionFiltered(messages, "DFW", emptyList()))
    }
}
