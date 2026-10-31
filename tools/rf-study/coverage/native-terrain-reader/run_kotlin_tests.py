#!/usr/bin/env python3
"""Offline host test runner using already-cached compiler jars; writes only to output."""
import argparse
import os
from pathlib import Path
import subprocess

p = argparse.ArgumentParser()
p.add_argument('fixtures', type=Path)
p.add_argument('output', type=Path)
p.add_argument('--cache', type=Path, default=Path.home()/'.gradle/caches/modules-2/files-2.1')
p.add_argument('--java', default='java')
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=True)

def jar(group, name, version):
    matches = list((a.cache/group/name/version).glob(f'*/{name}-{version}.jar'))
    if len(matches) != 1:
        raise SystemExit(f'Missing/ambiguous cached dependency: {group}:{name}:{version}')
    return str(matches[0])

stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.20')
compiler = [jar('org.jetbrains.kotlin', name, '2.2.20') for name in
            ['kotlin-compiler-embeddable', 'kotlin-script-runtime', 'kotlin-daemon-embeddable']]
compiler += [stdlib, jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
             jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
             jar('org.jetbrains', 'annotations', '13.0')]
runtime = [stdlib, jar('org.jetbrains.kotlin', 'kotlin-test', '2.2.20'),
           jar('org.jetbrains.kotlin', 'kotlin-test-junit', '2.2.20'),
           jar('junit', 'junit', '4.13.2'), jar('org.hamcrest', 'hamcrest-core', '1.3')]
source = Path(__file__).resolve().parent/'kotlin/src'
output = a.output/'reader-tests.jar'
subprocess.run([a.java, '-cp', os.pathsep.join(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', os.pathsep.join(runtime),
                '-d', str(output), *map(str, sorted(source.rglob('*.kt')))], check=True)
env = os.environ | {'TERRAIN_READER_FIXTURES': str(a.fixtures.resolve())}
subprocess.run([a.java, '-cp', os.pathsep.join([str(output), *runtime]),
                'org.junit.runner.JUnitCore', 'study.ReaderTest'], env=env, check=True)
