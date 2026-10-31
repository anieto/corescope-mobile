# Bounded coverage work and terrain memory — 2026-10-30

The isolated Candidate Sites prototype now enforces an aggregate adaptive sampling budget and
reads terrain through a bounded disk-backed cache. Both mobile runtime experiments pass. This
advances v0.10.1 feasibility; it does not integrate coverage into either app or qualify phones.

## What changed

Preflight rejects oversized grids and budgets that cannot fund even the first three qualifying
sampling passes for every eligible receiver. Accepted jobs enforce a second limit while running:
each path can spend only the remaining whole-job allowance, up to its own cap. Budget exhaustion
leaves later cells explicitly `NOT_COMPUTED`; it never silently reduces sampling quality or marks
unfinished terrain as uncovered. Cancelled and completed jobs cannot resume.

The new terrain provider reads 128 × 128 blocks from the original signed-height raster. It evicts
the least recently used block before allocating another. The former full-file mapping is absent
from these drivers. Failed/truncated reads and nodata remain missing terrain; cancellation discards
partially loaded blocks. The original DEM, model and adaptive accuracy policy remain unchanged.

The decoded-payload cap includes resident plus in-flight tile arrays, but excludes container
overhead, temporary row buffers, uncollected garbage, RF profile/model allocations and OS cache.
This is not an absolute process-memory cap. The local raster remains 152,704,834 bytes on disk.
Network fetching, disk cache quotas, tile integrity recovery and geographic source selection are
still future repository work.

## Results

| Scenario | Output cells | Outcome | Charged samples |
|---|---|---|---|
| 20 km radius, 2 km output grid | 441 | Complete: 315 qualified, 1 threshold-unresolved, 125 masked | 1,588,240 |
| 4 × 4 km refinement, 200 m grid | 441 | Complete: 438 qualified, 3 accuracy-unresolved | 1,987,139 |
| 100 km radius, 20 km grid | 121 | Complete: 80 qualified, 41 masked | 2,104,983 |
| Refinement with minimum-work budget | 441 | Work limit: 402 processed, 39 uncomputed | 1,799,135 / 1,799,201 |

Masked cells are outside the requested radius or within the 250 m near-site exclusion. The
limited job's processed cells include 399 qualified, 2 accuracy-unresolved and 1 work-limited.
The coarse 100 km run is a workload fixture, not a useful production coverage resolution.

The 20 km and refinement scenarios ran with both **128 KiB and 2 MiB** cache capacities. Their
states, sample counts and losses matched exactly within each platform despite forced eviction.
Every scenario's states, work counts and cache counters matched across platforms; the largest
loss difference was **4.20 × 10⁻¹⁰ dB**, well within the 0.01 dB parity limit. All 441 refinement
cells also matched the previous independently executed full-raster adaptive experiment, with
maximum loss difference **4.18 × 10⁻¹² dB**. This verifies that cache eviction preserves the prior
results; it is not a new independent RF model or sampling-accuracy validation.

All **24 unit tests passed**, including eight new cache/job tests. The simulator and physical-device
arm64 frameworks compile. Live cancellation stopped both runtime jobs and neither could resume;
observed flag-to-exit times were 0.0042 ms on iOS and 0.0161 ms on Android. These single trials
interrupted sampling/loading; the synchronous RF kernel remains non-interruptible.

## Memory and I/O tradeoff

Recorded whole-process peak RSS was **45,383,680 bytes (43.3 MiB)** on iOS and
**151,519,232 bytes (144.5 MiB)** on Android. The earlier adaptive validation batch recorded
172.5 MB and 297.9 MB, respectively, but the workloads differ: those numbers must not be treated
as a controlled memory reduction benchmark. App integration and additional app memory remain
unmeasured. Cache counters independently establish the configured decoded-payload limit.

The small cache deliberately stresses eviction. For the refinement, 128 KiB caused 8,308 evictions
and 272,367,616 bytes of logical terrain reads. The 2 MiB cache held all ten required blocks,
used only 327,680 bytes of tile payload and performed no eviction. Logical reads fell to 327,680
bytes. The 20 km large-cache run still evicted 72 blocks but reduced logical reads from 190.6 MB
to 4.46 MB. These are requested disk-read bytes, not measured physical storage I/O; OS caching helps.

| Scenario/cache | iOS elapsed | Android elapsed |
|---|---|---|
| 20 km / 128 KiB | 4.72 s | 3.65 s |
| 20 km / 2 MiB | 1.74 s | 0.81 s |
| Refinement / 128 KiB | 3.60 s | 3.49 s |
| Refinement / 2 MiB | 0.82 s | 1.61 s |
| Coarse 100 km / 128 KiB | 3.24 s | 5.61 s |

Runs were sequential across platforms. Timings include cache loading, propagation and CSV string
creation, but exclude writing the output file. They are single runs with differing warmup, file
cache and GC states; they do not select a production cache size or establish latency percentiles.
The sample budget bounds model work, not elapsed time, energy or a separate I/O-byte quota.

## Next gates

1. The subsequent [terrain/ridge study](terrain-fixture-study.md) passes the additional regional
   terrain paths but exposes 20 incorrectly accepted synthetic profiles. Implement and validate
   feature-preserving profiles before choosing production sampling defaults. Keep uncertainty explicit.
2. Extend this local block cache into the terrain repository contract: bounded downloads/disk usage,
   corrupt/missing tile handling, attribution, cancellation and cache pressure. Evaluate an I/O budget
   and a cache size large enough to avoid the measured rereading.
3. Resolve engine distribution/licensing and build representative packaged device probes. Measure
   incremental memory, time, cancellation inside long RF calls, lifecycle, thermal and battery
   behavior on physical iOS/Android hardware. Do not infer those gates from simulator RSS.
4. Once qualified, integrate Candidate Sites persistence and coverage overlays with job identities
   that prevent superseded results from publishing. Saved Link Profiles remains deferred.

See [machine-readable results](feasibility/bounded-coverage/summary.json),
[tests](feasibility/bounded-coverage/tests.json), [source hashes](feasibility/bounded-coverage/source-manifest.json),
[reproduction instructions](../../tools/rf-study/coverage/bounded/README.md), and the prior
[adaptive study](adaptive-sampling-study.md). Prior previews remain historical fixed-profile
experiments; no new app UI, visual checks or production app builds were performed here.
