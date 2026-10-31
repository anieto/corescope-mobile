package study

import kotlin.math.*
import org.meshtastic.kp1812.*

// Drivers own a read-only memory mapping; Kotlin owns indexing, interpolation and propagation.
interface RasterReader { fun value(index: Int): Int }
class WideConfig(val radiusM: Int, val gridM: Int, val profileM: Int,
    val minEastM: Int, val maxEastM: Int, val minNorthM: Int, val maxNorthM: Int)
class WorkEstimate(val accepted: Boolean, val reason: String, val columns: Int, val rows: Int,
    val cells: Long, val maxSamples: Int, val sampleWorkUpperBound: Long)
class WideJobLoad(val estimate: WorkEstimate, val job: WideJob?)

object WideScenarios {
    fun name(index: Int): String = listOf("r10_g200", "r20_g500", "r50_g1000", "r100_g2000", "r50_g500", "r100_g1000", "refine50", "reference50")[index]
    fun count(): Int = 8
    fun config(index: Int): WideConfig {
        val radius = listOf(10000,20000,50000,100000,50000,100000,50000,50000)[index]
        val grid = listOf(200,500,1000,2000,500,1000,50,50)[index]
        return when(index) {
            6 -> WideConfig(radius,grid,30,10000,14000,0,4000)
            7 -> WideConfig(radius,grid,30,8000,16000,-2000,6000)
            else -> WideConfig(radius,grid,30,-radius,radius,-radius,radius)
        }
    }
    fun preflight(c: WideConfig): WorkEstimate {
        fun reject(reason: String) = WorkEstimate(false,reason,0,0,0,0,0)
        if(c.radiusM !in 1000..100000 || c.gridM !in listOf(50,100,200,500,1000,2000) || c.profileM !in listOf(2,5,10,15,30,90)) return reject("unsupported_parameters")
        if(c.minEastM < -c.radiusM || c.maxEastM > c.radiusM || c.minNorthM < -c.radiusM || c.maxNorthM > c.radiusM || c.minEastM > c.maxEastM || c.minNorthM > c.maxNorthM) return reject("invalid_bounds")
        if((c.maxEastM-c.minEastM)%c.gridM != 0 || (c.maxNorthM-c.minNorthM)%c.gridM != 0) return reject("unaligned_extent")
        val cols=(c.maxEastM-c.minEastM)/c.gridM+1;val rows=(c.maxNorthM-c.minNorthM)/c.gridM+1
        val cells=cols.toLong()*rows
        val farthest=min(c.radiusM.toDouble(),hypot(max(abs(c.minEastM),abs(c.maxEastM)).toDouble(),max(abs(c.minNorthM),abs(c.maxNorthM)).toDouble()))
        val samples=ceil(farthest/c.profileM).toInt()+1
        val work=cells*samples
        val reason=when {cells>60000 -> "cell_budget";work>150000000 -> "sample_work_budget";else -> "accepted"}
        return WorkEstimate(reason=="accepted",reason,cols,rows,cells,samples,work)
    }
}

