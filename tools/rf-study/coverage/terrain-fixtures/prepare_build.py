#!/usr/bin/env python3
import argparse
from pathlib import Path
import shutil
import subprocess
import sys

p=argparse.ArgumentParser()
p.add_argument('upstream',type=Path)
p.add_argument('output',type=Path)
a=p.parse_args()
own=Path(__file__).resolve().parent
subprocess.run([sys.executable,str(own.parent/'bounded/prepare_build.py'),str(a.upstream),str(a.output)],check=True)
for flavor in ['native','jvm']:
    folder=a.output/flavor/('src/commonMain/kotlin' if flavor=='native' else 'src/main/kotlin')
    shutil.copy(own/'TerrainFixtures.kt',folder/'TerrainFixtures.kt')
    if flavor=='jvm':
        shutil.copy(own/'TerrainFixtureTests.kt',a.output/flavor/'src/test/kotlin/TerrainFixtureTests.kt')
        runner=folder/'BoundedRunner.kt'
        s=runner.read_text()
        begin=s.index('            val metrics=StringBuilder')
        end=s.rindex('\n        }')
        s=s[:begin]+'''            File(output,"synthetic.csv").writeText(TerrainFixtures.syntheticCsv())
            File(output,"terrain.csv").writeText(TerrainFixtures.terrainCsv(source,width,height,north,west,ppd))
'''+s[end:]
        runner.write_text(s)
swift=(own.parent/'bounded/BoundedRunner.swift').read_text()
swift=swift[:swift.index('var runs:')]+'''try TerrainFixtures.shared.syntheticCsv().write(to: output.appendingPathComponent("synthetic.csv"), atomically: true, encoding: .utf8)
try TerrainFixtures.shared.terrainCsv(source: source, width: source.width, height: source.height, north: source.north, west: source.west, ppd: source.ppd).write(to: output.appendingPathComponent("terrain.csv"), atomically: true, encoding: .utf8)
'''
(a.output/'TerrainRunner.swift').write_text(swift)
