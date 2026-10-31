package study

import kotlin.math.*
import org.meshtastic.kp1812.*

internal class FeatureStop(val reason: AccuracyState): RuntimeException()
internal class FeatureWork(val sampleLimit: Int, val geometryLimit: Int, val stop: StopSignal) {
    var samples=0; var geometry=0
    fun check() { if(stop.isCancelled())throw FeatureStop(AccuracyState.CANCELLED) }
    fun samples(count:Int) { check();if(count>sampleLimit-samples)throw FeatureStop(AccuracyState.WORK_LIMIT);samples+=count }
    fun geometry() { check();if(geometry==geometryLimit)throw FeatureStop(AccuracyState.WORK_LIMIT);geometry++ }
}
class FeatureResult(val state: AccuracyState, val lossDb: Double?, val samples: Int,
    val geometry: Int, val featureCount: Int, val spacingM: Int)

internal fun featurePositions(distance:Double,step:Int,features:DoubleArray):DoubleArray {
    val count=ceil(distance/step).toInt()
    // Millimetre keys make ordering/deduplication stable across mobile runtimes.
    val keys=mutableSetOf<Long>()
    for(i in 0..count)keys.add((distance*i/count*1000).roundToLong())
    for(x in features)keys.add((x*1000).roundToLong())
    return keys.sorted().map{it/1000.0}.toDoubleArray()
}

internal fun featurePredict(distance:Double,latitude:Double,work:FeatureWork,
    discover:()->DoubleArray,height:(Double)->Double?):FeatureResult {
    var features=doubleArrayOf();var spacing=0
    fun result(state:AccuracyState,loss:Double?=null)=FeatureResult(state,loss,work.samples,work.geometry,features.size,spacing)
    if(!distance.isFinite() || distance !in 250.0..100000.001 || !latitude.isFinite() ||
        work.sampleLimit !in 1..250000 || work.geometryLimit !in 1..200000)return result(AccuracyState.INVALID_INPUT)
    try {
        work.check();features=discover()
        if(features.size>50000 || features.any{!it.isFinite() || it<0 || it>distance})return result(AccuracyState.INVALID_INPUT)
        var previous:Double?=null;var stable=0;var last:Double?=null
        for(step in listOf(30,10,5,2,1)) {
            work.check();spacing=step
            val positions=featurePositions(distance,step,features)
            work.samples(positions.size)
            val d=DoubleArray(positions.size);val h=DoubleArray(positions.size)
            for(i in positions.indices) {
                if(i%32==0)work.check()
                d[i]=positions[i]/1000
                h[i]=height(positions[i])?:throw FeatureStop(AccuracyState.MISSING_TERRAIN)
                if(!h[i].isFinite())throw FeatureStop(AccuracyState.INVALID_MODEL)
            }
            work.check()
            val loss=P1812.predict(TerrainPath(d,h,DoubleArray(d.size),IntArray(d.size){4}),.915,30.0,1.5,
                50.0,latitude,Polarization.VERTICAL,Atmosphere(45.0,325.0,500.0,500.0),50.0,0.0).basicTransmissionLossDb
            work.check()
            if(!loss.isFinite())return result(AccuracyState.INVALID_MODEL)
            val old=previous
            if(old!=null)stable=if(abs(loss-old)<=.1)stable+1 else 0
            last=loss;previous=loss
            if(step<=5 && stable>=2 && abs(loss-152)>.25)return result(AccuracyState.QUALIFIED,loss)
        }
        return result(if(last!=null && abs(last-152)<=.25)AccuracyState.THRESHOLD_UNRESOLVED else AccuracyState.ACCURACY_UNRESOLVED)
    }catch(e:FeatureStop){return result(e.reason)}
    catch(_:IllegalArgumentException){return result(AccuracyState.INVALID_MODEL)}
}

