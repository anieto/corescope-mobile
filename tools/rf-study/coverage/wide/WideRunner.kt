package study
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object WideRunner {
    @JvmStatic fun main(args:Array<String>){
        val output=File(args[1]).apply{mkdirs()}
        val begin=System.nanoTime()
        val handle=RandomAccessFile(args[0],"r")
        val size=handle.length();check(size in 32..200000032)
        val map=handle.channel.map(FileChannel.MapMode.READ_ONLY,0,size).order(ByteOrder.BIG_ENDIAN)
        check(map.getInt(0)==0x43535632)
        val w=map.getInt(4);val h=map.getInt(8);check(w.toLong()*h*2+32==size)
        val engine=WideEngine(object:RasterReader{override fun value(index:Int)=map.getShort(32+2*index).toInt()},w,h,map.getInt(12)/1e6,map.getInt(16)/1e6,map.getInt(20),args[2].toDouble(),args[3].toDouble())
        check(engine.valid())
        if(args.size > 4 && args[4] == "diagnostic") {
            File(output,"diagnostic.csv").writeText(engine.diagnosticCsv());handle.close();return
        }
        val metrics=StringBuilder("{\"map_setup_ms\":${(System.nanoTime()-begin)/1e6},\"runs\":[")
        for(index in 0 until WideScenarios.count()){
            val name=WideScenarios.name(index);val config=WideScenarios.config(index);val loaded=engine.makeJob(config)
            val job=loaded.job;check(job!=null){loaded.estimate.reason}
            var maximum=0L;val start=System.nanoTime()
            while(job.state==JobState.RUNNING){val t=System.nanoTime();job.step(16,NeverStop());maximum=maxOf(maximum,System.nanoTime()-t)}
            val ms=(System.nanoTime()-start)/1e6;check(job.state==JobState.COMPLETE)
            File(output,"$name.csv").writeText(job.csv())
            if(index>0)metrics.append(',')
            metrics.append("{\"name\":\"$name\",\"compute_ms\":$ms,\"max_step_ms\":${maximum/1e6},\"cells\":${loaded.estimate.cells},\"sample_work_bound\":${loaded.estimate.sampleWorkUpperBound}}")
            println("$name $ms ms")
        }
        val rejected=engine.makeJob(WideConfig(100000,200,30,-100000,100000,-100000,100000))
        check(rejected.job==null && rejected.estimate.reason=="cell_budget")
        val sensitiveStart=System.nanoTime();File(output,"sensitivity.csv").writeText(engine.sensitivityCsv())
        val sensitiveMs=(System.nanoTime()-sensitiveStart)/1e6
        val signal=AtomicBoolean(false);val request=AtomicLong(0)
        val job=engine.makeJob(WideScenarios.config(5)).job!!
        val thread=Thread{Thread.sleep(20);request.set(System.nanoTime());signal.set(true)};thread.start()
        while(job.state==JobState.RUNNING)job.step(16,object:StopSignal{override fun isCancelled()=signal.get()})
        val end=System.nanoTime();thread.join();check(job.state==JobState.CANCELLED)
        val completed=job.completed;job.step(16,NeverStop());check(job.completed==completed)
        val rss=Regex("VmHWM:\\s+(\\d+)").find(File("/proc/self/status").readText())!!.groupValues[1].toLong()*1024
        metrics.append("],\"sensitivity_ms\":$sensitiveMs,\"process_peak_rss_bytes\":$rss,\"cancel_latency_ms\":${(end-request.get())/1e6},\"cancel_completed\":$completed,\"oversized_rejected\":true}")
        File(output,"metrics.json").writeText(metrics.toString());println(metrics)
        handle.close()
    }
}
