# Adaptive sampling experiment

Isolated extension of the [wide study](../wide/README.md), sharing its pinned engine, original
terrain and parameters. Production apps do not link this code. See the
[results and limitations](../../../../docs/node-planner/adaptive-sampling-study.md).

Prepare the wide terrain fixture first, then from the repository root:

```sh
python3 tools/rf-study/coverage/adaptive/prepare_build.py /tmp/nodescope-kp1812-study /tmp/nodescope-coverage-adaptive
/tmp/nodescope-kp1812-study/gradlew -p /tmp/nodescope-coverage-adaptive/jvm test jar --console=plain
/tmp/nodescope-kp1812-study/gradlew -p /tmp/nodescope-coverage-adaptive/native linkReleaseFrameworkIosSimulatorArm64 linkReleaseFrameworkIosArm64 --console=plain
python3 tools/rf-study/coverage/adaptive/run_android.py /tmp/nodescope-coverage-adaptive /tmp/nodescope-coverage-wide/data emulator-5554 /tmp/nodescope-coverage-adaptive/results/android
xcrun --sdk iphonesimulator swiftc -O -module-cache-path /tmp/nodescope-coverage-swift-cache -target arm64-apple-ios17.0-simulator -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" -F /tmp/nodescope-coverage-adaptive/native/build/bin/iosSimulatorArm64/releaseFramework -framework CoverageStudy /tmp/nodescope-coverage-adaptive/AdaptiveRunner.swift -o /tmp/nodescope-coverage-adaptive/adaptive-ios
xcrun simctl spawn SIMULATOR_UUID /tmp/nodescope-coverage-adaptive/adaptive-ios /tmp/nodescope-coverage-wide/data/terrain.bin /tmp/nodescope-coverage-adaptive/results/ios 50.96 -115.16 adaptive
python3 tools/rf-study/coverage/adaptive/analyze.py /tmp/nodescope-coverage-adaptive/results docs/node-planner/feasibility/adaptive-sampling
```

Start the existing mobile runtimes before execution. Use sequential runs for future timing
comparisons; recorded validation runs overlapped. Recorded iOS runtime: iPhone 18 Pro / iOS 27,
device UUID `20981F8B-4A70-4F7E-A376-BAE2462A278E`. Android: Pixel_10 arm64 emulator,
`google/sdk_gphone16k_arm64/emu64a16k:17/CP41.260831.007/16416850:user/dev-keys`.

The generator copies historical wrappers without editing their sources. The adaptive branch
executes 755 reference/adaptive pairs and a live cancellation fixture. Six added policy tests
exercise false early convergence, exhausted work, threshold ambiguity, unstable estimates,
terminal cancellation/nodata and invalid numeric output. The analyzer rejects platform-state
or sample-count differences, published losses for unresolved states, accuracy/threshold failures
and incorrect budget/threshold fixtures. A failed accuracy check remains in summary.json and
returns nonzero. Whole-grid scheduling and terrain cache eviction are not implemented here.