/** Preserve DEM cell crossings and within-cell interpolated extrema along the geodesic. */
class FeatureSampler(private val engine:WideEngine) {
    fun predict(east:Double,north:Double,sampleBudget:Int,geometryBudget:Int,stop:StopSignal):FeatureResult {
        val distance=canonicalDistanceM(hypot(east,north));val bearing=atan2(east,north)
        val work=FeatureWork(sampleBudget,geometryBudget,stop)
        if(!engine.valid() || !east.isFinite() || !north.isFinite())
            return FeatureResult(AccuracyState.INVALID_INPUT,null,0,0,0,0)
        fun point(m:Double):Point { work.geometry();return engine.pointAt(m,bearing) }
        fun coordinate(m:Double,axis:Int):Double {
            val p=point(m)
            return if(axis==0)(p.longitude-engine.west)*engine.ppd else (engine.north-p.latitude)*engine.ppd
        }
        fun elevation(m:Double):Double? {
            // Evaluation positions are budgeted separately from geometric root-search operations.
            val p=engine.pointAt(m,bearing)
            return engine.elevationAt(p.latitude,p.longitude)
        }
        fun discover():DoubleArray {
            val boundaries=mutableListOf(0.0,distance)
            // Latitude is monotone between great-circle latitude extrema. Longitude is monotone
            // within this study's non-polar, non-antimeridian domain.
            val lat=engine.siteLatitude*PI/180
            val critical=atan2(cos(lat)*cos(bearing),sin(lat))
            val splits=mutableListOf(0.0,distance)
            for(k in -1..1) {
                val metres=(critical+k*PI)*6371008.8
                if(metres>0 && metres<distance)splits.add(metres)
            }
            splits.sort()
            for(axis in 0..1)for(i in 0 until splits.size-1) {
                val start=splits[i];val end=splits[i+1]
                val a=coordinate(start,axis);val b=coordinate(end,axis)
                val bound=if(axis==0)engine.width-1.0 else engine.height-1.0
                if(min(a,b)<-1e-8 || max(a,b)>bound+1e-8)throw FeatureStop(AccuracyState.MISSING_TERRAIN)
                for(line in ceil(min(a,b)).toInt()..floor(max(a,b)).toInt()) {
                    var low=start;var high=end
                    // Search to a tenth of a millimetre before millimetre canonicalization;
                    // fixed iteration counts overspend the geometry budget on short brackets.
                    while(high-low>.0001) {
                        val mid=(low+high)/2;val value=coordinate(mid,axis)
                        if((value<line)==(b>a))low=mid else high=mid
                    }
                    boundaries.add(((low+high)/2*1000).roundToLong()/1000.0)
                }
            }
            boundaries.addAll(splits)
            val crossings=boundaries.map{(it*1000).roundToLong()}.distinct().sorted().map{it/1000.0}
            val features=crossings.toMutableList()
            fun measured(m:Double):Double {work.samples(1);return elevation(m)?:throw FeatureStop(AccuracyState.MISSING_TERRAIN)}
            for(i in 0 until crossings.size-1) {
                work.check()
                val a=crossings[i];val b=crossings[i+1]
                if(b-a<.002)continue
                val h0=measured(a);val hm=measured((a+b)/2);val h1=measured(b)
                // Bilinear terrain along a straight cell segment is quadratic. The geodesic
                // approximation is checked at both quarter points; do not accept a poor fit.
                val q=2*(h1+h0-2*hm);val linear=h1-h0-q
                for(t in listOf(.25,.75)) {
                    val actual=measured(a+(b-a)*t)
                    if(abs(actual-(h0+linear*t+q*t*t))>.01)throw FeatureStop(AccuracyState.ACCURACY_UNRESOLVED)
                }
                if(abs(q)>1e-7) {
                    val t=-linear/(2*q)
                    if(t>0 && t<1)features.add(((a+(b-a)*t)*1000).roundToLong()/1000.0)
                }
            }
            return features.distinct().sorted().toDoubleArray()
        }
        val mid=engine.pointAt(distance/2,bearing)
        return featurePredict(distance,mid.latitude,work,::discover,::elevation)
    }
}

