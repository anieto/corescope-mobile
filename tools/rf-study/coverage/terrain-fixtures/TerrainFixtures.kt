package study

import kotlin.math.*
import org.meshtastic.kp1812.*

object TerrainFixtures {
    private fun height(kind: String, x: Double, distance: Double, halfWidth: Double, offset: Double): Double {
        val base = 200.0
        return when(kind) {
            "flat" -> base
            "rolling" -> base + 35*sin(x/190) + 12*sin(x/43)
            "valley" -> base + 120*abs(x-distance/2)/(distance/2)
            "ridge" -> base + 100*max(0.0,1-abs(x-(distance*.4+offset))/halfWidth)
            else -> error("Unknown terrain fixture")
        }
    }
    internal fun loss(kind: String, distance: Double, halfWidth: Double, offset: Double, step: Double): Double {
        val count=ceil(distance/step).toInt()+1
        val d=DoubleArray(count){distance*it/(count-1)/1000}
        val h=DoubleArray(count){height(kind,d[it]*1000,distance,halfWidth,offset)}
        return P1812.predict(TerrainPath(d,h,DoubleArray(count),IntArray(count){4}),.915,30.0,1.5,50.0,51.0,
            Polarization.VERTICAL,Atmosphere(45.0,325.0,500.0,500.0),50.0,0.0).basicTransmissionLossDb
    }
    fun syntheticCsv(): String=buildString {
        append("kind,distance_m,half_width_m,offset_m,state,loss_db,reference_loss_db,finer_reference_loss_db,reference_step_m,final_spacing_m,samples_used\n")
        fun record(kind:String,distance:Double,width:Double,offset:Double) {
            val reference=loss(kind,distance,width,offset,.25)
            val finer=loss(kind,distance,width,offset,.125)
            val r=converge(distance,200000,152.0,NeverStop()) { step ->
                Attempt(AccuracyState.QUALIFIED,loss(kind,distance,width,offset,step.toDouble()))
            }
            append("$kind,$distance,$width,$offset,${r.state},${r.lossDb?:""},$reference,$finer,0.25,${r.finalSpacingM},${r.samplesUsed}\n")
        }
        for(distance in listOf(1000.0,10000.0)) {
            for(kind in listOf("flat","rolling","valley"))record(kind,distance,0.0,0.0)
            for(width in listOf(.75,2.0,5.0,15.0,30.0))for(offset in listOf(0.0,1.0,2.0,3.0,4.0))record("ridge",distance,width,offset)
        }
    }

    fun terrainCsv(source:TerrainRows,width:Int,height:Int,north:Double,west:Double,ppd:Int):String=buildString {
        append("site,latitude,longitude,bearing_deg,distance_m,state,loss_db,reference_state,reference_loss_db,final_spacing_m,samples_used\n")
        val names=listOf("eastern_plains","foothills","western_mountains")
        val latitudes=listOf(51.1,51.0,51.4)
        val longitudes=listOf(-113.9,-114.7,-116.0)
        for(site in names.indices) {
            val cache=BoundedRaster(source,width,height,2*1024*1024,NeverStop())
            val engine=WideEngine(cache,width,height,north,west,ppd,latitudes[site],longitudes[site])
            val sampler=AdaptiveSampler(engine)
            for(bearing in 0 until 360 step 30)for(distance in listOf(1000,5000,10000,20000)) {
                val angle=bearing*PI/180;val east=distance*sin(angle);val n=distance*cos(angle)
                val reference=sampler.dense(east,n,1,NeverStop())
                val r=sampler.predict(east,n,200000,152.0,NeverStop())
                append("${names[site]},${latitudes[site]},${longitudes[site]},$bearing,$distance,${r.state},${r.lossDb?:""},${reference.state},${reference.lossDb?:""},${r.finalSpacingM},${r.samplesUsed}\n")
            }
            cache.clear()
        }
    }
}
