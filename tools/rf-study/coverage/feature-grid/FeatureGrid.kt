package study

import kotlin.math.*

class FeatureGridConfig(val grid:BoundedConfig,val geometryBudget:Long,val pathGeometryBudget:Int)
class FeatureGridEstimate(val accepted:Boolean,val reason:String,val columns:Int,val cells:Int,
    val minimumSamples:Long,val minimumGeometry:Long,val maximumSamples:Long,val maximumGeometry:Long)
class FeatureGridCell(val state:String,val lossDb:Double?,val samples:Int,val geometry:Int)

object FeatureGridPreflight {
    fun estimate(c:FeatureGridConfig):FeatureGridEstimate {
        fun reject(reason:String)=FeatureGridEstimate(false,reason,0,0,0,0,0,0)
        val base=BoundedPreflight.estimate(c.grid)
        if(!base.accepted)return reject(base.reason)
        if(c.geometryBudget !in 1L..150000000L || c.pathGeometryBudget !in 1..200000)return reject("invalid_geometry_budget")
        var eligible=0L
        for(i in 0 until base.cells) {
            val e=c.grid.minEastM+(i%base.columns)*c.grid.gridM
            val n=c.grid.maxNorthM-(i/base.columns)*c.grid.gridM
            val distance=canonicalDistanceM(hypot(e.toDouble(),n.toDouble()))
            if(distance>=250 && distance<=c.grid.radiusM+.001)eligible++
        }
        if(eligible>0 && c.pathGeometryBudget<4)return reject("path_geometry_budget")
        val minimumGeometry=eligible*4
        return FeatureGridEstimate(minimumGeometry<=c.geometryBudget,
            if(minimumGeometry<=c.geometryBudget)"accepted" else "minimum_geometry_budget",
            base.columns,base.cells,base.minimumSampleWork,minimumGeometry,
            eligible*c.grid.pathBudget,eligible*c.pathGeometryBudget)
    }
}

/** Single-worker execution. Both counters include charged work discarded on cancellation. */
class FeatureGridJob(private val engine:WideEngine,val config:FeatureGridConfig) {
    val estimate=FeatureGridPreflight.estimate(config)
    var state=BoundedState.RUNNING;private set
    var completed=0;private set
    var samplesUsed=0L;private set
    var geometryUsed=0L;private set
    private val cells:Array<FeatureGridCell?>
    init {require(engine.valid() && estimate.accepted){estimate.reason};cells=arrayOfNulls(estimate.cells)}
    fun step(maxCells:Int,stop:StopSignal):Int {
        if(state!=BoundedState.RUNNING)return 0
        var processed=0
        repeat(maxCells.coerceIn(1,32)) {
            if(state!=BoundedState.RUNNING)return@repeat
            if(stop.isCancelled()){state=BoundedState.CANCELLED;return@repeat}
            val c=config.grid
            val e=c.minEastM+(completed%estimate.columns)*c.gridM
            val n=c.maxNorthM-(completed/estimate.columns)*c.gridM
            val distance=canonicalDistanceM(hypot(e.toDouble(),n.toDouble()))
            val cell:FeatureGridCell
            if(distance<250)cell=FeatureGridCell("NEAR_SITE",null,0,0)
            else if(distance>c.radiusM+.001)cell=FeatureGridCell("OUTSIDE_RADIUS",null,0,0)
            else {
                val remainingSamples=c.sampleBudget-samplesUsed
                val remainingGeometry=config.geometryBudget-geometryUsed
                if(remainingSamples==0L || remainingGeometry==0L){state=BoundedState.WORK_LIMIT;return@repeat}
                val result=FeatureSampler(engine).predict(e.toDouble(),n.toDouble(),
                    min(c.pathBudget.toLong(),remainingSamples).toInt(),
                    min(config.pathGeometryBudget.toLong(),remainingGeometry).toInt(),stop)
                samplesUsed+=result.samples;geometryUsed+=result.geometry
                check(samplesUsed<=c.sampleBudget && geometryUsed<=config.geometryBudget)
                if(result.state==AccuracyState.CANCELLED || stop.isCancelled()){state=BoundedState.CANCELLED;return@repeat}
                cell=FeatureGridCell(result.state.name,result.lossDb,result.samples,result.geometry)
                // Once either remaining job allowance constrains a failed path, stop conservatively.
                // Do not retry with cheaper sampling or publish its last diagnostic estimate.
                if(result.state==AccuracyState.WORK_LIMIT &&
                    (remainingSamples<=c.pathBudget || remainingGeometry<=config.pathGeometryBudget))state=BoundedState.WORK_LIMIT
            }
            if(stop.isCancelled()){state=BoundedState.CANCELLED;return@repeat}
            cells[completed]=cell;completed++;processed++
            if(completed==cells.size && state==BoundedState.RUNNING)state=BoundedState.COMPLETE
        }
        return processed
    }
    fun csv():String=buildString {
        append("east_m,north_m,state,loss_db,samples,geometry\n")
        for(i in cells.indices) {
            val c=config.grid;val r=cells[i]
            append("${c.minEastM+(i%estimate.columns)*c.gridM},${c.maxNorthM-(i/estimate.columns)*c.gridM},${r?.state?:"NOT_COMPUTED"},${r?.lossDb?:""},${r?.samples?:0},${r?.geometry?:0}\n")
        }
    }
}