object FeatureSuite {
    private fun height(kind:String,x:Double,distance:Double,width:Double,offset:Double):Double = when(kind) {
        "flat"->200.0
        "rolling"->200+35*sin(x/190)+12*sin(x/43)
        "valley"->200+120*abs(x-distance/2)/(distance/2)
        else->200+100*max(0.0,1-abs(x-(distance*.4+offset))/width)
    }
    internal fun analytic(kind:String,distance:Double,width:Double,offset:Double,budget:Int=250000,stop:StopSignal=NeverStop()):FeatureResult {
        val work=FeatureWork(budget,200000,stop)
        val center=distance*.4+offset
        val features=when(kind) {
            "ridge"->doubleArrayOf(0.0,center-width,center,center+width,distance)
            "valley"->doubleArrayOf(0.0,distance/2,distance)
            else->doubleArrayOf(0.0,distance)
        }
        return featurePredict(distance,51.0,work,{features}){height(kind,it,distance,width,offset)}
    }
    fun syntheticCsv():String=buildString {
        append("kind,distance_m,half_width_m,offset_m,state,loss_db,reference_loss_db,features,samples,geometry,spacing_m\n")
        fun record(kind:String,distance:Double,width:Double,offset:Double) {
            val ref=TerrainFixtures.loss(kind,distance,width,offset,.125)
            val r=analytic(kind,distance,width,offset)
            append("$kind,$distance,$width,$offset,${r.state},${r.lossDb?:""},$ref,${r.featureCount},${r.samples},${r.geometry},${r.spacingM}\n")
        }
        for(d in listOf(1000.0,10000.0)) {
            for(kind in listOf("flat","rolling","valley"))record(kind,d,0.0,0.0)
            for(w in listOf(.75,2.0,5.0,15.0,30.0))for(o in listOf(0.0,1.0,2.0,3.0,4.0))record("ridge",d,w,o)
        }
    }
    fun terrainCsv(source:TerrainRows,width:Int,height:Int,north:Double,west:Double,ppd:Int):String=buildString {
        append("site,bearing_deg,distance_m,state,loss_db,reference_state,reference_loss_db,features,samples,geometry,spacing_m\n")
        val latitudes=listOf(51.1,51.0,51.4,50.96)
        val longitudes=listOf(-113.9,-114.7,-116.0,-115.16)
        for(site in latitudes.indices) {
            val cache=BoundedRaster(source,width,height,2097152,NeverStop())
            val engine=WideEngine(cache,width,height,north,west,ppd,latitudes[site],longitudes[site])
            fun record(bearing:Double,distance:Double) {
                val east=distance*sin(bearing*PI/180);val n=distance*cos(bearing*PI/180)
                val ref=AdaptiveSampler(engine).dense(east,n,1,NeverStop())
                val r=FeatureSampler(engine).predict(east,n,250000,200000,NeverStop())
                append("$site,$bearing,$distance,${r.state},${r.lossDb?:""},${ref.state},${ref.lossDb?:""},${r.featureCount},${r.samples},${r.geometry},${r.spacingM}\n")
            }
            if(site<3)for(b in 0 until 360 step 30)for(d in listOf(1000,5000,10000,20000))record(b.toDouble(),d.toDouble())
            else {
                for(b in 0 until 360 step 15)for(d in listOf(1000,2000,5000,10000,20000,50000,100000))record(b.toDouble(),d.toDouble())
                for(b in 0 until 360 step 15)for(d in listOf(750,3000,15000,35000,75000,99000))record(b+7.5,d.toDouble())
                for(e in 10000..14000 step 200)for(n in 0..4000 step 200)record(atan2(e.toDouble(),n.toDouble())*180/PI,hypot(e.toDouble(),n.toDouble()))
            }
            cache.clear()
        }
    }
}
