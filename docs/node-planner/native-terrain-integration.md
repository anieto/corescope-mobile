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

## Convergence-to-node adapter

Both platforms now adapt convergence outcomes to node predictions using the fixed radio budget.
Unresolved states retain their reason; qualified results require matching unrounded budget margin,
completed refinement and the existing threshold guard. Invalid or mismatched results expose no
prediction. Tests pass controlled loss sequences through real convergence, budget and status code,
including positive/negative results and exact threshold boundaries. These are integration tests
of decision logic, not RF accuracy tests. The caller still owns exact-node path selection and run
identity; this adapter does not prove path provenance or replace the pending snapshot lifecycle.

Xcode build and 56 Swift host tests passed. Android unit tests and debug assembly passed.
No simulator or UI tests were run.

## Frozen node run lifecycle

Swift value-owned and Kotlin single-owner node runs now retain a fixed source/identity/coordinate
catalog, explicit upstream completeness, selected work and exclusion counts. Callers provide order
and processing cap. A defensive 10,000-entry ceiling rejects oversized input; it is not an RF
throughput qualification. Duplicate identities and invalid coordinates fail before work starts.
Android copies caller collections and exposes an unmodifiable selected list.

Only matching, selected, previously unreported node results are accepted while running. Results
publish after all selected entries are accounted for. Cancellation discards partial results and
rejects late outputs; invalidation preserves stale status but requires a new run. Empty catalogs
complete normally. Radio/model input snapshots, provenance, last-seen metadata, catalog pagination,
area-job coordination and actual scheduler/UI ownership are still pending. Owners must call
invalidation when inputs change and serialize access; this is not automatic input observation.

Xcode build and 59 Swift host tests passed. Android debug assembly and new run tests passed;
the full 447-test suite had one skipped test and the existing observer-page request-budget failure
(expected 16 requests, observed 17). No simulator or UI tests were run.

## Exact-node evaluation orchestration

Swift/Kotlin evaluators now compute spherical distance and initial bearing from transmitter to
node coordinates, then pass that path through the profile callback, convergence, radio budget
and node-status adapter. The calculation uses the same sphere as terrain path sampling and handles
the date line. Outside-radius nodes skip terrain/RF work; the existing 250 m–100 km and ±85°
model domain is enforced before profiling. Missing terrain, unresolved sampling and exhausted
per-path budgets retain explicit reasons. Cancellation and invalid-input/model errors propagate;
unrecognized exceptions are not disguised as missing terrain.

Tests use controlled profile/model callbacks to verify endpoint alignment across the date line,
convergence through run completion, skipped unsupported work, failure reasons and cancellation.
Xcode build and 62 Swift host tests passed; Android unit tests and debug assembly passed.
No simulator or UI tests were run. These tests do not qualify physical propagation accuracy.

Production callbacks, shared full-study work accounting and model/terrain provenance remain
pending. The evaluator currently applies existing per-path limits; it is not the bounded area
scheduler and does not bundle an RF kernel or connect a live catalog/map.

## Prepared terrain bound to exact-node evaluation

Both evaluators now accept a prepared geographic mosaic directly. The adapter forwards remaining
per-path sample/geometry budgets and cancellation through geographic crossing discovery and terrain
sampling. It borrows the mosaic; the run owner retains responsibility for closing its pinned readers.
Tests use a file-backed negative-elevation tile through exact-node evaluation, convergence and status,
then exercise missing-tile paths and cancellation during a model call. Pins remain owned until close.

Xcode build and 63 Swift host tests passed. Android unit tests and debug assembly passed.
No simulator or UI tests were run. RF responses remain controlled test values; production kernel
binding, provider discovery and full-study scheduling are still outstanding. This slice establishes
the terrain binding, not physical RF accuracy or a user-visible coverage map.

## Shared node-batch work limits

