package org.nodescope.android

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.network.*

class AnalyzerPagingTest {
    @Test fun coreScopePagesUseOffsetsAndRejectOtherSources() = runTest {
        val requests = mutableListOf<String>()
        val body = BodySource { url ->
            requests += url.encodedQuery.orEmpty()
            """{"packets":[{"id":1,"hash":"first"}],"total":2}"""
        }
        val url = endpoint("fixture.example", "api", "packets")
        val first = coreScopePacketPage(body, url, 1, null)
        assertNotNull(first.nextCursor)
        val last = coreScopePacketPage(body, url, 1, first.nextCursor)
        assertNull(last.nextCursor)
        assertEquals(listOf("limit=1", "limit=1&offset=1"), requests)
        try {
            coreScopePacketPage(body, endpoint("other.example", "api", "packets"), 1, first.nextCursor)
            fail("Cursor must belong to its source")
        } catch (_: IllegalArgumentException) {}
        assertEquals(2, requests.size)
    }
    @Test fun mergesLiveDemandsWithoutDisablingAnotherConsumer() {
        val map = LiveFeedDemand(configuration = LiveFeedConfiguration(resolvePath = true))
        val observer = LiveFeedDemand(configuration = LiveFeedConfiguration(includeObserverKey = true))
        assertTrue(mergeLiveFeedDemands(listOf(map, observer)).configuration.resolvePath)
        assertTrue(mergeLiveFeedDemands(listOf(map, observer)).configuration.includeObserverKey)
        assertTrue(mergeLiveFeedDemands(listOf(map)).configuration.resolvePath)
        assertFalse(mergeLiveFeedDemands(emptyList()).configuration.resolvePath)
    }
    @Test fun preservesServerOrderAndDeduplicatesOverlaps() = runTest {
        val sizes = mutableListOf<Int>()
        val result = collectAnalyzerPages(4, identity = { value: Int -> value }) { cursor, size ->
            sizes += size
            if (cursor == null) AnalyzerPage(listOf(3, 1), AnalyzerCursor("opaque"))
            else {
                assertEquals("opaque", cursor.value)
                AnalyzerPage(listOf(1, 4, 2), null)
            }
        }
        assertEquals(listOf(3, 1, 4, 2), result)
        assertEquals(listOf(4, 2), sizes)
    }

    @Test fun rejectsRepeatedCursor() = runTest {
        try {
            collectAnalyzerPages(10, identity = { value: Int -> value }) { _, _ ->
                AnalyzerPage(listOf(1), AnalyzerCursor("same"))
            }
            fail("Repeated cursor must fail")
        } catch (_: IllegalStateException) {}
    }

    @Test fun enforcesPageBudget() = runTest {
        var calls = 0
        try {
            collectAnalyzerPages(10, maximumPages = 2, identity = { value: Int -> value }) { _, _ ->
                calls++
                AnalyzerPage(listOf(calls), AnalyzerCursor(calls.toString()))
            }
            fail("Budget exhaustion must fail")
        } catch (_: IllegalStateException) {}
        assertEquals(2, calls)
    }
}
