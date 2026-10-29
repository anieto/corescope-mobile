# 0.9.0 large-selection validation

## Android repository evidence — 2026-10-20

`MyNodesBaseRepositoryTest.largeSelectionDrainsBoundedBatchesThenLoadsEntirelyFromCache`
uses 500 synthetic node keys, an in-memory yielding server and a fixed clock. It asserts every
batch's order, newly fetched/cached/deferred counts, total request count and peak concurrency.

| Measurement | Observed |
|---|---|
| Completion | All 500 nodes reached in 25 batches |
| Requests per batch | 20 |
| Peak concurrent requests | 2 |
| Total fetches | 500 |
| Fully warm reload | 500 cached rows, zero additional fetches |
| Total synthetic batch duration | 45.97 ms |
| Fully warm reload duration | 0.61 ms |
| Repository suite | 9 tests passed |

Measured on the development Mac (M5-Air.local), JVM unit tests. These are single-run diagnostic
measurements, not timing assertions, network benchmarks, memory measurements or UI frame times.
The fixture omits the observer catalog. Existing catalog-budget tests separately verify four
catalog requests leave at most sixteen node requests in the same twenty-request allowance.
Existing first/second-batch tests cover 0, 1, 20, 100 and 500 selections on both platforms.
The new full-drain test has run on Android only.

Both Activity screens already use lazy scrolling containers (SwiftUI LazyVStack and Compose
LazyColumn). This source observation does not establish smooth scrolling or cheap row evaluation.

## Still required

- Execute the new iOS full-drain test. It builds successfully, but has not been run; simulator
  execution remains user-owned.
- Measure physical-device scrolling, memory and row update costs with 100 and 500 favorites,
  including combined account/device membership and long names.
- Check maximum text size and narrow/wide windows while scrolling.
- Verify slow, partial, offline and rate-limited sources retain responsive controls.
- Measure real network timing only against approved test sources; never equate fixture timing
  with production latency.

## iOS test preparation — 2026-10-20

Added `MyNodesBaseRepositoryTests.largeSelectionDrainsBoundedBatchesThenLoadsEntirelyFromCache`
with the same 500-node, 25-batch assertions and monotonic timing output as Android. A fixed data
clock prevents cache expiry from making the regression dependent on host speed. Build-for-testing
passes; no iOS timing or test-pass result is claimed until execution.
