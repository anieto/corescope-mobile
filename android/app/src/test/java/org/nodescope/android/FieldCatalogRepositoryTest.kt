package org.nodescope.android

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.nodescope.android.core.network.BodySource
import org.nodescope.android.core.network.FieldCatalogRepository
import org.nodescope.android.core.network.HttpBrowseRepository

class FieldCatalogRepositoryTest {
    @Test fun recentNodeCatalogIsSharedAndForcedRefreshReplacesIt() = runBlocking {
        var now = 1_000L
        var requests = 0
        val upstream = HttpBrowseRepository(BodySource { url ->
            requests++
            val name = if (requests == 1) "first" else "refreshed"
            """{"nodes":[{"public_key":"aa","name":"$name","role":"repeater","last_seen":"2026-01-01T00:00:00Z"}],"total":1}"""
        })
        val repository = FieldCatalogRepository(upstream) { now }

        val first = repository.nodes("example.org")
        val shared = repository.nodes("example.org")
        assertEquals("first", first.value.single().name)
        assertEquals("first", shared.value.single().name)
        assertEquals(1, requests)

        now += 30_000
        val refreshed = repository.nodes("example.org", forceRefresh = true)
        assertEquals("refreshed", refreshed.value.single().name)
        assertEquals(2, requests)
    }

    @Test fun simultaneousNodeConsumersShareOneRefresh() = runBlocking {
        var requests = 0
        val upstream = HttpBrowseRepository(BodySource {
            requests++
            """{"nodes":[],"total":0}"""
        })
        val repository = FieldCatalogRepository(upstream) { 1_000L }

        val first = async { repository.nodes("example.org") }
        val second = async { repository.nodes("example.org") }
        first.await()
        second.await()
        assertEquals(1, requests)
    }
}
