package org.nodescope.android.feature.coverage

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The last calculation report, as iOS's `CoverageRunDiagnostics`: local and bounded, shared only when
 * the person chooses. One instance per run, used from its worker. Writing never fails a calculation.
 */
class CoverageRunDiagnostics(private val inputs: CoverageStudyInputs, private val file: File? = null,
    private val availableMemory: () -> Long? = ::heapHeadroom) {
    private val started = System.nanoTime()
    private var lastWrite = System.nanoTime()
    var pauseReason: String? = null; private set
    private var minimumAvailableBytes: Long? = null
    private var peakUsedHeapBytes = 0L
    private var phase = "validating"
    private var finished = false
    private val timings = sortedMapOf<String, Double>()
    private val counters = sortedMapOf<String, Int>()

    fun <T> measure(stage: String, operation: () -> T): T {
        val start = System.nanoTime()
        try { return operation() } finally { recordDuration(stage, start) }
    }

    fun recordDuration(stage: String, sinceNanos: Long) {
        timings[stage] = (timings[stage] ?: 0.0) + (System.nanoTime() - sinceNanos) / 1e9
        counters[stage + "Calls"] = (counters[stage + "Calls"] ?: 0) + 1
    }

    fun recordRadiusReuse(area: CoverageAreaResult) {
        counters["radiusReusedPredictions"] = area.cells.count { it.state == CoverageAreaState.qualified }
        counters["radiusPendingSamples"] = area.pendingCount
    }

    /** Path reuse and terrain reader counters; logical work charges are unchanged by cache hits. */
    fun recordCache(mosaic: CoverageTerrainMosaic) {
        counters["crossingCacheHits"] = mosaic.crossingCacheHits
        counters["elevationCacheHits"] = mosaic.elevationCacheHits.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        counters["elevationReads"] = mosaic.elevationReads.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        counters.putAll(mosaic.profilePassCounts)
        mosaic.readerCounters.forEach { (name, value) -> counters[name] = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
    }

    fun setPhase(phase: String) {
        this.phase = phase
        finished = phase.startsWith("failed") || phase == "cancelled"
        persist()
    }

    /**
     * Advisory headroom check between paths: below 32 MiB of Java heap left, the batch stops and keeps
     * what it has for Continue. It cannot guarantee protection from the system ending the app.
     */
    fun shouldPause(): Boolean {
        val runtime = Runtime.getRuntime()
        peakUsedHeapBytes = maxOf(peakUsedHeapBytes, runtime.totalMemory() - runtime.freeMemory())
        availableMemory()?.let { available ->
            minimumAvailableBytes = minOf(minimumAvailableBytes ?: available, available)
            if (available < 32L * 1024 * 1024) pauseReason = "low available memory"
        }
        if (phase == "calculating" && System.nanoTime() - lastWrite >= 2_000_000_000L) persist()
        return pauseReason != null
    }

    fun finishIfNeeded(cancelled: Boolean) {
        if (!finished) setPhase(if (cancelled) "cancelled" else "failed before completion")
    }

    fun finish(result: CoverageStudyResult) {
        finished = true
        phase = if (result.hasPendingWork) "partial; can continue in this screen" else "completed"
        persist(result)
    }

    private fun persist(result: CoverageStudyResult? = null) {
        val file = file ?: return
        val b = inputs.budget
        val document = buildJsonObject {
            put("formatVersion", 2); put("platform", "android"); put("updatedAt", Instant.now().toString())
            put("executionID", result?.executionID?.toString() ?: "")
            put("phase", phase); put("elapsedSeconds", (System.nanoTime() - started) / 1e9)
            put("predictionFingerprint", result?.let(::predictionFingerprint) ?: "")
            put("terrainMappingRequested", LeasedTerrainReader.mappingEnabled)
            put("timingSeconds", JsonObject(timings.mapValues { JsonPrimitive(it.value) }))
            put("performanceCounters", JsonObject(counters.mapValues { JsonPrimitive(it.value) }))
            put("peakObservedHeapBytes", peakUsedHeapBytes)
            put("minimumAvailableHeapBytes", minimumAvailableBytes?.let(::JsonPrimitive) ?: JsonNull)
            put("pauseReason", pauseReason ?: "")
            put("latitude", inputs.transmitter.latitude); put("longitude", inputs.transmitter.longitude)
            put("radiusMeters", inputs.grid.radiusMeters); put("spacingMeters", inputs.grid.spacingMeters)
            put("frequencyMHz", inputs.frequencyMHz); put("transmitPowerDbm", b.transmitPowerDbm)
            put("txHeightMeters", inputs.transmitHeightMeters); put("rxHeightMeters", inputs.receiveHeightMeters)
            put("txGainDbi", b.transmitGainDbi); put("rxGainDbi", b.receiveGainDbi)
            put("txLossDb", b.transmitLossDb); put("rxLossDb", b.receiveLossDb)
            put("receiverSensitivityDbm", b.sensitivityDbm); put("reserveDb", b.reserveDb)
            put("model", CoverageRFKernel.scenario); put("modelRevision", CoverageRFKernel.sourceRevision)
            put("pendingMapSamples", result?.area?.pendingCount ?: 0)
            put("chargedAreaSamples", result?.area?.chargedSamples ?: 0)
            put("chargedAreaGeometry", result?.area?.chargedGeometry ?: 0)
            put("terrainKeys", JsonArray(result?.terrainSources?.descriptors?.map { JsonPrimitive(it.cacheKey) }.orEmpty()))
            put("nodeComparisons", JsonArray(result?.let { output ->
                output.nodes.run.nodesToEvaluate.map { node ->
                    val status = output.nodes.run.status(node)
                    val margin = status.adjustedMarginDb
                    buildJsonObject {
                        put("nodeID", node.nodeID); put("sourceID", node.sourceID)
                        put("latitude", node.latitude); put("longitude", node.longitude)
                        put("state", status.state.name); put("reason", status.reason.name)
                        put("adjustedMarginDb", margin?.let(::JsonPrimitive) ?: JsonNull)
                        put("receiveLevelDbm", margin?.let { JsonPrimitive(it + b.sensitivityDbm + b.reserveDb) } ?: JsonNull)
                        put("pathLossDb", margin?.let { JsonPrimitive(b.maximumLossDb - it) } ?: JsonNull)
                    }
                }
            }.orEmpty()))
            put("note", "A report left in calculating/preparing terrain does not identify the cause of termination.")
        }
        write(file, document)
        lastWrite = System.nanoTime()
    }

    companion object {
        private val json = Json { prettyPrint = true }

        /** Heap the app may still allocate; terrain tiles and results live on the Java heap on Android. */
        fun heapHeadroom(): Long? = Runtime.getRuntime().let { it.maxMemory() - (it.totalMemory() - it.freeMemory()) }

        /**
         * Versioned digest of every grid verdict, loss and margin and each node's verdict and margin, as iOS:
         * the same study gives the same fingerprint, so a speed change can be checked for changed predictions.
         */
        fun predictionFingerprint(result: CoverageStudyResult): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(value: String) = digest.update((value + "\n").toByteArray(Charsets.UTF_8))
            fun bits(value: Double?) = value?.let { java.lang.Long.toHexString(it.toRawBits()) } ?: "nil"
            val g = result.grid
            add("coverage-predictions-v1")
            add("${g.radiusMeters},${g.spacingMeters},${g.columns},${g.rows},${g.minEastMeters},${g.maxNorthMeters}")
            result.area.cells.forEach { add("${it.state.name},${bits(it.lossDb)},${bits(it.marginDb)}") }
            result.nodes.run.nodesToEvaluate.forEach { node ->
                val status = result.nodes.run.status(node)
                add("${bits(node.latitude)},${bits(node.longitude)},${status.state.name},${status.reason.name},${bits(status.adjustedMarginDb)}")
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Adds the overlay's preparation time to the report for [executionID], if it is still the latest. */
        fun recordRasterPreparation(file: File, executionID: java.util.UUID, seconds: Double) {
            val document = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return
            if ((document["executionID"] as? JsonPrimitive)?.content != executionID.toString()) return
            val timing = (document["timingSeconds"] as? JsonObject).orEmpty() + ("rasterPreparation" to JsonPrimitive(seconds))
            write(file, JsonObject(document + ("timingSeconds" to JsonObject(timing.toSortedMap()))))
        }

        fun readLatest(file: File): String? = runCatching { file.readText() }.getOrNull()

        private fun write(file: File, document: JsonObject) {
            runCatching {
                file.parentFile?.mkdirs()
                val temporary = File(file.parentFile, file.name + ".tmp")
                temporary.writeText(json.encodeToString(JsonObject.serializer(), document))
                if (!temporary.renameTo(file)) { file.delete(); temporary.renameTo(file) }
            }
        }
    }
}
