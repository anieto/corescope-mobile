#!/usr/bin/env python3
"""Generate isolated builds, referring to pinned upstream sources without editing or vendoring them."""
import argparse
from pathlib import Path
import subprocess
p = argparse.ArgumentParser(); p.add_argument('upstream', type=Path); p.add_argument('output', type=Path)
a = p.parse_args(); upstream = a.upstream.resolve(); base = a.output.resolve(); own = Path(__file__).resolve().parent
pin = 'a911b6ecd58f8c16ac8ae4becf6bdbba1784e5a9'
assert subprocess.check_output(['git','-C',str(upstream),'rev-parse','HEAD'],text=True).strip() == pin
assert not subprocess.check_output(['git','-C',str(upstream),'diff','HEAD','--','src/commonMain'],text=True).strip()
for flavor in ['native', 'jvm']:
    dest = base/flavor; dest.mkdir(parents=True,exist_ok=True)
    (dest/'settings.gradle.kts').write_text('pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\nrootProject.name = "coverage-study"\n')
    (dest/'gradle.properties').write_text('org.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g\n')
    if flavor == 'native':
        script = f'''plugins {{ kotlin("multiplatform") version "2.4.20" }}
repositories {{ mavenCentral() }}
kotlin {{
    iosArm64 {{ binaries.framework {{ baseName = "CoverageStudy"; isStatic = true }} }}
    iosSimulatorArm64 {{ binaries.framework {{ baseName = "CoverageStudy"; isStatic = true }} }}
    sourceSets {{ commonMain {{ kotlin.srcDir("{upstream}/src/commonMain/kotlin"); kotlin.srcDir("src/commonMain/kotlin") }} }}
}}
'''
        src = dest/'src/commonMain/kotlin'; src.mkdir(parents=True,exist_ok=True)
        (src/'CoverageStudy.kt').write_text((own/'CoverageStudy.kt').read_text())
    else:
        script = f'''plugins {{ kotlin("jvm") version "2.2.20" }}
repositories {{ mavenCentral() }}
kotlin {{ jvmToolchain(17); sourceSets.main {{ kotlin.srcDir("{upstream}/src/commonMain/kotlin") }} }}
dependencies {{ testImplementation(kotlin("test")) }}
'''
        src = dest/'src/main/kotlin'; src.mkdir(parents=True,exist_ok=True)
        for name in ['CoverageStudy.kt','CoverageRunner.kt']: (src/name).write_text((own/name).read_text())
        tests=dest/'src/test/kotlin';tests.mkdir(parents=True,exist_ok=True)
        (tests/'CoverageStudyTest.kt').write_text((own/'CoverageStudyTest.kt').read_text())
    (dest/'build.gradle.kts').write_text(script)
print(base)