A synchronous serialized coordinator now constructs a frozen node run, evaluates selected paths
and publishes only the completed run. Terrain, geometry and model-call limits span all nodes and
refinement passes. Each profile reserves at most its remaining per-path and batch allowance;
validated successful work refunds the unused portion. A failed profile retains its full reservation
because its partial work is unknown. Reported charged work is therefore conservative, not necessarily
actual executed work. Exhausted budgets produce processing-limit status without further callbacks.

Cancellation and unhandled failures throw without returning the partially assembled run. Callers
must execute off the UI thread, serialize terrain access and keep the model configuration fixed.
The numeric validation ceilings are defensive bounds, not device-qualified settings or defaults.
This is the node coordinator, not the area raster scheduler or a full-study allocation policy.

Xcode build and 66 Swift host tests passed. Android unit tests and debug assembly passed.
Tests cover independent shared budgets, successful refunds, failed reservations, cancellation and
invalid model output. No simulator or UI tests were run. Production engine, full run provenance,
area scheduling and visible map integration remain outstanding.

## Native area grid layout

Swift/Kotlin now implement the study's local east/north grid layout with selectable spacing,
full-radius or selected rectangular extents, north-to-south row order and near-site/outside-radius
masks. Requests retain their radius and spacing; invalid bounds, unaligned extents and requests
over 60,000 sample points fail explicitly. Points are generated on demand, without allocating a
full raster during construction. Integer bounds are checked before extent arithmetic.

The port retains the study's 1–100 km radius and discrete 50 m–20 km spacings as internal supported
inputs, not shipping presets or a new long-range accuracy claim. It uses unrounded radial distance
for masking; no tolerance extends the requested boundary. This is the layout portion only:
work preflight, geographic mapping, area RF scheduling and rendered pixel extents remain pending.
A circular study mask is not a circular predicted coverage footprint.

Xcode build and 68 Swift host tests passed. Android unit tests and debug assembly passed. Tests
cover the study's 1,681-point wide/refined layouts, 121-point long-range workload, row ordering,
near-site/radius boundaries, excessive requests and integer-extreme inputs. No simulator or UI
tests were run.

## Native area-work preflight

Both platforms now estimate necessary work for eligible grid points before area execution.
Sampling lower bounds include uniform points for the first three refinement passes; feature
retention adds more work. Geometry lower bounds include four axis endpoint queries per pass,
12 per eligible point, matching the native adapter's repeated discovery rather than the earlier
study's discovery-once assumption. Both per-path and aggregate budgets reject impossible requests.
Acceptance is not a completion guarantee and does not qualify terrain/model accuracy.

Estimates include 64-bit aggregate per-path ceilings, which may exceed the allowed job budget;
these describe possible work, not a reservation or promise to execute it. The study's 150-million
aggregate budget ceiling remains internal. All-excluded grids need no work, and preflight supports
cancellation without terrain I/O. Geographic projection, area execution and rendering remain next.

Xcode build and 71 Swift host tests passed. Android unit tests and debug assembly passed. Tests
cover analytical single-point bounds, excluded points, independent path/job limits, cancellation
and totals beyond signed 32-bit range. No simulator or UI tests were run.

## Geographic paths for area samples

Grid samples now map to spherical distance/bearing paths from the transmitter on both platforms,
using the existing terrain-path implementation. Near-site and outside-radius samples return no RF
path; invalid indices/origins throw. Endpoints are checked against the current ±85° model domain,
while profile crossing discovery still checks interior latitude extrema. Longitude wrapping is
shared with exact-node terrain paths. These are sample-center paths, not projected raster pixel
boundaries; geographic image rendering remains a separate step.

Xcode build and 73 Swift host tests passed. Android assembly and new grid-path tests passed;
the full 461-test suite had one skipped test and two failures outside coverage:
`BeaconApiTest.beaconDiagnosticsMakeFourBoundedRequestsAndNoFalseFailures` missed an expected
observer request, and `MyNodesBaseRepositoryTest.observerPagesShareBudgetAndWarmCache` saw 17
requests instead of 16. No simulator or UI tests were run.

## Native area execution

