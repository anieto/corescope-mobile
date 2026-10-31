#!/usr/bin/env python3
import argparse
from pathlib import Path
import shutil
import subprocess
import sys
p=argparse.ArgumentParser();p.add_argument('upstream',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
own=Path(__file__).resolve().parent
subprocess.run([sys.executable,str(own.parent/'terrain-fixtures/prepare_build.py'),str(a.upstream),str(a.output)],check=True)
for flavor in ['native','jvm']:
    folder=a.output/flavor/('src/commonMain/kotlin' if flavor=='native' else 'src/main/kotlin')
    shutil.copy(own/'FeatureStudy.kt',folder/'FeatureStudy.kt')
    if flavor=='jvm':
        shutil.copy(own/'FeatureTests.kt',a.output/flavor/'src/test/kotlin/FeatureTests.kt')
        runner=folder/'BoundedRunner.kt';runner.write_text(runner.read_text().replace('TerrainFixtures.','FeatureSuite.'))
runner=a.output/'TerrainRunner.swift';runner.write_text(runner.read_text().replace('TerrainFixtures.shared','FeatureSuite.shared'))
