package org.nodescope.android.feature.coverage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.nodescope.android.feature.candidatesites.PortableInputFingerprint
import org.nodescope.android.feature.candidatesites.SavedCoverage

/**
 * `nodescope.coverage-result` version 1 (docs/node-planner/saved-plans-v3-result-fields.md), identical
 * on iOS (PortableCoverageResult.swift) and checked against shared/fixtures/saved-plans/results.
 *
 * Reading is strict: unknown or missing fields and unknown values are refused at every level, and the
 * payload's fingerprint must match both its own inputs and the study's. It then goes through the same
 * checks as a restored legacy result (grid masks, margins, node run), so nothing is half-trusted.
 */
internal object PortableCoverageResult {
    const val FORMAT = "nodescope.coverage-result"
    private const val MAXIMUM_BYTES = 8 * 1024 * 1024
    private const val MAX_MS = 253_402_300_799_999
    /** Encoding always starts with this, so stored bytes are recognized without parsing them. */
    private val prefix = "{\"format\":\"$FORMAT\"".toByteArray(Charsets.UTF_8)
    private val sha = Regex("[0-9a-f]{64}")
    private val dataset = Regex("[A-Za-z0-9_.-]{1,48}")
    private val doubles = listOf("transmitterLatitude", "transmitterLongitude", "frequencyMHz", "transmitHeightMeters",
        "receiveHeightMeters", "transmitPowerDbm", "transmitGainDbi", "receiveGainDbi", "transmitLossDb", "receiveLossDb",
        "sensitivityDbm", "reserveDb")
    private val gridNames = listOf("radiusMeters", "spacingMeters", "minEastMeters", "maxNorthMeters", "columns", "rows")
    private val limitNames = listOf("areaSamples", "areaGeometry", "nodeSamples", "nodeGeometry", "nodeModelCalls")
    /** Cell state codes, in order. */
    private val states = listOf(CoverageAreaState.qualified, CoverageAreaState.nearSite, CoverageAreaState.outsideRadius,
        CoverageAreaState.missingTerrain, CoverageAreaState.accuracyUnresolved, CoverageAreaState.thresholdUnresolved,
        CoverageAreaState.workLimit, CoverageAreaState.notComputed)
    /** The reasons evaluators write; status-only reasons are refused. */
    private val reasons = listOf(CoverageNodeReason.outsideRadius, CoverageNodeReason.invalidInput, CoverageNodeReason.missingTerrain,
        CoverageNodeReason.unsupportedPath, CoverageNodeReason.accuracyUnresolved, CoverageNodeReason.thresholdUnresolved,
        CoverageNodeReason.processingLimit, CoverageNodeReason.pathWorkLimit)
    private val terrainStrings = listOf("dataset", "revision", "tile", "url", "verticalDatum", "attribution", "sourceType", "originURL")

    fun isPortable(bytes: ByteArray) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

