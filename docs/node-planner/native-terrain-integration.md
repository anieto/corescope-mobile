# Native coverage terrain integration

Updated 2026-11-02. Part of the [standalone Estimated Coverage implementation](estimated-coverage.md).

## Implemented first slice

The app targets now contain the native leased HGT readers from the earlier
[reader study](native-terrain-reader-study.md), plus matching Swift/Kotlin geographic mosaics.
These components compile into the apps but are not yet called by a coverage screen or RF job.
The existing Link Profile terrain provider and behavior are unchanged.

- Each reader validates dimensions, decoded SHA-256 and exact file size before use, streams hash
  reads in 64 KiB chunks, and reads bounded signed big-endian samples. Repository-supplied pins
  transfer on open, release on failure or close, and must keep files immutable.
- A mosaic consumes at most 256 unique readers, owned by one background worker. It rejects an
  aliased reader registered under two tile coordinates and releases readers on failed construction.
  Closing the mosaic is idempotent. The eventual repository must enforce one dataset/revision/datum;
  geographic keys alone are not provenance and the mosaic does not validate a source catalog.
- A coordinate selects its one-degree HGT tile using southwest integer bounds. Exact seams belong
  to the north/east tile; +180 longitude canonicalizes to -180. Latitude 90 uses tile 89's north
  edge. No adjacent-border fallback hides a missing owner tile. Geographic validity does not imply
  that the chosen provider actually supplies terrain at poles or any other coordinate.
- Bilinear interpolation uses only contributors with nonzero weight. A valid exact grid point stays
  usable beside nodata; a weighted nodata contributor returns NoData. MissingTile and NoData are
  distinct from valid zero/negative elevations. Invalid coordinates, corruption, I/O, closed readers
  and cancellation throw explicit errors; these must not become zero elevation or red node status.
- Point sampling reads at most two rows of two samples each. This is not a fast full-job cache,
  a path decimator or a ridge-preservation algorithm. Subsequent integration must retain the
  feature-preserving sampler's validation and measure the additional I/O cost.

## Validation

`shared/fixtures/coverage/terrain-mosaic.json` supplies 18 independently specified geographic cases
and nine tiny synthetic tiles to both platforms. Different duplicate border heights deliberately
expose wrong seam ownership. Cases include exact points, bilinear interpolation, southwest tiles,
negative elevation, both date-line representations, polar boundaries, missing owner tiles and
zero/nonzero-weight nodata. These fixtures establish software behavior, not terrain/model accuracy.

Seven Swift Testing tests and seven Android JUnit tests also cover cancellation at all eight
interpolation checkpoints, exact lease release, checksum/length/descriptor failures, truncation
after open, integer-overflow guards, closed reads, invalid coordinates, empty mosaics and tile caps.
The reader copies match the studied source (apart from Android's app package name); historical
real-tile replay evidence remains in the reader study and was not rerun as part of this change.

Validation performed:

- Xcode build including test targets passed; no simulator or UI tests launched.
- `python3 tools/node-planner/run_coverage_terrain_tests.py`: 7 host tests passed against actual app
  Swift sources, with no external terrain requests.
- Android `./gradlew testDebugUnitTest assembleDebug`: passed, 392 tests passed / 1 skipped overall.

## Next implementation

Implement the native download/disk-cache owner against the existing
[repository contract](terrain-repository-study.md): descriptor/cache identity, bounded preflight
and transfer/decode, atomic publication, offline recovery, pinning/LRU and complete-set acquisition.
It must supply the readers with verified decoded digests and consistent provenance. Wire geographic
footprint/halo selection, the feature-preserving profile sampler and the bounded scheduler next.
Then package the reviewed propagation kernel and implement the shared run/node-result contract
before connecting standalone Estimated Coverage UI and green/red/neutral known-node markers.

Native networking, decoder/cache ownership, full engine integration, area raster rendering,
physical-device budgets and node classification are not completed by this slice. Existing saved
site data and release version numbers are unchanged.
