# Candidate Sites: wider area, refinement and sampling study

2026-10-30. **Adjustable extent and targeted refinement work on both mobile runtimes. Fixed 30 m
terrain-profile spacing is not qualified as a universal long-range default.** Keep the coarse-scan/
selected-refinement workflow, and make sampling accuracy the next engineering gate before UI integration.
No production app dependency, feature screen or version setting changed in this experiment.

## Implemented prototype

- Configurable radius, output spacing and selected-area bounds, independently of terrain-profile
  spacing. Runs retain separate extents/results; refinement recalculates receiver paths.
- Shared preflight with explicit cell and terrain-sample-work budgets. A 100 km radius at 200 m
  spacing is rejected before allocating its million-cell rectangular grid; no silent coarsening.
- Full-resolution, signed, read-only memory-mapped terrain on both platforms. The shared Kotlin
  code owns interpolation, geodesics, model calls and job state. JVM uses Kotlin 2.2.20/Java 17;
  Swift consumes the Kotlin/Native 2.4.20 static framework.
- Eight coverage runs, 168 paths at four sampling intervals, targeted finer-sampling diagnostics,
  asynchronous cancellation and an independent terrain-mosaic check.

The site is an elevated sample near Kananaskis at 50.96, −115.16, ground height 1794 m in the source
DEM, with a 30 m AGL antenna. It is the highest sample within a small documented search box, not a
surveyed summit or a known network node. Other assumptions remain 915 MHz, 1.5 m generic RX height,
vertical polarization, inland median atmosphere/statistics, zero clutter, and the illustrative
22 dBm combined link-budget numerator / −130 dBm receiver threshold. This is modeled terrain-only
coverage, not measured packet reception.

## Coverage and refinement results

| Study radius / grid | Predicted receiver cells | iOS simulator compute | Android emulator compute |
|---|---|---|---|
| 10 km / 200 m | 7,840 | 0.20 s | 2.37 s |
| 20 km / 500 m | 5,024 | 0.23 s | 1.32 s |
| 50 km / 1 km | 7,844 | 0.88 s | 9.45 s |
| 100 km / 2 km | 7,844 | 1.79 s | 8.14 s |
| 50 km / 500 m | 31,416 | 3.87 s | 9.40 s |
| 100 km / 1 km | 31,416 | 8.04 s | 10.88 s |
| Selected 4 × 4 km / 50 m | 6,561 | 0.27 s | 0.34 s |
| Matching 8 × 8 km / 50 m reference | 25,921 | 1.24 s | 1.20 s |

Every platform pair has identical states, coordinates and profile sample counts. Maximum raw-loss
difference across the coverage runs is approximately 4.38e-9 dB, below the unchanged 0.01 dB parity
bound. No missing-terrain/model-invalid cells occurred in these full-data cases; the earlier
injected-gap checks remain separate evidence. Near-site and outside-radius cells remain uncomputed.

All 6,561 refinement cells match the corresponding cells in the separately executed larger fine-grid
run exactly. Increasing radius from 50 to 100 km preserves all common in-radius 1 km-grid results
exactly. This verifies extent-independent calculations and separate refinement provenance rather
than image upscaling or approximation from coarse cells.

The spatial-detail comparison demonstrates why radius and grid spacing cannot be conflated:

- The 100 km study at 2 km spacing finds a farthest above-threshold sample at 24 km. At 1 km spacing,
  it finds an above-threshold pocket around 43 km. The coarse scan missed a small pocket even though
  both studies used the same radius and terrain.
- The 50 km/500 m run finds a farthest qualifying sample around 43.5 km. These are sampled qualifying
  points, not a guaranteed radio range or a continuous covered route.
- Assigning nearest-coarse-cell classifications to finer points changes 232 of 31,168 comparable
  classifications at 50 km, and 95 of 31,168 at 100 km. This is a display-approximation comparison,
  not error against observed RF truth.

Users must be able to refine any selected region, including one with no coarse-grid coverage.
Do not claim “nothing farther away” from an empty ring or a coarse scan.

## Terrain sampling is a separate accuracy decision

Across 168 exact receiver paths (24 bearings, seven distances from 1 to 100 km), compare profile
intervals while keeping the one-arcsecond DEM and all radio assumptions unchanged:

| Profile interval | Median difference from 10 m | 95th-percentile difference | Maximum difference |
|---|---|---|---|
| 90 m | 0.046 dB | 2.01 dB | 21.09 dB |
| 30 m | 0.0049 dB | 0.227 dB | 11.05 dB |
| 15 m | 0.0015 dB | 0.106 dB | 6.93 dB |

The 10 m run is a comparison reference, not measured truth. No threshold classification changed in
this 168-path set; the largest differences were on already weak paths. That limited result does
not establish classification safety for other locations, thresholds or radio budgets.

Two worst paths were checked at finer intervals on both platforms:

