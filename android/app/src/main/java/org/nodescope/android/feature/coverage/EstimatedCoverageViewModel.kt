package org.nodescope.android.feature.coverage

import org.nodescope.android.core.storage.DistanceUnit
import org.nodescope.android.R
import org.nodescope.android.core.design.UiText
import android.content.SharedPreferences
import org.nodescope.android.core.model.antennaSource
import org.nodescope.android.core.model.RepeaterMetadata
import org.nodescope.android.core.model.ReportedAntennaSource
import org.nodescope.android.core.model.ReportedRepeaterSelection
import org.nodescope.android.core.network.BrowseRepository
import androidx.compose.runtime.*
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.core.model.FieldCoordinate
import org.nodescope.android.core.model.FieldGeometry
import org.nodescope.android.core.model.MeshNode
import kotlin.coroutines.coroutineContext

/** Why Calculate is not available yet. Mirrors iOS `CoverageSetupProblem`. */
sealed interface CoverageSetupProblem {
    data object NoTransmitter : CoverageSetupProblem
    data class Field(val field: String, val issue: CoverageSetupIssue) : CoverageSetupProblem
    data object Radio : CoverageSetupProblem
    data object Grid : CoverageSetupProblem
    data object CheckingWork : CoverageSetupProblem
    data object TooMuchWork : CoverageSetupProblem
}

/**
 * Estimated Coverage's setup and the run it produces, the Android side of iOS's
 * `EstimatedCoverageModel`. Radio and antenna values stay as the editor's text so a half-typed
 * number keeps its own message; presets write into them, and editing a value a preset set makes the
 * radio custom. Held by the back stack entry, so rotation and folding keep the setup and the result.
 */
