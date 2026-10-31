# Feature-preserving profile candidate

An isolated replacement candidate for the repeated-uniform-sampling policy. Historical studies
and production apps are unchanged. Reuses the pinned engine, bounded disk reader and terrain
fixture generator; adds `FeatureStudy.kt` and seven regression tests.

## Construction and limits

For real terrain, split the geodesic at latitude extrema so each coordinate is monotone within
the study domain. Find every integer DEM row/column crossing by bounded bisection to 0.1 mm,
then canonicalize distances to millimetres. Retain crossings and any within-cell extremum found
from a quadratic fit to endpoint/midpoint elevations. Check that approximation at both quarter
points; discrepancies above 0.01 m produce `ACCURACY_UNRESOLVED` with no published loss.

Bilinear terrain is quadratic along a straight segment; a spherical geodesic is not exactly
straight in raster coordinates. The quarter-point check is an empirical safeguard, not a proof
of a universal interpolation/model error bound. No antimeridian or polar generalization is claimed.
These fixtures use the existing Canadian mosaic and its original one-arcsecond samples.

Every RF pass merges retained features with the uniform 30/10/5/2/1 m samples. Two consecutive
loss changes at most 0.1 dB and the existing 0.25 dB threshold guard remain required. Retaining
represented features addresses the demonstrated blind spot; repeated agreement alone still
does not establish general accuracy. Analytic ridge tests supply exact base/peak vertices and
valley tests their center. Flat and smooth rolling controls retain endpoints plus the uniform
samples. This does not invent ridges absent from the source DEM.

Two work counters are bounded independently: at most 250,000 terrain/model sampling positions
and 200,000 geometry-search evaluations per path. Five terrain evaluations per cell for feature
discovery count toward the former; each propagation pass reserves its full merged point count.
Geometry searches poll cancellation per evaluation; sampling polls every 32 points. The RF call
remains synchronous/non-interruptible. Work/fit/data/cancellation failures publish no loss.
Arrays and sorting remain bounded by the 100 km extent and 50,000-feature guard, but these counters
are not total memory, CPU, I/O or wall-clock limits. The old whole-map scheduler is not yet adapted
to reserve both counters; do not connect this candidate to it without updating accounting.

## Reproduce

Use the pinned engine and terrain from the [wide study](../wide/README.md). Start the same mobile
runtimes documented in the [bounded study](../bounded/README.md), then run sequentially:

```sh
python3 tools/rf-study/coverage/feature-profile/prepare_build.py /tmp/nodescope-kp1812-study /tmp/nodescope-coverage-features
/tmp/nodescope-kp1812-study/gradlew -p /tmp/nodescope-coverage-features/jvm test jar --console=plain
/tmp/nodescope-kp1812-study/gradlew -p /tmp/nodescope-coverage-features/native linkReleaseFrameworkIosSimulatorArm64 linkReleaseFrameworkIosArm64 --console=plain
python3 tools/rf-study/coverage/terrain-fixtures/run_android.py /tmp/nodescope-coverage-features /tmp/nodescope-coverage-wide/data/terrain.bin emulator-5554 /tmp/nodescope-coverage-features/results/android
xcrun --sdk iphonesimulator swiftc -O -module-cache-path /tmp/nodescope-coverage-swift-cache -target arm64-apple-ios17.0-simulator -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" -F /tmp/nodescope-coverage-features/native/build/bin/iosSimulatorArm64/releaseFramework -framework CoverageStudy /tmp/nodescope-coverage-features/TerrainRunner.swift -o /tmp/nodescope-coverage-features/features-ios
xcrun simctl spawn SIMULATOR_UUID /tmp/nodescope-coverage-features/features-ios /tmp/nodescope-coverage-wide/data/terrain.bin /tmp/nodescope-coverage-features/results/ios
python3 tools/rf-study/coverage/feature-profile/analyze.py /tmp/nodescope-coverage-features/results docs/node-planner/feasibility/terrain-fixtures docs/node-planner/feasibility/feature-profile
```

The reused Android runner writes only the two fixture CSVs from the newly generated entry point;
it does not execute the earlier algorithm. The analyzer checks 56 synthetic and 897 real-terrain
profiles on each platform, including all 20 saved false acceptances, the 144 additional regional
paths, original 168 paths, 144 holdouts and 441 refinement receivers. Synthetic references use
0.125 m; terrain references use 1 m. Accuracy remains 0.25 dB, separate from 0.01 dB platform parity.
The same propagation engine underlies candidate and references; this is not independent RF validation.