    /** The payload's input fingerprint, for its result reference; null if it isn't a readable payload. */
    fun fingerprint(bytes: ByteArray): String? = runCatching {
        (parse(bytes)["inputFingerprint"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(sha::matches)
    }.getOrNull()

    /** Captures [result]; null if a node it evaluated has no recorded evaluation. */
    fun encode(result: CoverageStudyResult): ByteArray? {
        val run = result.nodes.run
        val evaluations = run.nodesToEvaluate.map { run.evaluation(it) ?: return null }
        val inputs = result.inputs
        val values = inputValues(inputs)
        val grid = inputs.grid
        val payload = buildJsonObject {
            put("format", FORMAT)
            put("version", 1)
            put("calculatedAtMilliseconds", result.calculatedAtMilliseconds?.let(::JsonPrimitive) ?: JsonNull)
            put("inputFingerprint", PortableInputFingerprint.coverage(inputs, run.nodesToEvaluate, run.catalogComplete))
            put("inputs", buildJsonObject {
                doubles.zip(values).forEach { (name, value) -> put(name, value) }
                put("grid", buildJsonObject {
                    gridNames.zip(listOf(grid.radiusMeters, grid.spacingMeters, grid.minEastMeters, grid.maxNorthMeters,
                        grid.columns, grid.rows)).forEach { (name, value) -> put(name, value) }
                })
                put("catalogComplete", run.catalogComplete)
            })
            put("limits", result.limits.let { limits ->
                buildJsonObject {
                    limitNames.zip(listOf(limits.areaSamples, limits.areaGeometry, limits.nodeSamples, limits.nodeGeometry,
                        limits.nodeModelCalls)).forEach { (name, value) -> put(name, value) }
                }
            })
            put("model", result.model?.let { buildJsonObject { put("sourceRevision", it.sourceRevision); put("scenario", it.scenario) } } ?: JsonNull)
            put("terrain", result.terrainSources?.descriptors?.let { descriptors ->
                buildJsonArray {
                    descriptors.forEach { t ->
                        add(buildJsonObject {
                            put("dataset", t.dataset); put("revision", t.revision); put("tile", t.tile); put("url", t.url)
                            put("verticalDatum", t.verticalDatum); put("attribution", t.attribution); put("sourceType", t.sourceType)
                            put("originURL", t.originURL); put("compressedSHA256", t.compressedSHA256); put("sourceETag", t.sourceETag)
                            put("packedBytes", t.packedBytes); put("side", t.side)
                        })
                    }
                }
            } ?: JsonNull)
            put("area", buildJsonObject {
                put("stateCodes", buildJsonArray { result.area.cells.forEach { add(JsonPrimitive(states.indexOf(it.state))) } })
                put("lossesDb", buildJsonArray {
                    result.area.cells.forEach { add(if (it.state == CoverageAreaState.qualified) JsonPrimitive(it.lossDb) else JsonNull) }
                })
                put("chargedSamples", result.area.chargedSamples)
                put("chargedGeometry", result.area.chargedGeometry)
                put("workLimited", result.area.workLimited)
            })
            put("nodes", buildJsonArray {
                evaluations.forEach { evaluation ->
                    add(buildJsonObject {
                        put("sourceID", evaluation.node.sourceID); put("nodeID", evaluation.node.nodeID)
                        put("latitude", evaluation.node.latitude); put("longitude", evaluation.node.longitude)
                        put("distanceMeters", evaluation.distanceMeters)
                        put("prediction", when (val prediction = evaluation.prediction) {
                            is CoverageNodePrediction.QualifiedMargin -> buildJsonObject { put("kind", "qualifiedMargin"); put("marginDb", prediction.margin) }
                            is CoverageNodePrediction.Unavailable -> buildJsonObject { put("kind", "unavailable"); put("reason", prediction.reason.name) }
                        })
                    })
                }
            })
        }
        return Json.encodeToString(JsonElement.serializer(), payload).toByteArray(Charsets.UTF_8).takeIf { it.size <= MAXIMUM_BYTES }
    }

    /**
     * Rebuilds the result for a study's [inputs], captured [snapshots] and [catalogComplete]; throws if the
     * payload is malformed or belongs to other inputs.
     */
    fun decode(bytes: ByteArray, inputs: CoverageStudyInputs, snapshots: List<CoverageNodeSnapshot>, catalogComplete: Boolean): CoverageStudyResult {
        val root = parse(bytes)
        root.exactly("format", "version", "calculatedAtMilliseconds", "inputFingerprint", "inputs", "limits", "model", "terrain", "area", "nodes")
        require(root.string("format") == FORMAT && root.long("version") == 1L)
        val calculatedAt = root.nullable("calculatedAtMilliseconds")?.let { require(it is JsonPrimitive); it.asLong(0..MAX_MS) }
        val fingerprint = root.string("inputFingerprint").also { require(sha.matches(it)) }

        val payloadInputs = root.obj("inputs").also { it.exactly(*(doubles + listOf("grid", "catalogComplete")).toTypedArray()) }
        val values = doubles.map { payloadInputs.double(it) }
        val grid = payloadInputs.obj("grid").also { it.exactly(*gridNames.toTypedArray()) }
        val gridValues = gridNames.map { grid.long(it) }
        val payloadComplete = payloadInputs.boolean("catalogComplete")

        val limitsObject = root.obj("limits").also { it.exactly(*limitNames.toTypedArray()) }
        val limits = limitNames.map { limitsObject.long(it).also { value -> require(value in 0..Int.MAX_VALUE) }.toInt() }
        val model = root.nullable("model")?.let { element ->
            val model = element.objectValue().also { it.exactly("sourceRevision", "scenario") }
            CoverageStudyModel(model.string("sourceRevision").also { require(it.isNotEmpty()) }, model.string("scenario").also { require(it.isNotEmpty()) })
        }
        val terrain = root.nullable("terrain")?.let { element ->
            val list = (element as? JsonArray) ?: throw IllegalArgumentException()
            require(list.size <= 64)
            list.map { item ->
                val t = item.objectValue().also { it.exactly(*(terrainStrings + listOf("compressedSHA256", "sourceETag", "packedBytes", "side")).toTypedArray()) }
                val hash = t.nullable("compressedSHA256")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException() }
                val etag = t.nullable("sourceETag")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException() }
                require(dataset.matches(t.string("dataset")) && (hash == null || sha.matches(hash)))
                SavedCoverageCalculation.Terrain(t.string("dataset"), t.string("revision"), t.string("tile"), hash,
                    t.long("packedBytes"), t.long("side").also { require(it in 2..3601) }.toInt(), t.string("url"),
                    t.string("verticalDatum"), t.string("attribution"), t.string("sourceType"), t.string("originURL"), etag)
            }
        }

        val area = root.obj("area").also { it.exactly("stateCodes", "lossesDb", "chargedSamples", "chargedGeometry", "workLimited") }
        val codes = area.array("stateCodes").map { it.asLong(0L..7L).toInt() }
        val losses = area.array("lossesDb").map { if (it is JsonNull) null else it.asDouble() }
        require(codes.size == losses.size)
        val cells = codes.zip(losses).map { (code, loss) ->
            require((code == 0) == (loss != null))
            val state = states[code]
            SavedCoverageCalculation.Cell(state.name, loss, loss?.let { inputs.budget.reception(it).adjustedMarginDb })
        }
        val charged = area.long("chargedSamples") to area.long("chargedGeometry")
        require(charged.first >= 0 && charged.second >= 0)

        val nodes = root.array("nodes").also { require(it.size <= 200) }.map { item ->
            val node = item.objectValue().also { it.exactly("sourceID", "nodeID", "latitude", "longitude", "distanceMeters", "prediction") }
            val prediction = node.obj("prediction")
            val (margin, reason) = when (prediction.string("kind")) {
                "qualifiedMargin" -> { prediction.exactly("kind", "marginDb"); prediction.double("marginDb") to null }
                "unavailable" -> {
                    prediction.exactly("kind", "reason")
                    null to reasons.first { it.name == prediction.string("reason") }.name
                }
                else -> throw IllegalArgumentException()
            }
            SavedCoverageCalculation.Node(node.string("sourceID"), node.string("nodeID"), node.double("latitude"), node.double("longitude"),
                node.double("distanceMeters"), margin, reason)
        }

        val own = PortableInputFingerprint.coverage(values, gridValues, payloadComplete,
            nodes.map { PortableInputFingerprint.Node(it.sourceID, it.nodeID, it.latitude, it.longitude) })
        require(own == fingerprint && PortableInputFingerprint.coverage(inputs, snapshots, catalogComplete) == fingerprint)

        // The legacy form records the grid after the twelve doubles.
        return SavedCoverageCalculation(inputValues = values + gridValues.map(Long::toDouble), cells = cells, chargedSamples = charged.first,
            chargedGeometry = charged.second, workLimited = area.boolean("workLimited"), limits = limits,
            modelRevision = model?.sourceRevision, modelScenario = model?.scenario, terrain = terrain, nodes = nodes)
            .restore(inputs, snapshots, catalogComplete)
            .copy(calculatedAtMilliseconds = calculatedAt)
    }

