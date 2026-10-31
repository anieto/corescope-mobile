# Isolated RF engine probes

Latest follow-up: [adjustable-radius and refinement experiment](coverage/wide/README.md), including
100 km studies, full-resolution mapped terrain, and the long-path sampling issue found in testing.

The subsequent [real-terrain coverage prototype](coverage/README.md) combines the kp1812 wrapper,
shared terrain sampling and actual iOS/Android runtime runs. It includes a downloadable pinned
terrain fixture, missing-data/cancellation tests and a reproducible scientific comparison.

This experiment does not change or link either NodeScope app. Requires Python 3, Git, Apple clang,
Xcode SDKs and (for Android) an NDK and a booted arm64 emulator/device. The scripts use only Python's
standard library. Upstream source/terrain remain in a temporary checkout.

```sh
git clone --recurse-submodules https://github.com/meshtastic/meshtastic-site-planner.git /tmp/nodescope-site-planner-study
git -C /tmp/nodescope-site-planner-study checkout 481e5a482f10fc6956a98aed39aacdb59c3fb6ec
git -C /tmp/nodescope-site-planner-study submodule update --init --recursive
```

Expected SPLAT revision: `08f06f56470a8361a5609503a6b1cb02f07f48dc`. Probe runners assert both pins.
Inspect upstream build code before running it. From this repository root:

```sh
STUDY_ROOT=/tmp/nodescope-site-planner-study
mkdir -p "$STUDY_ROOT/engine/build"
clang++ -O2 -std=c++11 -dynamiclib "$STUDY_ROOT/engine/driver.cpp" "$STUDY_ROOT/splat/itwom3.0.cpp" -o "$STUDY_ROOT/engine/build/libstudy.dylib"
python3 tools/rf-study/site_planner_probe.py "$STUDY_ROOT" "$STUDY_ROOT/engine/build/libstudy.dylib" /tmp/nodescope-rf-probe.json
python3 tools/rf-study/boundary_probe.py "$STUDY_ROOT/engine/build/libstudy.dylib"
```

Host runner loads the supplied terrain fixtures, measures 16-radial batches and compares outputs
against upstream's compressed golden rasters. Peak RSS is the entire Python process, not just the
engine. Boundary runner intentionally omits terrain and supplies a negative height; its results
are adversarial behavior checks, never predictions suitable for the UI.

## iOS builds and execution

```sh
xcrun --sdk iphoneos clang++ -O2 -std=c++11 -arch arm64 -miphoneos-version-min=17.0 -c "$STUDY_ROOT/engine/driver.cpp" -o "$STUDY_ROOT/engine/build/driver-ios.o"
xcrun --sdk iphoneos clang++ -O2 -std=c++11 -arch arm64 -miphoneos-version-min=17.0 -c "$STUDY_ROOT/splat/itwom3.0.cpp" -o "$STUDY_ROOT/engine/build/itm-ios.o"
xcrun libtool -static -o "$STUDY_ROOT/engine/build/libstudy-ios.a" "$STUDY_ROOT/engine/build/driver-ios.o" "$STUDY_ROOT/engine/build/itm-ios.o"
xcrun --sdk iphonesimulator clang++ -O2 -std=c++11 -target arm64-apple-ios17.0-simulator "$STUDY_ROOT/engine/driver.cpp" "$STUDY_ROOT/engine/native/main.cpp" "$STUDY_ROOT/splat/itwom3.0.cpp" -o "$STUDY_ROOT/engine/build/study-ios-sim"
# Replace SIMULATOR_UUID with an available, booted device UUID.
python3 tools/rf-study/mobile_cli_probe.py "$STUDY_ROOT" ios SIMULATOR_UUID /tmp/nodescope-rf-ios.json
```

This uses simctl spawn on the isolated CLI, not application deployment. The iOS archive is a
compile check; simulator execution does not establish physical-device performance.

## Android builds and execution

Set `STUDY_NDK` to the NDK root. The recorded experiment used the official r27d Darwin archive from
`https://dl.google.com/android/repository/android-ndk-r27d-darwin.zip` extracted under `/tmp`.

