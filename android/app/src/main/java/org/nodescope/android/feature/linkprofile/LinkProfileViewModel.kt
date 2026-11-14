package org.nodescope.android.feature.linkprofile

import androidx.lifecycle.SavedStateHandle
import org.nodescope.android.core.storage.DistanceUnit
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

/** [distanceUnit] picks the default antenna height: 20 ft in imperial, 6 m in metric. */
class LinkProfileViewModel(private val terrain: LinkTerrainProviding, private val saved: SavedStateHandle,
    distanceUnit: DistanceUnit = DistanceUnit.METRIC) : ViewModel() {
    companion object {
        val defaults = linkedMapOf(
            "Latitude A" to "", "Longitude A" to "", "Height A (m)" to "10",
            "Latitude B" to "", "Longitude B" to "", "Height B (m)" to "10",
            "Frequency (MHz)" to "910.525",
            "Transmit power (dBm)" to "22", "Gain A (dBi)" to "2.15", "Gain B (dBi)" to "2.15",
            "Loss A (dB)" to "1", "Loss B (dB)" to "1", "Sensitivity (dBm)" to "-128",
        )
        /** The values a preset sets; editing one makes the radio custom. Gains and losses belong to each point. */
        val presetKeys = listOf("Frequency (MHz)", "Transmit power (dBm)", "Sensitivity (dBm)")
    }
    data class EndpointElevation(val meters: Double? = null, val loading: Boolean = false)
    data class State(val fields: Map<String, String>, val preset: String = "US/Canada Narrow", val loading: Boolean = false,
        val result: LinkAnalysis? = null, val error: String? = null,
        val elevations: Map<String, EndpointElevation> = emptyMap(),
        /** The known node each endpoint was placed on, by endpoint; absent for a dropped pin or typed coordinates. */
        val names: Map<String, String> = emptyMap(),
        /** The last placement, removal or reset, which Undo takes back for a few seconds. */
        val undo: Undo? = null,
        /** Reopened from Saved Plans: nothing is fetched or analyzed until Calculate, as on iOS. */
        val awaitingCalculation: Boolean = false)
    /** Both endpoints' coordinate fields and node names, as they were before a change. */
    data class Placement(val fields: Map<String, String>, val names: Map<String, String>)
    data class Undo(val message: String, val before: Placement, val id: Long = System.nanoTime())
    private val defaultHeight = if (distanceUnit == DistanceUnit.IMPERIAL) "6.096" else "6"
    private val mutable = MutableStateFlow(State((defaults + mapOf("Height A (m)" to defaultHeight, "Height B (m)" to defaultHeight))
        .mapValues { (key, value) -> saved.get<String>(key) ?: value }, saved["preset"] ?: "US/Canada Narrow",
        names = listOf("A", "B").mapNotNull { endpoint -> saved.get<String>("Name $endpoint")?.let { endpoint to it } }.toMap()))
    val state = mutable.asStateFlow()
    /** The saved study this profile came from, so Save replaces it rather than adding another. */
    var savedStudy: org.nodescope.android.feature.candidatesites.SavedStudy? = null
    private var job: Job? = null
    private val elevationJobs = mutableMapOf<String, Job>()

    init {
        if (saved.get<Boolean>("awaitingCalculation") == true) mutable.value = mutable.value.copy(awaitingCalculation = true)
        scheduleElevation("A")
        scheduleElevation("B")
        scheduleAnalysis()
    }

    /** Both endpoints parse: analysis runs on its own, shortly after the last change. */
    private fun scheduleAnalysis() {
        job?.cancel()
        if (mutable.value.awaitingCalculation || coordinate("A") == null || coordinate("B") == null) return
        mutable.value = mutable.value.copy(loading = true)
        job = viewModelScope.launch {
            delay(600)
            runAnalysis()
        }
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
        // A reopened study sends no coordinates for elevation until Calculate.
        val point = coordinate(endpoint)?.takeUnless { mutable.value.awaitingCalculation }
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

    /** A typed coordinate replaces whatever Undo would have restored. */
    fun edit(key: String, value: String) {
        if (key.startsWith("Latitude ") || key.startsWith("Longitude ")) mutable.value = mutable.value.copy(undo = null)
        update(key, value)
    }

    private fun update(key: String, value: String) {
        job?.cancel()
        saved[key] = value
        // Typed coordinates are no longer the known node they were placed on.
        val endpoint = key.removePrefix("Latitude ").removePrefix("Longitude ").takeIf { it != key }
        if (endpoint != null && endpoint in mutable.value.names) {
            saved.remove<String>("Name $endpoint")
            mutable.value = mutable.value.copy(names = mutable.value.names - endpoint)
        }
        val preset = if (key in presetKeys) "Custom" else mutable.value.preset
        saved["preset"] = preset
        mutable.value = mutable.value.copy(fields = mutable.value.fields + (key to value), preset = preset, loading = false, result = null, error = null)
        if (key == "Latitude A" || key == "Longitude A") scheduleElevation("A")
        if (key == "Latitude B" || key == "Longitude B") scheduleElevation("B")
        scheduleAnalysis()
    }

    /** Places [endpoint] at [point], named after the known node it was chosen on, if any. */
    fun place(endpoint: String, point: ProfileCoordinate, name: String?) {
        val before = placement()
        val moved = coordinate(endpoint) != null
        setPoint(endpoint, point.latitude.toString(), point.longitude.toString(), name)
        offerUndo(if (moved) "Point $endpoint moved" else "Point $endpoint placed", before)
    }
    fun remove(endpoint: String) {
        val before = placement()
        setPoint(endpoint, "", "", null)
        offerUndo("Point $endpoint removed", before)
    }
    /** Start over: both endpoints and the result are cleared; heights and radio settings are kept. */
    fun startOver() {
        val before = placement()
        listOf("A", "B").forEach { endpoint -> setPoint(endpoint, "", "", null) }
        offerUndo("Points cleared", before)
    }
    /** Puts both endpoints back as they were before the last placement, removal or reset. */
    fun undo() {
        val undo = mutable.value.undo ?: return
        listOf("A", "B").forEach { endpoint ->
            setPoint(endpoint, undo.before.fields["Latitude $endpoint"].orEmpty(), undo.before.fields["Longitude $endpoint"].orEmpty(),
                undo.before.names[endpoint])
        }
        mutable.value = mutable.value.copy(undo = null)
    }
    /** Undo is offered for a few seconds; a newer offer outlives an older one's timer. */
    fun dismissUndo(id: Long) {
        if (mutable.value.undo?.id == id) mutable.value = mutable.value.copy(undo = null)
    }
    private fun placement() = mutable.value.let { state ->
        Placement(state.fields.filterKeys { it.startsWith("Latitude ") || it.startsWith("Longitude ") }, state.names)
    }
    private fun offerUndo(message: String, before: Placement) {
        mutable.value = mutable.value.copy(undo = Undo(message, before))
    }
    private fun setPoint(endpoint: String, latitude: String, longitude: String, name: String?) {
        update("Latitude $endpoint", latitude)
        update("Longitude $endpoint", longitude)
        if (name != null) {
            saved["Name $endpoint"] = name
            mutable.value = mutable.value.copy(names = mutable.value.names + (endpoint to name))
        }
    }
    fun preset(name: String) {
        job?.cancel()
        val eu = name == "EU/UK Narrow"
        val fields = mutable.value.fields + if (name == "Custom") emptyMap() else mapOf(
            "Frequency (MHz)" to if (eu) "869.618" else "910.525",
            "Transmit power (dBm)" to if (eu) "14" else "22", "Sensitivity (dBm)" to if (eu) "-130" else "-128")
        fields.forEach { (key, value) -> saved[key] = value }
        saved["preset"] = name
        mutable.value = mutable.value.copy(fields = fields, preset = name, loading = false, result = null, error = null)
        scheduleAnalysis()
    }
    /** Analyzes now, as Try Again after a failure or Calculate on a reopened study. */
    fun analyze() {
        if (mutable.value.awaitingCalculation) {
            saved["awaitingCalculation"] = false
            mutable.value = mutable.value.copy(awaitingCalculation = false)
        }
        job?.cancel()
        job = viewModelScope.launch { runAnalysis() }
    }
    private suspend fun runAnalysis() {
        val input = mutable.value
        mutable.value = mutable.value.copy(loading = true, result = null, error = null)
        try {
            fun number(key: String) = input.fields.getValue(key).trim().toDoubleOrNull()?.takeIf(Double::isFinite)
                ?: throw IllegalArgumentException("Enter a valid number for $key.")
            val a = ProfileCoordinate(number("Latitude A"), number("Longitude A"))
            val b = ProfileCoordinate(number("Latitude B"), number("Longitude B"))
            val distance = LinkGeometry.distance(a, b)
            require(distance > 0.001) { "Choose two different locations." }
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

    /** The current inputs as a study to save; null until both points are placed. Matches iOS. */
    fun savedSnapshot(): org.nodescope.android.feature.candidatesites.SavedStudy? {
        val fields = mutable.value.fields
        fun number(key: String) = fields.getValue(key).trim().toDoubleOrNull()?.takeIf(Double::isFinite)
        val a = coordinate("A") ?: return null
        val b = coordinate("B") ?: return null
        fun endpoint(letter: String, point: ProfileCoordinate): org.nodescope.android.feature.candidatesites.SavedEndpoint? {
            val antenna = org.nodescope.android.feature.candidatesites.SavedAntenna(
                number("Height $letter (m)") ?: return null, number("Gain $letter (dBi)") ?: return null, number("Loss $letter (dB)") ?: return null)
            return org.nodescope.android.feature.candidatesites.SavedEndpoint(mutable.value.names[letter], point.latitude, point.longitude, antenna)
        }
        val inputs = org.nodescope.android.feature.candidatesites.SavedLinkProfile(endpoint("A", a) ?: return null, endpoint("B", b) ?: return null,
            number("Frequency (MHz)") ?: return null, number("Transmit power (dBm)") ?: return null,
            number("Sensitivity (dBm)") ?: return null, refractionK = 4.0 / 3.0, sampleCount = null)
        val now = System.currentTimeMillis()
        val study = org.nodescope.android.feature.candidatesites.SavedStudy(
            savedStudy?.id ?: org.nodescope.android.feature.candidatesites.SavedPlansLibrary.newStudyID(),
            savedStudy?.name ?: "Link Profile", savedStudy?.createdAtMilliseconds ?: now, maxOf(now, savedStudy?.createdAtMilliseconds ?: now),
            org.nodescope.android.feature.candidatesites.SavedStudy.Kind.LINK_PROFILE, linkProfile = inputs)
        return study.takeIf { runCatching { it.validate() }.isSuccess }
    }

    /**
     * Fills in a saved study's inputs without fetching elevation or analyzing, until Calculate.
     * Applied once per back-stack entry, so a process restart keeps edits made after reopening.
     */
    fun restore(study: org.nodescope.android.feature.candidatesites.SavedStudy) {
        savedStudy = study
        if (saved.get<String>("restoredStudy") == study.id) return
        val inputs = study.linkProfile?.takeIf { runCatching { study.validate() }.isSuccess } ?: return
        job?.cancel()
        elevationJobs.values.forEach { it.cancel() }
        elevationJobs.clear()
        fun text(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        val values = mapOf(
            "Latitude A" to inputs.pointA.latitude.toString(), "Longitude A" to inputs.pointA.longitude.toString(),
            "Height A (m)" to text(inputs.pointA.antenna.heightMeters), "Gain A (dBi)" to text(inputs.pointA.antenna.gainDbi),
            "Loss A (dB)" to text(inputs.pointA.antenna.lossDb),
            "Latitude B" to inputs.pointB.latitude.toString(), "Longitude B" to inputs.pointB.longitude.toString(),
            "Height B (m)" to text(inputs.pointB.antenna.heightMeters), "Gain B (dBi)" to text(inputs.pointB.antenna.gainDbi),
            "Loss B (dB)" to text(inputs.pointB.antenna.lossDb),
            "Frequency (MHz)" to text(inputs.frequencyMHz), "Transmit power (dBm)" to text(inputs.transmitPowerDbm),
            "Sensitivity (dBm)" to text(inputs.sensitivityDbm))
        val names = listOfNotNull(inputs.pointA.name?.let { "A" to it }, inputs.pointB.name?.let { "B" to it }).toMap()
        val preset = when (Triple(inputs.frequencyMHz, inputs.transmitPowerDbm, inputs.sensitivityDbm)) {
            Triple(910.525, 22.0, -128.0) -> "US/Canada Narrow"
            Triple(869.618, 14.0, -130.0) -> "EU/UK Narrow"
            else -> "Custom"
        }
        values.forEach { (key, value) -> saved[key] = value }
        listOf("A", "B").forEach { letter -> names[letter]?.let { saved["Name $letter"] = it } ?: saved.remove<String>("Name $letter") }
        saved["preset"] = preset
        saved["awaitingCalculation"] = true
        saved["restoredStudy"] = study.id
        mutable.value = mutable.value.copy(fields = mutable.value.fields + values, names = names, preset = preset, loading = false,
            result = null, error = null, elevations = emptyMap(), undo = null, awaitingCalculation = true)
    }
}
