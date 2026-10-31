#!/usr/bin/env python3
import argparse,subprocess,sys
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('upstream',type=Path);p.add_argument('output',type=Path);a=p.parse_args();own=Path(__file__).resolve().parent;wide=own.parent/'wide'
subprocess.run([sys.executable,str(wide/'prepare_build.py'),str(a.upstream),str(a.output)],check=True)
for flavor in ['native','jvm']:
 folder=a.output/flavor/('src/commonMain/kotlin' if flavor=='native' else 'src/main/kotlin')
 (folder/'AdaptiveStudy.kt').write_text((own/'AdaptiveStudy.kt').read_text())
 if flavor=='jvm':
  (a.output/flavor/'src/test/kotlin/AdaptiveTests.kt').write_text((own/'AdaptiveTests.kt').read_text())
  source=(wide/'WideRunner.kt').read_text()
  source=source.replace('        check(engine.valid())','''        check(engine.valid())
        if(args.size > 4 && args[4] == "adaptive") {
            val start=System.nanoTime()
            File(output,"adaptive.csv").writeText(AdaptiveSuite.run(engine))
            val elapsed=(System.nanoTime()-start)/1e6
            val rss=File("/proc/self/status").readLines().first { it.startsWith("VmHWM:") }.trim().split(Regex("\\\\s+"))[1].toLong()*1024
            File(output,"adaptive-metrics.json").writeText("{\\"elapsed_ms\\":$elapsed,\\"process_peak_rss_bytes\\":$rss}")
            val signal=AtomicBoolean(false);val ready=AtomicBoolean(false);val requested=AtomicLong(0)
            val thread=Thread { ready.set(true);Thread.sleep(1);requested.set(System.nanoTime());signal.set(true) };thread.start()
            while(!ready.get())Thread.yield()
            val cancelled=AdaptiveSampler(engine).predict(-86602.540378,-50000.0,200000,152.0,object:StopSignal{override fun isCancelled()=signal.get()})
            val ended=System.nanoTime();thread.join()
            check(cancelled.state==AccuracyState.CANCELLED && cancelled.lossDb==null)
            File(output,"cancel.txt").writeText("${cancelled.state},${(ended-requested.get())/1e6}")
            handle.close();return
        }''')
  (folder/'WideRunner.kt').write_text(source)
source=(wide/'WideRunner.swift').read_text().replace('precondition(engine.valid())','''precondition(engine.valid())
if CommandLine.arguments.count > 5 && CommandLine.arguments[5] == "adaptive" {
    let start = now()
    try AdaptiveSuite.shared.run(engine: engine).write(to: output.appendingPathComponent("adaptive.csv"), atomically: true, encoding: .utf8)
    let elapsed = Double(now() - start) / 1e6
    var usage = rusage(); getrusage(RUSAGE_SELF, &usage)
    try JSONSerialization.data(withJSONObject: ["elapsed_ms": elapsed, "process_peak_rss_bytes": usage.ru_maxrss])
        .write(to: output.appendingPathComponent("adaptive-metrics.json"))
    let signal = Stop()
    let ready = DispatchSemaphore(value: 0)
    DispatchQueue.global().async { ready.signal(); Thread.sleep(forTimeInterval: 0.001); signal.cancel() }
    ready.wait()
    let cancelled = AdaptiveSampler(engine: engine).predict(east: -86602.540378, north: -50000, budget: 200000, thresholdLoss: 152, stop: signal)
    let ended = now()
    precondition(cancelled.state == .cancelled && cancelled.lossDb == nil)
    try "CANCELLED,\\(Double(ended - signal.requestTime()) / 1e6)".write(to: output.appendingPathComponent("cancel.txt"), atomically: true, encoding: .utf8)
    exit(0)
}''')
(a.output/'AdaptiveRunner.swift').write_text(source)
