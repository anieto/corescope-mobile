package study

import kotlin.math.*

// Positional disk reads. A missing/truncated read is null, never zero-filled terrain.
interface TerrainRows { fun read(start: Int, count: Int): ShortArray? }

/** Single-worker cache. Budget covers resident plus in-flight decoded tile payloads. */
class BoundedRaster(
    private val source: TerrainRows, val width: Int, val height: Int,
    val capacityBytes: Int, private val stop: StopSignal
) : RasterReader {
    private val edge = 128
    private val tileBytes = edge * edge * 2
    private val tilesAcross: Int
    private val tiles = linkedMapOf<Int, ShortArray>()
    var residentBytes = 0; private set
    var peakPayloadBytes = 0; private set
    var hits = 0L; private set
    var misses = 0L; private set
    var evictions = 0L; private set
    var rowReads = 0L; private set
    var bytesRead = 0L; private set
    var failedLoads = 0L; private set

    init {
        require(width in 2..20000 && height in 2..20000 && width.toLong() * height <= 100000000)
        require(capacityBytes in tileBytes..64 * 1024 * 1024)
        tilesAcross = (width + edge - 1) / edge
    }

    override fun value(index: Int): Int {
        if (index < 0 || index.toLong() >= width.toLong() * height || stop.isCancelled()) return -32768
        val x = index % width; val y = index / width
        val tileX = x / edge; val tileY = y / edge
        val key = tileY * tilesAcross + tileX
        val found = tiles.remove(key)
        if (found != null) {
            hits++; tiles[key] = found
            return found[(y % edge) * edge + x % edge].toInt()
        }
        misses++
        // Evict before allocating: cache + newly decoded tile stays within the payload cap.
        while (residentBytes + tileBytes > capacityBytes) {
            tiles.remove(tiles.keys.first()); residentBytes -= tileBytes; evictions++
        }
        val tile = ShortArray(edge * edge) { -32768 }
        peakPayloadBytes = max(peakPayloadBytes, residentBytes + tileBytes)
        val columns = min(edge, width - tileX * edge)
        val rows = min(edge, height - tileY * edge)
        for (row in 0 until rows) {
            if (stop.isCancelled()) return -32768
            rowReads++
            val values = source.read((tileY * edge + row) * width + tileX * edge, columns)
            if (values == null || values.size != columns) { failedLoads++; return -32768 }
            bytesRead += values.size * 2
            values.copyInto(tile, row * edge)
        }
        if (stop.isCancelled()) return -32768
        tiles[key] = tile; residentBytes += tileBytes
        return tile[(y % edge) * edge + x % edge].toInt()
    }

    fun clear() { tiles.clear(); residentBytes = 0 }
}

class BoundedConfig(
    val radiusM: Int, val gridM: Int, val minEastM: Int, val maxEastM: Int,
    val minNorthM: Int, val maxNorthM: Int, val sampleBudget: Long, val pathBudget: Int
)
class BoundedEstimate(val accepted: Boolean, val reason: String, val columns: Int,
    val cells: Int, val minimumSampleWork: Long, val maximumSampleWork: Long)
enum class BoundedState { RUNNING, COMPLETE, WORK_LIMIT, CANCELLED }
class BoundedCell(val state: String, val lossDb: Double?, val samples: Int)

object BoundedPreflight {
    fun estimate(c: BoundedConfig): BoundedEstimate {
        fun reject(reason: String) = BoundedEstimate(false, reason, 0, 0, 0, 0)
        if (c.radiusM !in 1000..100000 || c.gridM !in listOf(50,100,200,500,1000,2000,5000,10000,20000) ||
            c.sampleBudget !in 1L..150000000L || c.pathBudget !in 1..250000) return reject("invalid_parameters")
        if (c.minEastM < -c.radiusM || c.maxEastM > c.radiusM || c.minNorthM < -c.radiusM ||
            c.maxNorthM > c.radiusM || c.minEastM > c.maxEastM || c.minNorthM > c.maxNorthM) return reject("invalid_bounds")
        if ((c.maxEastM-c.minEastM)%c.gridM != 0 || (c.maxNorthM-c.minNorthM)%c.gridM != 0) return reject("unaligned_extent")
        val columns = (c.maxEastM-c.minEastM)/c.gridM+1
        val rows = (c.maxNorthM-c.minNorthM)/c.gridM+1
        val count = columns.toLong()*rows
        if (count > 60000) return reject("cell_budget")
        var minimum = 0L; var maximum = 0L
        for (i in 0 until count.toInt()) {
            val east = c.minEastM+(i%columns)*c.gridM
            val north = c.maxNorthM-(i/columns)*c.gridM
            val distance = canonicalDistanceM(hypot(east.toDouble(),north.toDouble()))
            if (distance < 250 || distance > c.radiusM+.001) continue
            val first = listOf(30,10,5).sumOf { ceil(distance/it).toInt()+1 }
            val all = listOf(30,10,5,2,1).sumOf { ceil(distance/it).toInt()+1 }
            if (first > c.pathBudget) return reject("path_budget")
            minimum += first; maximum += min(all,c.pathBudget)
        }
        return BoundedEstimate(minimum <= c.sampleBudget,
            if (minimum <= c.sampleBudget) "accepted" else "minimum_work_budget", columns,count.toInt(),minimum,maximum)
    }
}

