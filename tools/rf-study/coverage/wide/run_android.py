#!/usr/bin/env python3
import argparse,json,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('study',type=Path);p.add_argument('serial');p.add_argument('output',type=Path);p.add_argument('--diagnostic',action='store_true');a=p.parse_args()
root=a.study.resolve();a.output.mkdir(parents=True,exist_ok=True);sdk=Path.home()/'Library/Android/sdk'
stdlib=next((Path.home()/'.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.20').glob('*/kotlin-stdlib-2.2.20.jar'))
jar=next((root/'jvm/build/libs').glob('*.jar'))
subprocess.run([str(sdk/'build-tools/36.0.0/d8'),'--min-api','26','--output',str(root/'wide-dex.jar'),str(jar),str(stdlib)],check=True,timeout=120)
adb=[str(sdk/'platform-tools/adb'),'-s',a.serial]
def run(*args,timeout=120):return subprocess.run([*adb,*args],check=True,capture_output=True,text=True,timeout=timeout)
remote='/data/local/tmp/nodescope-wide-study'
run('shell','mkdir','-p',remote+'/results');run('push',str(root/'wide-dex.jar'),remote+'/wide.jar');run('push',str(root/'data/terrain.bin'),remote+'/terrain.bin')
manifest=json.loads((root/'data/manifest.json').read_text())
assert run('shell','sha256sum',remote+'/terrain.bin').stdout.split()[0]==manifest['sha256']
site=manifest['site']
r=run('shell',f"CLASSPATH={remote}/wide.jar app_process / study.WideRunner {remote}/terrain.bin {remote}/results {site['latitude']} {site['longitude']} {'diagnostic' if a.diagnostic else ''}",timeout=600)
(a.output/'stdout.txt').write_text(r.stdout+r.stderr)
if a.diagnostic:
 run('pull',remote+'/results/diagnostic.csv',str(a.output/'diagnostic.csv'));print(r.stdout);raise SystemExit(0)
for name in ['r10_g200','r20_g500','r50_g1000','r100_g2000','r50_g500','r100_g1000','refine50','reference50','sensitivity']:
 run('pull',remote+'/results/'+name+'.csv',str(a.output/(name+'.csv')))
run('pull',remote+'/results/metrics.json',str(a.output/'metrics.json'))
(a.output/'device.txt').write_text(run('shell','getprop ro.build.fingerprint').stdout)
print(r.stdout)
