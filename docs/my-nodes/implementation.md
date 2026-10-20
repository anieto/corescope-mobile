# 0.9.0 implementation progress

Release marked complete by user direction on 2026-10-20. Entries below retain historical
implementation and validation evidence; unrun checks are not retroactively marked passed.
Final store copy and submission follow-ups are in [release notes](release.md).

## 2026-10-16 — Milestone A contract audit started

Branch: `0.9.0`. This increment adds reproducible contract tooling, shared synthetic account
fixtures, Swift/Kotlin account wire models and tests, and backend findings. It does not yet
change app behavior or enable account writes.
The existing Xcode build-number edit is unrelated and was preserved.

| Task | Status | Evidence / remaining work |
|---|---|---|
| A1: data contracts | doing | CoreScope release code, existing Beacon fixtures and four-source read-only probe reviewed; native fixture assertions and completeness modeling still needed |
| A2: request budgets | doing | 24 actual GET requests across four sources; synthetic cold/warm and 500-favorite repository harness passes; app bytes/timing and strict wire-count measurements remain |
| A3: product rules | done | Proceed with plan defaults: Explore entry, 24h metrics, 15-minute recency, optional accounts; contract-driven corrections recorded below |
| A4: account contract | doing | Release and settings schema pinned; four wire-model tests pass on each platform and 11 merge cases match upstream JavaScript; native session/CSRF integration and authenticated test-server flow remain unverified |
| B1: evidence foundation | done | Shared activity/signal/observer rules, compact SNR summaries and deterministic event identities implemented and tested |
| C1/C3: bounded analytics repository | doing | Source-bound memory cache, coalesced two-worker batches and Swift/Kotlin adapters implemented; base metadata, UI lifecycle and detail-cache sharing remain |
| B2/B3: baseline persistence | doing | Matching serialized stores, app ownership and cache controls tested; dashboard/source lifecycle remains |
| B4, C2/C4/C5, D–F | todo | No claim of completed dashboard, authentication or release validation |

### Pinned evidence

