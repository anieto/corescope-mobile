# Estimated Coverage performance checks — 0.10.5

First delivery includes radio-budget result reuse and bounded, per-path terrain preparation reuse. No parallel workers, radial sharing, or relaxed refinement tolerances are introduced.

## Adam's runtime checklist

Use the same physical device, study, terrain cache, and build configuration. Compare a small and a larger study, including an obstructed site. Avoid comparing a cold download with a warm calculation. Keep device temperature and debugger attachment consistent. Do not delete saved plans to clear terrain.

1. Run once to populate terrain, then reset/recalculate the same inputs three times. Save each **Last calculation report**. Record elapsed time, classifications, pending cells, and node margins.
2. Add `-CoverageDisablePathReuse` to the Xcode scheme's launch arguments. Repeat the same warm calculations three times. Remove the argument afterward. Compare median timings; predicted values and work-limit outcomes should match, except where cancellation or memory pressure interrupts a run.
3. After a completed study, change only power, gain, system loss, sensitivity, or reserve. Existing qualified losses should update without terrain/RF work. Paths within 0.25 dB of the new threshold, and previously accuracy/threshold/work-limited paths, remain pending for **Continue calculation**. Changing frequency, either antenna height, transmitter position, radius, spacing, or the node catalog must invalidate reuse.
4. Save/reopen the updated study; verify margins and colors persist. Continue pending work and confirm the retained terrain provenance is used. Reset or switch sources and confirm old results do not reappear.
5. Try cancellation, a scene/background transition, and narrow/wide layouts. Inspect memory on a physical device; simulator memory headroom is not equivalent.

## Report fields

