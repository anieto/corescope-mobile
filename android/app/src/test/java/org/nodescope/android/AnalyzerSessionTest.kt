package org.nodescope.android

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import org.nodescope.android.core.storage.*

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyzerSessionTest {
    @Test fun noRequestsBeforeOnboarding() = runTest {
        val preferences = MemoryPreferences(AppPreferences())
        var requests = 0
        val session = AnalyzerSession(preferences, object : AnalyzerRepository {
            override suspend fun load(selection: AnalyzerSelection): AnalyzerSnapshot { requests++; return snapshot() }
        })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.states.collect() }
        runCurrent()
        assertEquals(0, requests)
    }
    @Test fun savedSnapshotShowsWhileTheAnalyzerIsUnreachable() = runTest {
        val preferences = MemoryPreferences(AppPreferences(host = "a.example", onboarded = true))
        val saved = snapshot()
        val session = AnalyzerSession(preferences, object : AnalyzerRepository {
            override suspend fun load(selection: AnalyzerSelection): AnalyzerSnapshot = throw java.io.IOException("offline")
            override suspend fun saved(selection: AnalyzerSelection) = Cached(saved, 0L)
        })
        val states = mutableListOf<SessionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.states.toList(states) }
        runCurrent()
        assertTrue(states.any { it.snapshot === saved && it.loading })
        assertSame(saved, states.last().snapshot)
        assertEquals("offline", states.last().error)
    }
    @Test fun sourceChangeCancelsPendingWorkAndEmitsCleanState() = runTest {
        val preferences = MemoryPreferences(AppPreferences(host = "old.example", onboarded = true))
        var oldCancelled = false
        val releaseNew = CompletableDeferred<Unit>()
        val session = AnalyzerSession(preferences, object : AnalyzerRepository {
            override suspend fun load(selection: AnalyzerSelection): AnalyzerSnapshot {
                if (selection.host == "old.example") {
                    try { awaitCancellation() } finally { oldCancelled = true }
                }
                releaseNew.await()
                return snapshot()
            }
        })
        val states = mutableListOf<SessionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.states.toList(states) }
        runCurrent()
        preferences.selectSource("new.example")
        runCurrent()
        assertTrue(oldCancelled)
        assertEquals("new.example", states.last().selection?.host)
        assertNull(states.last().snapshot)
        assertTrue(states.last().loading)
        releaseNew.complete(Unit)
        runCurrent()
        assertNotNull(states.last().snapshot)
        assertFalse(states.last().loading)
    }
    @Test fun regionChangesDoNotReuseOtherRegionSnapshot() = runTest {
        val preferences = MemoryPreferences(AppPreferences(onboarded = true))
        val release = CompletableDeferred<Unit>()
        val session = AnalyzerSession(preferences, object : AnalyzerRepository {
            override suspend fun load(selection: AnalyzerSelection): AnalyzerSnapshot {
                if (selection.region != null) release.await()
                return snapshot()
            }
        })
        val states = mutableListOf<SessionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.states.toList(states) }
        runCurrent()
        assertNotNull(states.last().snapshot)
        preferences.setRegion("TEST")
        runCurrent()
        assertEquals("TEST", states.last().selection?.region)
        assertNull(states.last().snapshot)
    }
    @Test fun refreshFailureRetainsSameSourceSnapshotAndCanRetry() = runTest {
        val preferences = MemoryPreferences(AppPreferences(onboarded = true))
        var attempt = 0
        val session = AnalyzerSession(preferences, object : AnalyzerRepository {
            override suspend fun load(selection: AnalyzerSelection): AnalyzerSnapshot {
                if (++attempt == 2) throw java.io.IOException("Offline")
                return snapshot()
            }
        })
        val states = mutableListOf<SessionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.states.toList(states) }
        runCurrent()
        session.refresh(); runCurrent()
        assertNotNull(states.last().snapshot)
        assertEquals("Offline", states.last().error)
        session.refresh(); runCurrent()
        assertNull(states.last().error)
        assertEquals(3, attempt)
    }
    @Test fun appearanceAndDestinationChangesDoNotReloadNetwork() = runTest {
        val preferences = MemoryPreferences(AppPreferences(onboarded = true))
        var requests = 0
        val session = AnalyzerSession(preferences, object : AnalyzerRepository {
            override suspend fun load(selection: AnalyzerSelection): AnalyzerSnapshot { requests++; return snapshot() }
        })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.states.collect() }
        runCurrent()
        preferences.setAppearance(Appearance.DARK)
        preferences.setDestination("SETTINGS")
        runCurrent()
        assertEquals(1, requests)
    }
    private fun snapshot() = AnalyzerSnapshot(emptyList(), 0, emptyMap(), null)
}

private class MemoryPreferences(initial: AppPreferences) : PreferencesStore {
    override val values = MutableStateFlow(initial)
    override suspend fun selectSource(host: String) { values.update { it.copy(host = host, onboarded = true, region = null) } }
    override suspend fun setRegion(region: String?) { values.update { it.copy(region = region) } }
    override suspend fun setAppearance(appearance: Appearance) { values.update { it.copy(appearance = appearance) } }
    override suspend fun setDestination(destination: String) { values.update { it.copy(destination = destination) } }
    override suspend fun cacheRegistry(document: String) { values.update { it.copy(registry = document) } }
}
