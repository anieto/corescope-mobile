# Feature-preserving profile candidate — 2026-10-30

The new isolated profile builder passes the saved ridge failures and expanded terrain fixture
matrix on both mobile runtimes. It retains terrain features before comparing progressively finer
RF profiles. This repairs the demonstrated sampling blind spot for these fixtures; it is not a
universal accuracy guarantee or production app integration.

Follow-up: the [whole-map feature scheduler](feature-grid-study.md) now budgets both work counters
and passes integration checks. That separate experiment also provides the updated coverage preview.

## Construction

For real terrain, the builder splits the geodesic at latitude extrema, finds every DEM row/column
crossing, and retains those positions. Within each cell it fits the interpolated heights using
endpoints and midpoint, then retains any interior peak or valley. Two quarter-point checks reject
the fit if elevation discrepancy exceeds 0.01 m. Crossing searches stop at 0.1 mm precision before
millimetre canonicalization, avoiding unnecessary fixed search iterations.

Each 30/10/5/2/1 m RF pass includes the retained features. The existing repeated loss-agreement
and reception-threshold guard remain additional checks. For synthetic ridges, exact base/peak
vertices are supplied by the analytic fixture; they are not discovered from the one-arcsecond DEM.
The valley fixture similarly supplies its center. Flat and smooth rolling controls use endpoints
plus the uniform sampling passes.

A straight segment through bilinear terrain gives a quadratic height profile, but the spherical
geodesic in raster coordinates is only approximated this way. Quarter-point checks and this test
matrix provide empirical validation, not a mathematical bound for arbitrary terrain. Features
missing from the source DEM cannot be recovered by this method. Polar and antimeridian cases
remain outside this study's supported domain.

## Executed results

| Check | Result on both platforms |
|---|---|
| Saved incorrectly accepted ridge profiles | All 20 now accepted within the accuracy target |
| Complete synthetic suite | 56 / 56 qualified |
| Worst synthetic error against 0.125 m reference | 0.000693 dB |
| Real-terrain suite | 896 / 897 qualified; 1 threshold-unresolved |
| Worst qualified terrain error against 1 m reference | 0.120285 dB |
| Accuracy target | 0.25 dB, unchanged |
| Maximum platform loss difference | 3.65 × 10⁻⁹ dB, within 0.01 dB tolerance |
| Unit tests | 33 passed, including 7 new feature-profile regressions |

The real-terrain suite includes all 753 prior mountain-site paths/refinement receivers and the
144 additional regional paths. The synthetic suite is the same 56-profile adversarial matrix.
Output states, feature counts, work counts and final spacing agree exactly across platforms.
Serialized input bearings/distances allow 1e-8 tolerance for floating-point formatting differences;
this does not relax loss or state comparisons. Accepted profiles have no threshold classification
disagreements with the references. Unknown results publish no loss.

The 100 km / 240° path now reports 199.422871 dB versus 199.302586 dB in the 1 m reference, within
the unchanged target. The 285° path now qualifies at 207.784766 dB versus 207.816716 dB. Retaining
vertices changes the profile representation, so agreement is assessed numerically rather than by
expecting bit-identical losses to uniform profiles. Candidate and reference use the same engine;
these checks do not establish independent model correctness or measured RF coverage.

## Resource and cancellation contract

Feature discovery requires two separately bounded kinds of work:

- Terrain/model sampling positions: at most 250,000 per path, including five discovery evaluations
  per cell and the complete merged point count reserved before each propagation pass.
- Geometry-search evaluations: at most 200,000 per path, including coordinate root searches.

Observed maxima were 83,708 sampling positions and 182,674 geometry evaluations. No final fixture
exhausted either budget. Explicit low-budget tests return `WORK_LIMIT` without publishing a loss.
Cancellation is checked during geometry discovery and sampling; tests exercise interruption during
discovery, pre-cancellation and missing data. The propagation kernel itself remains synchronous.
This stage does not claim live cancellation latency, process memory or device performance metrics.

The old whole-map scheduler accounts only for the previous sampling policy and **must not be
connected unchanged**. Next update job preflight/reservations to budget both counters and verify
exhaustion, cancellation, stale-job publication and cache behavior with this new builder. Array,
sorting, I/O and runtime costs also need measurement; operation counts alone do not establish
memory, energy or wall-clock bounds.

## Remaining gates

After scheduler integration, continue the bounded download/disk-cache repository work and test
additional independent terrain regions and propagation references. Resolve engine distribution
and qualify representative packaged physical devices before selecting shipping defaults or
integrating Candidate Sites persistence and overlays. The iOS device and simulator frameworks
compile; execution here used an iOS simulator and Android emulator only. Saved Link Profiles
remains deferred.

See [machine-readable evidence](feasibility/feature-profile/summary.json),
[tests](feasibility/feature-profile/tests.json), [source hashes](feasibility/feature-profile/source-manifest.json),
[reproduction instructions](../../tools/rf-study/coverage/feature-profile/README.md), and the
[historical failed accuracy gate](terrain-fixture-study.md). No app UI, map preview or visual checks
were changed or exercised.
