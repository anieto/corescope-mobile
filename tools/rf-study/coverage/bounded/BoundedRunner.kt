package study

import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object BoundedRunner {
    @JvmStatic fun main(args:Array<String>) {
        val output=File(args[1]).apply{mkdirs()}
        RandomAccessFile(args[0],"r").use { file ->
            val size=file.length();check(size in 32..200000032)
            check(file.readInt()==0x43535632)
            val width=file.readInt();val height=file.readInt();check(width.toLong()*height*2+32==size)
            val north=file.readInt()/1e6;val west=file.readInt()/1e6;val ppd=file.readInt()
            val source=object:TerrainRows {
                override fun read(start:Int,count:Int):ShortArray? {
                    if(start<0 || count !in 1..128 || start.toLong()+count>width.toLong()*height)return null
                    return try {
                        file.seek(32+start.toLong()*2)
                        val bytes=ByteArray(count*2);file.readFully(bytes)
                        ShortArray(count){((bytes[it*2].toInt() shl 8) or (bytes[it*2+1].toInt() and 255)).toShort()}
                    }catch(_:IOException){null}
                }
            }
            val metrics=StringBuilder("{\"runs\":[")
            for(i in 0 until BoundedSuite.count()) {
                val start=System.nanoTime()
                val run=BoundedSuite.run(i,source,width,height,north,west,ppd,50.96,-115.16)
                val elapsed=(System.nanoTime()-start)/1e6
                File(output,"${run.name}.csv").writeText(run.csv)
                if(i>0)metrics.append(',')
                metrics.append("{\"name\":\"${run.name}\",\"elapsed_ms\":$elapsed,\"result\":${run.metrics}}")
            }
            val flag=AtomicBoolean(false);val requested=AtomicLong(0);val ready=AtomicBoolean(false)
            val stop=object:StopSignal{override fun isCancelled()=flag.get()}
            val cache=BoundedRaster(source,width,height,131072,stop)
            val engine=WideEngine(cache,width,height,north,west,ppd,50.96,-115.16)
            val job=BoundedJob(engine,BoundedSuite.config(4))
            val thread=Thread{ready.set(true);Thread.sleep(10);requested.set(System.nanoTime());flag.set(true)}
            thread.start();while(!ready.get())Thread.yield()
            while(job.state==BoundedState.RUNNING)job.step(8,stop)
            val end=System.nanoTime();thread.join()
            check(job.state==BoundedState.CANCELLED)
            val before=job.csv();check(job.step(8,NeverStop())==0 && before==job.csv())
            File(output,"cancel.csv").writeText(before)
            cache.clear();check(cache.residentBytes==0)
            val rss=Regex("VmHWM:\\s+(\\d+)").find(File("/proc/self/status").readText())!!.groupValues[1].toLong()*1024
            metrics.append("],\"process_peak_rss_bytes\":$rss,\"cancel_latency_ms\":${(end-requested.get())/1e6},\"cancel_state\":\"${job.state}\",\"cancel_samples\":${job.samplesUsed},\"cancel_completed\":${job.completed},\"cache_released\":true}")
            File(output,"metrics.json").writeText(metrics.toString());println(metrics)
        }
    }
}