| 100 km path | 30 m loss | 10 m loss | 5 m loss | 2 m loss |
|---|---|---|---|---|
| Bearing 240° | 188.2552 dB | 199.3023 dB | 199.3023 dB | 199.3023 dB |
| Bearing 285° | 208.2932 dB | 208.0507 dB | 207.9612 dB | 207.8189 dB |

The first confirms a material effect missed by the coarser profile: 10/5/2 m agree to about 0.00001 dB.
The second still differs by 0.232 dB between 10 and 2 m, and 0.142 dB between 5 and 2 m. Finer
interpolation does not create new DEM information, and these two diagnostics do not qualify an
all-terrain sampling rule. Do not substitute “always use 10 m” for an actual convergence policy.

**Next engineering step:** build and test terrain-aware/adaptive profile sampling against dense
reference paths, define a separate sampling-error budget, and retain explicit accuracy/unsupported
outcomes when the bounded work cannot satisfy it. Use both loss error and threshold stability,
with tests around narrow ridges, endpoint/horizon geometry, weak-path outliers and near-threshold
receivers. The 0.01 dB platform-agreement bound is not an RF-accuracy or sampling-error claim.
Keep output-detail presets independent of this policy; never silently weaken terrain sampling to
satisfy a larger radius request.

## Terrain, memory and cancellation

Eight SHA-256-pinned Skadi tiles total 84,062,769 compressed bytes. The local full-resolution window
is 152,704,834 bytes (~145.6 MiB), spanning 50.0–51.9° N and −116.7 to −113.6° longitude. Preparation
on the host took 5.81 seconds; this is not mobile download performance. No terrain decimation was
used. Datum, footprint, tile URLs/digests, attribution and duplicate-edge ownership are recorded in
the [manifest](feasibility/wide-coverage/terrain-manifest.json).

An independent geographic lookup compared 1,189 mosaic locations, including both sides of tile
seams, against original HGT bytes. All matched. Android's transferred payload digest also matches
the manifest. The terrain contains information licensed under the Open Government Licence – Canada;
SRTM data courtesy of the U.S. Geological Survey; distribution is through Mapzen Terrain Tiles.
Per-pixel contributing-source lineage remains unresolved as in the earlier report.

Whole-process peak RSS was 210,386,944 bytes (~200.6 MiB) on iOS and 299,761,664 bytes (~285.9 MiB) on
Android. Memory mapping avoids a full heap copy, but accessed pages still consume memory. These
numbers include runtime overhead, resident mapped pages, multiple scenarios and output generation;
they do not establish compliance with the planned 150 MiB **additional app memory** budget.
Bounded tile working sets/eviction and packaged-device measurements remain necessary.

Both runtimes rejected the oversized 100 km/200 m grid, and a separately cancelled 100 km job stayed
cancelled and could not resume. Observed flag-to-exit latency was below 0.004 ms, a single-trial
observation rather than a guarantee. Largest complete-run batches reached 24 ms on iOS and 221 ms
on Android, reinforcing the need to keep computation off the UI thread and poll cancellation
inside sampling. The synchronous RF call itself is not interruptible.

Timing order includes cold mapping/page faults and runtime warmup. In particular, Android's coarse
50 km run was slower than its coarse 100 km run; these single measurements do not imply reversed
scaling. Network, serialization and map rendering are excluded from compute time. Devices were
simulator/emulator, not phones. Actual physical-device, lifecycle, thermal and battery gates remain.

## Decisions for v0.10.1

- Keep the adjustable-radius and coarse-scan/refinement design. The former 20 km proposal must not
  become a hard RF-range assumption. Up to 100 km is now an executed experiment, not a promised
  supported production setting.
- Preserve the 10 km/200 m baseline only as historical evidence; no universal production defaults
  are selected yet. Test-derived output presets must disclose spacing and workload.
- Gate long-range shipping on the sampling-accuracy policy above, terrain working-set budgeting,
  distribution/compatible packaging and representative physical-device tests.
- Once those gates pass, integrate the saved Candidate Sites workflow and map overlays. Saved Link
  Profiles remains deferred; no unrelated app UI work is included here.

## Artifacts

[Preview](feasibility/wide-coverage/wide-preview.svg) shows above-threshold coverage at two grid
spacings plus the selected refinement. [Machine-readable results](feasibility/wide-coverage/summary.json)
include compressed-array hashes, timings, classification comparisons and convergence diagnostics.
[Terrain checks](feasibility/wide-coverage/terrain-checks.json), [ten passing tests](feasibility/wide-coverage/tests.json),
and [reproduction instructions](../../tools/rf-study/coverage/wide/README.md) support the result.
The scientific preview is not an app-screen design. No map UI, light/dark-mode layout or production
app build was exercised; both standalone mobile execution paths ran and both iOS framework targets
compiled.
