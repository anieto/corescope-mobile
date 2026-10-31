# Candidate Sites: real-terrain prototype

2026-10-30, branch `feature/candidate-sites-0.10.1`.
**The bounded prototype passes cross-platform numerical, missing-data and cancellation checks.**
Keep kp1812 as the preferred shared kernel. This is working isolated code with recorded outputs;
Candidate Sites is not yet integrated into either app and Gate A is not fully closed.

## What runs

A shared Kotlin wrapper decodes a signed elevation window, samples spherical geodesic paths,
validates inputs, calls the pinned kp1812 model and calculates raw receive levels/margins. Android
executes a Java-17 artifact compiled with Kotlin 2.2.20; Swift calls a Kotlin/Native 2.4.20 static
framework. Device-arm64 and simulator frameworks build. No production app dependency was added.

The scenario is a 10 km radius around 51.15° N, 114.30° W, west of Calgary. A 101×101 north-first,
local azimuthal-equidistant grid uses 200 m output spacing and 30 m nominal path sampling. Receiver
coordinates are exact geodesic destinations. The terrestrial path sampler and RF model are shared
on both platforms; neither app has a separate translation of the math.

A single real [Mapzen/AWS Skadi tile](https://registry.opendata.aws/terrain-tiles/) was downloaded
and pinned by SHA-256. The 5,291,023-byte compressed transfer took 0.736 seconds on the host. Its
1,662,658-byte retained window preserves original one-arcsecond samples, signed elevations and the
nodata sentinel. No sea-level filling or legacy SDF conversion was used. The study window contains
1059–1350 m elevations and no natural nodata; a second scenario explicitly injects a nodata strip.
Mobile runs operate on the local cropped window, so they do not measure mobile network fetches.

Scientific assumptions: 915 MHz; 10 m TX/1.5 m generic RX antenna heights; vertical polarization;
median time/location; zero clutter and location variability; inland zone; median refractivity
inputs and 500 km coast distances. The illustrative link budget is 20 dBm conducted TX power,
2 dBi TX/RX gains, 2 dB combined feed loss and −130 dBm receiver sensitivity. These are scenario
inputs, not claims about a real node or probability of successful packets.

## Results

| Check | Result |
|---|---|
| Complete grid | 7,840 predicted cells, 5 unsupported near-site cells, 2,356 outside-radius cells |
| Cross-platform states and sample counts | Exact match for complete-terrain and gap scenarios |
| Maximum raw-loss difference | 6.51 × 10⁻¹⁰ dB, below the unchanged 0.01 dB tolerance |
| Injected gap | 3,278 cells become Unknown; 4,562 remain predicted |
| Unaffected cells in the gap run | Loss values unchanged from the complete-terrain run |
| Unknown output | No loss, RX or margin value; never colored as coverage |
| Invalid input through Swift/JVM bridge | Typed failures without uncaught validation exceptions |
| Asynchronous cancellation | Both jobs stop incomplete and cannot resume |
| Exact receiver fixtures | Match within 4.13 × 10⁻¹² dB |
| Wrapper regression tests | 6 passed |

The gap deliberately contains actual −32768 terrain sentinels, and any path touching missing
bilinear support becomes Unknown. Valid negative elevation is separately covered by a signed
synthetic decoder test. The five cells inside the model's 250 m lower distance bound remain
unsupported. No different near-site propagation formula is silently substituted.

The receiver fixtures are **synthetic test positions**, not user node telemetry. They cover an
off-grid position, the same receiver at a different height, a western bearing, a near-site receiver
and a gap-crossing path. The off-grid loss is 140.23164 dB; using its closest grid cell would give
142.16123 dB, a difference of about 1.93 dB. Raising the exact receiver to 10 m gives 136.02441 dB.
This confirms why known-node link explanations need their own exact paths and antenna assumptions.

## Defect found and fixed

The first mobile comparison failed. At one 1.8 km path, floating-point differences around an exact
30 m multiple made `ceil` select 61 versus 62 profile points, yielding a 0.04409 dB discrepancy.
The analyzer stopped; its tolerance was not relaxed. The shared sampler now canonicalizes path
distance to millimetres before selecting sample count and profile positions. Exact receiver
coordinates remain unchanged. A regression checks both sides of that spacing boundary.

[Initial failure evidence](feasibility/coverage-prototype/initial-sampling-failure.json) is retained
alongside the corrected results. A separate initial unit-test failure at the raster boundary was
fixed with a strictly bounded floating-point tolerance, not extrapolation outside the window.

## Timing and memory

Final corrected run, on iPhone 18 Pro/iOS 27 simulator and Pixel 10/API 37.2 arm64 emulator:

| Measurement | iOS simulator | Android emulator |
|---|---|---|
| Local read/decode/bridge copy | 18.55 ms | 4.61 ms |
| Complete grid computation | 134.63 ms | 202.27 ms |
| Gap grid computation | 84.63 ms | 112.12 ms |
| Largest 16-cell batch, complete run | 3.83 ms | 3.10 ms |
| Stop-flag-to-job-exit, one cancellation trial | 0.0041 ms | 0.0033 ms |
| Whole process peak RSS across scenarios | 58,785,792 bytes (~56.1 MiB) | 152,911,872 bytes (~145.8 MiB) |

Computation includes grid geometry, profile sampling, validation and propagation; CSV serialization
and map rendering are excluded. No claim is made that the microsecond cancellation observation is
a worst-case guarantee. Cancellation is polled between cells and during sampling, and a synchronous
RF call must return before cancellation can take effect. Jobs are confined to one worker; only the
thread-safe cancellation signal is shared. Cancelled arrays are archived only for diagnostics and
are not rendered as completed overlays.

An earlier Android run took 682.54 ms, showing why one emulator timing is not a performance promise.
Peak RSS includes the runtime, bridge buffers, multiple scenarios and output generation; it is not
additional app memory. These measurements do not establish the plan's lower-end-device, incremental
150 MiB memory, thermal, battery or UI responsiveness budgets. The 20 km maximum was not exercised.

## Terrain decision and remaining gates

Use the pinned Skadi window as the reproducible prototype fixture. Do **not** treat this as final
production-provider approval: the one-degree download is larger than the retained study area,
and per-pixel source lineage/acquisition date have not been resolved. The provider documents WGS84
horizontal coordinates, EGM96-referenced heights and −32768 voids in its
[format specification](https://github.com/tilezen/joerd/blob/master/docs/formats.md).
The manifest records footprint, resolution, content hashes, HTTP metadata, decode/interpolation
policy and attribution. Canada terrain data contains information licensed under the Open Government
Licence – Canada; SRTM data courtesy of the U.S. Geological Survey. See the provider's
[attribution requirements](https://github.com/tilezen/joerd/blob/master/docs/attribution.md).

Before production integration, finish these concrete items:

1. Resolve kp1812 distribution permissions and the maintained compatible-artifact build strategy.
2. Qualify terrain resolution and sampling convergence, especially narrow ridges. At this latitude,
   one arcsecond is roughly 19 m east-west by 31 m north-south; a 30 m path step can miss features.
   One agreeing pair of implementations does not establish outdoor prediction accuracy.
3. Implement/qualify the bounded terrain repository: tile seams, dates/attribution, missing/corrupt
   responses, download cancellation, offline reuse, storage quotas and supported geographic regions.
4. Run the pipeline inside packaged apps on representative physical devices, including cancellation
   during actual work, lifecycle interruption, full-job memory and map-overlay rendering. Then
   integrate the planned Candidate Sites workflow for both platforms.

This prototype settles basic feasibility and fixes an actual parity issue. It does not justify
claiming reliable LoRa coverage without the stated assumptions and remaining validation.
Saved Link Profiles stays deferred.

## Review artifacts

- [Coverage preview](feasibility/coverage-prototype/coverage-preview.svg): modeled margin and the
  missing-terrain scenario, with attribution. This is a scientific preview, not a proposed app screen.
- [Machine-readable results](feasibility/coverage-prototype/summary.json),
  [terrain manifest](feasibility/coverage-prototype/terrain-manifest.json),
  [tests](feasibility/coverage-prototype/wrapper-tests.json), and
  [source/toolchain manifest](feasibility/coverage-prototype/source-manifest.json).
- Per-cell and receiver CSVs for both runtimes are gzip-compressed in that evidence directory;
  the summary contains their SHA-256 hashes.
- [Reproducible build/run instructions](../../tools/rf-study/coverage/README.md).

Production app builds and visual light/dark/compact/wide-layout checks were not run because this
change adds no app screen or production dependency. Both standalone mobile execution paths ran
successfully; the iOS device framework was compile-checked only.
