#!/usr/bin/env python3
"""Run isolated JVM-17 coverage code on a booted Android runtime, without installing the app."""
import argparse
from pathlib import Path
import subprocess
p=argparse.ArgumentParser();p.add_argument('study',type=Path);p.add_argument('serial');p.add_argument('output',type=Path)
a=p.parse_args();root=a.study.resolve();a.output.mkdir(parents=True,exist_ok=True)
sdk=Path.home()/'Library/Android/sdk'
stdlib=next((Path.home()/'.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.20').glob('*/kotlin-stdlib-2.2.20.jar'))
jar=next((root/'jvm/build/libs').glob('*.jar'))
subprocess.run([str(sdk/'build-tools/36.0.0/d8'),'--min-api','26','--output',str(root/'coverage-dex.jar'),str(jar),str(stdlib)],check=True,timeout=120)
adb=[str(sdk/'platform-tools/adb'),'-s',a.serial]
def run(*args):return subprocess.run([*adb,*args],check=True,capture_output=True,text=True,timeout=120)
remote='/data/local/tmp/nodescope-coverage-study'
run('shell','mkdir','-p',remote+'/results')
run('push',str(root/'coverage-dex.jar'),remote+'/coverage.jar')
run('push',str(root/'data/terrain.bin'),remote+'/terrain.bin')
r=run('shell',f'CLASSPATH={remote}/coverage.jar app_process / study.CoverageRunner {remote}/terrain.bin {remote}/results')
(a.output/'stdout.txt').write_text(r.stdout+r.stderr)
for name in ['clean.csv','gap.csv','cancel.csv','links.csv','metrics.json']:
    run('pull',remote+'/results/'+name,str(a.output/name))
(a.output/'device.txt').write_text(run('shell','getprop ro.build.fingerprint').stdout)
print(r.stdout)
