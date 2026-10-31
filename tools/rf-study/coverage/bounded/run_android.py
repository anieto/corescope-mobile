#!/usr/bin/env python3
import argparse
import hashlib
import subprocess
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('study', type=Path)
p.add_argument('terrain', type=Path)
p.add_argument('serial')
p.add_argument('output', type=Path)
a = p.parse_args()
root = a.study.resolve()
a.output.mkdir(parents=True, exist_ok=True)
sdk = Path.home()/'Library/Android/sdk'
stdlib = next((Path.home()/'.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.20').glob('*/kotlin-stdlib-2.2.20.jar'))
jar = next((root/'jvm/build/libs').glob('*.jar'))
subprocess.run([str(sdk/'build-tools/36.0.0/d8'), '--min-api', '26', '--output', str(root/'bounded-dex.jar'), str(jar), str(stdlib)], check=True, timeout=120)
adb = [str(sdk/'platform-tools/adb'), '-s', a.serial]
def run(*args, timeout=120):
    return subprocess.run([*adb, *args], check=True, capture_output=True, text=True, timeout=timeout)
remote = '/data/local/tmp/nodescope-bounded-study'
run('shell', 'mkdir', '-p', remote+'/results')
run('push', str(root/'bounded-dex.jar'), remote+'/bounded.jar')
run('push', str(a.terrain), remote+'/terrain.bin')
assert run('shell', 'sha256sum', remote+'/terrain.bin').stdout.split()[0] == hashlib.sha256(a.terrain.read_bytes()).hexdigest()
r = run('shell', f'CLASSPATH={remote}/bounded.jar app_process / study.BoundedRunner {remote}/terrain.bin {remote}/results', timeout=600)
(a.output/'stdout.txt').write_text(r.stdout+r.stderr)
for name in ['r20_small.csv', 'r20_large.csv', 'refine_small.csv', 'refine_large.csv', 'r100.csv', 'limited.csv', 'cancel.csv', 'metrics.json']:
    run('pull', remote+'/results/'+name, str(a.output/name))
print((a.output/'metrics.json').read_text())