/** One worker owns a job. Terminal states cannot resume or publish further cells. */
class BoundedJob(private val engine: WideEngine, val config: BoundedConfig) {
    val estimate = BoundedPreflight.estimate(config)
    private val cells: Array<BoundedCell?>
    var state = BoundedState.RUNNING; private set
    var completed = 0; private set
    var samplesUsed = 0L; private set
    init {
        require(engine.valid() && estimate.accepted) { estimate.reason }
        cells = arrayOfNulls(estimate.cells)
    }
    fun step(maxCells: Int, stop: StopSignal): Int {
        if (state != BoundedState.RUNNING) return 0
        var processed = 0
        repeat(maxCells.coerceIn(1,32)) {
            if (state != BoundedState.RUNNING) return@repeat
            if (stop.isCancelled()) { state = BoundedState.CANCELLED; return@repeat }
            val east = config.minEastM+(completed%estimate.columns)*config.gridM
            val north = config.maxNorthM-(completed/estimate.columns)*config.gridM
            val distance = canonicalDistanceM(hypot(east.toDouble(),north.toDouble()))
            val result: BoundedCell
            if (distance < 250) result = BoundedCell("NEAR_SITE",null,0)
            else if (distance > config.radiusM+.001) result = BoundedCell("OUTSIDE_RADIUS",null,0)
            else {
                val remaining = config.sampleBudget-samplesUsed
                if (remaining == 0L) { state = BoundedState.WORK_LIMIT; return@repeat }
                val allowance = min(config.pathBudget.toLong(),remaining).toInt()
                val value = AdaptiveSampler(engine).predict(east.toDouble(),north.toDouble(),allowance,152.0,stop)
                samplesUsed += value.samplesUsed
                check(samplesUsed <= config.sampleBudget)
                if (value.state == AccuracyState.CANCELLED || stop.isCancelled()) {
                    state = BoundedState.CANCELLED; return@repeat
                }
                result = BoundedCell(value.state.name,value.lossDb,value.samplesUsed)
                if (value.state == AccuracyState.WORK_LIMIT && remaining <= config.pathBudget) state = BoundedState.WORK_LIMIT
            }
            if (stop.isCancelled()) { state = BoundedState.CANCELLED; return@repeat }
            cells[completed] = result; completed++; processed++
            if (completed == cells.size && state == BoundedState.RUNNING) state = BoundedState.COMPLETE
        }
        return processed
    }
    fun csv(): String = buildString {
        append("east_m,north_m,state,loss_db,samples\n")
        for (i in cells.indices) {
            val r = cells[i]
            append("${config.minEastM+(i%estimate.columns)*config.gridM},${config.maxNorthM-(i/estimate.columns)*config.gridM},${r?.state?:"NOT_COMPUTED"},${r?.lossDb?:""},${r?.samples?:0}\n")
        }
    }
}

class BoundedRun(val name: String, val csv: String, val metrics: String)
object BoundedSuite {
    fun count() = 6
    fun config(index: Int): BoundedConfig = when(index) {
        0,1 -> BoundedConfig(20000,2000,-20000,20000,-20000,20000,15000000,200000)
        2,3 -> BoundedConfig(50000,200,10000,14000,0,4000,15000000,200000)
        4 -> BoundedConfig(100000,20000,-100000,100000,-100000,100000,15000000,200000)
        else -> {
            val base = config(2); val e = BoundedPreflight.estimate(base)
            BoundedConfig(base.radiusM,base.gridM,base.minEastM,base.maxEastM,base.minNorthM,base.maxNorthM,e.minimumSampleWork,base.pathBudget)
        }
    }
    fun run(index: Int, source: TerrainRows, width: Int, height: Int, north: Double, west: Double,
        ppd: Int, latitude: Double, longitude: Double): BoundedRun {
        val name = listOf("r20_small","r20_large","refine_small","refine_large","r100","limited")[index]
        val capacity = if(index == 1 || index == 3) 2*1024*1024 else 128*1024
        val cache = BoundedRaster(source,width,height,capacity,NeverStop())
        val engine = WideEngine(cache,width,height,north,west,ppd,latitude,longitude)
        val job = BoundedJob(engine,config(index))
        while(job.state == BoundedState.RUNNING) job.step(8,NeverStop())
        val before = job.completed
        check(job.step(8,NeverStop()) == 0 && job.completed == before)
        val metrics = "{\"state\":\"${job.state}\",\"completed\":${job.completed},\"cells\":${job.estimate.cells},\"samples\":${job.samplesUsed},\"budget\":${job.config.sampleBudget},\"minimum_work\":${job.estimate.minimumSampleWork},\"maximum_work\":${job.estimate.maximumSampleWork},\"capacity_bytes\":$capacity,\"peak_payload_bytes\":${cache.peakPayloadBytes},\"hits\":${cache.hits},\"misses\":${cache.misses},\"evictions\":${cache.evictions},\"row_reads\":${cache.rowReads},\"bytes_read\":${cache.bytesRead},\"failed_loads\":${cache.failedLoads}}"
        val csv = job.csv(); cache.clear(); check(cache.residentBytes == 0)
        return BoundedRun(name,csv,metrics)
    }
}
