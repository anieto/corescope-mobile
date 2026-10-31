# Terrain repository executable contract

Host-side Python prototype using real files, streamed HTTP, gzip and SHA-256. It is **not** linked
into either app and does not qualify iOS/Android networking or storage. It supplies deterministic
failure fixtures and cache-key vectors for native adapters, alongside the prior shared Kotlin
sampling/scheduler studies. No third-party Python packages are required.

## Reproduce

Run from the repository root; the tests/study start temporary loopback HTTP servers and stop them
on completion. The real study uses the already-pinned eight gzip files from the wide experiment.
It does not contact an external terrain provider.

```sh
python3 -m unittest discover -s tools/rf-study/coverage/terrain-repository -p 'test_*.py' -v
python3 tools/rf-study/coverage/terrain-repository/run_study.py /tmp/nodescope-coverage-wide/data docs/node-planner/feasibility/terrain-repository
python3 tools/rf-study/coverage/terrain-repository/cache_key_vectors.py docs/node-planner/feasibility/terrain-repository/cache-key-vectors.json
```

## Contract to implement natively

- **Identity:** SHA-256 of compact, key-sorted UTF-8 JSON with integer numeric fields, literal Unicode,
  no slash escaping and no Unicode normalization. Include dataset/revision, tile, source digest,
  compressed length, dimensions/resolution, datum, source-type/attribution and decoder policy.
  Transport/origin URLs are retained in provenance but excluded from identity so a pinned mirror
  does not invalidate data. Use the saved vectors, including Unicode and southern/eastern tiles.
- **Provenance:** source and replay URLs, fetch time, WGS84 footprint, stated vertical datum,
  nominal resolution, original compressed checksum, decoded checksum, signed big-endian decoder,
  nodata sentinel, no-resampling policy, attribution and unresolved source-type lineage. The sentinel
  is preserved in decoded HGT bytes; this prototype does not create a separate validity-mask raster.
- **Planning:** deduplicate at most 256 requested tiles; show expected missing payload bytes,
  worst attempt allowance, decoded/pinned sizes and worst staging reservation. Metadata-only cache
  estimates are provisional: every leased file is revalidated. Bound footprint selection separately;
  this contract accepts tile descriptors and does not select geographic halos itself.
- **Storage:** reserve compressed + decoded + metadata space before transfer. Committed entries
  reserve decoded bytes plus 8 KiB for metadata/replacement; 4 KiB covers repository control files.
  Quotas bound owned file-payload reservations, not filesystem blocks, inode/journal overhead,
  OS caching or process memory. Metadata copies are limited to 4 KiB each.
- **Pins:** leases prevent eviction. `acquire_many` prepares a complete pinned set or releases every
  lease it acquired on failure. Valid downloads from a failed preparation remain available as
  unpinned cache entries. Preflight conservatively reserves worst-order staging with the full set
  pinned, including unrelated active pins. Do not stream a whole study through LRU and call that
  equivalent to a retained terrain snapshot.
- **Downloads:** one serialized transfer; fixed attempt cap (two by default), byte limit shared
  across requests/retries, per-tile expected length plus at most one oversize-probe byte, and
  bounded 64 KiB reads/writes. Insufficient known budgets fail before a request. No redirects,
  proxy inheritance or transparent Content-Encoding changes. HTTPS is required except loopback
  test URLs. Temporary network failures and selected 5xx/408 responses get bounded retry; 404 is
  missing terrain and 429 is not immediately retried.
- **Cancellation/time:** check cancellation between reads, during hashing/decode, retry waits and
  before commit. A streaming-body deadline and socket idle timeout bound tested body stalls.
  These do not provide a proved absolute deadline during DNS/TLS/slow header parsing; native
  adapters need a resource deadline and direct task cancellation. No live-service latency or
  rate-limit policy is qualified by this loopback experiment.
- **Validation/commit:** compressed digest, exact decompressed dimensions and gzip CRC must pass.
  Limit decompression output before writing an oversized chunk. Stage within the cache filesystem,
  then atomically rename a complete entry directory. Cancellation/errors remove partial files.
  Atomic visibility is tested; power-loss durability is not established by rename alone.
- **Recovery:** exclusive process ownership, no deletion of unowned directories, interrupted-stage
  cleanup, strict committed metadata checks and decoded-byte checksum verification on every lease.
  Corrupt/missing cached data fails offline or is refetched online. Pinned corrupted data is never
  deleted under its reader. Cache clearing removes only unpinned terrain, independently of plans.

`Repository`, leases and planning use one serialized owner; `DownloadBudget` belongs to that owner.
Native callbacks will need explicit actor/thread ownership and the scheduler's captured generation.
The host `fcntl` lock is an experiment implementation, not a proposed shared mobile API.

## Validation scope

50 tests cover signed/nodata preservation, offline reuse, pinning/LRU, independent dataset identity,
restart/recovery, 404/503/429, redirects, missing/wrong content length, short/oversized bodies, digest
failure, corrupt gzip/decompression expansion, shared transfer budgets, cancellation/deadline,
staging/metadata/rename errors and complete pinned-set preparation. Real replay separately checks
all eight decoded hashes and 832 signed sample byte pairs, with quota pressure and offline restart.

Outstanding: native adapters and real mobile filesystem/network fixtures, the tile-to-profile reader
and seam ownership, geographic halo/date-line selection, provider/version/lineage audits, system
storage pressure/background cancellation, independent accuracy data and physical-device budgets.
This work does not change the existing Open-Meteo Link Profile provider.
