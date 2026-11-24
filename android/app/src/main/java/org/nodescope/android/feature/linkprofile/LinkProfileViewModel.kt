package org.nodescope.android.feature.linkprofile

import org.nodescope.android.core.design.parseDecimalInput
import org.nodescope.android.R
import org.nodescope.android.core.design.UiText
import org.nodescope.android.core.model.antennaSource
import org.nodescope.android.core.model.RepeaterMetadata
import org.nodescope.android.core.model.ReportedAntennaSource
import org.nodescope.android.core.model.ReportedRepeaterSelection
import org.nodescope.android.core.network.BrowseRepository
import androidx.lifecycle.SavedStateHandle
import org.nodescope.android.core.storage.DistanceUnit
import org.nodescope.android.feature.coverage.CoverageRadioPreset
import org.nodescope.android.feature.coverage.PlannerRadioDefault
import org.nodescope.android.feature.coverage.editorText
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
    distanceUnit: DistanceUnit = DistanceUnit.defaultUnit(), private val browse: BrowseRepository? = null) : ViewModel(), org.nodescope.android.feature.candidatesites.StudyDraftSource {
    companion object {
        private const val US_PRESET = "US/Canada Narrow"
        private const val EU_PRESET = "EU/UK Narrow"

        internal val selectablePresets = listOf(
            US_PRESET to R.string.link_profile_preset_us,
            EU_PRESET to R.string.link_profile_preset_eu,
            "meshcore-br-narrow" to R.string.coverage_radio_preset_meshcore_br_narrow,
        )

        val defaults = linkedMapOf(
            "Latitude A" to "", "Longitude A" to "", "Height A (m)" to "10",
            "Latitude B" to "", "Longitude B" to "", "Height B (m)" to "10",
            "Gain A (dBi)" to "2.15", "Gain B (dBi)" to "2.15",
            "Loss A (dB)" to "1", "Loss B (dB)" to "1",
        ) + radioFields(CoverageRadioPreset.standard)

        private fun presetKey(preset: CoverageRadioPreset): String = when (preset.id) {
            "meshcore-us-ca" -> US_PRESET
            "meshcore-eu-uk-narrow" -> EU_PRESET
            else -> preset.id
        }

        private fun radioFields(preset: CoverageRadioPreset): Map<String, String> {
            // Preserve the two original Link Profile presets' sensitivity assumptions.
            val sensitivity = when (preset.id) {
                "meshcore-us-ca" -> -128.0
                "meshcore-eu-uk-narrow" -> -130.0
                else -> preset.sensitivityDbm
            }
            return mapOf("Frequency (MHz)" to preset.frequencyMHz.editorText(),
                "Transmit power (dBm)" to preset.transmitPowerDbm.editorText(),
                "Sensitivity (dBm)" to sensitivity.editorText())
        }
        /** The values a preset sets; editing one makes the radio custom. Gains and losses belong to each point. */
        val presetKeys = listOf("Frequency (MHz)", "Transmit power (dBm)", "Sensitivity (dBm)")
    }
    data class EndpointElevation(val meters: Double? = null, val loading: Boolean = false)
    data class State(val fields: Map<String, String>, val preset: String = "US/Canada Narrow", val loading: Boolean = false,
        val result: LinkAnalysis? = null, val error: UiText? = null,
        val elevations: Map<String, EndpointElevation> = emptyMap(),
        /** The known node each endpoint was placed on, by endpoint; absent for a dropped pin or typed coordinates. */
        val names: Map<String, String> = emptyMap(),
        val selections: Map<String, ReportedRepeaterSelection> = emptyMap(),
        val reportedGains: Map<String, ReportedAntennaSource> = emptyMap(),
        val reportedHeights: Map<String, ReportedAntennaSource> = emptyMap(),
        /** The last placement, removal or reset, which Undo takes back for a few seconds. */
        val undo: Undo? = null,
        /** Reopened from Saved Plans: nothing is fetched or analyzed until Calculate, as on iOS. */
        val awaitingCalculation: Boolean = false,
        /** Why a reopened study's saved result isn't shown; null when there's none or it's shown. */
        val savedResultNote: UiText? = null,
        val radioDefaultLabel: Int? = null)
    /** Both endpoints' coordinate fields and node names, as they were before a change. */
    data class Placement(val fields: Map<String, String>, val names: Map<String, String>, val selections: Map<String, ReportedRepeaterSelection>)
    data class Undo(val message: UiText, val before: Placement, val id: Long = System.nanoTime())
    private val defaultHeight = if (distanceUnit.usesFeet) "6.096" else "6"
    private var defaultRadio = CoverageRadioPreset.preset(saved.get<String>("defaultRadio")) ?: CoverageRadioPreset.standard
    private val mutable = MutableStateFlow(State((defaults + mapOf("Height A (m)" to defaultHeight, "Height B (m)" to defaultHeight))
        .mapValues { (key, value) -> saved.get<String>(key) ?: value }, saved["preset"] ?: "US/Canada Narrow",
        names = listOf("A", "B").mapNotNull { endpoint -> saved.get<String>("Name $endpoint")?.let { endpoint to it } }.toMap(),
        radioDefaultLabel = PlannerRadioDefault.Origin.entries.firstOrNull { it.name == saved.get<String>("radioDefaultLabel") }?.label))
    val state = mutable.asStateFlow()
    val usesDefaultEquipment: Boolean get() {
        val equipment = defaults.filterKeys { !it.startsWith("Latitude") && !it.startsWith("Longitude") } +
            mapOf("Height A (m)" to defaultHeight, "Height B (m)" to defaultHeight) + radioFields(defaultRadio)
        return equipment.all { (key, value) ->
            mutable.value.fields[key]?.parseDecimalInput()?.let { kotlin.math.abs(it - value.toDouble()) < 0.0001 } == true
        }
    }
    internal fun initializeRadio(resolution: PlannerRadioDefault) {
        if (saved.get<String>("defaultRadio") != null || savedStudy != null ||
            presetKeys.any { saved.get<String>(it) != null }) return
        defaultRadio = resolution.preset
        saved["defaultRadio"] = resolution.preset.id
        preset(presetKey(defaultRadio))
        saved["radioDefaultLabel"] = resolution.origin.name
        mutable.value = mutable.value.copy(radioDefaultLabel = resolution.origin.label)
    }

    private fun clearRadioDefaultLabel() {
        saved.remove<String>("radioDefaultLabel")
        mutable.value = mutable.value.copy(radioDefaultLabel = null)
    }

    /** The saved study this profile came from, so Save replaces it rather than adding another. */
    var savedStudy: org.nodescope.android.feature.candidatesites.SavedStudy? = null
    /** The analyzer and region this tool is working against, for a study's `origin`. */
    var analyzerOrigin: org.nodescope.android.feature.candidatesites.StudyOrigin? = null

    /** Where the endpoints came from: the saved study's source while neither has moved, otherwise the analyzer they were placed against. Matches iOS. */
    private fun origin(inputs: org.nodescope.android.feature.candidatesites.SavedLinkProfile): org.nodescope.android.feature.candidatesites.StudyOrigin? {
        val saved = savedStudy?.linkProfile
        val unmoved = saved != null && saved.pointA.latitude == inputs.pointA.latitude && saved.pointA.longitude == inputs.pointA.longitude &&
            saved.pointB.latitude == inputs.pointB.latitude && saved.pointB.longitude == inputs.pointB.longitude
        return if (unmoved) savedStudy?.origin else analyzerOrigin
    }
    /** The plan this tool was opened from: the suggested place to save a new study. */
    var suggestedPlanID: String? = null

    // MARK: Recovery drafts (deliverable C)

    override val draftKind get() = org.nodescope.android.feature.candidatesites.SavedStudy.Kind.LINK_PROFILE
    /** The editor as last opened or saved; differences from it are unsaved work. */
    private var cleanEditor: Map<String, String>? = null
    private var encodedResult: Pair<LinkAnalysis, ByteArray?>? = null

    /** Everything the editor holds, as text: fields (including incomplete numbers), endpoint names and preset. */
    fun draftEditor(): Map<String, String> = mutable.value.let { state ->
        state.fields + state.names.mapKeys { "Name ${it.key}" } + ("preset" to state.preset) +
            state.reportedGains.mapKeys { "Reported Gain ${it.key}" }.mapValues { it.value.draftValue() } +
            state.reportedHeights.mapKeys { "Reported Height ${it.key}" }.mapValues { it.value.draftValue() }
    }

    /** What's open now counts as saved: on opening, and after a successful Save. */
    fun markClean() { cleanEditor = draftEditor() }

    /**
     * Resumes a recovery draft: its fields (including incomplete text), names and preset, the saved study
     * it edits ([study], at the draft's revision) and, if it's for these inputs, its result. Nothing is
     * fetched or analyzed until Calculate. Applied once per back-stack entry, as [restore].
     */
    fun restoreDraft(draft: org.nodescope.android.feature.candidatesites.StudyDraft, study: org.nodescope.android.feature.candidatesites.SavedStudy?, result: ByteArray?) {
        clearRadioDefaultLabel()
        savedStudy = study
        suggestedPlanID = draft.planID
        if (saved.get<String>("restoredDraft") == draft.id) return
        job?.cancel()
        elevationJobs.values.forEach { it.cancel() }
        elevationJobs.clear()
        val fields = draft.editor.filterKeys { it in defaults.keys }
        val names = listOf("A", "B").mapNotNull { letter -> draft.editor["Name $letter"]?.takeIf(String::isNotEmpty)?.let { letter to it } }.toMap()
        val preset = draft.editor["preset"] ?: "Custom"
        fields.forEach { (key, value) -> saved[key] = value }
        listOf("A", "B").forEach { letter -> names[letter]?.let { saved["Name $letter"] = it } ?: saved.remove<String>("Name $letter") }
        saved["preset"] = preset
        saved["awaitingCalculation"] = true
        saved["restoredDraft"] = draft.id
        mutable.value = mutable.value.copy(fields = mutable.value.fields + fields, names = names, preset = preset, loading = false,
            selections = emptyMap(), reportedGains = listOf("A", "B").mapNotNull { endpoint ->
                ReportedAntennaSource.fromDraft(draft.editor["Reported Gain $endpoint"])?.let { endpoint to it }
            }.toMap(),
            reportedHeights = listOf("A", "B").mapNotNull { endpoint ->
                ReportedAntennaSource.fromDraft(draft.editor["Reported Height $endpoint"])?.let { endpoint to it }
            }.toMap(),
            result = null, error = null, elevations = emptyMap(), undo = null, awaitingCalculation = true, savedResultNote = null)
        protectAntennaInputs()
        val restored = result?.let { bytes ->
            savedSnapshot()?.linkProfile?.let { inputs -> runCatching { PortableLinkProfileResult.decode(bytes, inputs) }.getOrNull() }
        }
        val elevations = restored?.terrain?.points?.let {
            mapOf("A" to EndpointElevation(it.first().elevationMeters), "B" to EndpointElevation(it.last().elevationMeters))
        } ?: emptyMap()
        mutable.value = mutable.value.copy(result = restored, elevations = elevations)
    }

    override fun draftContent(): org.nodescope.android.feature.candidatesites.DraftContent? {
        val editor = draftEditor()
        if (editor == cleanEditor || (savedStudy == null && coordinate("A") == null && coordinate("B") == null)) return null
        val result = mutable.value.result?.let { analysis ->
            encodedResult?.takeIf { it.first === analysis }?.second ?: savedSnapshot()?.linkProfileResult
                ?.let(java.util.Base64.getDecoder()::decode).also { encodedResult = analysis to it }
        }
        val names = mutable.value.names
        val title = savedStudy?.name ?: listOfNotNull(names["A"], names["B"]).joinToString(" → ").ifEmpty { "Link Profile" }
        return org.nodescope.android.feature.candidatesites.DraftContent(title, editor, suggestedPlanID, savedStudy?.id, savedStudy?.revision, result = result)
    }
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
        val latitude = fields.getValue("Latitude $endpoint").parseDecimalInput() ?: return null
        val longitude = fields.getValue("Longitude $endpoint").parseDecimalInput() ?: return null
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
    fun coverageSeed(endpoint: String): org.nodescope.android.feature.coverage.LinkCoverageSeed? {
        val fields = state.value.fields
        fun number(key: String) = fields[key]?.parseDecimalInput()?.takeIf { it.isFinite() }
        val point = org.nodescope.android.feature.candidatesites.SavedEndpoint(
            state.value.names[endpoint] ?: "Point $endpoint",
            number("Latitude $endpoint") ?: return null, number("Longitude $endpoint") ?: return null,
            org.nodescope.android.feature.candidatesites.SavedAntenna(
                number("Height $endpoint (m)") ?: return null, number("Gain $endpoint (dBi)") ?: return null,
                number("Loss $endpoint (dB)") ?: return null, state.value.reportedGains[endpoint], state.value.reportedHeights[endpoint]))
        return org.nodescope.android.feature.coverage.LinkCoverageSeed(point,
            number("Frequency (MHz)") ?: return null, number("Transmit power (dBm)") ?: return null,
            number("Sensitivity (dBm)") ?: return null, repeaterSelection = state.value.selections[endpoint])
    }

    suspend fun repeaterMetadata(selection: ReportedRepeaterSelection) =
        browse?.repeaterMetadata(selection.analyzerHost)?.get(selection.publicKey.lowercase(java.util.Locale.ROOT))

    suspend fun applySelectedReportedDefaults(host: String?) {
        for ((endpoint, selection) in state.value.selections) {
            if (selection.analyzerHost != host) continue
            val metadata = repeaterMetadata(selection) ?: continue
            if (state.value.selections[endpoint] == selection) {
                applyReportedDefaults(endpoint, metadata, selection.analyzerHost)
            }
        }
    }

    private fun protectAntennaInputs() {
        for (endpoint in listOf("A", "B")) {
            saved["Edited Height $endpoint"] = endpoint !in state.value.reportedHeights
            saved["Edited Gain $endpoint"] = endpoint !in state.value.reportedGains
        }
    }

    internal fun applyReportedDefaults(endpoint: String, metadata: RepeaterMetadata, host: String) {
        val source = metadata.antennaSource(host)
        if (saved.get<Boolean>("Edited Height $endpoint") != true &&
            source.isFromDifferentRepeater(state.value.reportedHeights[endpoint])) {
            metadata.applicableHeightMeters()?.let { applyReportedHeight(endpoint, it, source) }
        }
        if (saved.get<Boolean>("Edited Gain $endpoint") != true &&
            source.isFromDifferentRepeater(state.value.reportedGains[endpoint])) {
            metadata.applicableGainDbi?.let { applyReportedGain(endpoint, it, source) }
        }
    }

    fun applyReportedHeight(endpoint: String, heightMeters: Double, source: ReportedAntennaSource) {
        if (heightMeters !in 0.0..300.0) return
        saved["Edited Height $endpoint"] = false
        update("Height $endpoint (m)", heightMeters.toString())
        mutable.value = mutable.value.copy(reportedHeights = mutable.value.reportedHeights + (endpoint to source))
    }

    fun applyReportedGain(endpoint: String, gain: Double, source: ReportedAntennaSource) {
        if (!gain.isFinite() || gain !in -10.0..30.0 || gain == 0.0) return
        saved["Edited Gain $endpoint"] = false
        update("Gain $endpoint (dBi)", gain.toString())
        mutable.value = mutable.value.copy(reportedGains = mutable.value.reportedGains + (endpoint to source))
    }

    fun edit(key: String, value: String) {
        val gainEndpoint = listOf("A", "B").firstOrNull { key == "Gain $it (dBi)" }
        if (gainEndpoint != null && value != mutable.value.fields[key]) {
            saved["Edited Gain $gainEndpoint"] = true
            mutable.value = mutable.value.copy(reportedGains = mutable.value.reportedGains - gainEndpoint)
        }
        val heightEndpoint = listOf("A", "B").firstOrNull { key == "Height $it (m)" }
        if (heightEndpoint != null && value != mutable.value.fields[key]) {
            saved["Edited Height $heightEndpoint"] = true
            mutable.value = mutable.value.copy(reportedHeights = mutable.value.reportedHeights - heightEndpoint)
        }
        if (key.startsWith("Latitude ") || key.startsWith("Longitude ")) mutable.value = mutable.value.copy(undo = null)
        update(key, value)
    }

    private fun update(key: String, value: String) {
        job?.cancel()
        saved[key] = value
        // Typed coordinates are no longer the known node they were placed on.
        val endpoint = key.removePrefix("Latitude ").removePrefix("Longitude ").takeIf { it != key }
        if (endpoint != null) {
            saved.remove<String>("Name $endpoint")
            mutable.value = mutable.value.copy(names = mutable.value.names - endpoint, selections = mutable.value.selections - endpoint)
        }
        if (key in presetKeys) clearRadioDefaultLabel()
        val preset = if (key in presetKeys) "Custom" else mutable.value.preset
        saved["preset"] = preset
        mutable.value = mutable.value.copy(fields = mutable.value.fields + (key to value), preset = preset, loading = false, result = null, error = null)
        if (key == "Latitude A" || key == "Longitude A") scheduleElevation("A")
        if (key == "Latitude B" || key == "Longitude B") scheduleElevation("B")
        scheduleAnalysis()
    }

    fun seedFromNodeVision(origin: ProfileCoordinate, target: ProfileCoordinate, name: String,
                            selection: ReportedRepeaterSelection, originName: String) {
        if (saved.get<Boolean>("nodeVisionSeedApplied") == true) return
        setPoint("A", origin.latitude.toString(), origin.longitude.toString(), originName)
        setPoint("B", target.latitude.toString(), target.longitude.toString(), name, selection)
        saved["nodeVisionSeedApplied"] = true
    }

    /** Places [endpoint] at [point], named after the known node it was chosen on, if any. */
    fun place(endpoint: String, point: ProfileCoordinate, name: String?, selection: ReportedRepeaterSelection? = null) {
        val before = placement()
        val moved = coordinate(endpoint) != null
        setPoint(endpoint, point.latitude.toString(), point.longitude.toString(), name, selection)
        offerUndo(if (moved) UiText.Resource(R.string.link_profile_point_moved, endpoint) else UiText.Resource(R.string.link_profile_point_placed, endpoint), before)
    }
    fun remove(endpoint: String) {
        val before = placement()
        setPoint(endpoint, "", "", null)
        offerUndo(UiText.Resource(R.string.link_profile_point_removed, endpoint), before)
    }
    /** Start over: both endpoints and the result are cleared; heights and radio settings are kept. */
    fun startOver() {
        val before = placement()
        listOf("A", "B").forEach { endpoint -> setPoint(endpoint, "", "", null) }
        offerUndo(UiText.Resource(R.string.link_profile_points_cleared), before)
    }
    /** Puts both endpoints back as they were before the last placement, removal or reset. */
    fun undo() {
        val undo = mutable.value.undo ?: return
        listOf("A", "B").forEach { endpoint ->
            setPoint(endpoint, undo.before.fields["Latitude $endpoint"].orEmpty(), undo.before.fields["Longitude $endpoint"].orEmpty(),
                undo.before.names[endpoint], undo.before.selections[endpoint])
        }
        mutable.value = mutable.value.copy(undo = null)
    }
    /** Undo is offered for a few seconds; a newer offer outlives an older one's timer. */
    fun dismissUndo(id: Long) {
        if (mutable.value.undo?.id == id) mutable.value = mutable.value.copy(undo = null)
    }
    private fun placement() = mutable.value.let { state ->
        Placement(state.fields.filterKeys { it.startsWith("Latitude ") || it.startsWith("Longitude ") }, state.names, state.selections)
    }
    private fun offerUndo(message: UiText, before: Placement) {
        mutable.value = mutable.value.copy(undo = Undo(message, before))
    }
    private fun setPoint(endpoint: String, latitude: String, longitude: String, name: String?, selection: ReportedRepeaterSelection? = null) {
        update("Latitude $endpoint", latitude)
        update("Longitude $endpoint", longitude)
        if (name != null) {
            saved["Name $endpoint"] = name
            mutable.value = mutable.value.copy(names = mutable.value.names + (endpoint to name))
        }
        if (selection != null) {
            mutable.value = mutable.value.copy(selections = mutable.value.selections + (endpoint to selection))
        }
    }
    fun preset(name: String) {
        job?.cancel()
        clearRadioDefaultLabel()
        val radio = CoverageRadioPreset.presets(CoverageRadioPreset.Family.MESHCORE).firstOrNull { presetKey(it) == name }
        val fields = mutable.value.fields + (radio?.let(::radioFields) ?: emptyMap())
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
            fun number(key: String) = input.fields.getValue(key).parseDecimalInput()?.takeIf(Double::isFinite)
                ?: throw InvalidProfileNumber(key)
            val a = ProfileCoordinate(number("Latitude A"), number("Longitude A"))
            val b = ProfileCoordinate(number("Latitude B"), number("Longitude B"))
            val distance = LinkGeometry.distance(a, b)
            if (!(distance > 0.001)) throw ProfileValidationError(R.string.link_profile_error_same_location, "Choose two different locations.")
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
            mutable.value = mutable.value.copy(loading = false, error = when (error) {
                is InvalidProfileNumber -> UiText.Resource(R.string.link_profile_invalid_number, profileFieldLabel(error.field))
                is ProfileValidationError -> UiText.Resource(error.resource)
                else -> UiText.Resource(R.string.link_profile_unable_to_load_terrain_check_your_connection_and)
            })
        }
    }

    /** The current inputs as a study to save; null until both points are placed. Matches iOS. */
    fun savedSnapshot(): org.nodescope.android.feature.candidatesites.SavedStudy? {
        val fields = mutable.value.fields
        fun number(key: String) = fields.getValue(key).parseDecimalInput()?.takeIf(Double::isFinite)
        val a = coordinate("A") ?: return null
        val b = coordinate("B") ?: return null
        fun endpoint(letter: String, point: ProfileCoordinate): org.nodescope.android.feature.candidatesites.SavedEndpoint? {
            val antenna = org.nodescope.android.feature.candidatesites.SavedAntenna(
                number("Height $letter (m)") ?: return null, number("Gain $letter (dBi)") ?: return null, number("Loss $letter (dB)") ?: return null, mutable.value.reportedGains[letter], mutable.value.reportedHeights[letter])
            return org.nodescope.android.feature.candidatesites.SavedEndpoint(mutable.value.names[letter], point.latitude, point.longitude, antenna)
        }
        val inputs = org.nodescope.android.feature.candidatesites.SavedLinkProfile(endpoint("A", a) ?: return null, endpoint("B", b) ?: return null,
            number("Frequency (MHz)") ?: return null, number("Transmit power (dBm)") ?: return null,
            number("Sensitivity (dBm)") ?: return null, refractionK = 4.0 / 3.0, sampleCount = null)
        val now = System.currentTimeMillis()
        val study = org.nodescope.android.feature.candidatesites.SavedStudy(
            savedStudy?.id ?: org.nodescope.android.feature.candidatesites.SavedPlansLibrary.newStudyID(),
            savedStudy?.name ?: "Link Profile", savedStudy?.createdAtMilliseconds ?: now, maxOf(now, savedStudy?.createdAtMilliseconds ?: now),
            org.nodescope.android.feature.candidatesites.SavedStudy.Kind.LINK_PROFILE, linkProfile = inputs, revision = savedStudy?.revision,
            origin = origin(inputs),
            // The calculated result, when it's for exactly these inputs.
            linkProfileResult = mutable.value.result?.let { PortableLinkProfileResult.encode(it, inputs) }
                ?.let(java.util.Base64.getEncoder()::encodeToString))
        return study.takeIf { runCatching { it.validate() }.isSuccess }
    }

    /**
     * Fills in a saved study's inputs without fetching elevation or analyzing, until Calculate.
     * Applied once per back-stack entry, so a process restart keeps edits made after reopening.
     */
    fun restore(study: org.nodescope.android.feature.candidatesites.SavedStudy) {
        clearRadioDefaultLabel()
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
        val preset = CoverageRadioPreset.presets(CoverageRadioPreset.Family.MESHCORE).firstOrNull { radio ->
            radioFields(radio).all { (key, value) -> values[key]?.toDoubleOrNull() == value.toDouble() }
        }?.let(::presetKey) ?: "Custom"
        values.forEach { (key, value) -> saved[key] = value }
        listOf("A", "B").forEach { letter -> names[letter]?.let { saved["Name $letter"] = it } ?: saved.remove<String>("Name $letter") }
        saved["preset"] = preset
        saved["awaitingCalculation"] = true
        saved["restoredStudy"] = study.id
        // A saved result opens as it was, without fetching terrain; edits clear it and wait for Calculate.
        val restored = study.linkProfileResult?.let { encoded ->
            runCatching { PortableLinkProfileResult.decode(java.util.Base64.getDecoder().decode(encoded), inputs) }.getOrNull()
        }
        val elevations = restored?.terrain?.points?.let {
            mapOf("A" to EndpointElevation(it.first().elevationMeters), "B" to EndpointElevation(it.last().elevationMeters))
        } ?: emptyMap()
        val note = if (restored != null) null else when (study.resultStatus) {
            org.nodescope.android.feature.candidatesites.SavedResultStatus.MISSING -> UiText.Resource(R.string.link_profile_saved_result_not_on_this_device)
            org.nodescope.android.feature.candidatesites.SavedResultStatus.UNVERIFIED,
            org.nodescope.android.feature.candidatesites.SavedResultStatus.AVAILABLE -> UiText.Resource(R.string.link_profile_saved_result_could_not_be_verified)
            org.nodescope.android.feature.candidatesites.SavedResultStatus.UNSUPPORTED -> UiText.Resource(R.string.link_profile_saved_result_needs_another_version_of_nodescope)
            org.nodescope.android.feature.candidatesites.SavedResultStatus.NONE -> null
        }
        mutable.value = mutable.value.copy(fields = mutable.value.fields + values, names = names, preset = preset, loading = false,
            selections = emptyMap(),
            reportedGains = listOfNotNull(inputs.pointA.antenna.reportedGainSource?.let { "A" to it },
                inputs.pointB.antenna.reportedGainSource?.let { "B" to it }).toMap(),
            reportedHeights = listOfNotNull(inputs.pointA.antenna.reportedHeightSource?.let { "A" to it },
                inputs.pointB.antenna.reportedHeightSource?.let { "B" to it }).toMap(),
            result = restored, error = null, elevations = elevations, undo = null, awaitingCalculation = true, savedResultNote = note)
        protectAntennaInputs()
    }
}

private class InvalidProfileNumber(val field: String) : IllegalArgumentException("Enter a valid number for $field.")

internal fun profileFieldLabel(key: String): UiText {
    return when (key) {
        "Frequency (MHz)" -> UiText.Resource(R.string.link_profile_field_frequency)
        "Transmit power (dBm)" -> UiText.Resource(R.string.link_profile_field_power)
        "Sensitivity (dBm)" -> UiText.Resource(R.string.link_profile_field_sensitivity)
        else -> {
            val field = key.substringBefore(' ')
            val endpoint = key.substringAfter(' ').substringBefore(' ')
            UiText.Resource(when (field) {
                "Gain" -> R.string.link_profile_field_gain
                "Loss" -> R.string.link_profile_field_loss
                "Latitude" -> R.string.link_profile_field_latitude
                "Longitude" -> R.string.link_profile_field_longitude
                "Height" -> R.string.link_profile_field_height
                else -> return UiText.Raw(key)
            }, endpoint)
        }
    }
}

internal class ProfileValidationError(val resource: Int, message: String) : IllegalArgumentException(message)
