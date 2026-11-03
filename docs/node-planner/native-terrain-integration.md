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

## Cache identity and preflight — second native slice

Both app targets now contain immutable validated tile descriptors and a pure preflight calculator.
Nine shared identity vectors verify canonical UTF-8 JSON and SHA-256 keys against the Python
repository contract, including Unicode, decomposed text, quoting, slash/backslash/tab escaping,
southern/eastern coordinates, dimensions and mirror URLs. Transport and origin URLs are provenance
only; dataset/revision, source checksum, dimensions, decoder policy and attribution affect identity.
HTTPS is required except explicit localhost/127.0.0.1 HTTP replay. Credentials, fragments and
whitespace/control characters in URLs are rejected. This validates descriptors, not provider
licensing, catalog accuracy, HTTPS responses or compressed files.

Thirteen shared preflight cases were computed from the existing Python Repository._plan contract.
They check deduplication, mirror reuse, cached candidates, unrelated active pins, exact/insufficient
quotas, missing download bytes and retry allowances. Requests are capped at 256 entries before
deduplication and inventory at 4096 unique entries; sizes and limits are validated before arithmetic.
Contradictory cache metadata sizes fail explicitly rather than undercounting a pinned file.

Reservations include 4096 control bytes, decoded payload plus 8192 metadata bytes for every requested
tile, unrelated pinned entries, and the largest missing compressed payload for worst-order staging.
Unpinned unrelated entries may be evicted by the future owner; preflight itself performs no eviction
or mutation. These are owned-file payload reservations, not a claim about filesystem free space,
allocation overhead or process memory. Remaining-download-budget fit covers expected first-attempt
payload bytes; maximum-attempt bytes separately includes retries and one oversize probe per attempt.
The downloader must enforce the shared actual byte budget, including failed attempts.

All cached-file availability is provisional: hasAllCachedTiles reports metadata candidates only,
while cacheValidationPending remains true. Every acquired file still needs checksum validation.
No cache entry is pinned, transferred or verified by this pure calculator.

Validation: iOS build including test targets passed; host terrain tests now total 12 passing tests.
Android debug build/unit suite passed with 397 passed and 1 skipped. Five new tests per platform
cover identity/preflight fixtures, bad descriptors, URL/source identity and invalid limits/inventory.
No external downloads, simulator or UI tests were run.

## Decoded-tile disk cache — third native slice

Both apps now have a worker-owned disk cache for verified decoded HGT files. It uses an exclusive
process lock, an ownership marker, bounded metadata, private staging directories and same-filesystem
atomic publication. A second owner is rejected. Unowned directories are preserved. Startup removes
recognized interrupted stages without following symbolic links outside the cache. Invalid committed
metadata fails explicitly and is preserved rather than silently resetting the cache.

Publish streams at most 64 KiB per copy, rejects short/oversized payloads and verifies the decoded
SHA-256 before publication. Source URL, origin URL, fetch time, identity and decoded checksum are
retained. The input must be the future decoder's private output; this API does not establish gzip
CRC, compressed-source checksum or network provenance. Bad/cancelled staging leaves no committed
partial tile. An invalid replacement preserves the existing tile. Replacement requires spare space
for both old and staged entries; it is conservative rather than overwriting a tile in place.

Acquire revalidates file contents on every lease. Active readers prevent eviction, replacement,
clearing and owner closure. Acquiring a set either transfers all unique readers or releases every
reader acquired by the attempt. LRU access sequence persists across restart. Clear removes only
unpinned terrain; deployment plans live separately. Swift reader callbacks retain their cache owner;
Android callers must retain the owner and explicitly close readers/owner in worker cleanup.

Quota reservations bound cache-owned decoded payload/metadata/control files. The downloader must
add compressed and decoder-workspace reservations before transfer. The low-level cache can evict
unrelated unpinned entries while preparing a stage, even if that stage later fails. Its publication
makes a new complete directory visible atomically; replacing an unpinned existing entry removes the
old entry only after validation, but a failure at the final rename may leave a cache miss. No atomic
replacement/power-loss durability guarantee is claimed. Saved plans are never involved.

The native cache deliberately fails on invalid committed metadata or missing committed files rather
than automatically deleting them. Online repair/quarantine and explicit cache-reset behavior need
to be designed with the downloader; startup failure is not a successfully recovered online cache.
The implementation assumes one serialized background worker and immutable pinned files. It does not
provide concurrent-method safety, protection against a process ignoring the lock, or mobile storage
pressure qualification.