- CoreScope `v3.14.0`, commit `9dbc287579a237ffa744dd0c91fa7227d09763ac`.
- [Account allowlist](https://github.com/Kpa-clawbot/CoreScope/blob/9dbc287579a237ffa744dd0c91fa7227d09763ac/cmd/server/settings_allowlist.go),
  [settings handlers](https://github.com/Kpa-clawbot/CoreScope/blob/9dbc287579a237ffa744dd0c91fa7227d09763ac/cmd/server/settings_handlers.go),
  [web merge](https://github.com/Kpa-clawbot/CoreScope/blob/9dbc287579a237ffa744dd0c91fa7227d09763ac/public/settings-sync.js).
- [Authentication routes](https://github.com/Kpa-clawbot/CoreScope/blob/9dbc287579a237ffa744dd0c91fa7227d09763ac/cmd/server/auth_routes.go),
  [session handling](https://github.com/Kpa-clawbot/CoreScope/blob/9dbc287579a237ffa744dd0c91fa7227d09763ac/cmd/server/auth_session.go).
- [Analytics implementation](https://github.com/Kpa-clawbot/CoreScope/blob/9dbc287579a237ffa744dd0c91fa7227d09763ac/cmd/server/store.go),
  [route handlers](https://github.com/Kpa-clawbot/CoreScope/blob/9dbc287579a237ffa744dd0c91fa7227d09763ac/cmd/server/routes.go),
  [node battery](https://github.com/Kpa-clawbot/CoreScope/blob/9dbc287579a237ffa744dd0c91fa7227d09763ac/cmd/server/node_battery.go).
- Beacon mappings: existing `shared/fixtures/beacon/`, `BeaconNodeReader`,
  `BeaconNodeAnalyticsBuilder`, and the 0.8.0 phase 3 acceptance log. No newer Beacon contract is assumed.

The live CoreScope source reports `local-b5059cfa`, not the pinned release. Live observations below
validate that deployment only; release code is separate evidence. No live account was created or
authenticated, and no settings, cookies, passwords, node IDs or packet content were captured.

### Contract mapping and decisions

| Data | CoreScope | Beacon | 0.9.0 decision |
|---|---|---|---|
| Node recency | `last_seen`, optional newer `last_heard`, ISO dates | Existing adapter's latest regional hearing time | Keep shared newest-valid-timestamp policy |
| Activity count | Unique transmissions in node analytics | Observations | Preserve count unit; do not equate the two |
| Availability | Percentage of hourly buckets containing activity divided by requested hours, rounded and capped at 100 | Unsupported | Label as observed activity availability, never physical uptime |
| SNR | Timestamped representative transmission SNR with optional observer ID | Observation SNR without receiver identity | Match CoreScope observers only; Beacon aggregate-only with caveat |
| Coverage | `observerCoverage`, `uniqueObservers` | Unsupported | Missing remains unknown; no Beacon approximation |
| History completeness | No `context` or retained-history bounds in audited response | Reader's bounded paging/context can report partial | Introduce unknown completeness in My Nodes evidence; requested range is not proof of coverage |
| Battery | `/api/nodes/{key}/battery?days=1`, matching observer ID to that exact node key | No equivalent node contract established | Optional CoreScope enrichment within existing budget; no per-row telemetry fan-out |
| Observer status | Catalog recency, optional explicit fields if supplied | Explicit status and existing live recovery | Never compare inferred recency with explicit online/offline as the same method |
| Account support | `config/client.userManagement.enabled` | No account support integrated | Optional CoreScope-only login |

CoreScope availability can appear numeric even when retention is shorter than the requested range.
Render the server metric with its definition and unknown coverage. The new dashboard must not
silently translate a missing analytics context to complete. For significant signal events, the
plan's completeness requirement remains: suppress the event if coverage cannot be established.
Available SNR samples can still display with a history-coverage explanation. Do not modify existing
Node Analytics behavior as part of this audit.

Battery response fields: `public_key`, `days`, `samples[{timestamp,battery_mv}]`, nullable
`latest_mv`/`latest_ts`, `status`, `thresholds{low_mv,critical_mv}`. A successful empty history
uses `samples: []`, null latest values and `status: unknown`. This is the same device's observer
telemetry, not battery from an observer that merely heard it. Validate key, units, plausible values
and timestamp before display. No new low-battery alert category is added to this release.

### Saved selections and conflict handling

| Remote key | Shape | Native mapping |
|---|---|---|
| `meshcore-favorites` | Raw JSON string containing an array of node public-key strings | Account node favorites only; observer/channel favorites stay local |
| `meshcore-my-nodes` | Raw JSON string containing objects with `pubkey`, `name`, `addedAt` | Account own-node membership; preserve extra object fields |
| Other allowed keys | Raw strings | Preserve unchanged from latest remote document; never convert to app settings |

The server stores the document; three-way merging happens in the client. The release web algorithm
preserves independent additions and deletions. For same-item content edits, a local edit wins only
when the remote still equals the baseline; concurrent content edits use remote content. A removal
beats an edit of a baseline item. The golden cases lock these outcomes for Swift and Kotlin.

Wire identity is case-sensitive in the web merge. Preserve remote key spelling and raw object data;
case-insensitive public-key normalization is only for native display/deduplication. If multiple remote
entries differ only by key case, retain them through sync and make removal policy explicit rather
than silently rewriting the server document.

The website's `nodes.js` also adds own-node keys to favorites when opening that page. Native code
must therefore retain category provenance; a reappearing favorite may be a real web change, not a
failed deletion. Removing only one category must not remove the other category.

Cookie auth is present in the pinned release. Login uses Origin validation; account writes require
Origin plus `X-CS-CSRF`; session renewal can reissue `cs_session`. The native client must persist
renewed credentials as well as the first login. Device-token routes are absent from this release's
auth route file; defer that alternative until separately audited.

### Read-only measurements

Full aggregate results: [2026-10-16-probe.json](2026-10-16-probe.json).
Times include HTTP transfer from this development environment. First/repeated HTTP requests do
not establish app cold/warm cache behavior, and the first returned node is not a representative
sample of every favorite. No rate-limit stress testing was performed.

| Source | GETs | Observations |
|---|---:|---|
| CoreScope MeshTexas | 7 | Accounts enabled; first node page 200 of 1,822; bulk-health returns 200; 24h analytics 95,195 bytes, 231/218 ms first/repeat; 453 SNR samples; no completeness context |
| Beacon MeshTexas | 6 | First node page incomplete; three observation pages yield 600 rows and more history remains; 193–447 ms per observation page |
| Beacon Colorado | 5 | First node page incomplete; 270 observations over two pages exhaust the selected node history; 324–348 ms per observation page |
| Beacon dev.meshcore.ca | 6 | First node page incomplete; 524 observations over three pages; first observation request 15,870 ms, then 446/1,090 ms |

Loading decision: reuse existing node/observer catalogs first, then prioritize bounded exact
lookups for selected nodes missing from a complete cache. Bulk-health is capped, has no audited
arbitrary-key batch selector and is not a complete favorites lookup; do not make it a dashboard
dependency. Never scan an entire large network automatically just to find a few favorite nodes.
CoreScope detail analytics cost one GET per selected node; Beacon can consume an entire per-node
allowance with lookup/paging. Battery enrichment consumes the same allowance, not an extra loop.

Retain the 20-request base and 20-request enrichment ceilings, two concurrent requests, progressive
rows, and a four-request per-node allowance. With one CoreScope analytics GET each, a batch can
cover at most 20 uncached nodes (fewer if battery is requested). With four Beacon GETs each it can
cover five. These are bounds, not measured dashboard completion counts. Test repository cancellation
and underlying request accounting before connecting a timer or automatic enrichment.

### Verification and next increment

- `node tools/my-nodes-contract/verify-merge.mjs <pinned-settings-sync.js>`: all 11 cases pass.
- Probe performed 24 anonymous GETs, all HTTP 200. Report excludes identifiers and payloads.
- iOS BuildProject with build-for-testing succeeded; all four new Swift Testing tests passed.
- Android `:app:testDebugUnitTest --tests org.nodescope.android.CoreScopeAccountSettingsTest`
  succeeded: four tests passed. Gradle needed access to its existing cache outside the workspace.
- Four Python probe tests pass: request cap, 429 stop, payload omission and redirect rejection.
- Native models preserve raw settings strings and category distinctions, and fail capability
  checks for unknown schema versions, inconsistent generations or duplicate/changed allowlist entries.

Next: bind base metadata and the tested analytics repository to dashboard lifecycle, and finish
controlled-server measurements for A2. Authentication integration tests need a controlled test server/session; a public
server account must not be created or mutated merely to satisfy this audit.

## 2026-10-16 — Evidence model and request gate

Added `MyNodesEvidence` in Swift and Kotlin without changing existing analytics screens or cache
contracts. A missing analytics context remains unknown. History comparison requires explicit
complete coverage of the requested interval, a valid nonempty window and a fetch no more than
15 minutes old. Clock rollback/future fetches, future requested windows, partial/unsupported data
and missing or reversed coverage bounds fail closed. Per-value sample counts and receiver matching
remain responsibilities of the forthcoming change calculator.

Added `MyNodesRequestBudget` for a single batch: 20 total attempts, four per node, two concurrent
operations by default. Retries and failed attempts consume allowance; cache hits bypass it.
Node keys normalize case. Busy/exhausted/invalidated are separate outcomes. Cancellation releases
the concurrency slot but does not refund an attempt. Invalidation rejects late successful results.
The repository must cancel its task tree and schedule capacity; this gate does not maintain a
waiting queue or cancel transports on its own. Each closure must represent exactly one HTTP attempt,
not a paginated backend method. The gate is not yet connected to production HTTP clients.

Validation:

- Xcode build-for-testing succeeds; nine targeted Swift Testing results pass (five favorite-count
  parameter cases plus four other tests).
- All five targeted Android JVM tests pass, covering equivalent scenarios.
- Both suites consume all 12 cases in `shared/fixtures/my-nodes/evidence-cases.json`.
- Tests cover 0/1/20/100/500 favorites, node allowance after failure, concurrent capacity,
  cancellation cleanup and invalidation during an in-flight request.

No new live-server requests were needed for this increment. A2 remains open: a tested gate alone
does not establish end-to-end HTTP accounting, coalescing, or app cold/warm performance. Next work
is the dashboard repository's bounded HTTP integration and source/session ownership.


## 2026-10-16 — Budgeted HTTP clients and bounded Beacon history

Swift `APIClient` and `BeaconAPIClient` now expose `budgetingRequests`, which creates a
node-specific copy sharing a batch allowance. Kotlin exposes the matching Beacon copy and
`OkHttpClient.myNodesBodies` for CoreScope repositories. Admission occurs immediately around
the transport call, so a lookup and every observation page spend separate attempts. HTTP/decode
failures spend allowance; an existing Beacon cooldown rejects requests before admission.
Replacing a Beacon source session preserves its allowance. Normal detail clients remain unchanged.

`BeaconNodeReader.myNodesObservationHistory` on both platforms performs one exact-key lookup
and at most three observation pages. Exhaustion between pages retains collected observations with
`isComplete = false`; an exhausted lookup still throws, rather than implying the node is absent.
Cancellation, invalidation, busy admission and server failures are not converted to partial success.
Duplicate observations remain deduplicated by the existing reader.

Budgeted HTTP bypasses transport caches; repository cache hits must occur before calling these
clients. iOS refuses redirects through a task delegate. Android disables redirects, connection
retries, authenticators and the HTTP cache on a copied client. These are application transport-call
bounds: URLSession internal connection recovery, real redirect behavior and actual wire counts
still need controlled-server measurements before A2 can claim a strict on-wire ceiling. The current
fixture tests are not those measurements. Do not enable automatic refresh based on this checkpoint.

Remaining: source-scoped cache/coalescing and batch scheduling, CoreScope batch 429 policy,
controlled-server transport verification, dashboard integration and optional account login/sync.
The 0.9.0 milestones are not marked complete by this increment.

Validation: Xcode build-for-testing succeeded. Six new Swift test cases and four existing
network/analytics regressions passed across two targeted runs. Android `BeaconApiTest` (54 tests)
and `MyNodesFoundationTest` (5 tests) passed, including six new transport/history tests.
`git diff --check` passed. No live server traffic or account writes were used for these tests.


## 2026-10-16 — Analytics cache, coalescing and batch scheduler

Added `MyNodesAnalyticsRepository` on iOS, then Android, with production CoreScope and Beacon
factories. Create one shared repository per active source selection. It owns an in-memory 24h
analytics cache and one active enrichment batch; it does not replace detail-screen caches or
persist visit baselines. Backend kind/origin identify the repository, and its source session guards
reads, writes and completion. New source/session repositories begin with an empty cache.

- Cache reads expose original snapshots immediately, including stale ones. Up to 15-minute-old
  analytics can skip transport; future fetch dates fail reuse. Reuse never changes fetch/window
  timestamps. Missing CoreScope completeness remains unknown.
- Keys normalize case, deduplicate in caller order, and require full hexadecimal public keys.
  Results must match the requested node and a valid one-day range before entering the cache.
- Each new batch shares the existing 20-attempt/four-per-node gate and runs at most two node loads.
  Failed/deferred/rate-limited rows retain any prior snapshot without claiming a new success.
  Budget exhaustion or 429 stops scheduling; already-running work may finish. Unattempted selections
  precede retries in the next explicitly requested batch. There is no automatic next-batch loop.
- Concurrent refreshes (including force-refresh) join the active batch. Each caller receives only
  its requested keys, in order. Keys newly introduced by a joining caller are explicitly deferred;
  joining cannot enlarge the original batch or create another allowance. The next explicit load
  handles them. No favorite collection-size limit was added.
- Cancelling a caller removes just its wait. The last cancellation stops owned work. Cache clear
  cancels all waits and empties the cache; obsolete batch IDs cannot write into a replacement.
  Source invalidation releases callers immediately, even if a transport ignores cancellation.
- Budgeted CoreScope HTTP now honors `Retry-After` with a shared cooldown across dashboard clients
  and batches; missing/invalid headers use the existing 30-second fallback. Cooldown rejections spend
  no allowance. Existing unbudgeted CoreScope detail clients retain their behavior; cross-feature
  cooldown unification is not claimed by this increment. Beacon retains its existing shared gate.

Repository harness counts below use a synthetic one-attempt-per-node fetcher, not live servers.
Both platforms assert a maximum of two active fetches. Production adapter fixtures also verify the
CoreScope `days=1` request and Beacon lookup/history mapping, then warm reads without another HTTP call.

| Selected nodes | First batch attempts | Second explicit batch attempts | Successful cached nodes after both |
|---|---:|---:|---:|
| 0 | 0 | 0 | 0 |
| 1 | 1 | 0 | 1 |
| 20 | 20 | 0 | 20 |
| 100 | 20 | 20 | 40 |
| 500 | 20 | 20 | 40 |

Still open: base node/observer loading, binding the repository to dashboard/app cache-clear lifecycle,
progressive UI delivery, coalescing compatible detail-screen analytics, snapshot persistence and visit
comparisons, account integration, and controlled-server wire-count/bytes/timing measurements. A2 and
C1/C3 remain in progress. Neither the My Nodes UI nor an automatic refresh timer is enabled yet.

Validation: Xcode build-for-testing passed. All 16 targeted Swift repository/adapter checks passed;
the size-matrix check also ran separately with all five sizes inside one test to avoid the selected-test
runner selecting only the first parameterized case. Android passed 70 JVM tests: 57 Beacon API,
five foundation and eight repository tests. `git diff --check` passed. The initial iOS adapter fixture
used invalid multiline test-header content; it was corrected and the affected checks rerun successfully.
These tests used synthetic responses, with no account writes or new live contract probes.


## 2026-10-17 — Pure activity comparison rules

Added matching Swift/Kotlin `MyNodesActivity` assessments and change rules. The newest activity
timestamp determines the display state: exactly 15 minutes is not recently heard; up to five
minutes of future skew is tolerated, and greater skew is unknown. Missing/unsuccessful records
and future fetch times are unknown. Cached assessments can display their state, but fetches older
than five minutes are ineligible for comparison.

Comparisons retain the baseline's captured state. A change requires eligible assessments, a
successful current record fetched after the baseline evaluation, nondecreasing activity time,
and a baseline no older than seven days. A newly active event additionally requires a newer
activity timestamp. Timer-only aging, unknown-to-known, stale baselines, clock rollback and a
return to the baseline state produce no event. These pure rules require callers to supply records
from the same entity/source/account/policy; namespace isolation and rollback invalidation across
a whole visit remain the responsibility of the forthcoming store/view model.

Validation: Xcode build-for-testing succeeded. The Swift Testing fixture test and Android JVM
fixture test each passed all 22 shared scenarios in `activity-cases.json`. `git diff --check`
passed. No live requests were needed. B1 remains in progress: signal/observer comparisons,
event identities, persistence and dashboard integration remain open.


## 2026-10-17 — Signal and observer comparison rules

Added pure `MyNodesSignal` and `MyNodesObserverStatus` implementations on iOS and Android.
Signal calculations require a complete, fresh, exactly 24-hour evidence window and finite,
in-window samples. Half-day boundaries are disjoint (`[start, midpoint)` and `[midpoint, end)`).
CoreScope-style comparisons require at least five samples for each matched nonblank observer ID
in each comparison group, then take the median of per-observer median deltas. Receiver replacement
and sample-count imbalance cannot manufacture an aggregate change. Absolute deltas of at least
5 dB are significant. Mixed-observation trends carry no significant-event eligibility.

Visit signal comparisons additionally require matched-observer methods on both sides, eligible
captured baseline evidence, visits 24 hours to seven days apart, and nonoverlapping history windows.
Missing CoreScope history coverage still suppresses comparisons. These calculators accept transient
sample inputs; raw sample arrays must not become the compact persisted visit baseline.

Observer assessments prefer a timestamped explicit status and otherwise use last-seen recency.
Explicit status comparisons conservatively require both fetch and status measurement to be no
more than five minutes old; stale explicit status can display without becoming event evidence.
A changed explicit status requires a newer measurement. Inferred silence requires a newer successful
record fetch, and method changes, missing records, coverage omission, stale data and expired
baselines cannot create events. Source/entity/account identity and visit-wide rollback invalidation
remain caller responsibilities. Actual adapter status timestamps must be verified as measurement
times before making explicit status comparison eligible.

Validation: Xcode build-for-testing passed. Four Swift Testing tests and four Android JVM tests
passed, exercising 25 shared signal cases, 18 shared observer cases, the previous 22 activity cases,
and NaN/positive-infinity/negative-infinity sample rejection. No live traffic or account writes.
B1 remains in progress for deterministic event identities and compact per-metric summaries;
serialized visit persistence, lifecycle integration and dashboard presentation remain open.


## 2026-10-17 — Compact baselines, stable events and serialized visit storage

Added matching `MyNodesSnapshot` models and `MyNodesSnapshotStore` implementations. SNR visit
comparisons now use compact per-observer medians and sample counts, retaining the original evidence,
method and evaluation time. All existing signal fixtures still pass after summary serialization;
individual SNR samples are absent from the persistence payload. Activity and observer assessments
retain measurement/fetch/evaluation times and method. The enclosing namespace retains provenance
through canonical origin, backend, local/account scope and policy version. Callers must supply the
existing backend's canonical origin, including its port/base path, and a stable account identifier,
never a credential/session token.

Event identity is a deterministic UTF-8 length-prefixed tuple of namespace, entity, category and
baseline revision. Node keys normalize case; observer IDs retain spelling. Refreshes against a
fixed visit baseline keep the same event ID; return to the baseline state yields no net event.
Events retain old/new metric summaries for later presentation/export. The store does not keep an
incident log.

The Swift actor and Kotlin mutex serialize file replacement and in-memory commits. Each visit
captures its baseline; only fresh eligible metrics explicitly confirmed as rendered become
persisted candidates. Eligibility is rechecked at render time, so a queued assessment cannot cross
a recency/freshness boundary and silently become a baseline. Newer per-metric observations win
across windows; late candidates cannot replace newer measurement/fetch/evaluation evidence.
Leaving promotes only that visit's surviving candidates. Restart recovery promotes already-rendered
candidates. Failed file replacement leaves the previous in-memory document intact; read I/O failures
propagate rather than resetting data. Malformed or unsupported schemas reset only this feature file.

Selection supplied at visit start is authoritative: removal prunes records and revokes existing
visits' write access for those identities, including after re-addition. Favorite observers remain
unlimited; additional covering observers are sorted/capped at 200 with an omitted count. Baselines
older than seven days are exposed as expired rather than compared. A detected backward clock jump
invalidates that visit. Store clear invalidates every active visit and removes its file; late writes
and late visit endings cannot recreate it.

Validation: Xcode build-for-testing passed. All 12 targeted Swift tests and 12 Android JVM tests
passed, including eight store tests per platform, existing activity/signal/observer fixture suites,
compact-summary round trips, and nonfinite SNR rejection. Shared `snapshot-v1.json` verifies the
millisecond wire format and `identity-cases.json` verifies collision resistance/Unicode and the same
full event ID on both platforms. Store tests cover crash recovery, cache-only display, stable net
changes, concurrent-window interleaving, source/backend/account/policy isolation, expiration,
rollback, removal/re-addition, clear, observer caps, corrupt/unknown schemas and write failures.
Favorites are represented by an untouched neighboring sentinel in the feature-local reset test.
No live requests or account writes were performed.

B1 is complete for the pure foundation. B2/B3 remain in progress: these stores currently use injected
file locations and require one shared instance across windows (created off the UI thread). They are
not yet wired to app cache usage/clear controls, source-session cancellation, dashboard rendered-row
callbacks, or background/navigation lifecycle. The existing cache-clear UI therefore does not yet
own these stores. Base repository loading, screen lifecycle and production store ownership are next;
these unit checks do not claim end-to-end dashboard behavior or physical power-loss durability.


## 2026-10-17 — Shared ownership and app cache controls

The iOS `CacheStorageService.shared` now lazily owns one snapshot actor for all windows. Recovery
runs inside the cache actor rather than on the UI thread. Production snapshots live under
`Caches/MyNodesSnapshots/snapshots.json`, which is included in cache usage. Clear uses the owned
actor to invalidate visits and delete persisted data; unopened files can be deleted without first
recovering/decoding them. A deletion failure is returned to Storage settings, which shows a retry
message and does not display a successful-clear timestamp.

Android's application container now owns one `MyNodesStorage` and injects it into `CacheStorage`.
The snapshot store performs recovery on its IO dispatcher. General cache-directory cleanup excludes
the snapshot directory: only the shared store may delete its file under the mutex. This prevents a
later recursive directory deletion from erasing a fresh visit after the store's clear operation.
Tile-clear errors still allow snapshot clearing; snapshot deletion failures are reported separately.
Both Storage screens describe My Nodes comparison snapshots and preserve saved-selection wording.

Validation: iOS build-for-testing succeeded. Ten targeted Swift tests passed (two cache ownership
checks and eight persistence regressions). Eleven Android JVM tests passed (three cache ownership
checks and eight persistence regressions). Tests verify shared ownership, usage accounting, old-visit
invalidation, subsequent new writes, malformed unopened storage, and preserved neighboring favorites.
Android additionally exercises interleaving while tile clear is suspended, tile failure with successful
snapshot deletion, and snapshot deletion failure reporting. `git diff --check` passed. UI copy builds;
no device/simulator UI interaction or live network check was performed for this increment.

Production ownership/cache controls supersede the previous injected-store-only checkpoint. B2/B3
remain in progress for dashboard visit/render callbacks, source-session invalidation and navigation/
background lifecycle. The analytics repository's in-memory cache also still needs feature-lifecycle
ownership and cache-clear integration; this change covers persisted comparison snapshots only.


## 2026-10-17 — Source-bound foreground visit owner

Added matching `MyNodesVisitSession` owners on iOS and Android. A session captures one store visit
and a separate cancellation lifetime linked to the selected analyzer session. Keep it across nested
detail navigation; call `finish` on dashboard departure, backgrounding or source change, then create
a new owner on the next foreground visit. Finishing invalidates the lifetime before awaiting
persistence and promotes already-rendered candidates. It does not invalidate the analyzer session,
clear the shared analytics repository, or cancel unrelated consumers. Failed promotion can be retried.

Base/enrichment operations can run through the owner's cancellation wrapper. `loadAnalytics` uses
that wrapper around the existing coalesced repository, so closing one visit removes only its waiter.
Source invalidation rejects new operations and late results. Snapshot writes also check the lifetime
inside the serialized store, covering writes already queued when the owner closes. Cache clear now
invalidates every attached lifetime, cancelling outstanding visit work as well as deleting snapshots.
Clock rollback invalidates attached visit work too. The Android open/finish path retains cleanup under
UI coroutine cancellation; iOS invalidates its lifetime and unregisters the source callback on teardown.

Validation: Xcode build-for-testing passed. Thirteen Swift tests and fourteen Android JVM tests
passed across visit-session, snapshot and cache-ownership suites. Three new lifecycle tests on each
platform cover promotion/new foreground visit identity, source change/cache clear with late work,
and two shared analytics consumers where closing one leaves the other running. Existing persistence
and cache-clear regressions still pass. No new live requests or device UI lifecycle tests were run.

This is a tested lifecycle API for the upcoming screen, not completed screen wiring. Base metadata
loading, actual navigation/background callbacks, shared live-feed demand, favorite-change handling,
and analytics repository cache-clear ownership remain open. Source changes reject work immediately,
but callers still must finish the owner to promptly promote candidates and release visit bookkeeping.
B2/B3/C2 remain in progress until those app-level paths are connected and verified.


## 2026-10-17 — Bounded selected-node metadata repository

Added matching `MyNodesBaseRepository` implementations with CoreScope and Beacon adapters, and a
`MyNodesVisitSession.loadBase` wrapper. Base node metadata is independent of analytics: selected
rows can publish as each exact lookup finishes, before slower selected nodes or enrichment complete.
Catalog seeding accepts only positive, source-matching records and retains their original fetch time;
missing catalog entries never imply deletion. Five-minute-old metadata can be reused; stale rows
remain available for display and future-dated catalog fetches are rejected.

Each explicit batch has the existing 20-attempt/four-per-node allowance and two-worker scheduler.
Concurrent callers join the same batch without expanding its selected-key set or allowance. Keys
introduced by joiners are deferred to a later explicit load. Unattempted keys precede retries.
Failure/rate-limit/deferred rows retain any previous snapshot with an explicit state; exact confirmed
absence reports unavailable, not silent/offline, and evicts that key from reusable cache. Clear and
source/session cancellation reject obsolete completions. Progressive callbacks enqueue/yield promptly;
the visit wrapper checks its lifetime before forwarding rows and before returning results.

CoreScope uses its exact `/api/nodes/{key}` response via the budgeted client and maps 404 to unavailable.
Beacon uses one budgeted public-key summary lookup, with no detail/history/page fan-out. Android's
base adapter projects the newest valid CoreScope last-seen/last-heard timestamp into its existing
single activity field; missing timestamps remain empty/unknown without changing other feature models.

Validation: Xcode build-for-testing passed. Ten Swift and eleven Android tests passed across base
repository, adapter and visit-session suites. The 0/1/20/100/500 selection matrix verifies bounded
cold/warm/manual-next-batch attempts and at most two active synthetic fetches. Tests cover catalog
omissions, stale/future/wrong-source seeds, unavailable records, progressive delivery while one lookup
is blocked, coalescing/clear with late work, and scheduling stop on 429. Adapter fixtures exercise
both exact lookup paths and warm reuse; Android additionally verifies newest activity projection.
An initial Android test setup omitted the shared model's required activity argument; that and a
JUnit method's inferred non-void return were corrected before the final successful runs.
`git diff --check` passed. No live-source traffic or strict on-wire measurements were added.

This implements the selected-node part of C1/C3. The shared observer catalog must still fit inside
the same base allowance, not receive another independent 20-request budget. Production catalog cache
seeding, repository ownership/cache clear, detail-cache sharing, and dashboard rendering/navigation
remain open. No automatic refresh or dashboard entry is enabled by this checkpoint.


## 2026-10-17 — Shared observer catalog allowance

The Swift and Kotlin base repositories now fetch observer catalogs inside the same coalesced batch
and 20-attempt allowance as exact node metadata. Cached node rows publish first. A cold Beacon
catalog traverses at most four 200-item pages before exact node work starts; four catalog requests
leave sixteen node attempts. CoreScope uses one budgeted observer-list request. No per-observer
detail fan-out or automatic network-wide node scan is introduced.

Catalog snapshots retain their original fetch time and are reusable for five minutes, including
bounded partial snapshots. Beacon continuation exhaustion marks traversal complete; hitting four
pages marks it partial. Malformed/repeated continuations fail, and CoreScope completeness stays
unknown because the legacy response supplies no traversal contract. Missing observers never become
offline evidence, even after complete traversal. Failed refreshes preserve prior evidence and expose
an error state; 429 stops further node scheduling. Observer-only selections work without node keys.
Source checks, joined waiters and clear cancellation use the existing base batch lifecycle. Seeding
requires the same source identifier and rejects future fetch times.

Validation: iOS build-for-testing passed, followed by ten passing Swift tests. Fourteen Android tests
passed across base repositories, adapters and visit sessions. New cases verify four catalog requests
plus sixteen node requests, partial warm reuse without timestamp renewal, observer-only empty success,
rate-limit preservation and stop behavior, and repeated cursor rejection. Adapter fixtures verify
both observer endpoints alongside exact node lookup and warm reuse. No live-source measurements or
device UI tests were run.

This completes the bounded catalog portion of the repository, not the dashboard feature. The catalog
currently starts from the first page on a forced refresh; continuation/load-more UI is not implemented.
Catalog IO precedes fresh node lookup, so a slow catalog can delay uncached node rows. Production cache
seeding/ownership, dashboard rendering, navigation/background hooks and remaining C1/C3 integration
are still pending.


## 2026-10-17 — Application-owned repository generations

The existing shared cache owners now provide one My Nodes base/analytics repository pair per source
identifier and source-session UUID. CoreScope and Beacon factory methods construct budget-aware
adapters with a child source lifetime; all windows requesting the same pair share its caches and
coalesced loads. Different source sessions never reuse that pair. Source invalidation propagates to
the child, and obsolete pairs are pruned on subsequent acquisition.

The storage settings clear path now invalidates every owned repository generation before clearing
base metadata, observer catalogs, analytics and persisted visit snapshots. Old references reject
both new loads and cache reads; late completions cannot repopulate a current generation. A subsequent
acquisition creates an empty pair. The parent source session remains valid, preserving other live
feed/map consumers. Repository acquisition is rejected during the clear operation; Android serializes
concurrent clears and completes cleanup in a non-cancellable context. Existing saved favorites and
account state are outside this disposable ownership boundary.

Validation: Xcode build-for-testing and seven Swift ownership/visit tests passed. Eight Android
ownership/visit tests passed. New cases check pair identity across windows, source-session isolation,
cache-clear replacement, parent lifetime preservation, source invalidation, and cancellation of
simultaneous base/analytics loads whose simulated transports finish late. Existing snapshot usage,
corrupt-file clear, deletion-error reporting and visit persistence tests remain green. No live HTTP
requests or UI tests were performed.

The production owners and Settings clear path are connected; dashboard callers still need to acquire
these pairs and bind visits to their lifetime. Explore/dashboard UI, positive catalog seeding from
existing screen caches, detail-cache sharing, and actual navigation/background/live-demand hooks
remain open. C1/C2/C3 are not marked complete by this checkpoint.


## 2026-10-18 — Initial native activity dashboard

Explore now opens a My Nodes activity screen on iOS and Android. It follows the active source's
local node/observer favorites in their existing order, independent of region. Saved labels remain
visible immediately; owned repository caches then supply timestamped evidence before bounded fresh
lookups. Rows publish progressively and disclose fetched/cached/unavailable/deferred/failure/rate-limit
states. Refresh requests fresh evidence; Load More reuses warm data and prioritizes unattempted nodes.
Activity uses the existing 15-minute policy, with last-heard and fetched timestamps and stale-cache
wording. Favorite observers show recorded last-seen/catalog times and incomplete/unverified coverage;
omissions do not imply offline status. Node/observer rows open their existing detail destinations.

The screens acquire source-session-owned repository pairs only after source resolution. Foreground
view tasks own their waiters; disappearance/background cancels screen work, while shared consumers
remain independent. Source changes reset the screen. A visible screen retains an invalidation
subscription so Settings cache clear removes displayed repository evidence and offers Refresh.
Forced refresh is consumed once, not carried into later foreground resumes. No new polling loop or
socket was introduced. iOS Explore's existing automatic node refresh now pauses while a nested
screen is open. Empty selections do not fetch the observer catalog.

Validation: final iOS build-for-testing passed. Android compiled and thirteen base/ownership unit
tests passed; the instrumentation suite compiled with a new warm-cache, node-navigation and
cache-clear UI test. No Android device was connected, so that instrumentation test was not run.
iOS simulator verification confirmed Explore entry, readable empty state, Refresh and Back. An
orphan Nodes heading in the empty state was fixed after inspection. Populated iOS rows, device
background/cache-clear scenarios and accessibility/device-size matrices have not been verified.

This is the first activity presentation, not completion of D1/D2/D3. It deliberately does not mark
visit candidates as reviewed: Changes UI and visit persistence wiring remain pending. Analytics,
covering-observer inference, filtering/sorting, exports, account selections, production catalog
seeding from existing Explore caches, live recovery and full accessibility/adaptive verification
remain open. No baseline is advanced by simply opening this initial activity screen.


## 2026-10-18 — Explicit reviewed comparison results

Both platforms now expose `MyNodesReviewResult` through the snapshot store and visit session's
`reviewed` operation. It distinguishes unavailable evidence, first visit, expired baseline, unchanged
comparable evidence and a changed event. The existing event-only `rendered` API delegates to the same
implementation. Fresh-render eligibility and atomic candidate persistence remain enforced by the
store; merely computing a comparison does not persist or advance a baseline.

This closes a presentation ambiguity: previously a nil event could mean either unchanged or
unavailable. Signal windows without comparable evidence, observer-method switches, regressing
measurements and contradictory explicit statuses with no newer measurement are unavailable, never
unchanged. Refreshes retain the captured visit baseline and stable event identity. A first visit and
an expired baseline both allow an eligible reviewed candidate to establish the next visit's history,
while cached/failed renders cannot do so.

Validation: sixteen Swift and sixteen Android comparison, snapshot and visit tests passed. The new
visit regression covers all five states, repeat refresh against a fixed baseline, cache-only rejection,
promotion at visit completion and eight-day expiry. Shared signal fixtures also verify that
incomparable windows are not called unchanged; observer-method switching has matching tests.
Xcode's initial physical-device test build required a signing team; simulator build and tests passed,
and the original run destination was restored. A Kotlin test property-access typo was corrected
before the final passing run. `git diff --check` passed.

This is the store/session contract needed by the Changes UI, not completed dashboard integration.
Visible-row review callbacks, visit ownership across nested navigation/background transitions,
selection changes during a visit, and comparison presentation remain pending. The activity screen
still does not advance reviewed baselines.

### Dedicated Favorites navigation — 2026-10-18

Explore now opens a dedicated Favorites page. Saved channels, nodes, and observers, adding nodes, removal, and reordering moved to that page. Activity opens from Favorites. Existing source-scoped storage remains intact. CoreScope account sync and a Changes UI remain unimplemented. iOS build-for-testing and Android instrumentation compilation passed.

Simulator verification passed on iPhone 17: both Explore entry points open Favorites, adding and swipe removal update immediately, Activity shows the saved node, and back navigation works. Temporary test favorite removed; source settings preserved. Android UI tests compiled but were not run on a device.

### Activity visual consistency — 2026-10-18

Activity uses the existing iOS background and instrument cards, with a source summary, separate node/observer cards, toolbar Refresh, and Load More below nodes. Android uses the existing Material surface cards and a labeled refresh icon in the summary. Data loading, cache semantics, timestamps, and source scoping are unchanged. Both platforms compile.

Final iPhone 17 simulator verification passed: card styling and leading-aligned timestamps, Refresh, Load More, node header tap targets, detail navigation, and back navigation. Existing favorites preserved; device session closed and original Xcode destination restored. Android UI tests compiled; Android device validation remains outstanding.

### Favorites Changes — 2026-10-19

Favorites now opens Changes on iOS and Android. It compares node activity and favorite-observer recency using the existing 15-minute policy, with explicit first-visit, expired-baseline, unavailable, unchanged, and changed states. Source names and addresses match Favorites/Activity. The cards include current state, fetched time, and actual prior review time.

One foreground Changes visit owns a fixed snapshot baseline. Refresh requests fresh bounded evidence without resetting that baseline; Load More retains eligible evidence already fetched in the visit. Only cards at least half visible can record reviewed candidates. Off-screen, failed, cache-only, and unavailable evidence cannot advance history. Departure/background finishes the visit; source/cache invalidation rejects late writes. Changes currently has no nested detail navigation, so visiting node details from Activity cannot inadvertently finish a Changes visit.

Validation: Swift controller regression plus comparison/invalidation tests passed (3 tests), and Android controller/visit regressions passed (5 tests). The initial Swift fixture omitted its required role field; corrected before the passing run. iPhone 17 device interaction verified first visit, refresh with unchanged baseline, departure/reopen with last-reviewed time, and foreground recovery. Android Pixel 10 instrumentation verified visible-only baseline advancement and the full Favorites add/open/remove flow.

Scope: this page reports recency changes, not SNR/telemetry changes. Signal comparison/enrichment, covering-observer inference, account sign-in/sync, and controlled authenticated-server validation remain pending. Local favorites and account data are not modified by comparison persistence.

Final Android validation also passed Activity navigation/cache clearing and re-ran Changes visibility persistence after serializing visit teardown. A mutex prevents selection/lifecycle restarts from opening a new baseline before the previous visit has finished. Five Android controller/visit unit tests remain green. Account sync is deferred pending the controlled-server decision; no authentication or remote mutations were attempted.

### Activity simplification and Changes deferral — 2026-10-19

Product decision: remove the separate Changes entry and route from Favorites on both platforms. Recency transitions alone did not justify another page. Comparison models, persistence, and tests remain available for a future useful “Since your last visit” section; Activity still does not advance comparison history.

Activity now has a compact source identity card, role-colored icons, fully tappable node/observer cards, primary activity and relative last-heard information, and secondary check/cache details. Explanations moved to an About activity control. Load More appears only when node rows are missing or deferred after loading. Existing loading, source scoping, errors, and cache-clear behavior remain.

iPhone simulator verification passed for layout, whole-card navigation/back, Info/Done, Refresh, absent Changes entry, and hidden Load More with loaded favorites. The original physical-device destination was unavailable at cleanup; iPhone 17 remains selected.

Android Pixel 10 Activity instrumentation also passed: whole-card navigation, About activity dismissal, hidden Load More for loaded nodes, and cache-clear invalidation. Both platforms compile; diff whitespace checks passed.

### Account isolation and account-page styling — 2026-10-19

The working tree contained reachable account sign-in/sync code beyond the earlier “deferred”
checkpoint. This increment corrects that implementation on iOS and Android; it does not claim
completion of all account or dashboard milestones.

Account selections and pending favorite edits now persist separately by source/account in a new
versioned namespace. Login/refresh never seed account edits from device favorites and never replace
the device list. Import is explicit, stages account additions, and requires Sync now to upload.
Copy to device is explicit and additive. Sign-out offers copy-and-sign-out or removal of the local
account snapshot/pending edits, preserving preexisting device favorites. An account identity change
rejects the operation before settings access. Operations are serialized per origin, and a session
change invalidates stale windows. Page departure/background cancels owned work.

Favorite and claimed-node memberships remain independent. Removing an account favorite no longer
forces its re-addition merely because it is also claimed; claim changes remain on CoreScope.
A changed server generation pauses reconciliation and uploads with choices to accept server
selections or restore saved account favorites locally before an explicit sync. Refresh rebases
pending edits without losing remote deletions. Failed/conflicting writes retain the pending state.
Each sync makes at most one settings read and one write; conflict retries are explicit rather than
immediate, respecting the shared per-origin one-minute write gate. Account 429 responses participate
in the existing My Nodes CoreScope cooldown.

Migration deliberately preserves all existing device favorites: the previous implementation did
not record their local/account provenance, so automatically removing old downloaded entries would
risk removing genuine device favorites. Old mixed-list baselines are not used to infer new pending
account edits. Activity continues to follow device favorites; account selections can be copied there
explicitly. A future unified account-aware dashboard remains separate work.

The iOS account page now uses NodeScopeBackground, instrument cards, semantic text sizes, adaptive
content width and the app tint. Source, status, account identity, sync controls, selections and reset
choices are grouped separately; sync status is near the top rather than below the tab bar.

Validation: iOS build-for-testing passed; 15 Swift account tests passed (13 model/transport/storage
cases followed by two controller regressions). Android debug build and instrumentation compilation
passed; 14 account JVM tests passed. iPhone 17 simulator inspection verified top/bottom layout,
back navigation, and opening/dismissing import/sign-out dialogs. Existing signed-in state and device
favorites were preserved; no live import, settings write, selection removal or sign-out was performed.
Controlled authenticated-server write acceptance, Android account device checks and the full
accessibility/size matrix remain outstanding.

Final Android lint also passed after the lifecycle changes. No new release-readiness claim is made
from compilation or synthetic account tests alone.

### Approved smaller scope and combined Favorites — 2026-10-19

The user approved narrowing 0.9.0 to Favorites, useful Activity and reliable optional account sync.
The new scope section in plan.md supersedes the original dashboard milestones. ROADMAP.md now
reflects the decision: defer analytics enrichment, Changes, live Activity updates, extra navigation
controls and new dashboard exports; retain focused validation and release preparation.

On iOS and Android, account favorites and claimed nodes now appear directly in Favorites and
Activity. They are a display projection from source/account-scoped storage, not additions to the
device favorites document. Device order remains first; account-only nodes sort by name/key.
Favorites labels Device / Account / Claimed membership, with separate device/account removal.
Removing account membership stages an edit for Sync now; claimed membership is independently
preserved. Device-only reordering, explicit import and additive copy remain intact. Active account
identity persists across restart, changes on verified account selection, and clears on logout or
recognized sign-out. The app does not restore old mixed-list data as new pending account edits.

Account-node name presentation is now implemented on Android as well as iOS. It reuses local
metadata and bounded shared repository lookup; unknown names retain an abbreviated stable key.
These lookups do not upload account settings. The iOS import confirmation is anchored to its button.

Validation: 17 Swift account/controller/storage tests pass. Android account/settings/library suites
pass 23 tests; debug build and instrumentation compilation pass. Regression cases exercise overlapping
memberships, case-normalized deduplication, device removal retaining account membership, account
removal retaining claims, remote deletion retaining device membership, account switching, restart
projection, and logout leaving device storage intact. iOS builds successfully after the final
missing-credential handling adjustment. User requested to perform simulator checks themselves;
no automated device interaction was run for this increment.

Next evidence: account-acceptance.md contains an unrun controlled-server checklist. End-to-end
account validation, focused accessibility/device/performance checks, backend release regression
and release artifacts remain open. This checkpoint does not mark the release complete.

Final Android lint passes for the combined-account increment.

### In-flight account edits and failure-path regressions — 2026-10-19

Combined Favorites allows a user to stage account removals while another view awaits a sync response.
The original controller captured state before IO and could overwrite those newer edits. Both
platforms now reread the latest account state after the settings GET and merge post-send edits into
the acknowledged PUT baseline. Newer edits remain pending and the completion message says so,
rather than claiming everything is synced. This preserves additions/removals made during a request
without retrying the write immediately.

Sync now writes only when favorite membership differs from its baseline. Raw JSON whitespace or
an absent empty favorites key no longer cause a settings write by themselves. iOS also rechecks
cancellation and source lifetime immediately before starting account transport, after the shared
cooldown actor hop.

The Swift fixture can now stage an edit deterministically before each response. Its controller
regression removes one favorite during GET and another during PUT, then verifies the uploaded
baseline and remaining pending membership independently. Added expiry/offline-logout and no-op
write regressions. Android covers acknowledgment rebasing, failed logout credential deletion and
JSON-formatting-independent pending state. No live account was mutated. These synthetic tests
supplement, rather than replace, controlled-server acceptance.

Validation for this increment: 20 Swift account tests pass; 26 Android account/settings/library tests
pass. Both builds, Android instrumentation compilation, Android lint and whitespace checks pass.
The first Swift test build caught a missing nested `try` in a new assertion; corrected before the
passing run. Device interaction remains user-owned. Controlled-server acceptance and the focused
accessibility/adaptive-layout release checks remain open.

### Focused accessibility/layout source fixes — 2026-10-19

Account-node removal controls now stack below identity at accessibility Dynamic Type sizes.
Favorites subtitles wrap instead of forcing a single line; Android saved-row titles and subtitles
also wrap. Account card headings and Activity section headings expose heading traits. Claimed
node icons have a descriptive label. Android repeated Manage/Remove controls identify their node.
The sign-out confirmation is attached to its initiating button, matching the import anchor fix.

iOS compilation and Android debug assembly pass. Source review confirms semantic text styles
and no fixed-height text containers in the account and Activity views. This is a focused source
fix, not an app-wide accessibility certification. Simulator/device checks remain user-owned:
maximum text size, narrow layouts, VoiceOver/TalkBack reading order and removal actions, and
Import/Sign out popup placement at different scroll positions. Controlled-server acceptance,
performance/device matrix, and release preparation remain open.

### Release regression and preparation — 2026-10-20

Added draft store copy and explicit submission gates in [release preparation](release.md).
Fixed a date-sensitive Beacon analytics fixture using an injected clock; production still uses
the system clock. The full Android suite initially exposed this expired fixture, then passed
after the fix. Four contract-tool tests pass.

Final rerun: 329 Android tests, 328 passed and one skipped; zero failures/errors. Android debug assembly and lint pass.

### Large-selection loading regression — 2026-10-20

Extended Android coverage beyond the first two batches: all 500 nodes must become reachable
through 25 bounded loads, retain input order, then load entirely from cache without further IO.
All nine repository tests pass. Recorded timing and limitations in [performance evidence](performance.md).
This closes the Android full-drain loading check, not device performance or the iOS equivalent.

### iOS large-selection regression preparation — 2026-10-20

Added the equivalent full 500-node drain/cache regression on iOS and compiled the test target.
Also corrected the iOS Beacon analytics fixture's date dependence, matching the Android fix:
BeaconBackend accepts an injected clock while production keeps Date.now; the regression supplies
its fixture's newest observation plus one second. The initial build exposed a Swift Testing macro
expansion issue on a chained compactMap/max expression; splitting the expression fixed compilation.
Final build-for-testing passes. These tests have not been executed in this increment, and simulator
interaction remains user-owned. No release gates were marked passed based on compilation alone.