/** Caller serializes this coordinator (an actor/main-thread owner in eventual app integration). */
class FeaturePublicationGate {
    private var active=0L
    var published:String?=null;private set
    fun begin():Long {check(active<Long.MAX_VALUE);active++;published=null;return active}
    fun publish(generation:Long,job:FeatureGridJob):Boolean {
        if(generation!=active || job.state==BoundedState.CANCELLED)return false
        published=job.csv();return true
    }
}

object FeatureGridSuite {
    fun count()=8
    fun config(index:Int):FeatureGridConfig {
        val radius=if(index==0)20000 else if(index==4)100000 else 50000
        val spacing=when(index){0->1000;1,7->100;4->20000;else->200}
        val bounds=when(index){0,4->intArrayOf(-radius,radius,-radius,radius);7->intArrayOf(8000,16000,-2000,6000);else->intArrayOf(10000,14000,0,4000)}
        val base=BoundedConfig(radius,spacing,bounds[0],bounds[1],bounds[2],bounds[3],150000000,250000)
        val samples=if(index==5)BoundedPreflight.estimate(base).minimumSampleWork else base.sampleBudget
        return FeatureGridConfig(BoundedConfig(radius,spacing,bounds[0],bounds[1],bounds[2],bounds[3],samples,250000),
            if(index==6)500000 else 150000000,200000)
    }
    fun run(index:Int,source:TerrainRows,width:Int,height:Int,north:Double,west:Double,ppd:Int,latitude:Double,longitude:Double):BoundedRun {
        val name=listOf("r20_1000","refine100","refine200_small","refine200_large","r100_20000","sample_limited","geometry_limited","reference100")[index]
        val capacity=if(index==2)131072 else 2097152
        val cache=BoundedRaster(source,width,height,capacity,NeverStop())
        val engine=WideEngine(cache,width,height,north,west,ppd,latitude,longitude)
        val job=FeatureGridJob(engine,config(index))
        val publication=FeaturePublicationGate();val generation=publication.begin()
        while(job.state==BoundedState.RUNNING)job.step(8,NeverStop())
        val before=job.csv();check(job.step(8,NeverStop())==0 && job.csv()==before)
        check(publication.publish(generation,job))
        publication.begin();check(!publication.publish(generation,job) && publication.published==null)
        val metrics="{\"state\":\"${job.state}\",\"completed\":${job.completed},\"cells\":${job.estimate.cells},\"samples\":${job.samplesUsed},\"sample_budget\":${job.config.grid.sampleBudget},\"geometry\":${job.geometryUsed},\"geometry_budget\":${job.config.geometryBudget},\"minimum_samples\":${job.estimate.minimumSamples},\"minimum_geometry\":${job.estimate.minimumGeometry},\"capacity_bytes\":$capacity,\"peak_payload_bytes\":${cache.peakPayloadBytes},\"evictions\":${cache.evictions},\"row_reads\":${cache.rowReads},\"bytes_read\":${cache.bytesRead},\"failed_loads\":${cache.failedLoads},\"stale_publication_rejected\":true}"
        cache.clear();check(cache.residentBytes==0)
        return BoundedRun(name,before,metrics)
    }
}
