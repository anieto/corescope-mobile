# Native leased terrain readers — 2026-10-31

The first native storage component now exists in Swift and Kotlin: a leased, checksum-validated
raw HGT reader. Both implementations preserve signed/nodata samples and bound encoded reads to
64 KiB. This is isolated prototype code; neither app nor its existing Link Profile provider changed.

## Results

| Check | Swift / macOS | Kotlin / host JVM |
|---|---|---|
| Test functions | 13 passed | 12 passed |
| Pinned real HGT files | 8 | 8 |
| Reads compared with independent Python decoder | 848 matched | 848 matched |
| Signed samples compared | 576,505 matched | 576,505 matched |
| Maximum encoded sample read | 65,536 bytes | 65,536 bytes |
| Pin release on close and failed open | Passed | Passed |
| Cancellation, corrupt digest, bad sizes, missing files and truncation | Passed | Passed |

The test count difference is Swift's automatic deinitialization check; Kotlin uses explicit
`use`/`finally`. Parameterized checks cover invalid dimensions/digests, integer bounds and cancellation
at different checkpoints. Real-data tests cover tile corners, row transitions, maximum-size reads,
final blocks and seeded random runs. Expected values come from Python `struct` signed big-endian
decoding of independently hash-checked pinned gzip sources. Both readers rehash the complete decoded
file before accepting it. No external service was contacted.

## How this connects to the repository

The repository will transfer one cache pin and trusted decoded digest to the reader. The reader owns
that pin even if opening fails, closes its file before release, and retains it through cancelled or
failed reads until explicitly closed. This establishes the lifetime boundary needed to prevent LRU
eviction while coverage computation is reading a tile. The tests count release callbacks; actual
native repository eviction is not yet connected or tested.

Each reader belongs to one background worker, with no concurrent read/close calls. Pinned files
must remain immutable. Cancellation is checked between bounded file reads; the tests do not prove
an upper latency bound for an individual blocking OS read. Nodata stays −32768 so the future sampler
adapter can report unknown terrain instead of inventing a zero-height path.

## Remaining work

Implement native download/disk-cache ownership and cancellation against the prior
[repository contract](terrain-repository-study.md), then connect these readers to a tile mosaic
with explicit north/east seam ownership, geographic halos and date-line selection. Feed that tiled
source into the feature-aware scheduler and replay the existing path/grid references. Exercise the
complete chain on iOS/Android and measure representative devices before choosing shipping defaults.

This run used macOS Swift 6.4 / Swift Testing and Java 17 / Kotlin 2.2.20 / JUnit 4.13.2. It does not
qualify either mobile runtime, full cache/downloader, RF model accuracy or UI. No application build
or visual check was needed for these isolated sources. The fixture preparation harness holds whole
tiles in memory; only the native readers use bounded reads. No total-memory claim follows from that
I/O bound.

[Reproduction and ownership contract](../../tools/rf-study/coverage/native-terrain-reader/README.md),
[result manifest](feasibility/native-terrain-reader/summary.json),
[source hashes](feasibility/native-terrain-reader/source-manifest.json).