`timingSeconds` includes `terrainPreparation` (discovery/download/cache preparation), `pathPreparation` (geometry, features, terrain reads), `rfPrediction` (including Swift/Kotlin conversion), and `rasterPreparation` (raster creation and scheduling, not MapKit's asynchronous drawing). Budget-only edits produce a new report with `budgetReuse` and no RF calls.

`performanceCounters` includes profile passes at each spacing, crossing-cache hits, elevation-cache hits, and actual elevation lookups. `peakObservedResidentBytes` samples process resident memory at path boundaries; it can miss transient peaks. `minimumAvailableMemoryBytes` remains physical-device-only. Use Instruments for detailed allocation and rendering profiles if these aggregate measurements identify a bottleneck.

Logical terrain/geometry charges deliberately remain unchanged on cache hits. This preserves work-limit behavior and separates a speed optimization from a later budget-policy change. Per-path cached elevations are capped at 250,000 entries; changing paths releases cached values, and closing the mosaic releases cache storage. Only one previous result is retained for budget edits, not a history of studies.

## Detailed path profiling

Leave `-CoverageDisablePathReuse` removed for this measurement. Run the same warm study with the profiling build. To force a full calculation with identical final inputs, change antenna height, finish editing, then restore its original value and calculate. Power-only edits intentionally reuse results and do not measure path preparation.

`crossingDiscovery` times all uncached crossing discovery. One in 1,024 interpolation and path-coordinate operations is timed; `sampledInterpolationInclusive`, `sampledReaderInclusive`, and `sampledPathCoordinates` contain actual sampled seconds, with corresponding `timed…Calls` counters. The `estimated…` fields extrapolate those observations to the run and may be biased by periodic access/cache patterns. Compare several runs before drawing conclusions.

Reader timing includes seek/read, byte assembly, decoding and returned-array allocation; it does not isolate physical disk access. Interpolation timing includes reader timing. Both are within `pathPreparation`, so do not add these nested durations together. Approximate interpolation overhead is the difference between interpolation and reader estimates. Remaining path preparation includes feature processing, sorting, cache lookup/management, and other uninstrumented work. Use Instruments if sampled estimates are inconsistent or the residual dominates.

## Terrain block cache comparison

The production fallback again uses four 16 KiB blocks per reader (64 KiB each, at most 16 MiB payload across 256 readers). The larger-cache experiment showed no speed improvement in the measured study. It retains the existing digest verification on open, range checks, cancellation, signed big-endian decoding, and nodata values. Cached files must remain immutable under their lease. Closing a reader clears its blocks. Large requests can span blocks; the last block is limited to the file length.

For block-cache comparisons, first disable mapping with `-CoverageDisableTerrainMapping`. Add `-CoverageDisableTerrainBlockCache` only to compare the small cache with uncached file reads. Keep path reuse enabled. The old `-CoverageSmallTerrainBlockCache` argument is no longer needed or used.

Reports include `terrainBlockCacheEnabled` and counters `terrainBlockHits`, `terrainBlockMisses`, `terrainFileReadCalls`, `terrainFileReadBytes`, and `terrainCachedBlockBytes`. File counters cover prediction-time reader operations, excluding initial digest verification. Resident-memory measurements include the whole app; cached-block bytes include only payload still retained when the report is collected. Compare coverage and node predictions as well as elapsed time. No speedup is claimed until measured on device/simulator.

## Read-only mapped terrain

Mapping is enabled by default after full size and SHA-256 validation, using the same file descriptor. The repository must keep each leased file immutable (including its length); truncating a mapped file violates that contract and can fault the process. The mapping is read-only/private, decoded byte-wise without alignment assumptions, and unmapped before the lease is released. A failed mapping falls back to the small block cache. Cancellation and range validation apply to both readers.

For the next normal run, remove `-CoverageDisableTerrainMapping`, `-CoverageDisablePathReuse`, and `-CoverageDisableTerrainBlockCache`. To compare against the approximately 41-second small-cache baseline, add only `-CoverageDisableTerrainMapping` for the comparison run, then remove it.

Reports include `terrainMappingRequested`, `terrainMappedReaders`, `terrainFallbackReaders`, `terrainMappedReadCalls`, and `terrainMappedAddressBytes`. Mapped address bytes represent virtual file mappings, **not resident RAM**. Resident pages depend on access and OS reclamation; retain physical-device memory checks and cancellation testing. File-read counters exclude initial integrity verification and OS page faults, so zero API reads does not mean zero storage I/O. The returned sample array is still allocated; temporary `Data` buffers and file seeks are bypassed.

## Prediction parity and physical-device check

Reports now include a versioned `predictionFingerprint` covering every grid cell's state/loss/margin and each exact-node coordinate/verdict/margin. Run identical inputs and terrain with mapping enabled, save the report, then rerun with only `-CoverageDisableTerrainMapping` enabled and save that report. The fingerprints should match exactly. Different work-limit outcomes caused by cancellation or memory pressure can legitimately differ and must be inspected rather than accepted as parity. Matching fingerprints establish implementation consistency, not real-world RF accuracy.

Run `TerrainMappedPredictionTests` in Xcode for synthetic production-kernel parity and scalar-pair edge/nodata/cancellation checks. These have been built but not executed by the coding assistant. The mapped interpolation path now returns two scalar values rather than allocating a sample array per row; the general array reader and bounded fallback remain available.

On a physical iPhone, run the same study three times, save the reports, and watch Xcode's memory gauge. Include a larger study and cancellation followed by restarting. Confirm the app stays responsive and the reports show memory headroom; note any low-memory pause. After resetting/leaving the feature, inspect whether retained memory stabilizes across repeated runs. `peakObservedResidentBytes` samples at path boundaries and can miss brief peaks. For precise memory/allocations use Instruments. Simulator memory numbers do not establish physical-device safety. No app or tests are launched by the coding assistant.

## Validation status

Regression tests cover feature/profile parity, reduced elevation reads, cache invalidation when the path changes, unchanged logical work charges, cancellation/limits, budget remapping, and threshold-adjacent paths. App and test targets are compiled.

Simulator measurements (2026-11-13), same warm study, inputs and terrain sources:

- Mapping disabled (block-cache fallback): 40.3 s. Mapped reader: 25.9 s, 36% less time.
- Prediction fingerprints match exactly between the two runs.
- Across the optimization runs as a whole, the study went from 135 s to 26 s, about 5.2× faster.
- Peak simulator memory: 614 MiB mapped, 618 MiB fallback.

Still to do: physical-iPhone memory and responsiveness checks, cancellation on device, and running `TerrainMappedPredictionTests`. Simulator memory does not establish device safety, and matching fingerprints establish implementation consistency, not real-world RF accuracy.