```sh
STUDY_CXX="$STUDY_NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang++"
"$STUDY_CXX" -O2 -std=c++11 -Wl,-z,max-page-size=16384 -static-libstdc++ "$STUDY_ROOT/engine/driver.cpp" "$STUDY_ROOT/engine/native/main.cpp" "$STUDY_ROOT/splat/itwom3.0.cpp" -o "$STUDY_ROOT/engine/build/study-android"
python3 tools/rf-study/mobile_cli_probe.py "$STUDY_ROOT" android emulator-5554 /tmp/nodescope-rf-android.json
```

The runner uses adb at `~/Library/Android/sdk/platform-tools/adb`; adapt that path on other hosts.
It pushes only the experiment binary and fixture pages into `/data/local/tmp/nodescope-rf-study`.
A separate sensitivity build adds `-ffp-contract=off` and writes a different output JSON. A shared
library compile probe can use `-shared -fPIC` instead of the CLI main.cpp; linking/loading that
library from a production JNI wrapper is a separate task.

All fixtures intentionally preserve upstream's legacy power/height conversions for golden parity.
Use independently reviewed SI conversions in a future production wrapper. Runs do not download
live terrain or test the map UI. Mobile wall timing includes launcher/file I/O; startup contention
and simulator virtualization make it unsuitable as a battery, device-speed or thermal benchmark.

Review results even when scripts exit successfully: differences and warning counts are evidence,
not automatically passing tests. No production numerical tolerance is implied. Do not regenerate
upstream goldens to conceal a mismatch. No dependency binaries, upstream source or terrain datasets
are added to this repository.

## kp1812 study

Pin and build in temporary storage; no app dependency is needed:

```sh
git clone https://github.com/meshtastic/kp1812.git /tmp/nodescope-kp1812-study
git -C /tmp/nodescope-kp1812-study checkout a911b6ecd58f8c16ac8ae4becf6bdbba1784e5a9
cd /tmp/nodescope-kp1812-study
./gradlew jvmTest macosArm64Test iosSimulatorArm64Test linkReleaseFrameworkIosArm64 linkReleaseFrameworkIosSimulatorArm64 --console=plain
```

These commands populate Gradle/Kotlin caches and may download toolchains. Inspect upstream build
instructions first. Test XML is under `build/test-results`. The 14 test methods include 63
end-to-end cases and component reference checks; test-method count is not fixture count.
To reproduce the diagnostic boundary probe, copy our `Kp1812BoundaryProbe.kt` into the checkout's
`src/commonTest/kotlin/org/meshtastic/kp1812/` and rerun `jvmTest macosArm64Test`. It prints behavior,
not assertions that invalid values are safe. Never edit upstream generated fixture files.

From the NodeScope repository root, with a booted Android emulator:

```sh
python3 tools/rf-study/kp1812_android_probe.py /tmp/nodescope-kp1812-study emulator-5554 /tmp/kp1812-android.json
```

This runs the upstream reference tests via D8 and app_process, without installing NodeScope.
It deliberately does not imply compatibility with NodeScope's Kotlin compiler. For that check,
create a separate temporary Kotlin JVM Gradle project with plugin version 2.2.20, `mavenCentral()`,
`jvmToolchain(17)`, a file dependency on `build/libs/kp1812-jvm-0.2.0.jar`, and a source file that
constructs `TerrainPath`. `compileKotlin` fails with incompatible metadata. To check the compatible
source route, remove the jar dependency, copy unchanged upstream commonMain into `src/main/kotlin`
and commonTest into `src/test/kotlin`, add `testImplementation(kotlin("test"))`, and run `test`.
No compiler metadata-check bypass was used. Preserve this as an experiment until distribution and
the production artifact strategy are resolved.

Swift consumer smoke test (substitute a booted simulator UUID in the final command):

```sh
xcrun --sdk iphonesimulator swiftc -O -module-cache-path /tmp/nodescope-kp1812-swift-cache -target arm64-apple-ios17.0-simulator -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" -F /tmp/nodescope-kp1812-study/build/bin/iosSimulatorArm64/releaseFramework -framework Kp1812 tools/rf-study/kp1812_swift_probe.swift -o /tmp/nodescope-kp1812-study/swift-probe
xcrun simctl spawn SIMULATOR_UUID /tmp/nodescope-kp1812-study/swift-probe
```

That benchmark reuses a synthetic profile and omits terrain acquisition/sampling. It does not
qualify physical-device performance, cancellation, memory or the packaged apps. Results and
decision: [kp1812 feasibility](../../docs/node-planner/kp1812-feasibility.md).
