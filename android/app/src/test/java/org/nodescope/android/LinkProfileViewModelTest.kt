package org.nodescope.android

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.linkprofile.*

@OptIn(ExperimentalCoroutinesApi::class)
class LinkProfileViewModelTest {
    private fun profile(coordinates: List<ProfileCoordinate>, elevation: Double) = TerrainProfile(
        coordinates.mapIndexed { index, _ -> ElevationPoint(index * 100.0, elevation) }, 1000, "cache")

    @Test fun incompleteCoordinatesDoNotFetchAndTypingIsDebounced() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val calls = mutableListOf<List<ProfileCoordinate>>()
            val model = LinkProfileViewModel(LinkTerrainProviding { calls += it; profile(it, 120.0) }, SavedStateHandle())
            model.edit("Latitude A", "40")
            advanceUntilIdle()
            assertTrue(calls.isEmpty())
            model.edit("Longitude A", "-90")
            runCurrent()
            advanceTimeBy(200)
            model.edit("Longitude A", "-91")
            runCurrent()
            advanceTimeBy(349)
            assertTrue(calls.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(ProfileCoordinate(40.0, -91.0), ProfileCoordinate(40.0, -91.0)), calls.single())
            assertEquals(120.0, model.state.value.elevations.getValue("A").meters!!, 0.0)
            model.edit("Height A (m)", "30")
            model.preset("EU/UK Narrow")
            advanceUntilIdle()
            assertEquals(1, calls.size)
            assertEquals(120.0, model.state.value.elevations.getValue("A").meters!!, 0.0)
            model.edit("Latitude A", "")
            assertNull(model.state.value.elevations.getValue("A").meters)
            assertFalse(model.state.value.elevations.getValue("A").loading)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun oldLookupCannotOverwriteNewEndpointEvenIfProviderIgnoresCancellation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val model = LinkProfileViewModel(LinkTerrainProviding { points ->
                if (points.first().latitude == 40.0) withContext(NonCancellable) { delay(2000) }
                profile(points, points.first().latitude)
            }, SavedStateHandle())
            model.edit("Latitude A", "40")
            model.edit("Longitude A", "-90")
            advanceTimeBy(351)
            runCurrent()
            model.edit("Latitude A", "41")
            advanceUntilIdle()
            assertEquals(41.0, model.state.value.elevations.getValue("A").meters!!, 0.0)
            assertFalse(model.state.value.elevations.getValue("A").loading)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun restoredEndpointsLoadIndependentlyAndFailureDoesNotBlockAnalysisInputs() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val model = LinkProfileViewModel(LinkTerrainProviding { points ->
                if (points.first().latitude == 40.0) throw java.io.IOException("Offline")
                profile(points, 80.0)
            }, SavedStateHandle(mapOf("Latitude A" to "40", "Longitude A" to "-90", "Latitude B" to "41", "Longitude B" to "-90")))
            advanceUntilIdle()
            assertNull(model.state.value.elevations.getValue("A").meters)
            assertFalse(model.state.value.elevations.getValue("A").loading)
            assertEquals(80.0, model.state.value.elevations.getValue("B").meters!!, 0.0)
            assertNull(model.state.value.error)
            assertEquals("40", model.state.value.fields["Latitude A"])
        } finally { Dispatchers.resetMain() }
    }

    @Test fun analysisCompletionPreservesEndpointElevations() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val model = LinkProfileViewModel(LinkTerrainProviding { points ->
                if (points.size > 2) delay(1000)
                profile(points, 150.0)
            }, SavedStateHandle(mapOf("Latitude A" to "40", "Longitude A" to "-90", "Latitude B" to "41", "Longitude B" to "-90")))
            model.analyze()
            advanceUntilIdle()
            assertNotNull(model.state.value.result)
            assertFalse(model.state.value.loading)
            assertEquals(150.0, model.state.value.elevations.getValue("A").meters!!, 0.0)
            assertEquals(150.0, model.state.value.elevations.getValue("B").meters!!, 0.0)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun cancelledAnalysisFailureCannotOverwriteEditedState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val model = LinkProfileViewModel(LinkTerrainProviding { points ->
                if (points.size > 2) withContext(NonCancellable) {
                    delay(1000)
                    throw java.io.IOException("Late failure")
                }
                profile(points, 150.0)
            }, SavedStateHandle(mapOf("Latitude A" to "40", "Longitude A" to "-90", "Latitude B" to "41", "Longitude B" to "-90")))
            model.analyze()
            runCurrent()
            model.edit("Height A (m)", "20")
            advanceUntilIdle()
            assertNull(model.state.value.error)
            assertNull(model.state.value.result)
            assertFalse(model.state.value.loading)
            assertEquals("20", model.state.value.fields["Height A (m)"])
        } finally { Dispatchers.resetMain() }
    }

}
