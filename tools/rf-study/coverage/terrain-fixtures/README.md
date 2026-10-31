# Terrain diversity and adversarial ridge fixtures

This isolated study challenges the historical adaptive policy without changing it or production
code. It runs 144 real-terrain paths from three sites in the existing pinned mosaic and 56 analytic
profiles (flat, rolling, valley and triangular ridge). It is an accuracy gate, not a performance run.

The analytic profiles enter the same propagation kernel directly. They do **not** pass through the
one-arcsecond raster and must not be described as measured terrain or detail recovered from that
DEM. Ridge half-widths are 0.75, 2, 5, 15 and 30 m, with five phase offsets and 1/10 km path lengths.
References use uniform 0.25 m profiles, checked again at 0.125 m. Real-terrain references retain the
prior 1 m interpolation and use a 2 MiB decoded-block cache.

## Reproduce

Use the engine and raster from [the wide study](../wide/README.md). Start the mobile runtimes,
then run sequentially from the repository root:

```sh
python3 tools/rf-study/coverage/terrain-fixtures/prepare_build.py /tmp/nodescope-kp1812-study /tmp/nodescope-coverage-fixtures
/tmp/nodescope-kp1812-study/gradlew -p /tmp/nodescope-coverage-fixtures/jvm test jar --console=plain
/tmp/nodescope-kp1812-study/gradlew -p /tmp/nodescope-coverage-fixtures/native linkReleaseFrameworkIosSimulatorArm64 linkReleaseFrameworkIosArm64 --console=plain
python3 tools/rf-study/coverage/terrain-fixtures/run_android.py /tmp/nodescope-coverage-fixtures /tmp/nodescope-coverage-wide/data/terrain.bin emulator-5554 /tmp/nodescope-coverage-fixtures/results/android
xcrun --sdk iphonesimulator swiftc -O -module-cache-path /tmp/nodescope-coverage-swift-cache -target arm64-apple-ios17.0-simulator -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" -F /tmp/nodescope-coverage-fixtures/native/build/bin/iosSimulatorArm64/releaseFramework -framework CoverageStudy /tmp/nodescope-coverage-fixtures/TerrainRunner.swift -o /tmp/nodescope-coverage-fixtures/terrain-ios
xcrun simctl spawn SIMULATOR_UUID /tmp/nodescope-coverage-fixtures/terrain-ios /tmp/nodescope-coverage-wide/data/terrain.bin /tmp/nodescope-coverage-fixtures/results/ios
python3 tools/rf-study/coverage/terrain-fixtures/analyze.py /tmp/nodescope-coverage-fixtures/results docs/node-planner/feasibility/terrain-fixtures
```

The analyzer intentionally **returns exit code 1 for the current policy** after saving failures.
Do not weaken the 0.25 dB accuracy target or treat passing platform parity/unit tests as passing
scientific qualification. The known-ridge unit regression documents the old defect; future policy
work should add tests that reject or correctly resolve it while retaining this historical evidence.
The source generator reuses bounded disk readers but executes only these fixture suites.

Runtime identities match the [bounded study](../bounded/README.md). Radio parameters and the pinned
engine are unchanged. The real-site names are descriptive labels within one Canadian mosaic, not
independently verified global terrain classifications. This study does not cover buildings, foliage,
coastal propagation, new climates or an independent RF implementation.