Ten new tests per platform cover offline reopen, repeated pins, pinned close/replacement rejection,
LRU after restart, quota exhaustion, cancellation at every publication checkpoint, corrupt/short/long
payloads, all-or-none acquisition, per-lease revalidation, ownership locks, interrupted-stage cleanup,
unowned directory preservation, corrupt metadata and failed replacement. A Swift error-mapping
mismatch on a missing ownership marker was found and fixed. Stage cleanup also explicitly avoids
following nested symbolic links, with a regression test protecting an external sentinel file.

Validation: iOS build including test targets passed; all 22 Swift host terrain tests passed.
Android debug build/unit suite passed with 407 passed and 1 skipped. No simulator, UI tests, external
network downloads or real-provider runs were performed.

## Bounded gzip decoder — fourth native slice

Both apps now decode a private immutable downloaded file into an explicitly owned temporary HGT
artifact. The caller closes the artifact after cache publication; failures/cancellation remove
partial decoded output. Input/output processing uses bounded chunks, optional gzip headers are
limited to 64 KiB, source length/digest must match the descriptor, and output must exactly match
the tile's signed-sample dimensions. A one-byte expansion probe detects oversized output before
writing past that size. Source bytes are hashed during decode; decoded SHA-256 is retained for the
cache's independent validation. No nodata/negative-height conversion occurs.

The native contract accepts exactly one gzip member, including bounded optional extra/name/comment
fields and header CRC. It rejects other wrappers, reserved flags, invalid compression methods,
bad header/trailer CRC, bad ISIZE, truncated files, concatenated gzip members and all trailing
bytes (including zero padding). This is intentionally stricter than the Python prototype's generic
gzip reader. A future provider catalog must qualify this contract against its real payloads rather
than assuming every standards-compliant gzip file is accepted.

