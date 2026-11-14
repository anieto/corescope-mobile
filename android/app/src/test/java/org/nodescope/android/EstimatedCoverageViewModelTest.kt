package org.nodescope.android

import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.feature.coverage.*

/** Mirrors the model half of iOS `CoverageRadioPresetsTests`. */
@OptIn(ExperimentalCoroutinesApi::class)
class EstimatedCoverageViewModelTest {
    private fun model(imperial: Boolean = false, test: (EstimatedCoverageViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val root = Files.createTempDirectory("coverage").toFile()
        try { test(EstimatedCoverageViewModel(File(root, "terrain"), CoverageMemoryPreferences(), imperial)) }
        finally { root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun metricDefaultsProduceValidStudyInputs() = model { model ->
        assertEquals(CoverageSetupProblem.NoTransmitter, model.setupProblem)
        model.place(Coordinate(50.5, -115.5), null)
        val inputs = requireNotNull(model.inputs())
        assertEquals(CoverageRadioPreset.standard.frequencyMHz, inputs.frequencyMHz, 0.0)
        assertEquals(22.0, inputs.budget.transmitPowerDbm, 0.0)
        assertEquals(-127.5, inputs.budget.sensitivityDbm, 0.0)
        assertEquals(10.0, inputs.budget.reserveDb, 0.0)
        assertEquals(6.0, inputs.transmitHeightMeters, 0.0)
        assertEquals(1.5, inputs.receiveHeightMeters, 0.0)
        assertEquals(20_000, inputs.grid.radiusMeters)
        assertEquals(1000, inputs.grid.spacingMeters)
    }

    @Test fun imperialDefaultUsesTenMilesAndWholeFeet() = model(imperial = true) { model ->
        model.place(Coordinate(50.5, -115.5), null)
        val inputs = requireNotNull(model.inputs())
        assertEquals(16_093, inputs.grid.radiusMeters)
        assertEquals(1609, inputs.grid.spacingMeters)
        assertEquals(model.grid?.columns, inputs.grid.columns)
        assertEquals(6.096, inputs.transmitHeightMeters, 1e-9)
        assertEquals(1.524, inputs.receiveHeightMeters, 1e-9)
    }

    @Test fun editingAPresetValueMakesTheRadioCustomAndUndoRestoresPlacement() = model { model ->
        assertEquals(CoverageRadioPreset.standard, model.radioPreset)
        model.set("power", "30")
        assertNull(model.radioPreset)
        model.applyRadioPreset(CoverageRadioPreset.all[1])
        assertEquals(869.618, model.number("frequency"))
        assertEquals(14.0, model.number("power"))
        assertEquals(-130.0, model.number("sensitivity"))

        model.place(Coordinate(1.0, 2.0), "First")
        model.place(Coordinate(3.0, 4.0), null)
        assertNull(model.transmitterName)
        model.undoLastChange()
        assertEquals(Coordinate(1.0, 2.0), model.coordinate)
        assertEquals("First", model.transmitterName)
        model.clearTransmitter()
        assertNull(model.coordinate)
        assertEquals(CoverageSetupProblem.NoTransmitter, model.setupProblem)
        model.undoLastChange()
        assertEquals(1.0, model.coordinate?.latitude)
    }

    @Test fun knownNodesAreAvailableBeforePlacementAndRemainBounded() = model { model ->
        val nodes = (0 until 205).map { MeshNode("$it", "Node $it", "repeater", 10.0, it / 10.0, "") }
        model.offerCatalog(nodes + nodes[0], "test")
        assertNull(model.coordinate)
        assertEquals(200, model.knownNodes.size)
        assertEquals(200, model.knownNodes.map { it.publicKey }.toSet().size)
        assertTrue(model.nodeStatuses.isEmpty())
        model.place(Coordinate(10.0, 20.4), null)
        assertEquals("204", model.knownNodes.first().publicKey)
        model.clearTransmitter()
        assertEquals(200, model.knownNodes.size)
        // A later refresh of the same source keeps the catalog a result was calculated against.
        model.offerCatalog(nodes.take(3), "test")
        assertEquals(200, model.knownNodes.size)
        model.offerCatalog(nodes.take(3), "other")
        assertEquals(3, model.knownNodes.size)
    }

    @Test fun unsupportedPlacementPreservesTransmitterAndUndo() = model { model ->
        model.place(Coordinate(10.0, 20.0), "Original")
        val undo = model.undo?.id
        for (point in listOf(Coordinate(86.0, 20.0), Coordinate(-86.0, 20.0))) {
            model.place(point, null)
            assertEquals(Coordinate(10.0, 20.0), model.coordinate)
            assertEquals("Original", model.transmitterName)
            assertEquals(undo, model.undo?.id)
            assertNotNull(model.inputError)
        }
        model.place(Coordinate(85.0, 20.0), null)
        assertEquals(85.0, model.coordinate?.latitude)
        assertNull(model.inputError)
    }

    @Test fun outOfRangeValuesAreReportedPerField() = model { model ->
        model.place(Coordinate(10.0, 10.0), null)
        model.set("txHeight", "0.5")
        assertEquals(CoverageSetupIssue.height, model.issue("txHeight"))
        assertEquals(CoverageSetupProblem.Field("txHeight", CoverageSetupIssue.height), model.setupProblem)
        model.set("txHeight", "6")
        model.set("rxLoss", "-1")
        assertEquals(CoverageSetupProblem.Field("rxLoss", CoverageSetupIssue.nonnegative), model.setupProblem)
    }

    @Test fun acceptingTheAssumptionsIsRemembered() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val root = Files.createTempDirectory("coverage").toFile()
        try {
            val preferences = CoverageMemoryPreferences()
            EstimatedCoverageViewModel(File(root, "terrain"), preferences, false).updateAcceptsScenario(true)
            assertTrue(EstimatedCoverageViewModel(File(root, "terrain"), preferences, false).acceptsScenario)
        } finally { root.deleteRecursively(); Dispatchers.resetMain() }
    }
}

/** An in-memory SharedPreferences for JVM tests. */
private class CoverageMemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) values.clear()
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}