class WideEngine(val reader: RasterReader, val width: Int, val height: Int,
    val north: Double, val west: Double, val ppd: Int,
    val siteLatitude: Double, val siteLongitude: Double) {
    private val rad=PI/180.0
    private val earth=6371008.8
    fun valid(): Boolean = width in 2..20000 && height in 2..20000 && width.toLong()*height<=100000000 && ppd==3600 &&
        north.isFinite() && west.isFinite() && siteLatitude.isFinite() && siteLongitude.isFinite() &&
        north in -85.0..85.0 && west in -180.0..180.0 && siteLatitude in -85.0..85.0 && siteLongitude in -180.0..180.0
    fun elevationAt(lat: Double, lon: Double): Double? {
        if(!valid() || !lat.isFinite() || !lon.isFinite()) return null
        val rawX=(lon-west)*ppd;val rawY=(north-lat)*ppd
        if(rawX < -1e-8 || rawY < -1e-8 || rawX > width-1.0+1e-8 || rawY > height-1.0+1e-8) return null
        val x=rawX.coerceIn(0.0,width-1.0);val y=rawY.coerceIn(0.0,height-1.0)
        val left=floor(x).toInt().coerceAtMost(width-2);val top=floor(y).toInt().coerceAtMost(height-2)
        val a=reader.value(top*width+left);val b=reader.value(top*width+left+1)
        val c=reader.value((top+1)*width+left);val d=reader.value((top+1)*width+left+1)
        if(a !in -12000..10000 || b !in -12000..10000 || c !in -12000..10000 || d !in -12000..10000) return null
        val fx=x-left;val fy=y-top
        return (a*(1-fx)+b*fx)*(1-fy)+(c*(1-fx)+d*fx)*fy
    }
    fun pointAt(distanceM: Double,bearing: Double): Point {
        val lat=siteLatitude*rad;val arc=distanceM/earth
        val p=asin(sin(lat)*cos(arc)+cos(lat)*sin(arc)*cos(bearing))
        val l=siteLongitude*rad+atan2(sin(bearing)*sin(arc)*cos(lat),cos(arc)-sin(lat)*sin(p))
        return Point(p/rad,l/rad)
    }
    fun predict(east: Double,northM: Double,c: WideConfig,stop: StopSignal): CellResult {
        fun unknown(s: CellState)=CellResult(s,null,0)
        if(stop.isCancelled())return unknown(CellState.CANCELLED)
        if(!valid() || !east.isFinite() || !northM.isFinite() || c.radiusM !in 1000..100000 || c.profileM !in listOf(2,5,10,15,30,90))return unknown(CellState.INVALID_INPUT)
        val distance=canonicalDistanceM(hypot(east,northM))
        if(distance<250)return unknown(CellState.NEAR_SITE)
        if(distance>c.radiusM+.001)return unknown(CellState.OUTSIDE_RADIUS)
        val count=ceil(distance/c.profileM).toInt()+1
        if(count !in 3..50001)return unknown(CellState.INVALID_INPUT)
        val bearing=atan2(east,northM);val d=DoubleArray(count);val h=DoubleArray(count)
        for(i in 0 until count){
            if(i%32==0 && stop.isCancelled())return unknown(CellState.CANCELLED)
            val metres=distance*i/(count-1);val p=pointAt(metres,bearing)
            d[i]=metres/1000;h[i]=elevationAt(p.latitude,p.longitude)?:return unknown(CellState.MISSING_TERRAIN)
        }
        if(stop.isCancelled())return unknown(CellState.CANCELLED)
        return try {
            val midpoint=pointAt(distance/2,bearing)
            val loss=P1812.predict(TerrainPath(d,h,DoubleArray(count),IntArray(count){4}),.915,30.0,1.5,
                50.0,midpoint.latitude,Polarization.VERTICAL,Atmosphere(45.0,325.0,500.0,500.0),50.0,0.0).basicTransmissionLossDb
            when {stop.isCancelled()->unknown(CellState.CANCELLED);!loss.isFinite()->unknown(CellState.INVALID_MODEL);else->CellResult(CellState.PREDICTED,loss,count)}
        }catch(_:IllegalArgumentException){unknown(CellState.INVALID_MODEL)}
    }
    fun makeJob(config: WideConfig): WideJobLoad {
        val estimate=WideScenarios.preflight(config)
        return WideJobLoad(estimate,if(valid() && estimate.accepted)WideJob(this,config,estimate)else null)
    }
    // Targeted convergence diagnostics only; preflight still rejects expensive full-grid combinations.
    fun diagnosticCsv(): String=buildString {
        append("bearing_deg,distance_m,profile_m,state,loss_db,samples\n")
        for(bearing in listOf(240,285))for(step in listOf(90,30,15,10,5,2)){
            val c=WideConfig(100000,2000,step,-100000,100000,-100000,100000)
            val result=predict(100000*sin(bearing*rad),100000*cos(bearing*rad),c,NeverStop())
            append("$bearing,100000,$step,${result.state},${result.lossDb?:""},${result.sampleCount}\n")
        }
    }
    fun sensitivityCsv(): String=buildString {
        append("bearing_deg,distance_m,profile_m,state,loss_db,samples\n")
        for(bearing in 0 until 360 step 15)for(distance in listOf(1000,2000,5000,10000,20000,50000,100000))for(step in listOf(90,30,15,10)){
            val c=WideConfig(100000,2000,step,-100000,100000,-100000,100000)
            val result=predict(distance*sin(bearing*rad),distance*cos(bearing*rad),c,NeverStop())
            append("$bearing,$distance,$step,${result.state},${result.lossDb?:""},${result.sampleCount}\n")
        }
    }
}

class WideJob internal constructor(private val engine: WideEngine,val config: WideConfig,val estimate: WorkEstimate){
    var state:JobState=JobState.RUNNING;private set
    var completed:Int=0;private set
    private val cells=arrayOfNulls<CellResult>(estimate.cells.toInt())
    fun step(maxCells:Int,stop:StopSignal):Int{
        if(state!=JobState.RUNNING)return 0
        var processed=0
        repeat(maxCells.coerceIn(1,32)){
            if(state!=JobState.RUNNING)return@repeat
            if(stop.isCancelled()){state=JobState.CANCELLED;return@repeat}
            if(completed==cells.size){state=JobState.COMPLETE;return@repeat}
            val east=config.minEastM+(completed%estimate.columns)*config.gridM
            val north=config.maxNorthM-(completed/estimate.columns)*config.gridM
            val value=engine.predict(east.toDouble(),north.toDouble(),config,stop)
            if(value.state==CellState.CANCELLED){state=JobState.CANCELLED;return@repeat}
            cells[completed]=value;completed++;processed++
        }
        if(completed==cells.size && state==JobState.RUNNING)state=JobState.COMPLETE
        return processed
    }
    fun csv():String=buildString{
        append("east_m,north_m,state,loss_db,samples\n")
        for(i in cells.indices){val c=cells[i];append("${config.minEastM+(i%estimate.columns)*config.gridM},${config.maxNorthM-(i/estimate.columns)*config.gridM},${c?.state?:CellState.NOT_COMPUTED},${c?.lossDb?:""},${c?.sampleCount?:0}\n")}
    }
}