Swift/Kotlin now execute the bounded grid through preflight, geographic paths, profile callbacks
and convergence. Results preserve row order and explicit qualified, near-site, outside-radius,
missing-terrain, accuracy-unresolved, threshold-unresolved, work-limit and not-computed states.
Only qualified samples expose unrounded loss and margin; qualification alone does not mean
predicted reception. The displayed receiver threshold still determines that classification.

Sampling and geometry allowances span all samples and refinement passes. Failed profiles retain
full reservations because partial work is unknown. On work exhaustion, the affected sample is
marked work-limit and subsequent samples remain not-computed; sampling is never coarsened.
Cancellation or an unhandled invalid input/model failure throws without returning a partial area.
Execution is synchronous and serialized; callers must place it off the UI thread. UI generation
ownership, progress publication and resumable scheduling are not introduced by this entry point.

Xcode build and 76 Swift host tests passed. Android assembly and the 464-test unit suite passed
with one skipped test. Tests use controlled terrain/model callbacks to check masks, raw margins,
threshold guards, shared charges, exhaustion, missing terrain and cancellation. No simulator or UI
tests were run. Production RF binding, combined area/node allocation, provenance and geographic
raster rendering remain unfinished.

## Combined prepared-terrain study operation

Both platforms now run area and exact-node work against one borrowed prepared mosaic, fixed radio
budget and model callback. Explicit area/node allowances are reserved separately and validated
against a combined 150-million sample/geometry ceiling. The area cannot consume the node reserve;
unused allowances are not silently transferred. Catalog validation precedes area work, and Android
copies the input list before calculation. Results carry transmitter, grid, radio budget, allocations,
area cells and the completed node run together.

Cancellation or node failure returns no area-only study. Empty catalogs are valid. The operation
retains caller ownership of terrain readers and must execute serially off the UI thread. The caller
still must supply a fixed RF configuration: the closure is not a model/provenance snapshot, and
these results are not yet a persistence format or an async UI publication boundary.

Xcode build and 77 Swift host tests passed. Android unit tests and debug assembly passed.
File-backed tests exercise successful studies, area exhaustion with successful reserved node work,
cancellation in node evaluation, empty catalogs and invalid combined limits. RF outputs are controlled
test values. No simulator or UI tests were run. Production RF binding, source/model provenance,
progress/UI ownership and geographic raster rendering remain pending.

## Study publication generations

Study execution now carries a caller-provided execution UUID through its result. Publication
controllers accept only the active token once, reject incomplete node runs or mismatched area
counts, and ignore late success/failure/cancellation from superseded jobs. Beginning a run clears
prior display data. Input invalidation retains an already published result only in explicit stale
state. iOS transitions are MainActor-isolated; Android transitions and phase/result snapshots are
synchronized. Consumers must treat published result objects as read-only and honor the outer stale
state when rendering, including nodes. This gate does not establish model/provenance validity.

Owners still must pass the begin token into execution, signal worker cancellation separately,
call invalidate on input changes, and bind the controller to the UI. No task launcher, observable
screen state or automatic input observation is implemented here.

Xcode build and 79 Swift host tests passed. Android assembly and publication tests passed;
the full 467-test suite had one skipped test and the existing observer-cache request-count failure
(expected 16, observed 17). No simulator or UI tests were run.

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


## Regional catalog decoding (2026-11-03)

Both apps now decode a version-1 regional JSON catalog into validated terrain descriptors and
resolve it through the existing study-footprint selector. The document is limited to 4 MiB and
1–4096 entries. Global fields are `version`, `dataset`, `revision`, `side`, `vertical_datum`,
`attribution`, and `source_type`; each `tiles` entry supplies `tile`, `sha256`, `bytes`, and `url`.
The URL is retained as both download and origin provenance and must use HTTPS. Descriptor
validation enforces payload bounds, digest syntax, sample dimensions and geographic bounds.
Duplicate geographic tiles fail, including alternate zero-coordinate spellings. Unknown document
fields are ignored; unsupported schema versions fail. Missing required tiles remain an explicit
footprint failure, never a zero-filled elevation or smaller study area.