internal class EstimatedCoverageViewModel(
    private val cacheRoot: File,
    private val preferences: SharedPreferences,
    distanceUnit: DistanceUnit,
    /** Where the last calculation report is kept (app files, not the cache); null keeps no report. */
    private val diagnosticsFile: File? = null,
    private val browse: BrowseRepository? = null,
) : ViewModel(), org.nodescope.android.feature.candidatesites.StudyDraftSource {
    /** The last transmitter placement, offered back for a few seconds. */
    data class Undo(val id: Long, val message: UiText, val latitude: String, val longitude: String, val name: String?, val selection: ReportedRepeaterSelection?)

    // Transmitter location, as text so typed coordinates keep their own validation.
    var latitudeText by mutableStateOf(""); private set
    var longitudeText by mutableStateOf(""); private set
    /** The known node the transmitter was placed on, or "Current location"; null for a dropped pin. */
    var transmitterSelection by mutableStateOf<ReportedRepeaterSelection?>(null); private set
    private var editedTransmitHeight = false
    private var editedTransmitGain = false
    var reportedTransmitHeight by mutableStateOf<ReportedAntennaSource?>(null); private set
    var reportedTransmitGain by mutableStateOf<ReportedAntennaSource?>(null); private set
    var transmitterName by mutableStateOf<String?>(null); private set
    /** Choose New Point: the panel collapses and the header prompts for a touch & hold. */
    var isChoosingLocation by mutableStateOf(false)
    /** Bumps on every placement, so the phone panel can close and the map can recentre. */
    var placementID by mutableLongStateOf(0L); private set
    var transmitterExpanded by mutableStateOf(false)
    var undo by mutableStateOf<Undo?>(null); private set
    private var undoCounter = 0L

    val heightInFeet = distanceUnit.usesFeet

    /** Radio, antenna and reserve fields, keyed as [CoverageSetupValidation] expects. */
    var values by mutableStateOf(defaultValues(heightInFeet)); private set
    private var hasInitializedRadio = false
    internal var radioDefaultOrigin by mutableStateOf<PlannerRadioDefault.Origin?>(null); private set

    internal fun initializeRadio(resolution: PlannerRadioDefault) {
        if (hasInitializedRadio) return
        hasInitializedRadio = true
        applyRadioPreset(resolution.preset)
        radioDefaultOrigin = resolution.origin
    }
    var radiusMeters by mutableIntStateOf(if (distanceUnit.usesMiles) 16_093 else 20_000); private set
    var spacingMeters by mutableIntStateOf(if (distanceUnit.usesMiles) 1609 else 1000); private set
    var acceptsScenario by mutableStateOf(preferences.getBoolean(ASSUMPTIONS_KEY, false)); private set

    // Execution.
    val runner = CoverageStudyRunner()
    private var calculation: Job? = null
    private var calculating by mutableStateOf(false)
    val isCalculating: Boolean get() = calculating || runner.isBusy
    var startedAt by mutableStateOf<Long?>(null); private set
    /** The running calculation's map-sample count, or its known-node stage; null otherwise. */
    var calculationProgress by mutableStateOf<CoverageCalculationProgress?>(null); private set
    private var calculationToken = 0L
    var layer by mutableStateOf<CoverageMapLayer?>(null); private set
    var overlayError by mutableStateOf(false); private set
    var inputError by mutableStateOf<UiText?>(null); private set
    /** The grid size and spacing last checked against the work limit, and whether it fits. */
    private var workCheck by mutableStateOf<Triple<Int, Int, Boolean>?>(null)
    var minimumWorkSamples by mutableStateOf<Long?>(null); private set

    // Known nodes. The catalog is frozen per source so a live snapshot refresh cannot make a
    // finished result stale, as iOS loads its catalog once per source.
    private var catalogSource: String? = null
    private var locatedNodes by mutableStateOf<List<MeshNode>>(emptyList())
    var sourceID by mutableStateOf(""); private set
    /** The latest live catalog, kept while a saved one is shown so Refresh can switch to it. */
    private var liveNodes: List<MeshNode> = emptyList()
    private var liveSource by mutableStateOf<String?>(null)

    // Saved studies. A reopened study keeps the node locations it was saved with until refreshed.
    /** The saved study this setup came from, so Save replaces it rather than adding another. */
    var savedStudy by mutableStateOf<org.nodescope.android.feature.candidatesites.SavedStudy?>(null)
    /**
     * Where the node catalog came from, for a study's `origin`: the saved study's source while its captured
     * nodes are in use, otherwise the analyzer and region they were loaded from, and when. Matches iOS.
     */
    var catalogOrigin: org.nodescope.android.feature.candidatesites.StudyOrigin? = null
    private var liveRegion: String? = null
    /** The plan this tool was opened from, or the Link Profile's: the suggested place to save. */
    var suggestedPlanID: String? = null
    var usesSavedCatalog by mutableStateOf(false); private set
    private var savedCatalog: List<org.nodescope.android.feature.candidatesites.SavedCoverageNode> = emptyList()
    private var savedCatalogComplete = false
    /** The catalog the active result was calculated against, saved with it. */
    private var calculatedCatalog: List<org.nodescope.android.feature.candidatesites.SavedCoverageNode> = emptyList()
    /** A restored result, shown while its inputs and catalog are unchanged. */
    private var checkpoint by mutableStateOf<CoverageStudyResult?>(null)
    /** A smaller or differently budgeted result whose work a new calculation reuses, as iOS. */
    private var reusableResult by mutableStateOf<CoverageStudyResult?>(null)
    /** The last calculation report's text, for the report card. */
    var diagnosticReport by mutableStateOf<String?>(null); private set
    private val catalogComplete: Boolean get() = usesSavedCatalog && savedCatalogComplete

    init {
        // Any input change makes a result stale and cancels a running batch, as on iOS.
        viewModelScope.launch {
            // A restored result already matches its restored inputs, so it is left alone.
            snapshotFlow { inputSignature }.distinctUntilChanged().drop(1).collect { inputsChanged() }
        }
        viewModelScope.launch {
            snapshotFlow { radiusMeters to spacingMeters }.distinctUntilChanged().collectLatest { (radius, spacing) -> checkWork(radius, spacing) }
        }
        viewModelScope.launch {
            snapshotFlow { activeResult?.executionID }.distinctUntilChanged().collectLatest { renderOverlay() }
        }
        viewModelScope.launch { readReport() }
    }

    // MARK: Recovery drafts (deliverable C)

    override val draftKind get() = org.nodescope.android.feature.candidatesites.SavedStudy.Kind.COVERAGE
    /** The editor as last opened or saved; differences from it are unsaved work. */
    private var cleanEditor: Map<String, String>? = null
    private var encodedResult: Pair<CoverageStudyResult, ByteArray?>? = null

    /** Everything the setup holds, as text, including incomplete numbers. */
    fun draftEditor(): Map<String, String> = fields + mapOf("radiusMeters" to radiusMeters.toString(),
        "spacingMeters" to spacingMeters.toString(), "transmitterName" to (transmitterName ?: ""),
        "usesSavedCatalog" to usesSavedCatalog.toString(),
        "reportedTransmitGain" to reportedTransmitGain?.draftValue().orEmpty(),
        "reportedTransmitHeight" to reportedTransmitHeight?.draftValue().orEmpty())

    /** What's open now counts as saved: on opening, and after a successful Save. */
    fun markClean() { cleanEditor = draftEditor() }

    /**
     * Resumes a recovery draft: its setup (including incomplete text), its captured catalog, the saved
     * study it edits ([study], at the draft's revision) and, if it's for these inputs, its result.
     * Nothing is downloaded or calculated.
     */
    fun restoreDraft(draft: org.nodescope.android.feature.candidatesites.StudyDraft, study: org.nodescope.android.feature.candidatesites.SavedStudy?, result: ByteArray?) {
        hasInitializedRadio = true
        radioDefaultOrigin = null
        invalidate()
        savedStudy = study
        suggestedPlanID = draft.planID
        // The draft's captured nodes are the saved study's, or else came from the analyzer it was edited against.
        catalogOrigin = if (study != null && study.coverage?.nodes == draft.catalog) study.origin
            else draft.host?.let { org.nodescope.android.feature.candidatesites.StudyOrigin(it, draft.region, null) }
        val editor = draft.editor
        latitudeText = editor["latitude"].orEmpty()
        longitudeText = editor["longitude"].orEmpty()
        transmitterName = editor["transmitterName"]?.takeIf(String::isNotEmpty)
        transmitterSelection = null
        reportedTransmitGain = ReportedAntennaSource.fromDraft(editor["reportedTransmitGain"])
        reportedTransmitHeight = ReportedAntennaSource.fromDraft(editor["reportedTransmitHeight"])
        protectAntennaInputs()
        values = values + editor.filterKeys { it in values.keys }
        editor["radiusMeters"]?.toIntOrNull()?.let { radiusMeters = it }
        editor["spacingMeters"]?.toIntOrNull()?.let { spacingMeters = it }
        draft.catalog?.let { nodes ->
            calculatedCatalog = nodes
            if (editor["usesSavedCatalog"] == "true") {
                savedCatalog = nodes
                savedCatalogComplete = draft.catalogComplete
                usesSavedCatalog = true
                locatedNodes = nodes.map { MeshNode(it.nodeID, it.name, it.role, it.latitude, it.longitude,
                    it.lastSeenMilliseconds?.let { ms -> java.time.Instant.ofEpochMilli(ms).toString() } ?: "") }
                sourceID = nodes.firstOrNull()?.sourceID ?: "saved"
                catalogSource = sourceID
            }
        }
        checkpoint = result?.let { bytes ->
            inputs()?.let { inputs -> runCatching { PortableCoverageResult.decode(bytes, inputs, nodeSnapshots, catalogComplete) }.getOrNull() }
        }
    }

    override fun draftContent(): org.nodescope.android.feature.candidatesites.DraftContent? {
        val editor = draftEditor()
        if (editor == cleanEditor || (savedStudy == null && coordinate == null)) return null
        val result = activeResult
        val payload = result?.let { current ->
            encodedResult?.takeIf { it.first === current }?.second ?: runCatching { PortableCoverageResult.encode(current) }.getOrNull()
                .also { encodedResult = current to it }
        }
        // The captured catalog stays with the draft until an explicit refresh.
        val catalog = when {
            usesSavedCatalog -> savedCatalog
            result != null -> calculatedCatalog
            else -> null
        }
        val title = savedStudy?.name ?: transmitterName ?: coordinate?.let { "%.4f, %.4f".format(java.util.Locale.ROOT, it.latitude, it.longitude) } ?: "Estimated Coverage"
        return org.nodescope.android.feature.candidatesites.DraftContent(title, editor, suggestedPlanID, savedStudy?.id, savedStudy?.revision, catalog,
            savedCatalogComplete, payload)
    }

    // MARK: Derived state

    val coordinate: Coordinate? get() {
        val latitude = CoverageSetupValidation.number(latitudeText) ?: return null
        val longitude = CoverageSetupValidation.number(longitudeText) ?: return null
        return Coordinate.valid(latitude, longitude)?.takeIf { CoverageSetupValidation.supportsCoordinate(latitude, longitude) }
    }

    val fields: Map<String, String> get() = values + mapOf("latitude" to latitudeText, "longitude" to longitudeText)

    private val inputSignature: String get() =
        fields.toSortedMap().entries.joinToString("|") { "${it.key}=${it.value}" } + "|$radiusMeters|$spacingMeters|${nodeSnapshots.hashCode()}"

    fun issue(field: String): CoverageSetupIssue? = CoverageSetupValidation.issue(field, fields[field].orEmpty(), heightInFeet)

    fun number(field: String): Double? = CoverageSetupValidation.number(fields[field].orEmpty())

    fun inputs(): CoverageStudyInputs? =
        try { CoverageStudyInputs.fromEditor(fields, radiusMeters, spacingMeters, heightInFeet) } catch (_: Exception) { null }

    val grid: CoverageGrid? get() = try { CoverageGrid.studyArea(radiusMeters, spacingMeters) } catch (_: CoverageGridFailure) { null }

    val workFits: Boolean? get() = workCheck?.takeIf { it.first == radiusMeters && it.second == spacingMeters }?.third

    val setupProblem: CoverageSetupProblem? get() {
        if (coordinate == null) return CoverageSetupProblem.NoTransmitter
        try { CoverageStudyInputs.fromEditor(fields, radiusMeters, spacingMeters, heightInFeet) }
        catch (error: CoverageStudyInputFailure) {
            val field = error.field
            val issue = error.issue
            return if (field != null && issue != null) CoverageSetupProblem.Field(field, issue) else CoverageSetupProblem.Radio
        }
        catch (_: CoverageGridFailure) { return CoverageSetupProblem.Grid }
        catch (_: Exception) { return CoverageSetupProblem.Radio }
        return when (workFits) {
            null -> CoverageSetupProblem.CheckingWork
            false -> CoverageSetupProblem.TooMuchWork
            true -> null
        }
    }

    val canCalculate: Boolean get() = setupProblem == null && acceptsScenario && !isCalculating

    val radioPreset: CoverageRadioPreset? get() = CoverageRadioPreset.matching(number("frequency"), number("power"), number("sensitivity"))

    /** Before a transmitter is placed, a bounded catalog stays visible so it can be placed on a node. */
    val knownNodes: List<MeshNode> get() = coordinate?.let { nearestCoverageNodes(locatedNodes, it) } ?: locatedNodes.take(200)

    val nodeSnapshots: List<CoverageNodeSnapshot> get() = if (usesSavedCatalog) savedCatalog.map {
        CoverageNodeSnapshot(it.sourceID, it.nodeID, it.latitude, it.longitude)
    } else knownNodes.mapNotNull { node ->
        node.coordinate?.let { CoverageNodeSnapshot(sourceID, node.publicKey, it.latitude, it.longitude) }
    }

    /** The published result, while the inputs and catalog it was made from are unchanged. */
    val activeResult: CoverageStudyResult? get() {
        val available = if (runner.snapshot.phase == CoverageStudyPublicationPhase.completed) runner.snapshot.result else checkpoint
        val result = available ?: return null
        val current = inputs() ?: return null
        return result.takeIf { it.inputs.matches(current) && it.nodes.run.nodesToEvaluate == nodeSnapshots }
    }


    val nodeStatuses: Map<String, CoverageNodeStatus> get() {
        val result = activeResult ?: return emptyMap()
        return nodeSnapshots.associate { it.nodeID to result.nodes.run.status(it) }
    }

    // MARK: Editing

    var fromLinkProfile = false; private set

    fun applyLinkSeed(seed: LinkCoverageSeed) {
        hasInitializedRadio = true
        radioDefaultOrigin = null
        latitudeText = seed.point.latitude.toString()
        longitudeText = seed.point.longitude.toString()
        transmitterName = seed.point.name
        reportedTransmitGain = seed.point.antenna.reportedGainSource
        reportedTransmitHeight = seed.point.antenna.reportedHeightSource
        protectAntennaInputs()
        transmitterSelection = seed.repeaterSelection
        // Formatted as a reopened study is: "20 ft" and "22", never "20.0" or 32.808398950131235.
        values = values + mapOf("txHeight" to heightEditorText(seed.point.antenna.heightMeters, exact = reportedTransmitHeight != null),
            "txGain" to seed.point.antenna.gainDbi.editorText(), "txLoss" to seed.point.antenna.lossDb.editorText(),
            "frequency" to seed.frequencyMHz.editorText(), "power" to seed.transmitPowerDbm.editorText(),
            "sensitivity" to seed.sensitivityDbm.editorText())
        transmitterExpanded = true
        fromLinkProfile = true
    }

    /** A height in metres as the setup shows it: feet to two decimals when imperial, trailing zeros dropped. */
    private fun heightEditorText(meters: Double, exact: Boolean = false): String = when {
        !heightInFeet -> meters.editorText()
        exact -> {
            val feet = meters / 0.3048
            // A downward round-trip would reject a reported height exactly at the one-meter minimum.
            val editorFeet = if (feet * 0.3048 < meters) Math.nextUp(feet) else feet
            editorFeet.editorText()
        }
        else -> String.format(java.util.Locale.US, "%.2f", meters / 0.3048).trimEnd('0').trimEnd('.')
    }

    fun isCurrentRepeaterSource(selection: ReportedRepeaterSelection) =
        !usesSavedCatalog && selection.analyzerHost == liveSource

    suspend fun repeaterMetadata(selection: ReportedRepeaterSelection) =
        browse?.repeaterMetadata(selection.analyzerHost)?.get(selection.publicKey.lowercase(java.util.Locale.ROOT))

    internal fun applyReportedDefaults(metadata: RepeaterMetadata, host: String) {
        val source = metadata.antennaSource(host)
        if (!editedTransmitHeight && source.isFromDifferentRepeater(reportedTransmitHeight)) {
            metadata.applicableHeightMeters(minimum = 1.0)?.let { applyReportedHeight(it, source) }
        }
        if (!editedTransmitGain && source.isFromDifferentRepeater(reportedTransmitGain)) {
            metadata.applicableGainDbi?.let { applyReportedGain(it, source) }
        }
    }

    private fun protectAntennaInputs() {
        editedTransmitHeight = reportedTransmitHeight == null
        editedTransmitGain = reportedTransmitGain == null
    }

    fun applyReportedHeight(heightMeters: Double, source: ReportedAntennaSource) {
        if (heightMeters !in 1.0..300.0) return
        set("txHeight", heightEditorText(heightMeters, exact = true))
        reportedTransmitHeight = source
        editedTransmitHeight = false
    }

    fun applyReportedGain(gain: Double, source: ReportedAntennaSource) {
        if (!gain.isFinite() || gain !in -10.0..30.0 || gain == 0.0) return
        set("txGain", gain.editorText())
        reportedTransmitGain = source
        editedTransmitGain = false
    }

    fun set(field: String, text: String) {
        if (field == "txGain" && values[field] != text) {
            reportedTransmitGain = null
            editedTransmitGain = true
        }
        if (field == "txHeight" && values[field] != text) {
            reportedTransmitHeight = null
            editedTransmitHeight = true
        }
        if (field == "frequency" || field == "power" || field == "sensitivity") radioDefaultOrigin = null
        values = values + (field to text)
    }

    fun applyRadioPreset(preset: CoverageRadioPreset) {
        radioDefaultOrigin = null
        values = values + mapOf("frequency" to preset.frequencyMHz.editorText(), "power" to preset.transmitPowerDbm.editorText(),
            "sensitivity" to preset.sensitivityDbm.editorText())
    }

    fun setRadius(meters: Int) { radiusMeters = meters }
    fun setSpacing(meters: Int) { spacingMeters = meters }

    fun updateAcceptsScenario(accepts: Boolean) {
        acceptsScenario = accepts
        preferences.edit { putBoolean(ASSUMPTIONS_KEY, accepts) }
    }

    // MARK: Transmitter

    /** Places or moves the transmitter from the map or the location button, with Undo. */
    fun place(point: Coordinate, name: String?, publicKey: String? = null) {
        if (!CoverageSetupValidation.supportsCoordinate(point.latitude, point.longitude)) {
            inputError = UiText.Resource(R.string.coverage_choose_a_transmitter_between_85_s_and_85)
            return
        }
        inputError = null
        val moved = coordinate != null
        offerUndo(if (moved) UiText.Resource(R.string.coverage_transmitter_moved) else UiText.Resource(R.string.coverage_transmitter_placed))
        latitudeText = point.latitude.toString()
        longitudeText = point.longitude.toString()
        transmitterName = name
        transmitterSelection = publicKey?.takeIf { !usesSavedCatalog && sourceID == liveSource }
            ?.let { ReportedRepeaterSelection(sourceID, it) }
        transmitterExpanded = true
        isChoosingLocation = false
        placementID++
    }

    /** Clears the transmitter and its result, with Undo; the radio and antennas are kept. */
    fun clearTransmitter() {
        offerUndo(UiText.Resource(R.string.coverage_transmitter_removed))
        latitudeText = ""
        longitudeText = ""
        transmitterName = null
        transmitterSelection = null
        isChoosingLocation = false
    }

    fun undoLastChange() {
        val offer = undo ?: return
        latitudeText = offer.latitude
        longitudeText = offer.longitude
        transmitterName = offer.name
        transmitterSelection = offer.selection
        isChoosingLocation = false
        undo = null
    }

    fun dismissUndo(id: Long) { if (undo?.id == id) undo = null }

    /** Typed coordinates are no longer the known node the transmitter was placed on. */
    fun typeCoordinates(latitude: String, longitude: String) {
        latitudeText = latitude
        longitudeText = longitude
        transmitterName = null
        transmitterSelection = null
        undo = null
    }

    private fun offerUndo(message: UiText) {
        undo = Undo(++undoCounter, message, latitudeText, longitudeText, transmitterName, transmitterSelection)
    }

    // MARK: Catalog

    /** Takes the first located catalog for each source; later refreshes of the same source are ignored. */
    fun offerCatalog(nodes: List<MeshNode>, source: String, region: String? = liveRegion) {
        liveNodes = nodes
        liveSource = source
        liveRegion = region
        if (usesSavedCatalog) return
        if (source == catalogSource && locatedNodes.isNotEmpty()) return
        val located = nodes.filter { it.coordinate != null }.distinctBy { it.publicKey }
        if (located.isEmpty() && source == catalogSource) return
        catalogSource = source
        sourceID = source
        locatedNodes = located
        catalogOrigin = org.nodescope.android.feature.candidatesites.StudyOrigin(source, region, System.currentTimeMillis())
    }

    // MARK: Execution

    /** Whether Calculate will grow the previous, smaller study instead of starting over. */
    val canReuseSmallerStudy: Boolean get() {
        val source = reusableResult ?: return false
        val current = inputs() ?: return false
        return source.inputs.canExpand(current)
    }

    /**
     * Any input change makes a result stale, as iOS, but keeps what can be reused: a larger radius
     * grows the old study on Calculate, and a link-budget change re-scores the old losses at once.
     */
    private fun inputsChanged() {
        if (activeResult != null) return // A restored result already matches its restored inputs.
        val completed = runner.snapshot.result.takeIf { runner.snapshot.phase == CoverageStudyPublicationPhase.completed }
        val source = reusableResult ?: completed ?: checkpoint
        invalidate()
        val current = inputs() ?: return
        if (source == null || source.nodes.run.nodesToEvaluate != nodeSnapshots) return
        if (source.inputs.canExpand(current) && source.terrainSources != null &&
            source.model == CoverageStudyModel(CoverageRFKernel.sourceRevision, CoverageRFKernel.scenario)) {
            reusableResult = source
            return
        }
        if (!source.inputs.matchesPropagation(current)) return
        val diagnostics = CoverageRunDiagnostics(current, diagnosticsFile)
        val updated = runCatching { diagnostics.measure("budgetReuse") { source.updatingBudget(current) } }.getOrNull() ?: return
        diagnostics.finish(updated)
        reusableResult = source
        checkpoint = updated
        viewModelScope.launch { readReport() }
    }

    private suspend fun readReport() {
        val file = diagnosticsFile ?: return
        diagnosticReport = withContext(Dispatchers.IO) { CoverageRunDiagnostics.readLatest(file) }
    }

    fun invalidate() {
        reusableResult = null
        checkpoint = null
        runner.invalidate()
        calculation?.cancel()
        layer = null
        inputError = null
    }

    fun cancel() {
        runner.cancel()
        calculation?.cancel()
        if (activeResult == null) layer = null
    }

    /** Calculates; [continuing] finishes the shown result's unfinished samples and nodes with its terrain. */
    fun calculate(continuing: Boolean = false) {
        if (calculation != null || runner.isBusy) return
        val captured = inputs()
        if (captured == null) { inputError = UiText.Resource(R.string.coverage_review_the_highlighted_inputs_and_choose_a_smaller); return }
        val previous = if (continuing) activeResult else reusableResult?.takeIf { it.inputs.canExpand(captured) }
        if (continuing && previous == null) return
        // The result stays on the map while it's being continued.
        checkpoint = previous
        val snapshots = nodeSnapshots
        calculatedCatalog = currentSavedNodes
        val complete = catalogComplete
        inputError = null
        if (previous == null) layer = null
        startedAt = System.currentTimeMillis()
        calculating = true
        calculationProgress = null
        val token = ++calculationToken
        // Reports arrive on the worker; apply them on Main, and only for this calculation.
        val report: (CoverageCalculationProgress) -> Unit = { update ->
            viewModelScope.launch { if (token == calculationToken && calculating) calculationProgress = update }
        }
        calculation = viewModelScope.launch {
            try {
                runner.runWithProgress(captured) { id, fixed, progress ->
                    CoveragePreparedStudy.evaluate(fixed, snapshots, complete, 200,
                        CoverageStudyLimits(120_000_000, 120_000_000, 30_000_000, 30_000_000, 1000),
                        samplingHaloMeters = 100.0, cacheRoot = cacheRoot,
                        cacheQuota = 1_000_000_000, downloadLimit = 500_000_000, offline = false,
                        executionID = id, previous = previous, diagnosticsFile = diagnosticsFile,
                        progress = progress, calculationProgress = report)
                }
            } finally {
                if (runner.snapshot.phase == CoverageStudyPublicationPhase.completed) { checkpoint = null; reusableResult = null }
                viewModelScope.launch { readReport() }
                calculation = null
                calculating = false
                startedAt = null
                calculationProgress = null
            }
        }
    }

    /** Builds the overlay raster for the active result on a worker, stepping down zoom to fit the tile budget. */
    private suspend fun renderOverlay() {
        layer = null
        overlayError = false
        val result = activeResult ?: return
        val started = System.nanoTime()
        try {
            layer = withContext(Dispatchers.Default) {
                val raster = CoverageRaster(result.grid, result.transmitter, result.area)
                var zoom = when {
                    result.grid.radiusMeters <= 5000 -> 12; result.grid.radiusMeters <= 20_000 -> 11
                    result.grid.radiusMeters <= 50_000 -> 10; else -> 9
                }
                while (true) {
                    try { CoverageMapTilePlan.make(raster, zoom); break }
                    catch (error: CoverageMapLibreTilesFailure) {
                        if (error.state != CoverageMapLibreTilesState.tileLimit || zoom == 0) throw error
                        zoom--
                    }
                }
                CoverageMapLayer(result.executionID, raster, zoom)
            }
            diagnosticsFile?.let { file ->
                withContext(Dispatchers.IO) { CoverageRunDiagnostics.recordRasterPreparation(file, result.executionID, (System.nanoTime() - started) / 1e9) }
                readReport()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            overlayError = true
        }
    }

    /** Checks the study area against the initial work limit, off the main thread. */
    private suspend fun checkWork(radius: Int, spacing: Int) {
        workCheck = null
        minimumWorkSamples = null
        val grid = try { CoverageGrid.studyArea(radius, spacing) } catch (_: CoverageGridFailure) { return }
        val estimate = withContext(Dispatchers.Default) {
            val context = coroutineContext
            try {
                CoverageGridPreflight.estimate(grid, 120_000_000, 120_000_000, cancelled = { context.ensureActive(); false })
            } catch (_: CoverageGridPreflightFailure) { null }
        }
        workCheck = Triple(radius, spacing, estimate != null)
        minimumWorkSamples = estimate?.minimumSamples
    }

    // MARK: Saved studies

    /** Leaves the saved node locations for the current source's catalog. */
    fun refreshNodesFromSource() {
        usesSavedCatalog = false
        catalogSource = null
        liveSource?.let { offerCatalog(liveNodes, it) }
    }

    private val currentSavedNodes: List<org.nodescope.android.feature.candidatesites.SavedCoverageNode> get() =
        if (usesSavedCatalog) savedCatalog else knownNodes.mapNotNull { node ->
            node.coordinate?.let { point ->
                org.nodescope.android.feature.candidatesites.SavedCoverageNode(sourceID, node.publicKey, node.name, node.role,
                    point.latitude, point.longitude,
                    org.nodescope.android.core.model.parseInstant(node.lastSeen)?.toEpochMilli()?.takeIf { it in 0..253_402_300_799_999 })
            }
        }

    /** The current setup as a study to save, with the result and its catalog when there is one. Matches iOS. */
    fun savedSnapshot(): org.nodescope.android.feature.candidatesites.SavedStudy? {
        val inputs = inputs() ?: return null
        val point = coordinate ?: return null
        val result = activeResult
        fun antenna(prefix: String): org.nodescope.android.feature.candidatesites.SavedAntenna? {
            val gain = number("${prefix}Gain") ?: return null
            val loss = number("${prefix}Loss") ?: return null
            return org.nodescope.android.feature.candidatesites.SavedAntenna(
                if (prefix == "tx") inputs.transmitHeightMeters else inputs.receiveHeightMeters, gain, loss,
                if (prefix == "tx") reportedTransmitGain else null,
                if (prefix == "tx") reportedTransmitHeight else null)
        }
        val coverage = org.nodescope.android.feature.candidatesites.SavedCoverage(
            org.nodescope.android.feature.candidatesites.SavedEndpoint(transmitterName, point.latitude, point.longitude, antenna("tx") ?: return null),
            antenna("rx") ?: return null, inputs.frequencyMHz, inputs.budget.transmitPowerDbm, inputs.budget.sensitivityDbm,
            inputs.budget.reserveDb, radiusMeters, spacingMeters,
            if (result == null) currentSavedNodes else calculatedCatalog, catalogComplete,
            // Saved in the portable format both platforms read; older saves keep their legacy format.
            result?.let { runCatching { PortableCoverageResult.encode(it)?.let(java.util.Base64.getEncoder()::encodeToString) }.getOrNull() })
        val now = System.currentTimeMillis()
        val previous = savedStudy
        val study = org.nodescope.android.feature.candidatesites.SavedStudy(
            previous?.id ?: org.nodescope.android.feature.candidatesites.SavedPlansLibrary.newStudyID(),
            previous?.name ?: "Estimated Coverage", previous?.createdAtMilliseconds ?: now, maxOf(now, previous?.createdAtMilliseconds ?: now),
            org.nodescope.android.feature.candidatesites.SavedStudy.Kind.COVERAGE, coverage = coverage, revision = previous?.revision, origin = catalogOrigin)
        return study.takeIf { runCatching { it.validate() }.isSuccess }
    }

    /** Reopens a saved study: its inputs, its node locations and, when it checks out, its result. Nothing is downloaded or calculated. */
    fun restore(study: org.nodescope.android.feature.candidatesites.SavedStudy) {
        hasInitializedRadio = true
        radioDefaultOrigin = null
        val coverage = study.coverage?.takeIf { runCatching { study.validate() }.isSuccess } ?: return
        invalidate()
        savedStudy = study
        catalogOrigin = study.origin
        fun heightText(value: Double) = heightEditorText(value)
        latitudeText = coverage.transmitter.latitude.toString()
        longitudeText = coverage.transmitter.longitude.toString()
        transmitterName = coverage.transmitter.name
        transmitterSelection = null
        reportedTransmitGain = coverage.transmitter.antenna.reportedGainSource
        reportedTransmitHeight = coverage.transmitter.antenna.reportedHeightSource
        protectAntennaInputs()
        values = mapOf(
            "frequency" to coverage.frequencyMHz.editorText(), "power" to coverage.transmitPowerDbm.editorText(),
            "txHeight" to heightEditorText(coverage.transmitter.antenna.heightMeters, exact = reportedTransmitHeight != null), "txGain" to coverage.transmitter.antenna.gainDbi.editorText(),
            "txLoss" to coverage.transmitter.antenna.lossDb.editorText(),
            "rxHeight" to heightText(coverage.receiver.heightMeters), "rxGain" to coverage.receiver.gainDbi.editorText(),
            "rxLoss" to coverage.receiver.lossDb.editorText(),
            "sensitivity" to coverage.sensitivityDbm.editorText(), "reserve" to coverage.reserveDb.editorText())
        radiusMeters = coverage.radiusMeters
        spacingMeters = coverage.spacingMeters
        savedCatalog = coverage.nodes
        savedCatalogComplete = coverage.catalogComplete
        // An inputs-only study saved without nodes (such as a moved candidate site) uses the current source.
        usesSavedCatalog = !(coverage.nodes.isEmpty() && coverage.calculatedResult == null && study.resultRef == null)
        locatedNodes = coverage.nodes.map { MeshNode(it.nodeID, it.name, it.role, it.latitude, it.longitude, it.lastSeenMilliseconds?.let { ms ->
            java.time.Instant.ofEpochMilli(ms).toString() } ?: "") }
        sourceID = coverage.nodes.firstOrNull()?.sourceID ?: "saved"
        catalogSource = sourceID
        calculatedCatalog = coverage.nodes
        // A referenced result this device can't show: the inputs open alone, with the reason.
        inputError = when (study.resultStatus) {
            org.nodescope.android.feature.candidatesites.SavedResultStatus.MISSING ->
                UiText.Resource(R.string.coverage_saved_result_not_on_this_device_your_inputs)
            org.nodescope.android.feature.candidatesites.SavedResultStatus.UNVERIFIED ->
                UiText.Resource(R.string.coverage_the_saved_result_could_not_be_verified_so)
            org.nodescope.android.feature.candidatesites.SavedResultStatus.UNSUPPORTED ->
                UiText.Resource(R.string.coverage_this_saved_result_needs_another_version_of_nodescope)
            else -> null
        }
        coverage.calculatedResult?.let { encoded ->
            val inputs = inputs()
            checkpoint = try {
                val bytes = java.util.Base64.getDecoder().decode(encoded)
                if (PortableCoverageResult.isPortable(bytes)) PortableCoverageResult.decode(bytes, requireNotNull(inputs), nodeSnapshots, catalogComplete)
                else SavedCoverageCalculation.decode(encoded).restore(requireNotNull(inputs), nodeSnapshots, catalogComplete)
            } catch (_: Exception) {
                inputError = UiText.Resource(R.string.coverage_the_saved_calculation_could_not_be_restored_your)
                null
            }
        }
    }

    override fun onCleared() {
        runner.cancel()
        calculation?.cancel()
    }

    companion object {
        // Bump this key when the disclosed model assumptions change (iOS uses the same name).
        private const val ASSUMPTIONS_KEY = "coverage.acceptedModelAssumptions.v1"

        val metricSpacings = listOf(100, 200, 500, 1000, 2000, 5000, 10_000)
        val imperialSpacings = listOf(201, 402, 805, 1609, 2414, 3219, 8047)

        /** iOS's starting setup: the standard radio preset, a rooftop transmitter and a handheld receiver. */
        fun defaultValues(heightInFeet: Boolean): Map<String, String> {
            val radio = CoverageRadioPreset.standard
            return mapOf(
                "frequency" to radio.frequencyMHz.editorText(), "power" to radio.transmitPowerDbm.editorText(),
                // Whole-foot heights in imperial, as iOS: 20 ft up and 5 ft for the receiver.
                "txHeight" to if (heightInFeet) "20" else CoverageHeightPreset.ROOFTOP.meters.editorText(),
                "txGain" to "2.15", "txLoss" to "1",
                "rxHeight" to if (heightInFeet) "5" else CoverageHeightPreset.HANDHELD.meters.editorText(),
                "rxGain" to "2.15", "rxLoss" to "1",
                "sensitivity" to radio.sensitivityDbm.editorText(), "reserve" to "10")
        }
    }
}

/** A setup value as the editor shows it: no trailing ".0". */
internal fun Double.editorText(): String = if (this % 1.0 == 0.0 && kotlin.math.abs(this) < 1e15) toLong().toString() else toString()

/** The nearest 200 located nodes to [selected], ranked over the whole catalog before capping. */
internal fun nearestCoverageNodes(nodes: List<MeshNode>, selected: Coordinate?): List<MeshNode> {
    val origin = selected?.let { FieldCoordinate.create(it.latitude, it.longitude) } ?: return emptyList()
    // Rank before capping markers so API ordering cannot exclude a nearer node.
    return nodes.asSequence().distinctBy { it.publicKey }.mapNotNull { node ->
        val destination = node.coordinate?.let { FieldCoordinate.create(it.latitude, it.longitude) }
            ?: return@mapNotNull null
        node to FieldGeometry.geometry(origin, destination).distanceMeters
    }.sortedWith(compareBy<Pair<MeshNode, Double>> { it.second }.thenBy { it.first.publicKey })
        .take(200).map { it.first }.toList()
}

@kotlinx.serialization.Serializable
data class LinkCoverageSeed(val point: org.nodescope.android.feature.candidatesites.SavedEndpoint,
    val frequencyMHz: Double, val transmitPowerDbm: Double, val sensitivityDbm: Double,
    /** Where to suggest saving the new coverage study. */
    val planID: String? = null, val repeaterSelection: ReportedRepeaterSelection? = null)
