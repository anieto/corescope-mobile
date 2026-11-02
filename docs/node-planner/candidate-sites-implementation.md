# Estimated Coverage — technical implementation and validation plan

Status: v0.10.3 plan/site model, atomic storage, editor, confirmed map placement, current location and height units implemented; device checks pending. Standalone coverage is now the primary target; terrain studies still require production integration. Updated 2026-11-02.
Platforms: iOS and Android. Parent: [Node Planner plan](plan.md).

## Outcome and reference tools

Place a proposed node, enter equipment/installation assumptions, run an analysis and see a
terrain-aware estimated signal footprint on the map together with known nodes that might receive
it. Inspect an individual path, change antenna height or location, and compare saved alternatives.
Coverage must reveal terrain shadows and irregular reach; a radius circle is only the analysis
boundary, never the prediction. It is not a packet-routing or live-activity overlay.

Reference review (2026-10-29):

- [Meshtastic Site Planner](https://site.meshtastic.org) is the functional reference for a map-led
  transmitter/receiver configuration and cancellable coverage run. Its
  [repository](https://github.com/meshtastic/meshtastic-site-planner) describes ITM/Longley-Rice,
  local computation, heatmaps, multiple sites and coverage statistics. NodeScope borrows workflow
  ideas, not defaults or an assertion of numerical equivalence.
- Its [architecture](https://raw.githubusercontent.com/meshtastic/meshtastic-site-planner/main/ARCHITECTURE.md)
  uses a compiled propagation core, downloaded terrain pages, incremental radial work and a signal
  raster. This supports investigating a shared native core and a tiled terrain cache for NodeScope.
  Do not copy its documented missing-page-as-sea-level policy: absent data stays Unknown here.
- [MeshPeaks](https://meshpeaks.com) is a user-specified visual/workflow reference. The site yielded
  no readable application content in browsing, and direct retrieval returned HTTP 403. Its current
  model, defaults and interactions were not independently verified. Do not attribute an engine or
  claim feature parity. A manual walkthrough is outstanding before visual design sign-off.
- [Meshtastic's test plan](https://raw.githubusercontent.com/meshtastic/meshtastic-site-planner/main/TESTING.md)
  checks native/browser outputs and worker determinism. Use similarly reproducible tests; a
  visually similar heatmap is not numerical validation.

## Current product scope and workflow — 2026-11-02

The [Estimated Coverage product contract](estimated-coverage.md) supersedes the saved-sites-first
scope. Estimated Coverage is a standalone peer of Link Profile, with one transmitter, explicit
radio/receiver assumptions, adjustable analysis extent/detail, a terrain-aware overlay and known
nearby nodes colored by predicted reception. No named/saved plan is required. Saved Plans later
collects optional saved studies from both tools, with candidate sites as locations within plans.

Reuse existing site placement/configuration and atomic storage work. Pause Candidate Sites polish.
Prioritize engine/terrain integration and one complete unsaved coverage flow, then extend the
versioned storage model and connect Save/Open from both tools. See the product contract for exact
navigation, cancellation/stale behavior, node colors, persistence migration and delivery order.
The numerical and resource gates below remain required; a LOS-only circle is not coverage.

## Inputs and prediction meaning

| Input group | Contract |
|---|---|
| Site | WGS84 coordinate, label, antenna height above ground; terrain supplies ground elevation with datum/provenance |
| Transmitter | Frequency MHz, modem/preset identity and version, explicit TX dBm, gain dBi, system loss dB |
| Coverage receiver | Hypothetical height AGL, gain/loss, sensitivity dBm; always visible in the legend/details |
| Known-node receiver | Separately labeled shared defaults plus optional explicit per-node overrides; never infer mast height from terrain/coordinate altitude |
| Propagation | Engine/version, supported polarization, climate, ground electrical and statistical parameters; expose reviewed defaults |
| Analysis | Radius, terrain resolution, output spacing, model domain and resource limits |

Defaults must be reviewed for the app's MeshCore use cases. Do not import Meshtastic radio presets
as MeshCore settings. Store explicit numeric values alongside preset IDs; a preset update does not
rewrite plans. Use canonical meters/degrees/MHz/dB/dBm internally. Verify conversions independently;
for example 0.1 W = 20 dBm. External documentation can contain inconsistent defaults.

Define RX = TX + TX gain + RX gain − TX loss − RX loss − modeled path loss. Raw margin = RX −
receiver sensitivity. An optional user-selected planning reserve is a separately displayed term:
adjusted margin = raw margin − reserve. Do not count antenna gains or a fade allowance twice.

Coverage is forward reception by the configured generic receiver. Known-node predictions use their
own receiver assumptions and exact coordinates; they need not match the nearest generic grid cell.
A viable forward path does not prove a return path, matching modem/channel, compatible protocol,
online node, successful packet delivery or mesh routing. If compatibility is not supplied, label it
unknown rather than suppressing the caveat behind a favorable RF result.

## Engine feasibility study result (2026-10-29)

The [pinned Site Planner native-core study](site-planner-feasibility.md) built iOS/Android targets
and ran four fixtures on both simulator/emulator runtimes. Decision: do not adopt unchanged;
missing-terrain and per-cell model-validity behavior fail our contract, and Android signal parity
needs investigation.
This is evidence for Gate A, not completion of the gate or approval for production reuse.

The [kp1812 follow-up study](kp1812-feasibility.md) passed reference suites on JVM, macOS, iOS
simulator and Android emulator, built device/simulator frameworks and exercised a Swift consumer.
Prefer its shared Kotlin kernel for the next bounded terrain-backed prototype, with typed input/
output validation. Its prebuilt Kotlin 2.4 artifact is incompatible with our Kotlin 2.2 compiler;
an isolated rebuild of unchanged source with Kotlin 2.2.20/Java 17 passed the reference suite.
Distribution review, terrain selection, full-job performance, cancellation and production packaging
remain open.

The [real-terrain prototype](coverage-prototype.md) now runs a 10 km grid through shared sampling
and validation on both mobile runtimes. Final masks and sample counts match exactly; maximum raw
loss difference is 6.51e-10 dB. A deliberately missing strip becomes Unknown, exact receiver fixtures
avoid display-grid snapping, and asynchronous cancellation is terminal. A spacing-boundary parity
defect was found and fixed with millimetre distance canonicalization and a regression test.
This qualifies the bounded prototype, not the full gate. Before production integration: resolve
distribution/artifact packaging, qualify sampling convergence and the terrain repository, then
measure packaged apps and overlays on physical devices. See the report for measured budgets/limits.

The [wider-area/refinement study](wide-coverage-study.md) now executes 10–100 km cases on both
mobile runtimes with full-resolution terrain. Output masks/counts match, raw losses agree within
4.38e-9 dB, and selected 50 m refinement matches the larger fine-grid run exactly. Coarse grids
missed an above-threshold pocket near 43 km. More importantly, 30 m profile spacing differed by
11 dB from denser sampling on a long mountain path. Do not approve a universal 30 m sampling default.
The [adaptive sampling study](adaptive-sampling-study.md) now qualifies 748 of 753 terrain paths
against a 1 m reference, with maximum accepted error 0.1293 dB against a 0.25 dB target. Five paths
remain explicitly unresolved; platform states agree. This is staged whole-path refinement, not
a universal terrain-feature preservation guarantee. The [bounded coverage follow-up](bounded-coverage-study.md)
now enforces aggregate sample-work limits and a bounded local terrain-block cache. Cache eviction
preserves predictions; the smallest tested cache causes substantial rereading. Next extend terrain
and ridge fixtures, qualify download/disk-cache behavior and engine distribution, and measure
packaged physical devices. Wider radii remain experiments, not supported product promises.

The [expanded terrain/ridge fixtures](terrain-fixture-study.md) now expose a general-accuracy
failure: repeated uniform-sampling agreement incorrectly accepts 20 synthetic profiles, with
maximum error 45.13 dB, although the additional regional terrain paths pass. The immediate next
implementation was feature-preserving profile construction. That [candidate now passes](feature-profile-study.md)
all 56 synthetic cases and 896 of 897 terrain paths; one remains threshold-unresolved. All 20
counterexamples are resolved within the unchanged target. The [feature-aware scheduler](feature-grid-study.md)
now accounts for both geometry discovery and sampling in bounded coverage jobs, with matching
platform/cache/refinement results and an updated visual preview. Next qualify terrain download/disk
cache behavior and packaged physical-device performance. Neither the
old heuristic nor the new finite fixture matrix establishes a universal accuracy guarantee.

## Engine feasibility gate (A1)

Current code implements free-space loss plus peak single-obstruction diffraction and Fresnel
classification (`NodePlannerRFEngine` / Android `LinkProfileEngine`, engine version 1). That is a
useful existing path model, not an audited area-coverage model. Fresnel clearance bands must not be
reused as receive-margin categories.

Preferred architecture after the studies: a pinned shared Kotlin kp1812 kernel behind a validated
wrapper, exposed to Swift through a static framework and to Android through a compatible JVM build.
Keep terrain sampling/orchestration contracts identical. Avoid separately translated propagation math.
Retain these alternatives if distribution or terrain-backed qualification fails:

- [NTIA ITM](https://github.com/NTIA/itm) as a reference implementation; its documented model domain
  includes paths greater than 1 km. Audit supported heights, frequency, warnings and statistical
  modes, not just whether the library returns a number.
- The exact SPLAT!/ITM implementation used by the reference planner, with its dependency licenses,
  numerical behavior and mobile integration cost reviewed independently.

Select one after a native proof of concept on both platforms. Audit licenses/redistribution before
importing code; the existing MeshCore One permission does not cover another upstream. Record pinned
commit, build flags, source/attribution, warning handling and independently obtained reference outputs.
No embedded remote website/WebView dependency and no server compute requirement for v0.10.1.

Unsupported near-site distances are an explicit mask/Unknown band, unless a separately validated,
visibly named short-range model is approved. Do not silently clamp distances or splice free-space
predictions into an ITM result. Warnings that invalidate confidence become typed Unknown reasons;
retain nonfatal model warnings in provenance.

Potential-link detail must use the chosen coverage kernel and dataset. Reuse Link Profile's chart
and controls through an engine-tagged result contract. Existing standalone v1 Link Profile may
remain available with its model label; never recalculate a candidate link with v1 and present it as
an explanation of a different coverage engine's output.

## Terrain feasibility gate (A2)

The current Open-Meteo provider/cache is coordinate-oriented (100 coordinates per batch), and the
Android path sampler uses up to 100 points per path. Repeating that request per coverage cell is
not the coverage architecture. Example: a 20 km disk at 200 m spacing contains roughly 31,400 cells;
100 terrain samples per independent path would be roughly 3.1 million samples before reuse.

Prototype a tiled elevation repository supplying overlapping paths from one bounded local terrain
window. Evaluate the [AWS terrain tiles dataset](https://registry.opendata.aws/terrain-tiles/) and
an appropriate versioned raster source, reviewing actual regional coverage, datum, attribution,
redistribution, offline storage and bandwidth. No provider selected merely because it is public.

Required manifest: dataset and revision/content digest, source resolution, geographic footprint,
vertical datum, tile coordinates, fetch time, resampling policy, nodata validity mask and checksum.
Cache key includes dataset/version/resolution and decoder policy. Handle tile borders, antimeridian,
high latitudes, below-sea-level terrain and interpolation deterministically. Never treat HTTP 404,
corrupt payloads or out-of-coverage as ocean; sea-level water needs affirmative dataset evidence.
Any path crossing an unsupported terrain gap is Unknown under the initial conservative policy.

Terrain sample spacing and coverage display-cell spacing are different controls. A 200 m output
cell does not justify a coarse 100-sample profile across 20 km. Select path sampling using terrain
resolution and prove ridge/obstruction preservation in fixtures. A smooth image does not add terrain
accuracy. Existing Copernicus surface data and a bare-earth source are not interchangeable; record
that distinction and do not silently mix them in one analysis.

Download only the requested bounded study footprint and necessary path/tile halo. Preview uncached
bytes, permit cancellation, use bounded concurrency/retries, verify checksums and apply an LRU disk
quota. In-use tiles are pinned. Cache deletion is separate from saved-plan deletion. Coordinate/tile
requests disclose location to the terrain provider; reflect actual data transfer in help/privacy.

The [host-side repository contract study](terrain-repository-study.md) now exercises these storage
and transfer failure paths with 50 tests and real pinned-tile replay. It rejects a complete pinned
set that cannot fit the configured quota before downloading. The subsequent
[native leased-reader study](native-terrain-reader-study.md) passes Swift/macOS and Kotlin/JVM
tests against the same pinned tiles. Native cache/network adapters, reader-to-sampler integration,
geographic footprint selection and mobile lifecycle/network qualification remain outstanding;
the host prototype must not be reported as completed iOS/Android terrain integration.

## Coverage computation and rendering

### Adjustable extent and detail

Expose **study radius** and **detail level** consistently on iOS and Android. Show the actual
output spacing in metres alongside the detail label. Radius defines where calculations stop, not
the maximum reach of the proposed site. The prototype's 10 km radius and 200 m grid were bounded
feasibility choices, not validated production defaults; the earlier proposed 20 km ceiling is
superseded pending longer-range measurements.

Keep three quantities separate in settings, help and saved provenance: study radius, coverage
receiver-grid spacing, and terrain-profile sample spacing. Detail presets change the output grid;
terrain sampling must independently preserve relevant terrain features. Never reduce path-sampling
quality merely to make a larger coverage grid fit a runtime budget.

Plan a **coarse wide-area scan followed by user-selected finer analysis**. Before Run, preview
radius, actual spacing, approximate cell count, uncached download size and estimated cost when
calibrated measurements exist. If a request exceeds supported limits, offer explicit smaller-area
or coarser-grid alternatives; do not silently change settings. Large-radius support remains gated
by terrain availability, model validity, and measured device budgets.

Refinement recalculates paths to finer receiver locations within the selected area; it is not
image upscaling. It may target any selected area, including areas with weak or absent coarse-grid
coverage: a coarse scan can miss small coverage pockets. Preserve the wide scan and refinement as
distinct runs with their own bounds, grid, inputs, completeness and provenance. The initial UI
switches the active result instead of silently stitching different resolutions into one overlay.
Known-node paths always use exact coordinates independently of the display grid. Comparisons must
use compatible extents/resolutions or clearly disclose the differences.

Label the study boundary and everything beyond it as **not analyzed**, not out of range. If qualifying
coverage reaches the boundary, offer to expand the study within qualified limits. Even without
boundary coverage, do not claim that no disconnected coverage exists farther away.

### Scientific grid and display

Use a deterministic study grid with explicit projection, origin, spacing, cell-center convention,
extent and circular analysis mask. Evaluate a path to each sampled receiver or an optimized radial
sweep proven equivalent within declared error; never paint all points along a radial with its last
receiver result. Freeze deterministic ordering and parallel merge behavior before optimization.

Results retain raw path loss/RX/margin arrays, validity/reason masks and completed-cell mask. Store
scientific values independently of RGBA display tiles. Missing, outside-radius, unsupported and
below-threshold are distinct states. Do not interpolate coverage across missing cells or hide
terrain shadows with smoothing. Separate contour rendering is deferred.

Use bounded raster overlays, not one polygon/view per cell. Implement adapters for iOS map rendering
and both Android MapLibre and Google map paths. Confirm projection/extent, date-line splitting,
clipping, opacity, z-order and tile seams with fixtures. Basemap switches preserve results. Keep
known-node and candidate markers above the prediction layer; observed routes remain distinguishable.

Default legend: modeled margin in dB, fixed across runs/candidates. Initial proposed bands are
>= 10 dB, 0–10 dB and < 0 dB, with an explicit planning reserve if enabled. These are planning bands,
not probability or validated reliability thresholds; final labels/thresholds are gated by A1 and
reference testing. Prefer numeric labels initially over “Likely”. Unknown uses a distinct patterned
or neutral mask and accessible text; the radius boundary remains visible.

Default display shows predictions at or above the selected reception threshold, producing a
terrain-shaped footprint. Offer an explicit weaker-signal display option. Below-threshold results
remain available for inspection even when transparent; missing terrain keeps its distinct Unknown
representation. Explain that a circular study boundary is a calculation extent, not a coverage contour.

Tap a cell to show coordinate, RX/margin, assumed receiver, engine, terrain and calculation date.
List equivalents expose results without relying on color/map touch. Show valid-area percentage,
area above threshold and unknown area separately; do not count unsampled/unknown cells as covered
or include them silently in the denominator. Area uses correct geographic/projected cell area,
not pixel count. Any farthest qualifying sample is labeled as such, not “range in every direction”.

## Known-node evaluation

The [green/red/neutral status contract](estimated-coverage.md#known-node-colors-and-meaning) is
authoritative: green for valid adjusted margin >= 0 dB, red below zero, neutral for unknown or
not evaluated, and explicit stale styling. Initially use shared generic receiver assumptions;
per-node overrides are deferred. Markers and an accessible list expose status without color alone.

Capture a bounded analyzer snapshot at run start, with source, filters, timestamp and completeness.
Include valid-coordinate known nodes within the selected radius even when outside the current
viewport. Fetch/paginate according to backend capability; if capped or partial, show “N analyzed of
M available” or “partial catalog”, never imply exhaustive coverage. Changing a list filter alone
does not change the model; changing analysis scope marks node results stale.

Calculate exact candidate-to-node paths with the shared kernel. Show name, distance, bearing,
modeled margin, terrain state, receiver assumptions and node last-seen context. Rank valid results
by descending adjusted margin, then distance and stable ID; put Unknown in a separate group with
its reason. Out-of-range nodes remain neutral context markers where available, with an excluded count. Zero valid nodes does not
prevent a coverage run.

Selecting a node highlights one proposed path and opens its matching terrain/LOS/Fresnel/model
breakdown. Unknown receiver equipment is not fabricated. Generic coverage and node overrides are
shown side by side when their different assumptions explain different predictions.

## Models, persistence and comparison

| Component | Responsibility |
|---|---|
| DeploymentPlan | UUID, name, schema/revision, timestamps, ordered candidate IDs, source/catalog snapshot references |
| CandidateSite | UUID, location, explicit configuration, receiver defaults/overrides, run settings |
| AnalysisInput | Immutable inputs, canonical fingerprint, engine/terrain identity, node snapshot identity |
| CoverageResult | Grid geometry, numeric arrays, masks, statistics, provenance, warnings, input fingerprint |
| PotentialLink | Stable target reference, exact path inputs/output, assumptions, status and provenance |
| PlanRepository | Serialized atomic records/blobs, migration, recovery, save status and storage accounting |
| AnalysisCoordinator | Terrain preparation, bounded workers, progress, cancellation and stale-result rejection |

Save editable plans explicitly; save a completed result snapshot only when matched to those inputs.
Do not serialize localized field text. Unsupported schema/corrupt records are preserved for recovery,
not grounds to reset the library. Define binary-grid encoding, checksums, size limits and shared
JSON fixtures before implementation. Draft/session restoration is separate from saved-plan identity.

Reopen saved results offline as historical estimates; rerun only with compatible cached terrain or
an explicit download. Ordinary cache cleanup does not remove saved inputs/result snapshots. Switching
sources does not silently move a candidate or mutate the saved node catalog. Each late result must
match plan/candidate revision, engine, terrain and run token before publication.

Compare two immutable candidate runs through a selector on the same map, with a fixed legend and
camera. Show changed assumptions, area and node-margin deltas. Different footprints must use a
common sampled intersection for quantitative area deltas; disclose excluded/unknown cells. Changed
models/terrain/catalogs are flagged non-equivalent, not presented as height-only effects. Multiple
candidates are stored; simultaneous overlapping coverage colors are not required.

Export includes candidate coordinates, direction, all assumptions, engine/terrain provenance,
calculation date, analysis bounds/completeness, selected node results and limitations. Preview the
included coordinates before the system share action. No credentials or location history. Versioned
JSON and readable summary are required; georeferenced GIS exports are deferred.

## Performance experiments and provisional limits

These are starting benchmark targets, not measured promises or final supported ranges:

| Item | Prototype target |
|---|---|
| Existing measured baseline | 10 km radius, 200 m output spacing; provisional benchmark, not final default |
| Radius/detail policy | User-selectable radius and detail; supported presets and maximums determined by experiments |
| Initial workload reference | Approximately 40,000 in-radius output cells for budgeting experiments, not a fixed range cap or universal device limit |
| Wider-area experiments | Test 20, 50 and 100 km where terrain/model support permits; these are test cases, not promised launch ranges |
| Coverage receiver | Proposed 1.5 m AGL; explicit and reviewed independently of known-node receiver defaults |
| Execution | One candidate run at a time; bounded 1–2 compute workers initially |
| Warm run | <= 30 seconds for default study on representative lower-end supported devices |
| Additional resident memory | <= 150 MiB including terrain, work buffers and overlay staging |
| Cancel responsiveness | UI responds immediately; work stops within 1 second |
| Terrain storage | Prototype 250 MiB LRU quota with visible usage and explicit management |

Measure default/max studies in flat, ridge and mountain terrain, cold/warm cache, repeated runs,
low-memory and thermal conditions. Record device/OS, bytes fetched, peak RSS, per-stage time, UI frame
responsiveness and energy/thermal observations. Larger terrain packages may exceed quotas even for
a small study; preflight actual footprint and refuse with a clear explanation. Set production limits
from results and publish them before UI completion. Never increase cell size automatically without
showing the changed resolution and obtaining an explicit rerun choice.

Experiment specification (initial matrix now completed; see the wider-area study): extend the shared prototype to configurable radius/output spacing and refinement
bounds. Compare 10/20/50/100 km studies with 100/200/500/1000 m grids selectively under a preflight
work budget, then refine a small region to 50/100/200 m. Include an elevated ridge/mountain site
with long potential reach, tile crossings, and disconnected coverage. A 50 km disk at 200 m spacing
has roughly 196,000 receiver locations; a 10 km disk has roughly 7,850. Longer paths also increase
terrain sample work, so cell count alone is not an adequate cost estimate.

Separately compare profile sampling intervals against finer reference terrain to quantify missed
ridges and loss/threshold changes. Record raw-loss parity, valid/unknown masks, coverage changes,
download bytes, memory, cancellation, and runtime by stage. Test device-class budgets before
selecting shipped presets. Refinement must agree with an independent fine-grid run over matching
coordinates and inputs. The completed matrix provides initial evidence, not final device limits.
The adaptive follow-up now tests those outliers and additional paths with a 0.25 dB sampling-error
target separate from 0.01 dB platform parity. Preserve explicit unresolved states and extend the
fixtures to other terrain regimes and narrow ridges before selecting production settings. Next
qualify the download/disk-cache repository and device budgets using the newly bounded job/cache;
do not silently coarsen profiles to meet budgets.
This remains isolated prototype work before Candidate Sites UI or production dependency integration.

## Delivery gates and platform tasks

| Gate | Deliverable | Exit evidence |
|---|---|---|
| A1: Engine | Selected/pinned shared kernel, license/source audit, ABI, model domain, parameters | Native iOS/Android probe matches approved reference fixtures; short-range/warning policy recorded |
| A2: Terrain | Selected dataset, decoder/cache, sampling and privacy contract | Cross-platform identical tile/sample fixtures; nodata/date-line/edge tests; bounded downloads |
| A3: Feasibility/design | End-to-end bounded run and adaptive low-fidelity layouts | Measured budgets and compact/wide/fold states approved; numerical/performance limits frozen |
| B: Run contract | Standalone unsaved input/result state, known-node status fixtures | No plan required; matching classification and stale/cancel semantics |
| C: Predictions | Coordinator, numeric grid, exact node paths and provenance | Determinism/cancel/stale-input tests and reference comparison report |
| D: Map/results | Overlay adapters, legend, cell inspection, node list and matched link detail | Geographic alignment and accessible list parity on both Android map providers and iOS |
| E: Save/release | Shared Saved Plans for both tools, migration, help and device matrix; compare/export deferred | Reopen/migration tests, acceptance walkthrough, builds, limitations and numerical validation |

Suggested iOS modules: Models/NodePlanner domain DTOs; Networking repositories; a shared native RF
package; an observable planner session; CandidateSitesScreen and map overlay adapter. Android mirrors
these with core/model DTOs, core/storage repositories, NDK/JNI wrapper, ViewModel/StateFlow and Compose
workspace plus MapLibre/Google adapters. No duplicate numerical implementations. Introduce SwiftUI
and Compose integration only after the respective framework guidance is reviewed during coding.

## Acceptance and evidence

Numerical checks: flat path, single/multiple ridges, narrow valley, mountain shadows, coastal water,
below-sea-level terrain, tile seam, high latitude, antimeridian, very short/out-of-domain path,
missing/corrupt tile, zero/large heights and unit conversions. Run a common serialized fixture set
on both platforms. Same-kernel target: identical validity masks and classifications, finite outputs
within 0.01 dB unless documented platform behavior requires tighter investigation. Independent
reference tolerances must be chosen from identical-model/terrain experiments, not arbitrary visual
agreement. Record all parameters; cross-model disagreement is not automatically an implementation bug.

Test exact-node output against grid samples using identical receiver inputs; account for deliberate
grid approximation. Confirm parallel and serial output equivalence, raw-value recoloring invariance,
terrain version invalidation, cancellation at each stage, source/profile edits during work, disk-full
save, process death and offline cache eviction. Check terrain omissions cannot become favorable
coverage. Verify known-node catalog completeness and protocol-compatibility caveats.

UX release walkthrough on each platform: create candidate → configure → run → inspect a cell and
known node → open matching path → duplicate and raise antenna → rerun/compare → save → relaunch
offline → export. Check phone portrait/landscape, iPad multitasking, Duo and Android fold/tabletop,
large text, VoiceOver/TalkBack, light/dark and both Android maps. The map/session stays stable while
resizing and essential controls never cross the hinge.

Outstanding before implementation-ready sign-off: engine/model selection and license audit; terrain
source and datum policy; presets/receiver defaults and bands; model/sampling agreement with Link
Profile; measured resource limits; native build proof; reference fixtures and tolerances; MeshPeaks
manual review; and adaptive empty/editing/running/result/error/large-text sketches. These are concrete
Gate A tasks, not decisions to defer until after the feature is built.