    /** A study's saved inputs as the engine takes them; throws if they aren't valid coverage inputs. */
    fun inputs(saved: SavedCoverage): CoverageStudyInputs = CoverageStudyInputs(
        CoverageTerrainCoordinate(saved.transmitter.latitude, saved.transmitter.longitude),
        CoverageGrid.studyArea(saved.radiusMeters, saved.spacingMeters),
        CoverageLinkBudget(saved.transmitPowerDbm, saved.transmitter.antenna.gainDbi, saved.receiver.gainDbi,
            saved.transmitter.antenna.lossDb, saved.receiver.lossDb, saved.sensitivityDbm, saved.reserveDb),
        saved.frequencyMHz, saved.transmitter.antenna.heightMeters, saved.receiver.heightMeters)

    fun snapshots(saved: SavedCoverage) = saved.nodes.map { CoverageNodeSnapshot(it.sourceID, it.nodeID, it.latitude, it.longitude) }

    private fun inputValues(inputs: CoverageStudyInputs): List<Double> {
        val b = inputs.budget
        return listOf(inputs.transmitter.latitude, inputs.transmitter.longitude, inputs.frequencyMHz, inputs.transmitHeightMeters,
            inputs.receiveHeightMeters, b.transmitPowerDbm, b.transmitGainDbi, b.receiveGainDbi, b.transmitLossDb, b.receiveLossDb,
            b.sensitivityDbm, b.reserveDb)
    }

    // Strict reading helpers: every accessor throws on a missing field or the wrong type.

    private fun parse(bytes: ByteArray): JsonObject {
        require(bytes.size <= MAXIMUM_BYTES)
        return Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).objectValue()
    }

    private fun JsonElement.objectValue() = this as? JsonObject ?: throw IllegalArgumentException()
    private fun JsonObject.exactly(vararg names: String) = require(keys == names.toSet())
    private fun JsonObject.field(name: String) = this[name] ?: throw IllegalArgumentException()
    private fun JsonObject.nullable(name: String): JsonElement? = field(name).takeIf { it !is JsonNull }
    private fun JsonObject.obj(name: String) = field(name).objectValue()
    private fun JsonObject.array(name: String) = field(name) as? JsonArray ?: throw IllegalArgumentException()
    private fun JsonObject.string(name: String): String =
        (field(name) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException()
    private fun JsonObject.boolean(name: String): Boolean =
        (field(name) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw IllegalArgumentException()
    private fun JsonObject.long(name: String) = field(name).asLong(Long.MIN_VALUE..Long.MAX_VALUE)
    private fun JsonObject.double(name: String) = field(name).asDouble()

    private fun JsonElement.asLong(range: LongRange): Long {
        val primitive = this as? JsonPrimitive ?: throw IllegalArgumentException()
        require(!primitive.isString && primitive !is JsonNull)
        return primitive.longOrNull?.takeIf { it in range } ?: throw IllegalArgumentException()
    }

    private fun JsonElement.asDouble(): Double {
        val primitive = this as? JsonPrimitive ?: throw IllegalArgumentException()
        require(!primitive.isString && primitive !is JsonNull && primitive.booleanOrNull == null)
        return primitive.doubleOrNull?.takeIf { it.isFinite() } ?: throw IllegalArgumentException()
    }
}
