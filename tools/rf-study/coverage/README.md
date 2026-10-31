# Terrain-backed coverage experiment

Isolated feasibility code, not an app feature or a production RF library. No upstream implementation
or terrain payload is vendored. The fixed scenario is west of Calgary (51.15, -114.30), a 10 km disk
on a 101×101 grid at 200 m spacing, with 30 m maximum nominal profile spacing. The spherical forward
geodesic uses radius 6,371,008.8 m; the grid is local azimuthal equidistant, north-first, with the site
at the centre cell. Path distance is canonicalized to millimetres before sample-count calculation
so exact spacing multiples do not choose different profiles across platforms. Receivers use exact
coordinates; their positions are never snapped to the display grid.

Source dependency: kp1812 `a911b6ecd58f8c16ac8ae4becf6bdbba1784e5a9`, GPL-3.0-or-later.
Distribution review remains open. Read upstream instructions before running its Gradle wrapper.
Use an existing pinned checkout from the parent README. Python scripts require only stdlib.

## Prepare and build

From the NodeScope repository root:

```sh
python3 tools/rf-study/coverage/prepare_terrain.py /tmp/nodescope-coverage-study/data
python3 tools/rf-study/coverage/prepare_build.py /tmp/nodescope-kp1812-study /tmp/nodescope-coverage-study
/tmp/nodescope-kp1812-study/gradlew -p /tmp/nodescope-coverage-study/jvm test jar --console=plain
/tmp/nodescope-kp1812-study/gradlew -p /tmp/nodescope-coverage-study/native linkReleaseFrameworkIosSimulatorArm64 linkReleaseFrameworkIosArm64 --console=plain
```

The first command downloads one ~5.3 MB compressed Mapzen/AWS Skadi tile, checks its pinned digest,
validates shape, and extracts a ~1.7 MB window without downsampling or replacing nodata. Fetch timing,
HTTP metadata, datum, footprint, hashes and attribution are in `data/terrain-manifest.json`. No
mobile network fetch happens during the compute measurements. This single-window study does not
implement an LRU cache, tile seams, antimeridian handling, or the full production terrain repository.

The JVM project uses Kotlin 2.2.20 and Java 17 (matching the current Android toolchain); native uses
Kotlin 2.4.20 with installed Apple SDKs. Both compile the exact same wrapper and pinned model source.
The six wrapper tests cover sign/orientation/interpolation, nodata, malformed/oversized payloads,
owned inputs, terminal cancellation, invalid receivers, and the cross-platform sample-count regression.

## Execute on mobile runtimes

Start an existing arm64 Android emulator and an iOS simulator. Substitute your serial/UUID:

```sh
python3 tools/rf-study/coverage/run_android.py /tmp/nodescope-coverage-study emulator-5554 /tmp/nodescope-coverage-study/results/android
xcrun --sdk iphonesimulator swiftc -O -module-cache-path /tmp/nodescope-coverage-swift-cache -target arm64-apple-ios17.0-simulator -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" -F /tmp/nodescope-coverage-study/native/build/bin/iosSimulatorArm64/releaseFramework -framework CoverageStudy tools/rf-study/coverage/CoverageRunner.swift -o /tmp/nodescope-coverage-study/coverage-ios
xcrun simctl spawn SIMULATOR_UUID /tmp/nodescope-coverage-study/coverage-ios /tmp/nodescope-coverage-study/data/terrain.bin /tmp/nodescope-coverage-study/results/ios
python3 tools/rf-study/coverage/analyze.py /tmp/nodescope-coverage-study docs/node-planner/feasibility/coverage-prototype
```

Android uses SDK build-tools 36.0.0 D8 (min-api 26), Kotlin stdlib 2.2.20 and app_process. iOS calls the
static framework through Swift. Neither command installs or changes NodeScope. A device-arm64
framework is compiled, but only simulator/emulator execution is qualified here.

Drivers run complete terrain, a deliberately injected nodata strip, and a separate asynchronously
cancelled job. Batches are capped at 32 cells (drivers request 16); cancellation is checked before
cells, every 32 profile samples, and before/after the synchronous RF call. Worker owns job state;
only the cancellation signal crosses threads. A cancelled job cannot resume or claim completion.
Saved partial CSVs are diagnostic evidence, not publishable coverage overlays.

`decode_ms` includes local file read and bridge copying. `compute_ms` includes geometry, sampling,
validation and RF calculation; it excludes CSV serialization. Peak RSS is the entire driver process
across all scenarios, not incremental app memory. The cancellation clock starts when the other
thread actually sets its flag, not when the timer was scheduled. Host virtualization, runtime warmup
and scheduling affect all measurements. Actual packaged app lifecycle, map rendering, physical-device
speed, battery and lower-end-device budgets remain untested.

Five synthetic receiver fixtures cover off-grid positions, a different receiver height, a second
bearing, the near-site exclusion and a path crossing missing terrain. They exercise the future
known-node computation route but are not discovered mesh nodes or observed links.

The analyzer requires identical masks/sample counts, finite computed results, blank unknown
values, correct link-budget arithmetic, terminal cancellation, unchanged results outside the gap's
influence, and <0.01 dB raw-loss differences. It archives compressed cell arrays and hashes, metrics,
receiver results and a standalone SVG preview. It deliberately fails rather than relaxing parity
bounds. The first run exposed a ceil/sample-count discrepancy; `initial-sampling-failure.json`
records it. The final regression normalizes distances before choosing sample counts.

## Fixed scientific assumptions

915 MHz; TX antenna 10 m AGL; generic RX 1.5 m AGL; vertical polarization; time/location 50%; zero
location variability; inland zones; ΔN=45, N0=325 and both coast distances 500 km; zero clutter.
Link budget: 20 dBm conducted power + 2 dBi TX + 2 dBi RX − 2 dB combined feed losses. Display margin
uses an illustrative −130 dBm sensitivity. There is no modem/PDR or interference model. A positive
margin is not a confirmed usable link. All assumptions belong in future result provenance.

30 m profile spacing can undersample some terrain features; a one-arcsecond grid is roughly 19 m
by 31 m here. Ridge-preservation and convergence studies remain required before production. Nodata
and any missing bilinear support cause Unknown; negative valid elevations remain valid. Cells below
250 m are explicitly unsupported rather than patched with a different propagation model.

Terrain source and attribution: [Mapzen/AWS terrain tiles](https://registry.opendata.aws/terrain-tiles/),
[Skadi format](https://github.com/tilezen/joerd/blob/master/docs/formats.md),
[provider attribution](https://github.com/tilezen/joerd/blob/master/docs/attribution.md).
Canada terrain data contains information licensed under the Open Government Licence – Canada;
SRTM data courtesy of the U.S. Geological Survey. The multi-source product's exact per-pixel source
and acquisition date are not resolved by this prototype.