This decoder accepts data already obtained by a caller. It does not fetch or authenticate a
catalog, select a production endpoint, or establish that its declared checksums/provenance are
trustworthy. A trusted regional-catalog delivery source remains required. The fixture is synthetic
and must not be shipped as real terrain. Provider documentation was rechecked against the
[AWS terrain registry](https://registry.opendata.aws/terrain-tiles/) and
[Skadi format documentation](https://github.com/tilezen/joerd/blob/master/docs/formats.md);
neither check qualifies a production catalog or supplies missing per-tile SHA-256 pins.

Fourteen shared cases exercise valid selection, missing coverage, incompatible versions, malformed
records, aliases/duplicates, insecure URLs and numeric validation. Malformed/oversized raw data is
also tested natively. Swift host tests: 81 passed. Xcode build including test targets passed;
Android unit tests and debug assembly passed. No simulator, UI or real-provider network run was
performed. Production RF binding, catalog transport, screen execution and result rendering remain
outstanding.


## Catalog transport (2026-11-03)

`CoverageTerrainCatalogLoader` now fetches a caller-configured HTTPS catalog on both platforms,
then passes its bounded body to the catalog decoder. It disables redirects, automatic retries,
HTTP caching and cookies; rejects non-200 responses and non-identity content encoding; checks any
advertised length; and enforces the 4 MiB limit while consuming the body even without a length
header. Request/resource or call timeouts default to 30 seconds. Cancellation cancels the active
request. It returns no partial catalog or files and does not persist a stale fallback.

Controlled URLProtocol/OkHttp responses exercise successful fetch-to-footprint selection,
malformed documents, declared and streamed size violations, length mismatches, encoding errors,
HTTP errors/redirect responses, invalid endpoints and cancellation while awaiting a response.
These are transport boundary tests, not live-provider or socket/TLS qualification. Swift host
suite: 84 passed; Android unit suite and debug build passed; Xcode build including tests passed.
No simulator or UI tests were run.

No production catalog URL has been configured or deployed. The publisher must supply independently
reviewed terrain pins and provenance; HTTPS transport alone does not qualify those records.
Screen wiring, production RF binding and result rendering remain outstanding, and Calculate stays
disabled. The new loader does not remove those release gates.


## Reproducible real-terrain regional catalog (2026-11-03)

The [regional catalog](feasibility/regional-terrain-catalog.json) now expresses the eight real
Canadian Rockies Skadi tiles from the October 30 study in the native catalog schema. It preserves
the reviewed SHA-256 pins, source URLs, recorded sizes, datum and attribution. Per-pixel source
and acquisition-date uncertainty remain explicit. It is a development catalog covering the
one-degree tiles at 50–52° N and 117–113° W, not worldwide service coverage. It is neither bundled
in the app nor deployed to a public endpoint.

Reproduce it from the repository root:

```sh
python3 tools/node-planner/build_study_catalog.py
python3 tools/node-planner/build_study_catalog.py --check
```

The converter rejects disagreements between study evidence and reviewed pins, unexpected origins,
duplicate/missing tiles and inconsistent payload totals. It performs no download, does not update
pins, and does not imply current upstream availability. Eight tiles total 84,062,769 compressed
bytes. Catalog SHA-256: `abb45aa950c3aaabab1e1439a1332a186ad3d020e96929f1e079f3cbd93b3de7`.

Both native test suites now consume this same artifact. A 20 km study at 50.96, -115.16 with a
100 m sampling halo selects four tiles and estimates 45,503,666 cold-download bytes. Tests verify
adequate and insufficient download/disk budgets and explicit missing-tile failure for San Antonio.
No new live download or RF prediction was performed. Swift: 85 tests passed; Android unit tests
and debug assembly passed; Xcode build including tests passed. Simulator and UI tests were not run.

The catalog establishes a reproducible integration dataset. Broad regional discovery/publication,
production RF integration and screen execution/rendering remain unfinished; Calculate stays off.
