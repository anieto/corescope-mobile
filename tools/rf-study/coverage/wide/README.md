# Adjustable-radius, refinement and terrain-sampling experiment

This extends the isolated prototype without editing its original scripts, input fixture or recorded
results. It is not linked into either production app. The pinned kp1812 source and base wrapper are
shared with the preceding experiment. Kotlin owns geodesics, terrain interpolation, scientific
parameters, preflight and jobs; drivers only supply read-only memory-mapped signed raster access.

## Reproduce

From the repository root, using the existing pinned kp1812 checkout:

```sh
python3 tools/rf-study/coverage/wide/prepare.py /tmp/nodescope-coverage-wide/data
python3 tools/rf-study/coverage/wide/verify_terrain.py /tmp/nodescope-coverage-wide/data /tmp/terrain-checks.json
python3 tools/rf-study/coverage/wide/prepare_build.py /tmp/nodescope-kp1812-study /tmp/nodescope-coverage-wide
/tmp/nodescope-kp1812-study/gradlew -p /tmp/nodescope-coverage-wide/jvm test jar --console=plain
/tmp/nodescope-kp1812-study/gradlew -p /tmp/nodescope-coverage-wide/native linkReleaseFrameworkIosSimulatorArm64 linkReleaseFrameworkIosArm64 --console=plain
```

Preparation downloads eight digest-pinned Mapzen/AWS Skadi tiles (~84 MB), checks HGT dimensions,
and writes a full-resolution 153 MB mosaic without decimation. Memory mapping avoids a full heap
copy; it does not make resident pages free. `terrain-pins.json` pins the downloaded tile bytes.
The manifest records the mosaic hash, source tiles, resolution, datum, bounds and site. Duplicate
borders use the north/east tile's sample. The independent verification checks 1,189 source locations,
including exact and adjacent seams. The raster has no antimeridian or high-latitude support.

The site at 50.96, −115.16 is the highest source sample in the stated small search box near Kananaskis,
1794 m above the dataset's vertical reference. It is not asserted to be a surveyed summit or a known
mesh node. Antenna height is 30 m AGL. Other radio assumptions match the baseline study: 915 MHz,
1.5 m generic RX, median atmosphere and statistics, inland/zero-clutter, illustrative −130 dBm
receiver threshold and 22 dBm combined link-budget numerator. Source dates/lineage and model/data
limitations remain in the parent report; this is not measured RF coverage.

Start the existing arm64 mobile runtimes, then run sequentially to avoid concurrent compute load:

```sh
python3 tools/rf-study/coverage/wide/run_android.py /tmp/nodescope-coverage-wide emulator-5554 /tmp/nodescope-coverage-wide/results/android
xcrun --sdk iphonesimulator swiftc -O -module-cache-path /tmp/nodescope-coverage-swift-cache -target arm64-apple-ios17.0-simulator -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" -F /tmp/nodescope-coverage-wide/native/build/bin/iosSimulatorArm64/releaseFramework -framework CoverageStudy tools/rf-study/coverage/wide/WideRunner.swift -o /tmp/nodescope-coverage-wide/wide-ios
xcrun simctl spawn SIMULATOR_UUID /tmp/nodescope-coverage-wide/wide-ios /tmp/nodescope-coverage-wide/data/terrain.bin /tmp/nodescope-coverage-wide/results/ios 50.96 -115.16
python3 tools/rf-study/coverage/wide/run_android.py /tmp/nodescope-coverage-wide emulator-5554 /tmp/nodescope-coverage-wide/results/android --diagnostic
xcrun simctl spawn SIMULATOR_UUID /tmp/nodescope-coverage-wide/wide-ios /tmp/nodescope-coverage-wide/data/terrain.bin /tmp/nodescope-coverage-wide/results/ios 50.96 -115.16 diagnostic
python3 tools/rf-study/coverage/wide/analyze.py /tmp/nodescope-coverage-wide docs/node-planner/feasibility/wide-coverage
python3 tools/rf-study/coverage/wide/preview.py /tmp/nodescope-coverage-wide/results/ios docs/node-planner/feasibility/wide-coverage/wide-preview.svg
```

The source manifest/site arguments must agree if deliberately changing fixtures. `--diagnostic`
only reruns two problematic paths at 90/30/15/10/5/2 m sampling; it does not overwrite main-run metrics.
The large runs were executed with the initial 10/15/30/90 m allowed set. The follow-up only added
2/5 m options and a bounded 50,001-sample diagnostic allowance; existing numerical paths are unchanged.
Do not reinterpret finer sampling of the same DEM as obtaining higher-resolution terrain data.

## Experiment matrix and limits

| Radius | Output spacing | Profile spacing | Purpose |
|---|---|---|---|
| 10 km | 200 m | 30 m | Existing scale at an elevated site |
| 20 km | 500 m | 30 m | Larger footprint |
| 50 km | 1000 / 500 m | 30 m | Coarse scan/detail comparison |
| 100 km | 2000 / 1000 m | 30 m | Longer-range workload and sparse pockets |
| Selected 4 × 4 km | 50 m | 30 m | Refinement, 10–14 km east / 0–4 km north |
| Matching 8 × 8 km | 50 m | 30 m | Independently executed fine-grid reference |
| 168 exact paths, 1–100 km | Not a coverage grid | 90 / 30 / 15 / 10 m | Terrain-sampling sensitivity |
| Two worst 100 km paths | Not a coverage grid | Also 5 / 2 m | Targeted convergence diagnostic |

Grid bounds must align to spacing. Preflight checks geometry, parameters, receiver count (60,000
rectangular cells maximum) and a conservative sample-work bound (150 million). These are experiment
limits, not supported production ranges or calibrated time predictions. A requested 100 km/200 m
study is rejected before cell allocation. Refinement does not silently alter the wide-area result.
Increasing radius preserves identical-coordinate results. A coarse pixel approximation is never
used for the finer run's calculations.

Both drivers record per-run computation and maximum batch times, cancellation and whole-process
peak RSS. They omit network, serialization and map rendering from compute time. Page faults, runtime
warmup, GC and simulator virtualization materially affect timings. The small mapping-setup time
is not terrain loading time: actual demand paging occurs during calculations. File cache residency
may also differ between platforms. These are single runs, not latency percentiles or device limits.
The native arm64 device framework was compiled but not run on a physical device.

Ten JVM tests cover the original wrapper plus preflight/bounds, independent profile spacing, signed
interpolation, nodata and terminal cancellation. Mobile runs check fine-grid overlap exactly,
platform parity <0.01 dB, receiver masks/sample counts, unchanged inner results when radius grows,
and explicit oversized-work rejection. Missing/corrupt HTTP responses, LRU quotas, tile eviction,
download cancellation, app lifecycle and UI frames still need production-repository tests.

## Important result

Cross-platform parity passes, but fixed 30 m terrain sampling is **not qualified as a universal
long-range default**. Coarse sampling missed a material feature on one 100 km path; even the 10 m
reference is not guaranteed converged everywhere. Report the actual interval and retain a separate
accuracy/convergence policy. Never reduce terrain sampling quality automatically when the user
requests a wider area. See the [results and next decisions](../../../../docs/node-planner/wide-coverage-study.md).
