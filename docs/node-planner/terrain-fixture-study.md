# Terrain diversity and ridge accuracy gate — 2026-10-30

**The existing adaptive policy fails the expanded accuracy gate.** Additional real-terrain paths
pass, but synthetic ridges show that repeated agreement between uniform sample intervals can
accept a materially wrong loss estimate. Platform agreement does not establish correctness.
No production app code or historical experiment was changed.

Follow-up: the separate [feature-profile candidate](feature-profile-study.md) now passes these
saved counterexamples and the expanded matrix. The failures below remain historical evidence
for the original uniform-sampling policy; they have not been overwritten or relabeled as passes.

## Executed fixtures

The same pinned engine and radio parameters were used on both mobile runtimes. Real-terrain
tests reuse the original mosaic at three sites: (51.1, −113.9), (51.0, −114.7), and (51.4, −116.0).
Descriptive labels are eastern plains, foothills and western mountains. Each site has twelve
bearings and distances of 1, 5, 10 and 20 km: 144 paths total, with independent uniform 1 m reference
execution. This expands sites within one regional dataset, not global or climatic coverage.

The 56 synthetic profiles comprise flat, rolling and valley profiles plus triangular ridges
100 m above a 200 m base. Paths are 1 or 10 km; ridge half-widths are 0.75, 2, 5, 15 and 30 m;
offsets shift their center by 0–4 m relative to 40% of the path length. Uniform 0.25 m references
were checked again at 0.125 m. These analytic profiles bypass raster interpolation; sub-DEM
features are deliberately adversarial, not terrain recovered from the one-arcsecond source.

| Check | Result on both platforms |
|---|---|
| Real-terrain paths | 141 qualified, 2 accuracy-unresolved, 1 threshold-unresolved |
| Maximum qualified real-terrain error | 0.058085 dB; 0 failures against 0.25 dB target |
| Synthetic profiles | 36 qualified, 20 accuracy-unresolved |
| Incorrectly qualified synthetic profiles | 20 / 36 |
| Maximum qualified synthetic error | 45.125245 dB |
| Synthetic reference change, 0.25 → 0.125 m | At most 0.0000117 dB |
| Maximum platform loss difference | 2.36 × 10⁻¹⁰ dB |
| Threshold flips at the illustrative 152 dB loss threshold | 0 |

Failures occur at every tested ridge-width group, including the 60 m full-width group. The largest
counterexample is a 1 km path with a ridge centered at 401 m and half-width 0.75 m: the policy
stops at 5 m and reports 91.631948 dB, while the dense reference reports 136.757192 dB. The nested
30/10/5 m sampling locations miss the ridge, so repeated agreement is misleading. No threshold
flip in this particular suite rescues a 45 dB numerical error; other radio thresholds can differ.

Both simulator/emulator runs agree on states and sample counts. Both iOS framework targets build.
All 26 unit tests pass, including a regression that explicitly reproduces the old blind spot;
that test passing means the defect remains reproducible, **not** that the policy is approved.
The scientific analyzer saves the failing cases and intentionally exits with code 1.

## Decision and next implementation

Do not promote the current repeated-agreement rule into a generally qualified production sampler.
The previous studies remain valid evidence for their specific fixtures and cache/budget behavior.
They never proved a universal accuracy bound, and this study now supplies concrete counterexamples.

The next isolated implementation should build profiles that preserve features represented in the
source terrain: retain path/DEM-cell boundary crossings and relevant extrema of the interpolated
profile, then validate model sensitivity against dense references. For analytic tests, retain the
known ridge vertices. Do not claim that adding a few phase-shifted uniform samples guarantees
feature preservation; use adversarial phase tests to challenge any approximation. Features absent
from the DEM remain a separate data-resolution limitation.

Validate this candidate against these exact counterexamples, the regional paths and historical
fixtures, with unchanged 0.25 dB accuracy and 0.01 dB platform targets. Include missing data and
work exhaustion: unqualified results must remain explicitly unknown. Revisit work accounting for
nonuniform sampling before connecting it to the bounded scheduler. Additional independent terrain
regions, propagation references, repository/download limits, engine distribution and physical-device
qualification remain open. App overlay integration stays behind these gates.

See [machine-readable failures and results](feasibility/terrain-fixtures/summary.json),
[unit tests](feasibility/terrain-fixtures/tests.json),
[source hashes](feasibility/terrain-fixtures/source-manifest.json), and
[reproduction instructions](../../tools/rf-study/coverage/terrain-fixtures/README.md).
No map preview was regenerated, and no app UI or visual checks were performed.
