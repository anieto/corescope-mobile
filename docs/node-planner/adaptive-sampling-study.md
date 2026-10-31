# Adaptive profile sampling study — 2026-10-30

The isolated Candidate Sites v0.10.1 experiment passes its numerical acceptance gate on iOS and
Android. This qualifies the tested fixture, not a universal RF accuracy bound or a shipping default.
No app integration or UI changes were made.

## Policy

The shared Kotlin sampler evaluates each entire path at 30, 10, 5, 2 and, if needed, 1 metre
spacing. It accepts a result only after two consecutive loss changes of at most 0.1 dB, at 5 m
or finer, and more than 0.25 dB from the illustrative receiver threshold. This is adaptive
whole-path density, not local terrain-feature insertion. All passes use the same original
1-arcsecond DEM; finer interpolation does not add terrain detail.

The external acceptance target is at most **0.25 dB** difference from a separately executed
uniform 1 m reference, with identical above/below-threshold classification. Platform agreement
has a separate **0.01 dB** tolerance. The reference uses the same propagation engine and terrain,
so this tests sampling sensitivity, not independent model correctness or measured reception.

Each path reserves its next pass's sample count before starting. Normal fixtures permit 200,000
sample evaluations; the API rejects budgets above 250,000 and distances outside 250 m–100 km.
`samplesUsed` is charged work, including a full reserved pass if cancellation/nodata stops it early.
Unresolved accuracy, threshold proximity, exhausted budget, missing terrain, invalid input/model
and cancellation have explicit states and no published loss. The last diagnostic estimate remains
separate. Unknown results must never become uncovered or confidently colored map cells.

## Executed results

The suite includes 168 original paths, 144 additional bearings/distances, 441 receivers across the
previous refinement region, and two explicit budget/threshold fixtures: **755 rows per platform**.
The additional paths span 750 m–99 km; original paths extend to 100 km.

| Check | Result on both platforms |
|---|---|
| Ordinary paths accepted | 748 / 753 |
| Ordinary paths unresolved | 5 / 753 |
| Maximum accepted error versus 1 m | 0.129293 dB |
| 95th percentile accepted error | 0.013299 dB |
| Threshold classification disagreements among accepted results | 0 |
| Maximum iOS/Android loss difference | 1.003 × 10⁻⁹ dB |
| Charged adaptive samples / dense reference samples | 39.48% |
| Final interval: 5 / 2 / 1 m | 701 / 38 / 14 paths |
| Unit tests | 16 passed, including 6 adaptive policy tests |

The previous 100 km / 240° outlier now refines to 2 m and differs from the 1 m reference by
0.000251 dB. The 285° outlier remains explicitly unresolved: it reaches 1 m but lacks two
consecutive stable comparisons. The budget fixture returns `WORK_LIMIT`; the threshold fixture
returns `THRESHOLD_UNRESOLVED`. No failing result publishes a loss.

Observed cancellation latency was 0.057 ms on iOS and 1.525 ms on Android. This is one sampling
cancellation trial, not a worst-case guarantee; the synchronous propagation call remains
non-interruptible. Both simulator and device arm64 frameworks compiled; only the simulator ran.

Total validation batches took 18.69 s on iOS and 15.47 s on Android, including dense references
and CSV creation. Runs overlapped and these timings are not a platform performance comparison.
Whole-process peak RSS was 172,507,136 and 297,910,272 bytes respectively. Neither figure measures
incremental app memory. The sample ratio is not a measured speedup; difficult paths may cost
more than a single dense pass.

## Limits and next work

Repeated numerical agreement can miss a shared sampling blind spot. The tested region and
holdouts do not establish a universal 0.25 dB guarantee. Add other terrain regimes and explicit
narrow-ridge fixtures before selecting production settings; consider terrain-cell crossings or
feature-preserving profiles if those expose failures. Keep unresolved states available.

The subsequent [bounded coverage study](bounded-coverage-study.md) implements aggregate adaptive
job limits and a local terrain-block working set without changing this historical experiment.
Physical-device cancellation, memory, runtime and lifecycle qualification remains pending. This
study's per-path cap alone must not be multiplied across an unrestricted map grid. Engine
distribution/licensing and production terrain provenance gates remain open. Map integration
follows those gates; Saved Link Profiles remains deferred.

The terrain, radio parameters and pinned engine are unchanged from the
[wide study](wide-coverage-study.md). See [machine-readable evidence](feasibility/adaptive-sampling/summary.json),
[test results](feasibility/adaptive-sampling/tests.json),
[source hashes](feasibility/adaptive-sampling/source-manifest.json) and
[reproduction instructions](../../tools/rf-study/coverage/adaptive/README.md).
The earlier preview still depicts the earlier fixed-profile experiment; it was not regenerated
or relabeled as adaptive coverage. No visual/light/dark-mode checks or production app builds were
performed in this numerical experiment.
