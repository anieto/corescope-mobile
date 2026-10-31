#!/usr/bin/env python3
import argparse
from pathlib import Path
import shutil
import subprocess
import sys
p=argparse.ArgumentParser();p.add_argument('upstream',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
own=Path(__file__).resolve().parent
subprocess.run([sys.executable,str(own.parent/'feature-profile/prepare_build.py'),str(a.upstream),str(a.output)],check=True)
for flavor in ['native','jvm']:
    folder=a.output/flavor/('src/commonMain/kotlin' if flavor=='native' else 'src/main/kotlin')
    shutil.copy(own/'FeatureGrid.kt',folder/'FeatureGrid.kt')
    if flavor=='jvm':
        shutil.copy(own/'FeatureGridTests.kt',a.output/flavor/'src/test/kotlin/FeatureGridTests.kt')
        runner=(own.parent/'bounded/BoundedRunner.kt').read_text().replace('BoundedSuite','FeatureGridSuite').replace('BoundedJob','FeatureGridJob')
        runner=runner.replace('\\"cancel_samples\\":${job.samplesUsed}', '\\"cancel_samples\\":${job.samplesUsed},\\"cancel_geometry\\":${job.geometryUsed}')
        (folder/'BoundedRunner.kt').write_text(runner)
runner=(own.parent/'bounded/BoundedRunner.swift').read_text().replace('BoundedSuite','FeatureGridSuite').replace('BoundedJob','FeatureGridJob')
runner=runner.replace('"cancel_samples": job.samplesUsed,','"cancel_samples": job.samplesUsed, "cancel_geometry": job.geometryUsed,')
(a.output/'FeatureGridRunner.swift').write_text(runner)
