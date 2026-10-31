package study

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object CoverageRunner {
    @JvmStatic fun main(args: Array<String>) {
        val output = File(args[1]).apply { mkdirs() }
        val start = System.nanoTime()
        val loaded = CoverageEngine.load(File(args[0]).readBytes())
        check(loaded.engine != null) { "Terrain load: ${loaded.state}" }
        val engine = loaded.engine!!
        val decodeMs = (System.nanoTime() - start) / 1e6
        check(CoverageEngine.load(byteArrayOf(1)).state == CellState.INVALID_INPUT)
        check(engine.predict(Double.NaN, -114.3, 1.5, NeverStop()).state == CellState.INVALID_INPUT)
        check(engine.predict(51.16, -114.3, -1.0, NeverStop()).state == CellState.INVALID_INPUT)
        val metrics = StringBuilder("{\"decode_ms\":$decodeMs,\"runs\":[")
        for ((number, scenario) in listOf("clean", "gap", "cancel").withIndex()) {
            val source = if (scenario == "gap") engine.withGap() else engine
            val job = source.newJob()
            val flag = AtomicBoolean(false); val requested = AtomicLong(0)
            val signal = object : StopSignal { override fun isCancelled() = flag.get() }
            val cancelThread = if (scenario == "cancel") Thread {
                Thread.sleep(20); requested.set(System.nanoTime()); flag.set(true)
            }.also { it.start() } else null
            val begin = System.nanoTime(); var maxStepNs = 0L
            while (job.state == JobState.RUNNING) {
                val before = System.nanoTime(); job.step(16, signal)
                maxStepNs = maxOf(maxStepNs, System.nanoTime() - before)
            }
            val end = System.nanoTime()
            cancelThread?.join()
            val completed = job.completed
            if (scenario == "cancel") {
                check(job.state == JobState.CANCELLED && completed < job.total)
                job.step(16, NeverStop()); check(job.completed == completed)
            } else check(job.state == JobState.COMPLETE)
            File(output, "$scenario.csv").writeText(job.csv())
            if (number > 0) metrics.append(',')
            metrics.append("{\"scenario\":\"$scenario\",\"state\":\"${job.state}\",\"completed\":$completed,\"compute_ms\":${(end - begin) / 1e6},\"max_step_ms\":${maxStepNs / 1e6},\"cancel_latency_ms\":${if (requested.get() == 0L) "null" else (end - requested.get()) / 1e6}}")
        }
        val links = StringBuilder("fixture,east_m,north_m,rx_height_m,latitude,longitude,state,loss_db,samples\n")
        for ((name, east, north, height) in listOf(
            listOf("off_grid", "1375", "1825", "1.5"), listOf("high_receiver", "1375", "1825", "10"),
            listOf("west", "-2225", "-1625", "1.5"), listOf("near", "10", "20", "1.5"),
            listOf("gap_crossing", "3500", "0", "1.5"))) {
            val point = engine.pointAt(east.toDouble(), north.toDouble())
            val source = if (name == "gap_crossing") engine.withGap() else engine
            val result = source.predict(point.latitude, point.longitude, height.toDouble(), NeverStop())
            links.append("$name,$east,$north,$height,${point.latitude},${point.longitude},${result.state},${result.lossDb ?: ""},${result.sampleCount}\n")
        }
        File(output, "links.csv").writeText(links.toString())
        val status = File("/proc/self/status")
        val rss = if (status.exists()) Regex("VmHWM:\\s+(\\d+)").find(status.readText())?.groupValues?.get(1)?.toLong()?.times(1024) else null
        metrics.append("],\"process_peak_rss_bytes\":${rss ?: "null"},\"boundary_checks_passed\":true}")
        File(output, "metrics.json").writeText(metrics.toString())
        println(metrics)
    }
}
