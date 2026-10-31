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
            // Only A's own lookup fails; the path analysis that restored endpoints start still runs.
            val model = LinkProfileViewModel(LinkTerrainProviding { points ->
                if (points.size == 2 && points.first().latitude == 40.0) throw java.io.IOException("Offline")
                profile(points, 80.0)
            }, SavedStateHandle(mapOf("Latitude A" to "40", "Longitude A" to "-90", "Latitude B" to "41", "Longitude B" to "-90")))
            advanceTimeBy(500)
            runCurrent()
            assertNull(model.state.value.elevations.getValue("A").meters)
            assertFalse(model.state.value.elevations.getValue("A").loading)
            assertEquals(80.0, model.state.value.elevations.getValue("B").meters!!, 0.0)
            assertNull(model.state.value.error)
            advanceUntilIdle()
            assertNotNull(model.state.value.result)
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
            var analyses = 0
            val model = LinkProfileViewModel(LinkTerrainProviding { points ->
                if (points.size > 2 && analyses++ == 0) withContext(NonCancellable) {
                    delay(1000)
                    throw java.io.IOException("Late failure")
                }
                profile(points, 150.0)
            }, SavedStateHandle(mapOf("Latitude A" to "40", "Longitude A" to "-90", "Latitude B" to "41", "Longitude B" to "-90")))
            model.analyze()
            runCurrent()
            model.edit("Height A (m)", "20")
            advanceUntilIdle()
            // The edit's own analysis replaces the cancelled one; its late failure never shows.
            assertNull(model.state.value.error)
            assertEquals(20.0, model.state.value.result!!.request.heightAMeters, 0.0)
            assertFalse(model.state.value.loading)
            assertEquals("20", model.state.value.fields["Height A (m)"])
        } finally { Dispatchers.resetMain() }
    }


    @Test fun analysisRunsOnItsOwnOnceBothPointsAreSetAndAfterEditsSettle() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var analyses = 0
            val model = LinkProfileViewModel(LinkTerrainProviding { points ->
                if (points.size > 2) analyses++
                profile(points, 100.0)
            }, SavedStateHandle())
            model.place("A", ProfileCoordinate(40.0, -90.0), "Hilltop")
            advanceUntilIdle()
            assertEquals(0, analyses)
            assertFalse(model.state.value.loading)
            model.place("B", ProfileCoordinate(40.1, -90.0), null)
            assertTrue(model.state.value.loading)
            advanceTimeBy(300)
            model.edit("Height A (m)", "20")
            advanceTimeBy(599)
            runCurrent()
            assertEquals(0, analyses)
            advanceUntilIdle()
            assertEquals(1, analyses)
            assertNotNull(model.state.value.result)
            assertEquals(mapOf("A" to "Hilltop"), model.state.value.names)
            // Typing over a node's coordinates makes it a plain point.
            model.edit("Latitude A", "40.01")
            assertTrue(model.state.value.names.isEmpty())
            model.startOver()
            advanceUntilIdle()
            assertNull(model.state.value.result)
            assertFalse(model.state.value.loading)
            assertEquals("20", model.state.value.fields["Height A (m)"])
        } finally { Dispatchers.resetMain() }
    }

    @Test fun undoRestoresPointsAfterPlacingMovingRemovingAndResetting() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val model = LinkProfileViewModel(LinkTerrainProviding { profile(it, 100.0) }, SavedStateHandle())
            fun point(endpoint: String) = model.state.value.fields.let { it["Latitude $endpoint"] to it["Longitude $endpoint"] }
            model.place("A", ProfileCoordinate(40.0, -90.0), "Hilltop")
            assertEquals("Point A placed", model.state.value.undo?.message)
            model.place("B", ProfileCoordinate(40.1, -90.0), null)
            model.place("A", ProfileCoordinate(40.2, -90.2), null)
            assertEquals("Point A moved", model.state.value.undo?.message)
            assertTrue("A" !in model.state.value.names)
            // Undo puts A back on its node, name and all.
            model.undo()
            assertNull(model.state.value.undo)
            assertEquals("40.0" to "-90.0", point("A"))
            assertEquals(mapOf("A" to "Hilltop"), model.state.value.names)
            model.remove("B")
            assertEquals("Point B removed", model.state.value.undo?.message)
            assertEquals("" to "", point("B"))
            model.undo()
            assertEquals("40.1" to "-90.0", point("B"))
            model.startOver()
            assertEquals("Points cleared", model.state.value.undo?.message)
            model.undo()
            assertEquals("40.0" to "-90.0", point("A"))
            assertEquals("40.1" to "-90.0", point("B"))
            // An offer times out only if it is still the latest; typing a coordinate withdraws it.
            model.place("B", ProfileCoordinate(40.3, -90.0), null)
            val offer = model.state.value.undo!!
            model.dismissUndo(offer.id + 1)
            assertNotNull(model.state.value.undo)
            model.edit("Latitude B", "40.31")
            assertNull(model.state.value.undo)
            advanceUntilIdle()
        } finally { Dispatchers.resetMain() }
    }
}
