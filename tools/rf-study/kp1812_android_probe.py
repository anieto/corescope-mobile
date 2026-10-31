#!/usr/bin/env python3
"""Dex and execute pinned upstream JVM tests on an already booted Android device.

Run upstream jvmTest first. Requires SDK build-tools 36.0.0, adb and populated
Gradle dependency caches. This does not compile NodeScope or test its Kotlin consumer.
"""
import argparse
import json
from pathlib import Path
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("checkout", type=Path)
parser.add_argument("serial")
parser.add_argument("output", type=Path)
args = parser.parse_args()
root = args.checkout.resolve()
pin = "a911b6ecd58f8c16ac8ae4becf6bdbba1784e5a9"
assert subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip() == pin
with zipfile.ZipFile(root / "probe-tests.jar", "w") as archive:
    classes = root / "build/classes/kotlin/jvm/test"
    for path in classes.rglob("*.class"):
        archive.write(path, path.relative_to(classes))
cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
dependencies = [
    ("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20"),
    ("org.jetbrains.kotlin", "kotlin-test", "2.4.20"),
    ("org.jetbrains.kotlin", "kotlin-test-junit", "2.4.20"),
    ("junit", "junit", "4.13.2"), ("org.hamcrest", "hamcrest-core", "1.3"),
]
jars = [next(cache.glob(f"{group}/{name}/{version}/*/{name}-{version}.jar"))
        for group, name, version in dependencies]
sdk = Path.home() / "Library/Android/sdk"
subprocess.run([str(sdk / "build-tools/36.0.0/d8"), "--min-api", "26", "--output",
                str(root / "probe-dex.jar"), str(root / "probe-tests.jar"),
                str(root / "build/libs/kp1812-jvm-0.2.0.jar"), *map(str, jars)], check=True)
adb = [str(sdk / "platform-tools/adb"), "-s", args.serial]
remote = "/data/local/tmp/nodescope-kp1812-probe.jar"
subprocess.run([*adb, "push", str(root / "probe-dex.jar"), remote], check=True)
result = subprocess.run([*adb, "shell", f"CLASSPATH={remote} app_process / "
    "org.junit.runner.JUnitCore org.meshtastic.kp1812.EndToEndTest "
    "org.meshtastic.kp1812.ReferenceConformanceTest"], capture_output=True, text=True, timeout=120)
record = {"source_commit": pin, "serial": args.serial, "exit_code": result.returncode,
          "stdout": result.stdout, "stderr": result.stderr,
          "device": subprocess.check_output([*adb, "shell", "getprop ro.build.fingerprint"], text=True).strip()}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(record, indent=2) + "\n")
print(result.stdout)
assert result.returncode == 0 and "OK (14 tests)" in result.stdout, record
