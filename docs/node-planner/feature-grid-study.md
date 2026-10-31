# Feature-preserving coverage jobs and preview — 2026-10-31

The isolated whole-map scheduler now uses the feature-preserving profile builder and enforces
both sampling and geometry-work budgets. All cross-platform checks pass. An updated, self-contained
[interactive preview](feasibility/feature-grid/coverage-preview.html) shows the new algorithm's
20 km scan and 100 m selected-area refinement over terrain relief. A
[PNG](feasibility/feature-grid/coverage-preview.png) and [SVG](feasibility/feature-grid/coverage-preview.svg)
are also saved. This is a scientific preview, not an app screen or street basemap.

## Scheduler behavior

Preflight checks bounds, cell count and necessary minimum work for both counters. Those lower
bounds do not promise completion: feature discovery may require more work. Each path receives
only the remaining allowance for each counter, capped by its per-path limit. Normal study jobs
allow 150 million sampling positions and 150 million geometry-search evaluations; path caps remain
250,000 and 200,000 respectively. Work is charged even if cancellation discards an in-flight cell.

Exhaustion never removes retained features or silently coarsens sampling. The affected cell has
no loss and later cells remain `NOT_COMPUTED`. Terminal jobs cannot resume. A serialized publication
coordinator rejects old captured job generations and cancelled jobs. The app will still need to
place that coordinator behind its own actor/main-thread boundary and carry the generation through
every asynchronous callback; this experiment does not constitute integrated UI lifecycle testing.

## Executed results

| Scenario | Result, identical on both platforms |
|---|---|
| 20 km radius / 1 km grid | 1,681 cells: 1,254 qualified, 2 threshold-unresolved, 425 excluded |
| Selected 4 × 4 km / 100 m grid | 1,681 cells: 1,679 qualified, 2 threshold-unresolved |
| Selected 200 m grid, 128 KiB and 2 MiB caches | 441 cells; exactly matching results despite eviction |
| Coarse 100 km / 20 km grid | 121 cells: 80 qualified, 41 excluded; workload fixture only |
| Sampling-limited refinement | Stops at 1,799,201 charged positions; 259 cells remain uncomputed |
| Geometry-limited refinement | Stops at 500,000 evaluations; 415 cells remain uncomputed |
| Independent 8 × 8 km / 100 m grid | 6,561 cells: 6,529 qualified, 32 threshold-unresolved |

Qualified means the numerical policy accepted a loss estimate, **not** that reception is predicted.
The preview colors only qualified cells above the illustrative receiver threshold. There are 156
such cells in the wide scan and 120 in the refined view. The dashed circle is the calculation extent;
orange hatching distinguishes unresolved cells. Predictions retain disconnected terrain-shaped areas.

All 1,681 refined cells match the independent larger-grid cells exactly, including states, losses
and both work counters. The 441 selected 200 m cells also match the preceding single-path feature
study exactly. Platform states, work and cache counters match; the maximum raw-loss difference
is **3.93 × 10⁻⁹ dB**, within the unchanged 0.01 dB parity tolerance. The scientific accuracy target
and limitations remain those of the [feature-profile study](feature-profile-study.md).

All **39 unit tests pass**, including six new scheduler tests. Runtime scenarios also verify
terminal behavior and rejection of superseded publication. Live cancellation returned in 0.143 ms
on iOS and 0.019 ms on Android in one trial each; these are not worst-case guarantees. Cancellation
during the synchronous RF kernel remains deferred until that call returns. Both iOS framework
targets compiled, but execution used a simulator and Android emulator, not phones.

## Performance and memory observations

| Scenario | iOS elapsed | Android elapsed |
|---|---|---|
| 20 km scan / 1 km grid | 39.97 s | 13.20 s |
| Selected 100 m refinement | 13.49 s | 16.22 s |
| Independent larger 100 m reference | 43.20 s | 79.77 s |

These single runs include feature discovery, RF calculations, cache reads, CSV construction and
publication checks, excluding final file writes. The iOS run resumed after the Android run; host
conditions, runtime warmup and file caches differ. This is not a controlled platform speed comparison.
The timings nevertheless make clear that operation caps alone do not establish interactive latency.
Keep the work off the UI thread, support progress/cancellation, and benchmark representative devices
before selecting output presets. The reference grid is a validation workload, not a shipping promise.

The selected 100 m job charged 16,115,568 sampling positions and 31,148,418 geometry evaluations.
The larger reference charged 64,712,166 and 125,628,002 respectively. Both counters stayed within
their limits. With the 128 KiB cache, 200 m refinement caused 10,885 evictions and 356.8 MB of logical
reads; the 2 MiB cache retained the required ten blocks with 327,680 bytes of payload and no eviction.

Whole-process peak RSS was 43,909,120 bytes on iOS and 161,005,568 bytes on Android. These values
include runtimes and allocations, are not additional app memory, and do not qualify a production
memory budget. Cache payload counters independently respected their configured caps.

## Preview and remaining work

The HTML viewer is entirely local and includes an opacity slider; no network requests or map-service
credentials are needed. The PNG was rendered and visually inspected for readable labels, correct
panel placement, coverage colors and unresolved hatching. HTML/SVG structure was checked, but the
browser slider was not interactively exercised. No app UI or light/dark-mode layouts were tested.

The subsequent [host terrain repository study](terrain-repository-study.md) now tests bounded
downloads/disk caching, missing/corrupt tiles and cache pressure. Native adapters and a leased tile
reader are next. Resolve engine distribution, add independent terrain/model validation,
and qualify packaged physical-device performance, lifecycle and resource usage. Only then select
shipping presets and integrate Candidate Sites persistence and overlays. Saved Link Profiles
remains deferred. No production app dependencies were added.

See [machine-readable results](feasibility/feature-grid/summary.json),
[tests](feasibility/feature-grid/tests.json), [source hashes](feasibility/feature-grid/source-manifest.json),
and [reproduction instructions](../../tools/rf-study/coverage/feature-grid/README.md).
