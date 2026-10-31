#!/usr/bin/env python3
import argparse,json,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('study',type=Path);p.add_argument('terrain',type=Path);p.add_argument('serial');p.add_argument('output',type=Path);a=p.parse_args()
root=a.study.resolve();a.output.mkdir(parents=True,exist_ok=True);sdk=Path.home()/'Library/Android/sdk'
stdlib=next((Path.home()/'.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.20').glob('*/kotlin-stdlib-2.2.20.jar'))
jar=next((root/'jvm/build/libs').glob('*.jar'))
subprocess.run([str(sdk/'build-tools/36.0.0/d8'),'--min-api','26','--output',str(root/'adaptive-dex.jar'),str(jar),str(stdlib)],check=True,timeout=120)
adb=[str(sdk/'platform-tools/adb'),'-s',a.serial]
def run(*args,timeout=120):return subprocess.run([*adb,*args],check=True,capture_output=True,text=True,timeout=timeout)
remote='/data/local/tmp/nodescope-adaptive-study'
run('shell','mkdir','-p',remote+'/results');run('push',str(root/'adaptive-dex.jar'),remote+'/adaptive.jar');run('push',str(a.terrain/'terrain.bin'),remote+'/terrain.bin')
manifest=json.loads((a.terrain/'manifest.json').read_text());assert run('shell','sha256sum',remote+'/terrain.bin').stdout.split()[0]==manifest['sha256']
site=manifest['site']
r=run('shell',f"CLASSPATH={remote}/adaptive.jar app_process / study.WideRunner {remote}/terrain.bin {remote}/results {site['latitude']} {site['longitude']} adaptive",timeout=600)
(a.output/'stdout.txt').write_text(r.stdout+r.stderr)
for name in ['adaptive.csv','adaptive-metrics.json','cancel.txt']:run('pull',remote+'/results/'+name,str(a.output/name))
print((a.output/'adaptive-metrics.json').read_text())
