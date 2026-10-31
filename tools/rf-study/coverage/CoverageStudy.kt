// Isolated feasibility code. No NodeScope application target depends on this file.
package study

import kotlin.math.*
import org.meshtastic.kp1812.*

private const val EARTH_M = 6371008.8
private const val TX_LAT = 51.15
private const val TX_LON = -114.30
private const val SAMPLE_M = 30.0
private const val MAX_SAMPLES = 2001
private const val RAD = PI / 180.0

// Stabilize ceil at exact 30 m multiples across native/JVM math implementations.
// Millimetre distance precision is far below this terrain's spatial resolution.
internal fun canonicalDistanceM(distanceM: Double): Double = round(distanceM * 1000.0) / 1000.0
internal fun profileSampleCount(distanceM: Double): Int = ceil(canonicalDistanceM(distanceM) / SAMPLE_M).toInt() + 1

enum class CellState { PREDICTED, OUTSIDE_RADIUS, NEAR_SITE, MISSING_TERRAIN, INVALID_INPUT, INVALID_MODEL, CANCELLED, NOT_COMPUTED }
enum class JobState { RUNNING, COMPLETE, CANCELLED }
interface StopSignal { fun isCancelled(): Boolean }
class NeverStop : StopSignal { override fun isCancelled(): Boolean = false }
class Point(val latitude: Double, val longitude: Double)
class CellResult(val state: CellState, val lossDb: Double?, val sampleCount: Int) {
    // Explicit SI link budget: 20 dBm conducted power, 2 dBi TX/RX gains, 2 dB total feed loss.
    val rxDbm: Double? get() = lossDb?.let { 22.0 - it }
    val marginDb: Double? get() = rxDbm?.let { it - (-130.0) }
}
class TerrainLoad(val state: CellState, val engine: CoverageEngine?)

private fun destination(distanceM: Double, bearing: Double): Point {
    val lat = TX_LAT * RAD
    val arc = distanceM / EARTH_M
    val resultLat = asin(sin(lat) * cos(arc) + cos(lat) * sin(arc) * cos(bearing))
    val resultLon = TX_LON * RAD + atan2(sin(bearing) * sin(arc) * cos(lat), cos(arc) - sin(lat) * sin(resultLat))
    return Point(resultLat / RAD, resultLon / RAD)
}

