# Terrain repository contract and failure study — 2026-10-31

The host-side terrain repository prototype passes **50 tests** and an eight-tile real-data replay.
It implements bounded transfer/staging, checksum validation, persistent LRU storage, in-use leases,
complete pinned-set preparation, offline recovery and cancellation cleanup. This is an executable
contract and fixture set for mobile adapters; it has **not** been integrated or run as an iOS/Android
repository. The existing coverage sampler and app provider are unchanged.

## What the tests establish

The repository preserves signed HGT elevations, including below-sea-level values and the −32768
nodata sentinel. HTTP 404, malformed dimensions, corrupt gzip, checksum failures and unavailable
offline data produce explicit failures; none become zero-height terrain. Failed or cancelled
downloads never become visible cache entries. Completed entries survive restart and are checksum
validated before being leased again.

Downloads reserve compressed, decoded and metadata space before starting. A shared payload-byte
budget covers requests/retries, and known insufficient budgets fail before network contact.
Concurrency is one transfer, retries are capped, redirects and transparent content encoding are
rejected, and decompression cannot write beyond the expected tile dimensions. Cache leases protect
active tiles from eviction; failed multi-tile preparation releases its acquired pins while retaining
valid completed downloads as ordinary cache entries.

The failure suite exercises missing/truncated/oversized responses, temporary/permanent server
failure, rate limiting, cancellation during streaming and before commit, a streaming deadline,
decompression expansion, corruption, disk-write/rename failures, recovery after interrupted staging,
offline reuse, cache purging, LRU pressure and oversized pinned-set rejection. These are controlled
loopback tests, not measurements of a public terrain service or native mobile networking.

## Real pinned-tile replay

The study replays the eight exact source files from the previous wide-area experiment over a local
HTTP server. Original provider URLs and content pins remain in provenance; replay URLs are recorded
separately. No new requests were sent to an external terrain provider.

| Measurement | Result |
|---|---|
| Source compressed payload, eight tiles | 84,062,769 bytes |
| Decoded HGT payload, eight tiles | 207,475,216 bytes |
| Decoded hashes and signed byte-pair checks | All 8 hashes and 832 samples match |
| Configured disk reservation quota | 75,000,000 bytes |
| Peak observed payload/staging reservation | 68,326,413 bytes |
| Complete pinned set, including metadata/control | 207,544,848 bytes |
| Conservative pinned-set staging reservation | 223,981,977 bytes |
| Full eight-tile preparation under 75 MB quota | Rejected before any request |
| Sequential-access LRU evictions | 8 |
| Offline restart and interrupted-download cleanup | Passed |

The storage distinction matters: a 75 MB working cache can replay tiles sequentially, but it cannot
retain all eight for one analysis. The planner must request a sufficiently sized cache or a smaller
terrain footprint; silently evicting in-use tiles is not an acceptable fallback. The metadata-only
plan is provisional until cached content is validated.

The cancellation trial stopped after a partial real gzip transfer, left no partial entry and released
the staging reservation. Exact transferred bytes and observed duration are saved in
[the result manifest](feasibility/terrain-repository/summary.json); they are scheduler-dependent
single-run observations, not cancellation guarantees. Overall replay traffic includes initial pin
tests, re-fetching after eviction and the interrupted request, so it exceeds the eight-file total.

## Native implementation contract

Cache identity includes dataset/revision, content pin, tile geometry/resolution, decoder, datum,
source type and attribution. It excludes transport URLs so verified mirrors retain identity.
Canonicalization explicitly uses integer JSON numbers and literal UTF-8 to avoid Swift/Kotlin
serialization differences. Six [cache-key vectors](feasibility/terrain-repository/cache-key-vectors.json)
cover Unicode, mirrors, changed versions/resolution and southern/eastern coordinates.

Metadata retains footprint, source/replay URL, fetch time, original and decoded digests, nominal
resolution, datum, signed decoder, nodata, no-resampling policy and attribution. Per-pixel source
lineage and the surface/bare-earth distinction remain unresolved for the source dataset; this
repository does not make those provider decisions implicitly.

The first [native leased-reader component](native-terrain-reader-study.md) now passes host Swift
and Kotlin tests; it has not yet been connected to native cache ownership or the coverage sampler.
Next implement native networking/storage adapters against these fixtures, with serialized ownership,
native task/resource cancellation and the scheduler's job-generation checks. Add a leased tile reader
that preserves the established seam/interpolation policy, geographic footprint/halo selection and
date-line behavior. Then exercise missing/corrupt tiles, quota changes, storage pressure and offline
jobs on both mobile platforms. Actual service terms/provenance, distribution, independent RF checks
and representative physical-device performance remain release gates.

## Limits of this stage

Disk counters cover owned file-payload reservations, not filesystem allocation, OS caching or
process memory. Atomic visibility/recovery is tested; power-loss durability is not proven. The host
HTTP adapter checks a streaming-body deadline and socket idle timeout, but does not establish an
absolute deadline during DNS/TLS/slow header parsing. Native resource deadlines remain necessary.
No app builds, UI checks or new coverage preview were required for this repository contract.

See [test evidence](feasibility/terrain-repository/tests.json),
[source hashes](feasibility/terrain-repository/source-manifest.json), and
[reproduction instructions and detailed contract](../../tools/rf-study/coverage/terrain-repository/README.md).
