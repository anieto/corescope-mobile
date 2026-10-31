#!/usr/bin/env python3
import argparse,subprocess,sys
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('upstream',type=Path);p.add_argument('output',type=Path);a=p.parse_args();own=Path(__file__).resolve().parent
subprocess.run([sys.executable,str(own.parent/'prepare_build.py'),str(a.upstream),str(a.output)],check=True)
for flavor in ['native','jvm']:
 folder=a.output/flavor/('src/commonMain/kotlin' if flavor=='native' else 'src/main/kotlin')
 (folder/'WideStudy.kt').write_text((own/'WideStudy.kt').read_text())
 if flavor=='jvm':
  (folder/'WideRunner.kt').write_text((own/'WideRunner.kt').read_text())
  (a.output/flavor/'src/test/kotlin/WideTests.kt').write_text((own/'WideTests.kt').read_text())