class CoverageEngine private constructor(
    private val width: Int, private val height: Int, private val row0: Int,
    private val col0: Int, private val tileLat: Int, private val tileLon: Int,
    private val ppd: Int, private val elevations: ShortArray,
) {
    companion object {
        // All expected parse failures become a typed result before crossing the Swift bridge.
        fun load(bytes: ByteArray): TerrainLoad {
            if (bytes.size < 32 || bytes.size > 4_000_032) return TerrainLoad(CellState.INVALID_INPUT, null)
            fun intAt(offset: Int): Int = (0..3).fold(0) { value, n -> (value shl 8) or (bytes[offset + n].toInt() and 255) }
            val w = intAt(4); val h = intAt(8); val row = intAt(12); val col = intAt(16)
            val lat = intAt(20); val lon = intAt(24); val ppd = intAt(28)
            if (intAt(0) != 0x43535631 || w !in 2..3601 || h !in 2..3601 || ppd != 3600 ||
                w.toLong() * h > 2_000_000 || bytes.size.toLong() != 32L + 2L * w * h ||
                row < 0 || col < 0 || row.toLong() + h > 3601 || col.toLong() + w > 3601 ||
                lat !in -89..89 || lon !in -180..179) return TerrainLoad(CellState.INVALID_INPUT, null)
            val values = ShortArray(w * h) { i ->
                (((bytes[32 + i * 2].toInt() and 255) shl 8) or (bytes[33 + i * 2].toInt() and 255)).toShort()
            }
            if (values.any { it.toInt() != -32768 && it.toInt() !in -12000..10000 }) return TerrainLoad(CellState.INVALID_INPUT, null)
            return TerrainLoad(CellState.PREDICTED, CoverageEngine(w, h, row, col, lat, lon, ppd, values))
        }
    }

    // A fixture-only transformation: a real sentinel gap, rather than fake sea-level terrain.
    fun withGap(): CoverageEngine {
        val copy = elevations.copyOf()
        for (x in 0 until width) {
            val lon = tileLon + (col0 + x).toDouble() / ppd
            if (lon in -114.280..-114.275) for (y in 0 until height) copy[y * width + x] = (-32768).toShort()
        }
        return CoverageEngine(width, height, row0, col0, tileLat, tileLon, ppd, copy)
    }

    fun elevationAt(latitude: Double, longitude: Double): Double? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        val rawY = (tileLat + 1.0 - latitude) * ppd - row0
        val rawX = (longitude - tileLon) * ppd - col0
        // Allow only floating-point roundoff at an exact raster edge (sub-micrometre scale).
        val epsilon = 1e-8
        if (rawX < -epsilon || rawY < -epsilon || rawX > width - 1.0 + epsilon || rawY > height - 1.0 + epsilon) return null
        val x = rawX.coerceIn(0.0, width - 1.0)
        val y = rawY.coerceIn(0.0, height - 1.0)
        val left = floor(x).toInt().coerceAtMost(width - 2)
        val top = floor(y).toInt().coerceAtMost(height - 2)
        val a = elevations[top * width + left].toInt(); val b = elevations[top * width + left + 1].toInt()
        val c = elevations[(top + 1) * width + left].toInt(); val d = elevations[(top + 1) * width + left + 1].toInt()
        if (a == -32768 || b == -32768 || c == -32768 || d == -32768) return null
        val fx = x - left; val fy = y - top
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    fun pointAt(eastM: Double, northM: Double): Point = destination(hypot(eastM, northM), atan2(eastM, northM))

    fun predict(latitude: Double, longitude: Double, receiverHeightM: Double, stop: StopSignal): CellResult {
        fun unknown(state: CellState) = CellResult(state, null, 0)
        if (stop.isCancelled()) return unknown(CellState.CANCELLED)
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 ||
            longitude !in -180.0..180.0 || !receiverHeightM.isFinite() || receiverHeightM !in 1.0..3000.0) return unknown(CellState.INVALID_INPUT)
        val lat1 = TX_LAT * RAD; val lat2 = latitude * RAD; val deltaLon = (longitude - TX_LON) * RAD
        val hav = sin((lat2 - lat1) / 2).pow(2) + cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
        val rawDistance = 2 * EARTH_M * asin(sqrt(hav.coerceIn(0.0, 1.0)))
        if (rawDistance < 250.0) return unknown(CellState.NEAR_SITE)
        // This bounded prototype supports only a 10 km study, not the model's entire domain.
        if (rawDistance > 10000.001) return unknown(CellState.OUTSIDE_RADIUS)
        val distance = canonicalDistanceM(rawDistance)
        val count = profileSampleCount(distance)
        if (count !in 3..MAX_SAMPLES) return unknown(CellState.INVALID_INPUT)
        val bearing = atan2(sin(deltaLon) * cos(lat2), cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon))
        val distances = DoubleArray(count); val heights = DoubleArray(count)
        for (i in 0 until count) {
            if (i % 32 == 0 && stop.isCancelled()) return unknown(CellState.CANCELLED)
            val metres = distance * i / (count - 1)
            val p = if (i == count - 1) Point(latitude, longitude) else destination(metres, bearing)
            distances[i] = metres / 1000.0
            heights[i] = elevationAt(p.latitude, p.longitude) ?: return unknown(CellState.MISSING_TERRAIN)
        }
        if (stop.isCancelled()) return unknown(CellState.CANCELLED)
        return try {
            // Explicit bounded scenario: inland terrain-only, median atmosphere, vertical polarization.
            val value = P1812.predict(TerrainPath(distances, heights, DoubleArray(count), IntArray(count) { 4 }),
                frequencyGhz = 0.915, txHeightM = 10.0, rxHeightM = receiverHeightM,
                timePercent = 50.0, pathCenterLatitudeDeg = (TX_LAT + latitude) / 2,
                polarization = Polarization.VERTICAL, atmosphere = Atmosphere(45.0, 325.0, 500.0, 500.0),
                locationPercent = 50.0, locationVariabilityDb = 0.0).basicTransmissionLossDb
            when {
                stop.isCancelled() -> unknown(CellState.CANCELLED)
                !value.isFinite() -> unknown(CellState.INVALID_MODEL)
                else -> CellResult(CellState.PREDICTED, value, count)
            }
        } catch (_: IllegalArgumentException) { unknown(CellState.INVALID_MODEL) }
    }

    fun newJob(): CoverageJob = CoverageJob(this)
}

// Single worker owns the job; only StopSignal is shared across threads. Terminal states never resume.
class CoverageJob internal constructor(private val engine: CoverageEngine) {
    val side: Int = 101
    val total: Int = side * side
    var state: JobState = JobState.RUNNING
        private set
    var completed: Int = 0
        private set
    private val cells = arrayOfNulls<CellResult>(total)
    fun step(maxCells: Int, stop: StopSignal): Int {
        if (state != JobState.RUNNING) return 0
        var processed = 0
        for (unused in 0 until maxCells.coerceIn(1, 32)) {
            if (stop.isCancelled()) { state = JobState.CANCELLED; break }
            if (completed == total) { state = JobState.COMPLETE; break }
            val east = ((completed % side) - 50) * 200.0
            val north = (50 - (completed / side)) * 200.0
            val result = if (hypot(east, north) > 10000.000001) CellResult(CellState.OUTSIDE_RADIUS, null, 0)
                else engine.pointAt(east, north).let { engine.predict(it.latitude, it.longitude, 1.5, stop) }
            if (result.state == CellState.CANCELLED) { state = JobState.CANCELLED; break }
            cells[completed] = result; completed++; processed++
        }
        if (completed == total && state == JobState.RUNNING) state = JobState.COMPLETE
        return processed
    }
    fun cell(index: Int): CellResult = if (index in 0 until total) cells[index] ?: CellResult(CellState.NOT_COMPUTED, null, 0)
        else CellResult(CellState.INVALID_INPUT, null, 0)
    fun csv(): String = buildString {
        append("index,state,loss_db,rx_dbm,margin_db,samples\n")
        for (i in 0 until total) {
            val c = cell(i)
            append("$i,${c.state},${c.lossDb ?: ""},${c.rxDbm ?: ""},${c.marginDb ?: ""},${c.sampleCount}\n")
        }
    }
}
