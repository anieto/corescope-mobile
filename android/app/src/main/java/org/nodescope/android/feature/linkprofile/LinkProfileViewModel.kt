package org.nodescope.android.feature.linkprofile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class LinkProfileViewModel(private val terrain: LinkTerrainProviding, private val saved: SavedStateHandle) : ViewModel() {
    companion object {
        val defaults = linkedMapOf(
            "Latitude A" to "", "Longitude A" to "", "Height A (m)" to "10",
            "Latitude B" to "", "Longitude B" to "", "Height B (m)" to "10",
            "Frequency (MHz)" to "910.525", "Bandwidth (kHz)" to "62.5", "Spreading factor" to "7", "Coding rate (4/x)" to "5",
            "Transmit power (dBm)" to "22", "Gain A (dBi)" to "2.15", "Gain B (dBi)" to "2.15",
            "Loss A (dB)" to "1", "Loss B (dB)" to "1", "Sensitivity (dBm)" to "-128",
        )
    }
    data class EndpointElevation(val meters: Double? = null, val loading: Boolean = false)
    data class State(val fields: Map<String, String>, val preset: String = "US/Canada Narrow", val loading: Boolean = false,
        val result: LinkAnalysis? = null, val error: String? = null,
        val elevations: Map<String, EndpointElevation> = emptyMap())
    private val mutable = MutableStateFlow(State(defaults.mapValues { (key, value) -> saved.get<String>(key) ?: value }, saved["preset"] ?: "US/Canada Narrow"))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val elevationJobs = mutableMapOf<String, Job>()

    init {
        scheduleElevation("A")
        scheduleElevation("B")
    }

    private fun coordinate(endpoint: String): ProfileCoordinate? {
        val fields = mutable.value.fields
        val latitude = fields.getValue("Latitude $endpoint").trim().toDoubleOrNull() ?: return null
        val longitude = fields.getValue("Longitude $endpoint").trim().toDoubleOrNull() ?: return null
        return runCatching { ProfileCoordinate(latitude, longitude) }.getOrNull()
    }

    private fun setElevation(endpoint: String, value: EndpointElevation) {
        mutable.value = mutable.value.copy(elevations = mutable.value.elevations + (endpoint to value))
    }

    private fun scheduleElevation(endpoint: String) {
        elevationJobs.remove(endpoint)?.cancel()
        val point = coordinate(endpoint)
        setElevation(endpoint, EndpointElevation(loading = point != null))
        if (point == null) return
        elevationJobs[endpoint] = viewModelScope.launch {
            delay(350)
            try {
                // A duplicate coordinate reuses the terrain provider's batching and cache.
                val meters = terrain.profile(listOf(point, point)).points.first().elevationMeters
                coroutineContext.ensureActive()
                if (coordinate(endpoint) == point) setElevation(endpoint, EndpointElevation(meters))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                coroutineContext.ensureActive()
                if (coordinate(endpoint) == point) setElevation(endpoint, EndpointElevation())
            }
        }
    }

    fun edit(key: String, value: String) {
        job?.cancel()
        saved[key] = value
        val preset = if (key in defaults.keys.drop(6)) "Custom" else mutable.value.preset
        saved["preset"] = preset
        mutable.value = mutable.value.copy(fields = mutable.value.fields + (key to value), preset = preset, loading = false, result = null, error = null)
        if (key == "Latitude A" || key == "Longitude A") scheduleElevation("A")
        if (key == "Latitude B" || key == "Longitude B") scheduleElevation("B")
    }
    fun preset(name: String) {
        job?.cancel()
        val eu = name == "EU/UK Narrow"
        val fields = mutable.value.fields + if (name == "Custom") emptyMap() else mapOf(
            "Frequency (MHz)" to if (eu) "869.618" else "910.525", "Bandwidth (kHz)" to "62.5",
            "Spreading factor" to if (eu) "8" else "7", "Coding rate (4/x)" to "5",
            "Transmit power (dBm)" to if (eu) "14" else "22", "Sensitivity (dBm)" to if (eu) "-130" else "-128")
        fields.forEach { (key, value) -> saved[key] = value }
        saved["preset"] = name
        mutable.value = mutable.value.copy(fields = fields, preset = name, loading = false, result = null, error = null)
    }
    fun analyze() {
        job?.cancel()
        val input = mutable.value
        job = viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true, result = null, error = null)
            try {
                fun number(key: String) = input.fields.getValue(key).trim().toDoubleOrNull()?.takeIf(Double::isFinite)
                    ?: throw IllegalArgumentException("Enter a valid number for $key.")
                val a = ProfileCoordinate(number("Latitude A"), number("Longitude A"))
                val b = ProfileCoordinate(number("Latitude B"), number("Longitude B"))
                val distance = LinkGeometry.distance(a, b)
                require(distance > 0.001) { "Choose two different locations." }
                require(number("Bandwidth (kHz)") > 0) { "Bandwidth must be positive." }
                require(number("Spreading factor") in 5.0..12.0 && number("Spreading factor") % 1 == 0.0) { "Spreading factor must be an integer from 5 to 12." }
                require(number("Coding rate (4/x)") in 5.0..8.0 && number("Coding rate (4/x)") % 1 == 0.0) { "Coding rate must be an integer from 5 to 8." }
                val request = PathRequest(listOf(ElevationPoint(0.0, 0.0), ElevationPoint(distance, 0.0)),
                    number("Height A (m)"), number("Height B (m)"), number("Frequency (MHz)"), linkBudget = LinkBudget(
                        number("Transmit power (dBm)"), number("Gain A (dBi)"), number("Gain B (dBi)"),
                        number("Loss A (dB)"), number("Loss B (dB)"), number("Sensitivity (dBm)")))
                // Reject invalid radio inputs before sending any coordinates.
                LinkProfileEngine.analyze(request)
                val profile = terrain.profile(LinkGeometry.samples(a, b, LinkGeometry.sampleCount(distance)))
                val sampled = request.copy(profile = profile.points)
                coroutineContext.ensureActive()
                elevationJobs.values.forEach { it.cancel() }
                setElevation("A", EndpointElevation(profile.points.first().elevationMeters))
                setElevation("B", EndpointElevation(profile.points.last().elevationMeters))
                mutable.value = mutable.value.copy(loading = false, result = LinkAnalysis(sampled, profile, LinkProfileEngine.analyze(sampled), LinkGeometry.bearing(a, b), System.currentTimeMillis()))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                coroutineContext.ensureActive()
                mutable.value = mutable.value.copy(loading = false, error = if (error is IllegalArgumentException) error.message else "Unable to load terrain. Check your connection and try again.")
            }
        }
    }
}