Swift uses system zlib in gzip-only mode; Android reuses the existing Okio dependency's GzipSource.
The implementation also validates the bounded header before invoking the library. Library behavior
was checked against the [zlib manual](https://zlib.net/manual.html) and
[Okio GzipSource](https://raw.githubusercontent.com/square/okio/master/okio/src/zlibMain/kotlin/okio/GzipSource.kt).
The checked-in shared fixtures, rather than those descriptions alone, verify the implementations
actually compiled by this project.

`shared/fixtures/coverage/terrain-gzip.json` contains 22 synthetic cases generated independently
with Python gzip/zlib by `tools/node-planner/make_terrain_gzip_fixtures.py`. Three valid cases cover
signed/nodata samples, optional fields/header CRC, and incompressible data spanning multiple input
and output chunks. Rejection cases cover hash/length mismatch, damaged structure, excess expansion,
invalid headers and extra data. Tests also cancel every observed checkpoint on a multi-chunk file,
verify temporary-file cleanup and preserve an unrelated file on workspace failure. A complete
local decode → cache publish → cache restart → signed sample read test passes on both platforms.

Validation: iOS build including test targets passed; 26 Swift host terrain tests passed. Android
debug build/unit suite passed with 411 passed and 1 skipped. Four new tests per platform execute
these fixtures and integration paths. No HTTP download, simulator, UI or physical-device test was
run. Provider payload compatibility, throughput and peak memory remain unqualified.

The future download coordinator must reserve both its compressed file and decoder workspace in
addition to cache staging. The current publish API copies the decoded artifact, so a job can hold
two decoded copies temporarily; the existing preflight's cache-only reservation is insufficient
for that complete pipeline until the coordinator accounts for the additional workspace. Decoder
cancellation is cooperative between bounded operations, not an absolute latency guarantee.

## Full-pipeline disk estimate

Both native platforms now provide `CoverageTerrainPipelinePreflight` alongside the
cache-only contract. It adds the largest missing tile's compressed file, private decoded
output and 8 KiB workspace allowance to the complete pinned-set estimate. The pinned set
already accounts for the cache publication copy. Duplicate requests do not increase the
estimate; unrelated pinned entries remain protected; all-cached runs need no decode workspace.
Boundary tests cover exact quota, one byte short, cached reuse and outside pins.

This is a conservative logical-byte estimate, not an acquired reservation or a physical
free-space guarantee. It assumes serialized transfers, cleanup between tiles/retries, and
eviction of unrelated unpinned entries. The coordinator must enforce those assumptions,
check actual free space, handle write failures, and recompute after invalid-cache repair.
The original cache-only preflight remains unchanged for its existing callers.

## Bounded native HTTP attempts

Both platforms now implement a cancellable single-attempt downloader that returns an owned
private compressed artifact. It reserves expected compressed bytes plus one oversize probe
before starting the request, validates HTTP 200, exact optional Content-Length, identity
Content-Encoding, exact consumed length and SHA-256, then synchronizes the output. Failed
attempts remove their temporary directory and retain consumed-byte accounting. Redirects,
cookies, credentials, inherited proxies and automatic Android connection retries are disabled.
Swift uses an ephemeral URLSession byte stream; Android uses OkHttp worker callbacks.
Cancellation cancels the underlying session/call. Both configure idle and total request limits.

The download allowance counts bytes consumed by the application, including invalid/failed
response bodies, not TCP/TLS overhead or transport-library prefetch. Writes are at most 64 KiB;
oversize bytes are never written. Concurrent budget reservations cannot double-spend remaining
allowance. The job coordinator still needs to serialize tile preparation and enforce the
full-pipeline disk estimate; the downloader alone does not reserve disk space.

Swift host tests use URLProtocol fixtures for valid and malformed responses, budget exhaustion
and stalled-request cancellation. Android MockWebServer exercises those cases over loopback
HTTP, including rejected redirects and a total-timeout failure. Neither qualifies real provider
compatibility or on-device throughput. A 404 remains an explicit HTTP status for the coordinator
to classify; 429 and transient statuses are not automatically retried by this layer.

## Serialized terrain preparation

The cache now exposes an exclusive async `prepare` operation on both platforms. It validates
the full request and budgets, verifies/pins existing requested tiles, evicts only unrelated
unpinned entries to leave room for all missing tiles and the largest temporary workspace, and
checks actual free space on cache/workspace destinations before downloading. It serially
downloads, decodes, publishes and acquires each missing tile. Duplicate descriptors reuse one
reader. Success transfers the complete pinned set; failure closes every reader opened by the
operation while preserving any successfully committed tiles for reuse.

Offline preparation requires a complete verified cache hit and never invokes the downloader.
Default attempts are two, bounded to one through three. Selected transient HTTP statuses
(408/500/502/503/504) and transport failures retry after cancellable delays using the same byte
budget. Corruption, 404 and rate limiting do not immediately retry. Cache corruption still
fails explicitly without deletion; online repair remains unimplemented.

The cache remains owned by one serialized background worker; callers must not invoke other
cache operations while preparation is suspended. Its process lock excludes other cache
owners, but the disk estimate is logical accounting, not an OS reservation. External disk
consumption and write/cleanup failures remain possible. Crash recovery for external download/
decode workspaces and physical-device qualification remain outstanding.

iOS declares disk-space reason E174.1 for avoiding terrain writes/downloads when space is
insufficient, per [Apple's required-reason documentation](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons).
Free-space values are used locally and are not included in network requests.

Integration tests cover download/decode/publication, signed samples, deduplication, offline
reopen, preflight rejection before download, transient retries, cancellation and partial-set
cleanup. They exercise synthetic terrain, not real provider qualification.

## Payload repair and owned workspace recovery

Online preparation now reclassifies confirmed decoded checksum/length corruption as a missing
tile, recalculates the full pipeline and transfer budgets, and refetches only after those checks.
A tile with an outstanding reader remains protected. Offline corruption and insufficient repair
budgets preserve the existing payload and fail explicitly. Once an authorized repair removes a
bad payload, a failed transfer leaves a cache miss for the next attempt.

Preparation now uses a private `.workspace` inside the cache directory, under the same process
lock and on the same filesystem. On restart or before another preparation, a matching workspace
ownership marker permits cleanup of recognized download/decode UUID directories. All top-level
workspace entries are validated before deletion; unknown files, invalid ownership and symbolic
directory links fail closed. Nested symbolic links are removed without following their targets.
Temporary directories from older callers outside the cache are deliberately not swept.

Android cancellation now waits for the HTTP callback's cleanup to complete before returning,
so the cache worker cannot reuse or close the workspace while that callback still writes.
Tests cover partial-body cancellation, budget return, restart cleanup with a symbolic-link
sentinel, unknown-file preservation, online repair, offline/underfunded preservation and pin
protection.

This repairs payload corruption with valid metadata. Invalid metadata, missing committed files
at cache open, and unrecognized cache contents still fail explicitly and remain preserved.
Power-loss durability and real-device filesystem qualification are not claimed.

## Geographic tile selection and mosaic preparation

Both platforms now select one-degree terrain tiles from transmitter coordinates, requested radius
and an explicit sampling halo. Selection conservatively encloses the spherical study disk, using
the 6,371,008.8 m Earth radius in the reviewed coverage studies. It handles date-line wrapping,
north/east seam ownership and zero-radius point requests. Bounds round outward to avoid excluding
a boundary tile. The resulting rectangle is a terrain-fetch footprint, not the displayed coverage
shape or a promise of reception.

The 256-tile ceiling is enforced before returning a set: no radius or tile list is silently clipped.
A nonzero disk touching a pole requires all longitude columns and therefore exceeds the current
ceiling. The bounding rectangle can overfetch compared with an exact circle/tile intersection.
The halo is supplied by the eventual profile sampler, independent of output-grid spacing.

A bounded manifest resolver rejects missing coordinates, ambiguous source identities and mixtures
of dataset, revision, vertical datum, sample dimensions or source type. Mirrors of the same
identity reuse the first transport URL. The new `prepareMosaic` entry point resolves the entire
footprint before downloading and transfers verified pinned readers to the geographic sampler.
Closing that sampler releases the pins.

Eight shared fixtures cover interior, seams, hemispheres, date line, sampling halo, longer range
and point/pole ownership. Each platform additionally checks 7,200 radial path positions across
four locations and three ranges, along with limits and manifest errors. Offline integration
preserves explicit nodata through the prepared mosaic. Production provider-manifest discovery,
profile sampling and RF execution remain unconnected.

## Native feature-profile construction

Swift and Kotlin now implement the profile-building portion of the
[feature-preserving study](../../tools/rf-study/coverage/feature-profile/README.md). Given all DEM
cell crossings and latitude-extremum splits, the builder retains those positions, fits each
interval from endpoint/midpoint heights, checks both quarter points against a 0.01 m tolerance,
retains interior fitted extrema and merges regularly spaced samples. Positions deduplicate on
millimetre keys while retaining the exact requested endpoint, avoiding a rounded sample past
the path end. Signed elevations remain signed.

The current port retains the study's 100 km per-profile domain, 50,000 crossing/feature ceiling,
250,000 terrain-evaluation ceiling and 1–30 m profile sample spacing. These are internal sampler
limits, not a new user-facing radius or output-grid policy. Longer-range qualification remains
required; the footprint selector's ability to cover larger areas does not establish RF support.
The caller must supply **every** cell crossing: the builder cannot detect an omitted ridge from
an incomplete crossing list. Geographic crossing discovery is not yet ported or connected.

Missing data, nonfinite input, unresolved fits, work exhaustion and cancellation throw explicit
failures with no partial profile result. The fit check remains an empirical safeguard, not a
proof of RF error bounds. Loss convergence and threshold checks from the study are not part of
this builder and must still be applied by the future RF adapter.

Regression tests on each platform cover a narrow off-grid triangular ridge, a within-cell
quadratic peak, exact endpoints, millimetre deduplication, missing data, fit rejection, work
budgets and cancellation at every observed checkpoint. Xcode build and 44 Swift host tests
passed. Android debug assembly and terrain tests passed; the full 432-test suite encountered
the existing observer-page request-budget failure, with one skipped test.

## Geographic crossings connected to terrain profiles

Swift and Kotlin now discover every integer DEM row/column crossing for short spherical paths.
The path splits at great-circle latitude extrema, searches monotone segments to 0.1 mm, and
deduplicates positions at millimetre precision while retaining the exact endpoint. Longitude
remains unwrapped for crossing discovery and normalizes only when reading the geographic mosaic,
so date-line crossings do not become a spurious traversal across the globe.

The mosaic's `profile` entry point derives sampling resolution from its readers, rejects mixed
dimensions, discovers crossings and invokes the feature-profile builder using real tile samples.
It retains its reader pins throughout success or failure. Missing tiles/nodata fail explicitly.
The returned result reports geometry-search evaluations separately from terrain evaluations;
the future scheduler must budget both.

This adapter retains the initial 100 km path limit and explicitly rejects origins outside ±85°
latitude or paths that leave that domain. Those are current implementation limits, not newly
qualified long-range/polar RF support. The spherical approximation and empirical interpolation
checks still require broader real-terrain and device qualification.

Tests cover analytic equatorial crossing distances, east/west date-line crossings, latitude
turning points, per-segment DEM-cell containment, work limits, cancellation checkpoints and a
file-backed mosaic/profile integration including missing-terrain failure and pin ownership.
Xcode build and 48 Swift host tests passed. Android debug assembly and coverage tests passed;
the full 436-test suite hit the existing observer-page request-budget failure, with one skipped
test. No simulator or UI tests were run.

## Native RF convergence policy

Swift and Kotlin now share the reviewed sampling convergence policy through eight decision
fixtures. Profiles refine at 30, 10, 5, 2 and 1 m spacing. Qualification requires two consecutive
loss changes no greater than 0.1 dB, spacing at most 5 m, and an absolute unrounded reception
margin greater than 0.25 dB. Exhausted refinements return accuracy-unresolved or threshold-unresolved
with no usable loss or margin. Invalid model output, missing terrain, work limits and cancellation
remain explicit failures; cancellation during a synchronous model call discards its output.

Terrain and geometry budgets apply cumulatively across passes for one path. The current mosaic
adapter recomputes crossings on each pass and charges that work; it does not yet cache discovery
or enforce a full-study scheduler budget. The initial RF policy retains the study's 250 m–100 km
path domain. Shorter and longer paths require separate qualification.

The propagation model is injected, not bundled. Tests use controlled losses to verify decisions,
not physical RF accuracy. Integration tests run the policy over file-backed terrain profiles while
preserving reader pins. Production kp1812 packaging and distribution permissions remain unresolved
as documented in the [engine feasibility study](kp1812-feasibility.md); no third-party engine
source or binary was imported in this slice.

Xcode build including test targets and 51 Swift host tests passed. Android debug assembly passed;
the 439-test unit suite had no failures and one skipped test. No simulator or UI tests were run.

## Explicit radio link budget

Swift and Kotlin now calculate the maximum allowable path loss from explicitly supplied transmit
power (dBm), antenna gains (dBi), feed/system losses (dB), sensitivity (dBm) and reserve (dB).
The same budget derives receive level and unrounded adjusted margin from a qualified path loss.
Margin arithmetic matches convergence exactly. No equipment defaults or node classification are
introduced; negative antenna gains are valid, while negative losses/reserve and nonfinite or
overflowing arithmetic are rejected. This arithmetic boundary does not validate model-specific
radio limits or establish regulatory compliance.

Five shared fixtures cover positive, zero, negative and near-zero margins. Both platforms also
test invalid values and overflow. Xcode build and 53 Swift host tests passed; Android debug
assembly and unit tests passed. No simulator or UI tests were run. Production RF execution,
run snapshots and exact-node status handling remain unconnected.

## Known-node status resolution

Both platforms now resolve exact-node evaluations against source/ID/coordinate snapshots and an
active run token. Changed nodes are not evaluated, changed input tokens are stale, and paths
outside the study radius remain not evaluated rather than red. Missing terrain, unsupported
paths, absent assumptions and unresolved estimates remain unknown; cancellation and processing
caps remain not evaluated. Only finite qualified margins expose values, with unrounded zero
included in predicted coverage. Non-predicted states expose no margin.

Eighteen shared fixtures cover these decisions, source changes, invalid model values and tiny
positive/negative margins. The caller must rotate the token whenever calculation inputs change
and supply exact-coordinate qualified predictions; this resolver does not establish qualification,
calculate distances, fingerprint inputs or implement a catalog snapshot lifecycle. A convergence
adapter must preserve its threshold guard, so zero-margin policy tests do not bypass that guard.
Catalog completeness, bounded scheduling, full run provenance and map wiring remain outstanding.

Xcode build and 54 Swift host tests passed. Android unit tests and debug assembly passed.
No simulator or UI tests were run.

## Next implementation

Resolve production RF engine packaging and distribution permissions, then connect its adapter
and the bounded scheduler to the geographic profile/convergence pipeline. Preserve explicit limits, terrain/source failures and both work counters. Connect a qualified provider manifest
to footprint selection. Define explicit recovery for invalid metadata separately; never silently reset unknown
cache contents. See the [repository contract](terrain-repository-study.md).
Then package the reviewed propagation kernel and implement the shared run/node-result contract
before connecting standalone Estimated Coverage UI and green/red/neutral known-node markers.

Terrain preparation now connects networking, decoding and caching. Full engine integration, area raster rendering,
physical-device budgets and node classification are not completed by this slice. Existing saved
site data and release version numbers are unchanged.

Validation for this preparation slice: Xcode build including test targets and 34 Swift host tests
passed. The Android coverage tests passed; the full 421-test suite had one skipped test and the
existing `MyNodesBaseRepositoryTest.observerPagesShareBudgetAndWarmCache` failure (expected
16 requests, observed 17), repeated on rerun. No simulator or UI tests were run.
