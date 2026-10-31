#!/usr/bin/env python3
import argparse
from pathlib import Path
import shutil
import subprocess
import sys

p = argparse.ArgumentParser()
p.add_argument('upstream', type=Path)
p.add_argument('output', type=Path)
a = p.parse_args()
own = Path(__file__).resolve().parent
subprocess.run([sys.executable, str(own.parent/'adaptive/prepare_build.py'), str(a.upstream), str(a.output)], check=True)
for flavor in ['native', 'jvm']:
    folder = a.output/flavor/('src/commonMain/kotlin' if flavor == 'native' else 'src/main/kotlin')
    shutil.copy(own/'BoundedStudy.kt', folder/'BoundedStudy.kt')
    if flavor == 'jvm':
        shutil.copy(own/'BoundedRunner.kt', folder/'BoundedRunner.kt')
        shutil.copy(own/'BoundedTests.kt', a.output/flavor/'src/test/kotlin/BoundedTests.kt')
