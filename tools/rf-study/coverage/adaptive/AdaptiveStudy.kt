package study
import kotlin.math.*
import org.meshtastic.kp1812.*

enum class AccuracyState { QUALIFIED, ACCURACY_UNRESOLVED, THRESHOLD_UNRESOLVED, WORK_LIMIT, MISSING_TERRAIN, INVALID_INPUT, INVALID_MODEL, CANCELLED }
class AdaptiveResult(val state:AccuracyState,val lossDb:Double?,val diagnosticLastLossDb:Double?,val samplesUsed:Int,val finalSpacingM:Int,val passes:Int,val lastDeltaDb:Double?)
internal class Attempt(val state:AccuracyState,val loss:Double?)

// Experimental numerical-convergence policy, not an assurance of outdoor RF accuracy.
internal fun converge(distance:Double,budget:Int,thresholdLoss:Double,stop:StopSignal,evaluate:(Int)->Attempt):AdaptiveResult {
    var used=0;var previous:Double?=null;var last:Double?=null;var stable=0;var spacing=0;var passes=0;var delta:Double?=null
    fun result(state:AccuracyState)=AdaptiveResult(state,if(state==AccuracyState.QUALIFIED)last else null,last,used,spacing,passes,delta)
    if(!distance.isFinite() || distance !in 250.0..100000.001 || budget !in 1..250000 || !thresholdLoss.isFinite())return result(AccuracyState.INVALID_INPUT)
    for(step in listOf(30,10,5,2,1)){
        if(stop.isCancelled())return result(AccuracyState.CANCELLED)
        val count=ceil(distance/step).toInt()+1
        if(count>budget-used)return result(AccuracyState.WORK_LIMIT)
        used+=count;spacing=step;passes++
        val attempt=evaluate(step)
        if(stop.isCancelled())return result(AccuracyState.CANCELLED)
        if(attempt.state!=AccuracyState.QUALIFIED)return result(attempt.state)
        val value=attempt.loss
        if(value==null || !value.isFinite())return result(AccuracyState.INVALID_MODEL)
        last=value
        val old=previous
        if(old!=null){delta=abs(value-old);stable=if(delta!!<=.1)stable+1 else 0}
        previous=value
        // Never accept the coarse pass or just one apparently stable comparison.
        // A 0.25 dB guard around the sensitivity boundary avoids confident threshold labels.
        if(step<=5 && stable>=2 && abs(value-thresholdLoss)>.25)return result(AccuracyState.QUALIFIED)
    }
    return result(if(last!=null && abs(last!!-thresholdLoss)<=.25)AccuracyState.THRESHOLD_UNRESOLVED else AccuracyState.ACCURACY_UNRESOLVED)
}

class AdaptiveSampler(private val engine:WideEngine){
    fun dense(east:Double,north:Double,step:Int,stop:StopSignal):CellResult {
        fun unknown(s:CellState)=CellResult(s,null,0)
        if(stop.isCancelled())return unknown(CellState.CANCELLED)
        if(!engine.valid() || !east.isFinite() || !north.isFinite() || step !in listOf(1,2,5,10,30))return unknown(CellState.INVALID_INPUT)
        val distance=canonicalDistanceM(hypot(east,north))
        if(distance !in 250.0..100000.001)return unknown(CellState.INVALID_INPUT)
        val count=ceil(distance/step).toInt()+1
        if(count>100002)return unknown(CellState.INVALID_INPUT)
        val bearing=atan2(east,north);val distances=DoubleArray(count);val heights=DoubleArray(count)
        for(i in 0 until count){
            if(i%32==0 && stop.isCancelled())return unknown(CellState.CANCELLED)
            val metres=distance*i/(count-1);val p=engine.pointAt(metres,bearing)
            distances[i]=metres/1000;heights[i]=engine.elevationAt(p.latitude,p.longitude)?:return unknown(CellState.MISSING_TERRAIN)
        }
        if(stop.isCancelled())return unknown(CellState.CANCELLED)
        return try {
            val middle=engine.pointAt(distance/2,bearing)
            val loss=P1812.predict(TerrainPath(distances,heights,DoubleArray(count),IntArray(count){4}),.915,30.0,1.5,50.0,middle.latitude,
                Polarization.VERTICAL,Atmosphere(45.0,325.0,500.0,500.0),50.0,0.0).basicTransmissionLossDb
            when {stop.isCancelled()->unknown(CellState.CANCELLED);!loss.isFinite()->unknown(CellState.INVALID_MODEL);else->CellResult(CellState.PREDICTED,loss,count)}
        }catch(_:IllegalArgumentException){unknown(CellState.INVALID_MODEL)}
    }
    fun predict(east:Double,north:Double,budget:Int,thresholdLoss:Double,stop:StopSignal):AdaptiveResult =
        converge(canonicalDistanceM(hypot(east,north)),budget,thresholdLoss,stop){step->
            val r=dense(east,north,step,stop)
            val state=when(r.state){CellState.PREDICTED->AccuracyState.QUALIFIED;CellState.MISSING_TERRAIN->AccuracyState.MISSING_TERRAIN;CellState.CANCELLED->AccuracyState.CANCELLED;CellState.INVALID_INPUT->AccuracyState.INVALID_INPUT;else->AccuracyState.INVALID_MODEL}
            Attempt(state,r.lossDb)
        }
}

object AdaptiveSuite {
    fun run(engine:WideEngine):String=buildString {
        append("set,bearing_deg,distance_m,state,loss_db,diagnostic_last_loss_db,samples_used,final_spacing_m,passes,last_delta_db,reference_state,reference_loss_db,reference_samples,budget,threshold_loss_db\n")
        val sampler=AdaptiveSampler(engine);val rad=PI/180
        fun record(set:String,bearing:Double,distance:Double,budget:Int,threshold:Double){
            val east=distance*sin(bearing*rad);val north=distance*cos(bearing*rad)
            val reference=sampler.dense(east,north,1,NeverStop())
            val r=sampler.predict(east,north,budget,threshold,NeverStop())
            append("$set,$bearing,$distance,${r.state},${r.lossDb?:""},${r.diagnosticLastLossDb?:""},${r.samplesUsed},${r.finalSpacingM},${r.passes},${r.lastDeltaDb?:""},${reference.state},${reference.lossDb?:""},${reference.sampleCount},$budget,$threshold\n")
        }
        for(b in 0 until 360 step 15)for(d in listOf(1000,2000,5000,10000,20000,50000,100000))record("original",b.toDouble(),d.toDouble(),200000,152.0)
        for(b in 0 until 360 step 15)for(d in listOf(750,3000,15000,35000,75000,99000))record("holdout",b+7.5,d.toDouble(),200000,152.0)
        for(east in 10000..14000 step 200)for(north in 0..4000 step 200)record("refinement",atan2(east.toDouble(),north.toDouble())/rad,hypot(east.toDouble(),north.toDouble()),200000,152.0)
        record("limited",240.0,100000.0,10000,152.0)
        val ref=sampler.dense(1000.0,0.0,1,NeverStop()).lossDb
        if(ref!=null)record("threshold",90.0,1000.0,200000,ref)
    }
}
