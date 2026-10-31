# Bounded adaptive coverage jobs and disk terrain cache

Isolated experiment; no production app dependency or UI changes. The historical adaptive and
wide studies remain unchanged. This stage adds an aggregate sample-work budget, terminal job
states, and a shared LRU cache of 128 × 128 signed-height blocks. Platform drivers use positional
disk reads instead of mapping or copying the full 153 MB terrain raster.

## Reproduce

Use the pinned engine and wide terrain prepared in [the wide experiment](../wide/README.md).
Run from the repository root with the mobile runtimes started. Execute platform runs sequentially:

```sh
python3 tools/rf-study/coverage/bounded/prepare_build.py /tmp/nodescope-kp1812-study /tmp/nodescope-coverage-bounded
/tmp/nodescope-kp1812-study/gradlew -p /tmp/nodescope-coverage-bounded/jvm test jar --console=plain
/tmp/nodescope-kp1812-study/gradlew -p /tmp/nodescope-coverage-bounded/native linkReleaseFrameworkIosSimulatorArm64 linkReleaseFrameworkIosArm64 --console=plain
python3 tools/rf-study/coverage/bounded/run_android.py /tmp/nodescope-coverage-bounded /tmp/nodescope-coverage-wide/data/terrain.bin emulator-5554 /tmp/nodescope-coverage-bounded/results/android
xcrun --sdk iphonesimulator swiftc -O -module-cache-path /tmp/nodescope-coverage-swift-cache -target arm64-apple-ios17.0-simulator -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" -F /tmp/nodescope-coverage-bounded/native/build/bin/iosSimulatorArm64/releaseFramework -framework CoverageStudy tools/rf-study/coverage/bounded/BoundedRunner.swift -o /tmp/nodescope-coverage-bounded/bounded-ios
xcrun simctl spawn SIMULATOR_UUID /tmp/nodescope-coverage-bounded/bounded-ios /tmp/nodescope-coverage-wide/data/terrain.bin /tmp/nodescope-coverage-bounded/results/ios
python3 tools/rf-study/coverage/bounded/analyze.py /tmp/nodescope-coverage-bounded/results docs/node-planner/feasibility/adaptive-sampling docs/node-planner/feasibility/bounded-coverage
```

Runtimes used: iPhone 18 Pro / iOS 27 simulator, UUID
`20981F8B-4A70-4F7E-A376-BAE2462A278E`; Pixel_10 arm64 Android 17 emulator,
`google/sdk_gphone16k_arm64/emu64a16k:17/CP41.260831.007/16416850:user/dev-keys`.
The iOS device framework compiles but has not been run on a phone.

## Contracts

- Preflight rejects invalid extents, more than 60,000 rectangular cells, and a budget below the
  minimum needed for three qualifying passes at every eligible receiver. The API permits at most
  150 million charged sample evaluations and 250,000 per path; executed normal jobs use 15 million
  and 200,000. Bounds are experimental, not shipping defaults or wall-clock guarantees.
- Each cell receives the smaller of its path cap and the remaining job budget. The existing
  adaptive policy reserves a complete pass before execution. Charged work never exceeds the
  total budget, including in-flight work that is cancelled and never published.
- Exhaustion stops a job with `WORK_LIMIT`; subsequent cells retain `NOT_COMPUTED`. Per-path
  accuracy/threshold/work failures have no published loss. Cancellation and other terminal job
  states cannot resume. The cache and job are owned by a single worker; the cancellation signal
  alone is shared across threads. Integration must still discard results from superseded jobs.
- The cache evicts before allocation and caps reachable decoded tile payload, including the
  in-flight tile. A tile is 32 KiB; partial edge tiles are padded with nodata. Counters exclude
  object/map overhead, driver row buffers, temporary row arrays, uncollected garbage, profile
  arrays, propagation internals and OS file caching. `clear()` releases cache ownership, not a
  synchronous RSS reduction. Driver row buffers and returned rows are at most 256 bytes each.
- Truncated/failed row reads do not install a partial tile. They map to missing terrain; cancellation
  during loading discards that tile and is terminal for the job. Missing data is never flat zero
  elevation. This is a cache over an already validated local mosaic, not an HTTP tile repository.

Eight new unit tests cover seams/partial tiles/signed data, LRU order, failed/truncated reads,
nodata, cancellation during loading, invalid sizes, preflight bounds and terminal cancellation.
Six real-terrain runs per platform cover 20 km grids, 4 × 4 km refinement, a coarse 100 km grid,
and aggregate budget exhaustion. The analyzer requires exact cache-size invariance, platform
states/work/cache counters, numerical parity, and agreement with 441 historical refinement cells.
It archives compressed output and checksums without overwriting prior experiments.

See the [report and remaining gates](../../../../docs/node-planner/bounded-coverage-study.md).
