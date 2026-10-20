# NodeScope 0.9.0 — Favorites and network awareness

Status: narrowed release implementation is in progress; acceptance and release preparation remain open.
Prepared: 2026-10-15. Predecessor: 0.8.0 is complete, pending Apple/Google approval.
See [implementation progress](implementation.md) for audited contracts, tests and remaining work.
See [release preparation](release.md) for draft store copy and outstanding submission gates.

## Approved release scope — 2026-10-19

This decision supersedes the broader dashboard/analytics requirements below. Release 0.9.0
focuses on Favorites, useful Activity, and reliable optional CoreScope account sync on iOS and
Android. The older detailed specifications remain design references, not release blockers.

| Work | Release decision |
|---|---|
| Favorites and Activity | Keep readable names, favorite ordering, last heard, last checked, cached/partial/error states, and existing detail navigation |
| Account nodes | Show account favorites and claimed nodes alongside device favorites, deduplicated by source/full key; preserve separate memberships and account storage |
| Membership controls | Label Device / Account / Claimed provenance; removing one membership must preserve the others; device import and copy remain explicit |
| Account reliability | Required: controlled-server sync validation, account switching, remote deletion/reset, expiry, offline behavior, logout, cancellation and throttling |
| Android parity | Required for shipped behavior, including account-node names and combined Favorites/Activity |
| Activity filtering | Optional small follow-up only if larger favorite sets demonstrate a need; not a release blocker |
| Live Activity updates | Defer; manual refresh and refresh on return are sufficient for this release |
| Signal/availability/coverage/telemetry dashboard | Defer; use existing Node Analytics and observer/detail screens |
| Since your last visit / Changes | Defer; retained comparison infrastructure does not require a new presentation |
| Extra analytics/map/packet shortcuts | Defer unless a demonstrated navigation problem justifies one |
| Dashboard CSV/JSON exports | Defer; existing Node Analytics exports remain available |
| Accessibility/layout/performance | Required, focused on shipped screens and representative large favorites collections |
| Release preparation | Required: regression and beta smoke tests, version/build, screenshots, notes and privacy/store copy |

### Execution order and completion evidence

1. Integrate account nodes into Favorites/Activity on iOS, then Android. Test overlapping
   memberships, remote removals, account/source switching, logout and guest persistence.
2. Finish account-name presentation parity and validate account flows against a controlled server.
   Never create or mutate a public account as a substitute for controlled test fixtures.
3. Polish Activity's names, freshness, unknown/error states and retry behavior. Keep row-tap detail
   navigation; do not add analytics requests to every row.
4. Validate accessibility, iPhone/iPad/Android adaptive layouts, large-favorites loading and both
   backend regressions. Record limitations and actual measurements.
5. Prepare beta/release artifacts and record user smoke-test outcomes separately from build results.

Account membership is an overlay, not a copy into device storage. Device ordering stays first;
account-only nodes follow deterministically. Account nodes are visible for the active signed-in
account only; pending edits never migrate to another account. Sign-out hides account-only nodes,
while explicit copies and original device favorites survive. Guest/Beacon browsing remains usable.

## Outcome

Answer three questions without opening every favorite: which nodes have been heard recently,
what changed since the last visit, and how current and complete is the analyzer's evidence?
“My Nodes” means nodes selected for monitoring on the selected source, not ownership or remote
administration. Local favorites supply membership by default; optional CoreScope account sync
can also supply saved nodes. Mesh browsing stays read-only; opted-in account sync writes only
personal saved selections. The app stays native and useful with either CoreScope or Beacon.

This document defines the proposed release scope and defaults. Product choices below can be
revised during review. Milestone A verifies backend evidence before implementation depends on it;
it is not a promise that unverified endpoints or fields exist.

## Scope and product decisions

| Decision | 0.9.0 behavior |
|---|---|
| Entry point | Compact Favorites card in Explore opens a dedicated Favorites page; saved lists and management live there, with Activity nested inside |
| Membership | Local node favorites by default; signed-in users can also include CoreScope saved nodes, preserving the distinction between own-node and favorite lists |
| Accounts | Optional CoreScope sign-in and saved-selection sync; off by default, never required for My Nodes or public browsing |
| Source | One selected analyzer at a time; no combined cross-source health score |
| Region | All favorites on that source, independent of map region; label this explicitly; no new dashboard region filter |
| Time range | Fixed last 24 hours for dashboard analytics; full existing range controls remain in Node Analytics |
| Changes | Compare eligible observations with the last reviewed visit; in-app only |
| Observer changes | Favorite observers plus identifiable observers covering favorite nodes; no network-wide event feed |
| Health wording | Observed activity and individual metrics; no synthetic healthy/unhealthy score or claim that a silent node is offline |
| Telemetry | Display only explicitly associated, timestamped telemetry; never attribute observer battery to a node it heard |
| Platforms | Each functional milestone lands on iOS, then Android, before the next begins |
| Monetization | Preserve existing access rules; no new paywall or entitlement changes |

Required: dashboard, visit comparison, source/data status, supported analytics, drill-downs,
CSV/JSON exports, source-safe persistence, optional CoreScope account integration, accessibility,
adaptive layouts, and Android parity. Optional means users can choose not to sign in; the account
integration itself is planned release work on both platforms.

Excluded: push/background notifications, background fetch jobs, adjustable alert rules, ownership
verification, cross-source aggregation, new backend services, operator/network writes, map playback,
Field Mode, topology workspaces, full offline investigation, and unrelated navigation redesigns.
Account administration, in-app registration, notification preference management, channel proposals,
and website theme/filter sync are also excluded. Personal saved-selection sync is the sole new
server-write feature.

## Optional CoreScope accounts and saved-selection sync

User-approved scope, 2026-10-15: include optional account login in 0.9.0, with saved-node and
supported-favorite sync. Guest operation is the default. No onboarding login gate, recurring
sign-in prompts, NodeScope account service, or reduction in guest/Beacon functionality.

### Entry point and authentication

Settings shows “CoreScope account” only for a CoreScope source advertising account support.
Provide a native email/password sign-in form with the destination hostname clearly visible.
Create account and Forgot password open that source's HTTPS website; activation/reset happens
there, followed by native sign-in. No embedded website cookie extraction is required.

Implementation contract to verify in A4:

- Detect `userManagement.enabled` through `/api/config/client`; do not infer support from HTTP
  200 on auth routes, since disabled routes can return the website's HTML fallback.
- Use documented `/api/auth/login`, `/api/auth/me` and `/api/auth/logout` session flow. Honor
  required Origin and CSRF headers for cookie-authenticated account writes.
- Use a dedicated account client and isolated cookie handling. Store the session credential in
  iOS Keychain and Android storage encrypted with a Keystore-backed key; never persist the password,
  put credentials in URLs/logs/exports, or send them through the public browsing client.
- Require HTTPS and bind credentials to the validated server origin. Reject cross-origin credential
  forwarding and redirects; do not silently change the login destination to satisfy server config.
- Key credentials by source and account identity. Cancel and reject late account work on sign-out,
  account/source changes, and source backend changes; ordinary data cache clear does not sign out.
- Restore a session by validating `/api/auth/me`. An authentication rejection pauses sync and
  shows “Sign in again”; a timeout/5xx leaves session state intact and shows temporary unavailability.
  Honor 429 and Retry-After; never retry passwords automatically.
- Newer CoreScope device-token support is a capability-gated alternative only after its routes,
  allowed settings operations and server-version availability are verified. It is not required
  for the initial cookie-based implementation or assumed to exist on every 3.14.0 server.

After login, show account identity, hostname, sync enabled/disabled, last successful sync,
pending changes, Sync Now, Manage account on website, and Sign Out. Sign-in alone must not upload
local favorites. Offer “Use account selections” (default) or an explicit previewed “Add local
favorites to account” import, plus the option to leave sync disabled.

### Membership and synchronization semantics

CoreScope's own-node list and favorites are separate concepts. Preserve their remote categories
and provenance; never mark all NodeScope favorites as owned nodes. A4 captures the actual allowlist,
keys, identity fields and payload shapes before mapping them.

- Guest dashboard: existing local node favorites. With sync enabled: the union of local favorites,
  account node favorites and account own-node selections, deduplicated by full public key within
  the source. Rows disclose their membership when managing selections.
- An account selection arriving from sync is not a newly active node event. It starts its own
  baseline. Removing one membership leaves the row if another membership still includes it.
- Keep local favorites intact. Manage local membership through existing actions; label account
  favorite/own-node removal explicitly. Adding a favorite while sync is enabled updates the account
  favorites category; it does not also create an undeletable local membership. Local-only saving
  remains available as an explicit choice.
- Local favorite order comes first, followed by account-only nodes in deterministic name/key order.
  Keep app ordering local unless A4 verifies a compatible remote ordering contract.
- Sync node selections only for the audited CoreScope 3.14.0 contract: `meshcore-favorites` is a
  node-key array, and `meshcore-my-nodes` is an object array keyed by `pubkey`. Observer/channel
  favorites stay local; preserve unrecognized remote entries without converting or deleting them.
  Never sync private channel keys, decrypted messages, passwords, analytics, visit baselines,
  location data, or app-specific display settings.
- Use `/api/account/settings` with its allowlist, revision and generation. Preserve unrelated
  document keys. Persist a last acknowledged document and account-scoped pending additions/removals;
  merge per-item edits against that base instead of replacing the remote document or simply unioning
  lists. Remote deletions must stay deleted when this device made no corresponding edit.
- For simultaneous contradictory edits to the same membership, removal wins; a later intentional
  re-add after fetching that removal is allowed. Test this against the server's web sync behavior.
  If that policy conflicts with the verified contract, resolve it in A4 before enabling writes.
- A 409 refetches and rebases once; repeated conflict leaves changes pending with Retry. A changed
  generation (deleted/reset server document) pauses uploads and asks whether to restore local
  account selections rather than silently resurrecting deleted settings.
- While offline or expired, retain labeled account selections and pending edits in that account's
  namespace. They can sync only after the same account is authenticated again, never into another.

Sync occurs only while foregrounded and opted in: on login, source return, explicit Sync Now,
and coalesced selection edits. Foreground polling is no more frequent than once per minute.
Allow one sync operation per source/account at a time, with at most four settings requests
(read, write, conflict read, retry write). Limit writes to one per minute, including retries,
and respect the server's hourly limit and shared cooldown; pending work waits rather than loops.
Account traffic is measured separately from dashboard analytics budgets and must not delay rows.

### Sign-out, storage and privacy

Attempt server revocation, then clear local session credentials regardless of network success.
If revocation cannot be confirmed, explain that the server session may remain active until revoked
on the website or expired. Cancel queued uploads and purge local account sync state. Offer an
explicit copy of downloaded selections into local favorites before purging; preserve preexisting
guest favorites in every case. Keeping copies never automatically imports them into a later account.

Turning sync off stops requests and account writes while retaining a labeled local snapshot;
re-enabling fetches remote state before reconciling pending edits. General cache clear removes
downloaded analytics and visit baselines but preserves saved selections, pending personal edits
and credentials. Account sync state is separate from disposable response caches.

Include the local/account scope in My Nodes baseline identity; switching accounts establishes
a separate comparison history. Scope exports to the active view and exclude email/session data.
Update privacy/store descriptions to explain that opted-in selections are stored on the chosen
community server, and link account management/deletion on that server. No new NodeScope backend.

### Contract references and verification boundary

Planning references reviewed on 2026-10-15:

- [CoreScope account API](https://github.com/Kpa-clawbot/CoreScope/blob/master/docs/api-spec.md#user-management-optional)
- [Released 3.14.0 account guide](https://github.com/Kpa-clawbot/CoreScope/blob/v3.14.0/docs/user-guide/accounts.md)
- [Current account/device-token guide](https://github.com/Kpa-clawbot/CoreScope/blob/master/docs/user-guide/accounts.md)

The current branch can differ from deployed releases. A4 records the tested release/commit and
fixtures. No live credentials, account creation, or server writes are authorized by this plan edit.

## Existing implementation to reuse

Paths in this section are repository-relative, not Xcode navigator paths.

| Existing component | Reuse / required extension |
|---|---|
| `CoreScopeViewer/Networking/FavoritesStore.swift` | Source-scoped favorites, saved node snapshots and order; preserve stored format and existing data |
| `CoreScopeViewer/Features/Explore/ExploreScreen.swift` | Entry point, favorite management, navigation and existing foreground refresh; avoid a duplicate polling loop |
| `CoreScopeViewer/Models/Node.swift` | `latestActivity` is the newest available last-seen/last-heard timestamp |
| `CoreScopeViewer/Models/NodeAnalytics.swift` | Availability, SNR points, coverage and time range; optional values remain optional |
| `CoreScopeViewer/Models/AnalyticsContext.swift` | Completeness, actual/requested ranges, provenance, and packets versus observations |
| `CoreScopeViewer/Features/NodeDetail/NodeAnalyticsViewModel.swift` | Existing detail behavior; extract only the shared loading needed for coalescing |
| `CoreScopeViewer/Features/NodeDetail/NodeSignalQuality.swift` | Existing signal math; new change calculation must use comparable samples, not just the current best observer |
| `CoreScopeViewer/Networking/AnalyzerBackend.swift` | Existing backend interface; no bulk analytics endpoint exists in this interface |
| `CoreScopeViewer/Networking/BeaconNodeReader.swift` | Observation history currently allows one lookup plus up to 19 pages per node; never call this unbounded for every favorite |
| `CoreScopeViewer/Networking/LiveFeedService.swift` | Shared socket, consumer demands, metadata recovery and connection state |
| `CoreScopeViewer/Networking/CacheStorageService.swift` | Include new snapshots in usage and clear operations |
| `android/.../core/storage/NodeLibrary.kt` | Android favorite identity and persistence |
| `android/.../core/network/BrowseRepository.kt` | Android loading, cache and backend routing |
| `android/.../feature/explore/ExploreModel.kt` | Existing summary semantics; currently uses a 15-minute activity window |
| `android/.../feature/nodes/NodeAnalyticsLogic.kt` | Equivalent calculations; match shared fixture results |

The current iOS analytics cache key includes a source-session UUID. Persistent My Nodes visit
history must instead use stable source identity, while guarding all writes with the active session.
The neutral node model currently contains no battery/temperature telemetry. Observer telemetry is
available, but does not establish a node-to-observer identity relationship by itself.

## Screen and interaction specification

### Explore summary

Place a compact “Favorites” card after Network at a Glance with the saved-item count.
Tapping it opens a dedicated Favorites page containing saved channels, nodes, and observers,
with add, remove, reorder, and detail actions. Remove the inline saved lists and add/reorder
controls from Explore. Activity opens from Favorites. Keep Changes deferred until its UI works.
No analytics requests are triggered merely by showing the entry card.

The accepted product name is Favorites: saved nodes need not belong to the user. Local and
future CoreScope account favorites share this home and are deduplicated by source and identity;
account sync is not implemented yet. Preserve existing local storage and ordering.
Internal MyNodes type/file names remain implementation details.

### My Nodes dashboard

Order: source/data status, “Since your last visit”, selected node list, relevant observer changes.
The header shows source, last successful refresh, Refresh, export, and favorite management.
The comparison section names the actual prior review time, including the date for older visits.

Each row shows name, role, activity label and last-heard age first. Secondary content shows
24-hour availability, signal trend, observer count, and eligible telemetry as space allows.
An expandable metrics area provides units, observation range, freshness, sample count and any
unsupported/partial explanation. Missing metrics do not render as zero or healthy green badges.
Keep row identity tied to public keys; preserve local favorite order and the account-only ordering
defined above. When account sync is enabled, show its state separately from analyzer freshness.

Filters: All, Changes, Not heard recently, Unknown. Sort: Favorite order (default), Name,
Last heard. Unknown dates sort last; public key breaks ties. Filter/sort choices are local screen
preferences, not new saved network presets. Live updates do not reorder the default list.
A “Changes” filter uses the current visit comparison and explicitly identifies unevaluated rows.

Row tap opens Node Detail. Metric actions open Node Analytics at 24h, its observer coverage, or
the relevant observer detail. Actions also offer Show on Map and existing node packet/route
sections. Open an exact packet/route only when its hash/confirmed route exists; never invent a
packet filter or route from aggregate history. Preserve source and back navigation on every path.

Use the existing adaptive presentation: readable content widths, expanding rows at large text
sizes, and platform-appropriate navigation. Preserve selection and scroll during rotation,
tablet resizing, fold/unfold and background/foreground. A new permanent split layout is not required.

Proposed content layout (values are illustrative):

```text
My Nodes                         Refresh · Export
Colorado Mesh · All favorite nodes on this source
Source reachable · Live connected · Updated 1 min ago

Since your last visit — yesterday, 18:30
2 activity changes · Analytics compared for 3 of 8 nodes

All | Changes | Not heard recently | Unknown
Sort: Favorite order

Roof repeater                     Heard 2 min ago
  Recently heard · Changed since last visit
  Signal trend: +3 dB, mixed observations
  Availability / coverage: Not supplied by Beacon
  Analytics · Map · More

Load more analytics
Observer changes                  1 change
```

### Explicit states and copy

| Condition | Presentation / action |
|---|---|
| No favorites | Explain favorite membership; Add nodes / Open map |
| First comparable visit | “Changes will appear after your next visit”; still show current metrics |
| No eligible changes | “No changes detected in the data compared”; include evaluated count |
| Some rows not evaluated | “Compared 6 of 12 nodes”; Load more analytics |
| Cold load | Stable row placeholders from favorites; no zero-valued metrics |
| Cached/offline | Last saved snapshot and date; no fresh silent-node alerts |
| Partially successful refresh | Keep successful rows, show per-row failures and Retry |
| Missing/deleted node | “Not found on this source”; retain favorite and offer remove/retry |
| Unsupported analytics | Activity still works; explain unavailable metrics |
| Rate limit | Keep content, honor server retry time, disable premature repeated refresh |
| Live feed recovering | Recovery notice; REST timestamps stay separate from socket status |
| No trustworthy timestamps | Unknown activity; show available identity and navigation |

## Metric and comparison rules

All numerical thresholds below are proposed product defaults, not claims about RF reliability.
Keep them in one versioned policy shared by fixtures on both platforms. Use an injectable clock.

### Activity and availability

- Use newest valid `lastSeen`/`lastHeard`, matching iOS `latestActivity` and Android's projection.
- Recently heard: age less than 15 minutes. Not heard recently: age at least 15 minutes.
  Unknown: no valid timestamp or no current record. Exactly 15 minutes enters the latter state.
- Display the threshold in explanatory copy. “Not heard recently” is an observation, not an outage.
- A fresh successful lookup may confirm an old timestamp. A timer alone, failed refresh,
  filtered-list omission, or disconnected socket cannot establish a newly silent event.
- A future timestamp more than 5 minutes ahead is unknown with clock-skew explanation. Smaller
  positive skew displays “Just now”; clock rollback invalidates visit comparison until reset.
- Availability is the backend-provided 24-hour metric with its provenance and definition verified
  in A1. Do not derive it from packet count or label it physical device uptime.
- Beacon availability and per-node observer coverage remain unsupported, as in 0.8.0.
- A newly active event requires a previous eligible not-recently-heard state and a current
  recently-heard state with a newer activity timestamp. A newly silent event requires the reverse
  state transition confirmed by a fresh successful record fetch; label it “No longer heard in
  the last 15 minutes”. Unknown-to-known establishes a baseline, not an activity change.

### Signal

- Use finite SNR samples from the 24-hour response. Do not compare RSSI with SNR or packets with
  observation counts. Show sample count and actual coverage alongside the trend.
- For a within-day trend, compare median SNR in the older and newer 12-hour halves; require at
  least five samples in each half and complete coverage of the requested window.
- For CoreScope, compare matching nonempty observer IDs only. Compute each observer's median
  delta; summarize with the median of those deltas, requiring five samples per observer per half.
  Name the number of comparable observers. Observer composition changes cannot manufacture a trend.
- Beacon lacks observer identity in these samples: show the aggregate SNR delta labeled
  “Mixed observations; receiver changes may affect this”. Do not turn it into a significant
  signal-change event or a receiver-normalized quality grade.
- A significant CoreScope shift is an absolute comparable delta of at least 5 dB. Smaller shifts
  are “Little change”; insufficient samples/identity/coverage are “Not enough comparable data”.
- Visit-to-visit signal events compare stored last-24-hour per-observer medians under the same
  minimum-sample rules and matched observer IDs. Visits must be at least 24 hours apart so windows
  do not overlap, and at most seven days apart. The within-day trend can still display sooner.
- Existing detailed quality labels remain in Node Analytics. Do not infer a changed radio setup
  when the backend does not provide historical radio configuration; describe SNR, not causality.

### Observer coverage and telemetry

- CoreScope: show the provided unique-observer metric for the same 24-hour range, linking to its
  coverage rows. Missing arrays/fields are unknown unless the contract proves a known zero.
- Track status changes for favorite observers and the union of observers in comparable previous
  and current node coverage. Removing an observer from coverage is not evidence it went offline.
- Prefer an explicit timestamped server online/offline status. Otherwise use a separately labeled
  15-minute recently-seen classification. Never compare these two methods as equivalent statuses.
- Fetch one shared observer catalog, not an analytics request per covering observer. Missing status
  is unknown. Beacon can still report changes for favorite observers without per-node coverage.
- Observer telemetry stays labeled as observer telemetry. Node telemetry is shown only if A1
  verifies an explicit identity association, units and sample timestamp in an existing contract.
  If no such contract exists, show an explanation; adding a new node telemetry API is out of scope.
- No battery/noise/availability threshold alerts in 0.9.0; only activity, observer status and
  comparable SNR changes are event categories.

### Data freshness and confidence

Maintain separate facts: fetch success time, measurement time, covered window, completeness,
backend capability, source reachability, and live connection/recovery state. A connected socket
does not prove the dataset is current; a successful REST response does not prove ingestion health.
Show “Source reachable”, not “Analyzer healthy”, unless an existing verified health signal supports it.

Baseline freshness limits: node/observer snapshots five minutes, analytics fifteen minutes.
Older data may display as cached but cannot create a new change event. Old measurements returned
by a fresh request retain their measurement age. When ingestion health cannot be established,
describe changes as “reported by this source”; never claim a physical outage or recovery.
Partial histories may show values with a partial label, but are excluded from signal comparisons.
The audited CoreScope response does not prove retained-history coverage. Missing completeness
metadata is unknown, not complete; show returned samples with an explanation and suppress significant
signal events until sufficient coverage is established. See the milestone A findings.
Source errors and unknown metrics never contribute to recently heard/not-recently-heard totals.
Show how many rows were actually evaluated rather than “all healthy”.

## Visit baseline and persistence

Use a new versioned snapshot store, separate from favorites. Stable key: canonical analyzer
origin (including applicable port/base path), backend kind, local/account scope, policy version, and normalized full
public key or observer ID. Follow existing canonicalization; do not invent alternate host aliases.
Session UUID and request generation protect writes but are not persistent identity keys.

1. Opening My Nodes starts a visit and captures the last reviewed baseline. Explore previews do
   not consume changes. Returning from a nested detail stays in the same visit.
   Foregrounding after a backgrounded visit starts a new visit with the committed baseline.
2. Compare fresh eligible data against that fixed baseline. Refreshing does not clear changes.
3. Each metric retains its own observation/fetch time, method, range, completeness and provenance.
   Only an eligible successfully rendered metric becomes a candidate for the next baseline.
4. On leaving My Nodes or backgrounding, atomically promote rendered candidates for that visit.
   Persist candidates during the visit so termination can recover the last rendered values.
   Failed or never-displayed metrics do not advance; cached display alone cannot advance them.
5. Baselines older than seven days re-establish quietly with “Previous comparison expired”. New
   favorites establish their own first baseline; removed favorites drop associated snapshots/events.
6. Events represent net changes against the visit baseline, not a full incident log. Stable ID is
   source + entity + category + baseline revision. Refresh replaces/deduplicates an event; a state
   that returns to its baseline no longer appears as a net change. Keep at most one per category/entity.
7. Multiple windows share a serialized source store. Each keeps its captured baseline; generation
   checks prevent an older window overwriting newer candidates. Compare-and-merge only newer eligible
   metric observations, never blind replacement of the whole source snapshot.
8. Store at most the baseline and latest candidate per favorite/relevant observer, retaining at most
   200 covering nonfavorite observers per source (favorites remain represented). If coverage exceeds
   this, disclose the observer-comparison limit and omit excess observer events deterministically.
9. Cache clear deletes snapshots/baselines and cancels pending writes, preserving favorites/settings.
   Next visit is a first comparison. Corrupt/unknown schemas reset this feature only; no favorite migration.

Store local timestamps and compact metric summaries, not raw packet histories or location tracks.
Include snapshot storage in existing cache reporting. Analytics and visit baselines never sync;
only opted-in personal saved selections use account sync. No telemetry upload or background service.

## Loading, scheduling and request budgets

These are feature request ceilings to verify with fixtures and measurements in A2. Count actual
HTTP requests, including lookup, pages and retries; counting backend method calls is insufficient.

| Operation | Budget / policy |
|---|---|
| Explore summary | Zero additional requests; derive from existing loads and saved My Nodes summary |
| Dashboard base refresh | At most 20 actual requests, concurrency at most two; use shared catalogs/caches first |
| Automatic analytics enrichment | Separate maximum 20 requests per visit batch, concurrency at most two; begins after base rows render |
| Per-node enrichment | At most four requests within that batch; partial result if exhausted; do not loosen existing detail-screen budget |
| Load more analytics | Explicit next batch with the same 20-request ceiling; continue unattempted favorites first |
| Timer refresh | Foreground-visible base metadata only, no more often than every 60 seconds; no periodic analytics fan-out |
| Analytics reuse | Reuse eligible 24h data up to 15 minutes, retaining actual snapshot times; show older cached data as stale |
| Pull to refresh | Coalesce with in-flight work; base refresh plus at most one bounded enrichment batch, respect cooldown |
| Diagnostics | Navigate to existing diagnostics on demand; never run the full probe suite on dashboard entry |

Base and enrichment together therefore cap at 40 new requests per explicit dashboard refresh.
Enrichment prioritizes visible rows then saved favorite order; rows pending budget remain explicit.
No new collection-size limit on favorites. Test 0, 1, 20, 100 and 500 favorites. Display cached rows
immediately; avoid fetching every node individually on launch.

A2 chooses existing complete catalog reuse or bounded direct favorite lookups based on measured
costs. If a catalog cannot complete within the budget, retain cached rows and mark unverified rows
unknown; absence from a partial/capped catalog is never deletion. Verify CoreScope list limits too.
Observer loading shares the base budget and can be deferred with a clear unavailable comparison.

The present backend interface needs an internal budget-aware analytics loading path so Beacon
lookup/paging can honor a remaining request allowance. Preserve existing `nodeAnalytics` semantics
for detail screens; never globally reduce their history. Allow only the narrow adapter extension
needed for bounded dashboard reads, covered by tests on both platforms.

Source switch, cache clear, removed favorite, screen disappearance and backgrounding cancel
owned work and release only this consumer's live demand. Shared map/feed consumers remain active.
Reuse one socket. Positive identified live events may update last-heard display, but never infer
silence from missing live packets or recompute all analytics per packet. Throttle presentation
updates to at most once per second. Reconnection waits for authoritative recovery before comparisons.
All requests obey shared per-origin 429 cooldown and existing backoff; no timer resets that cooldown.

## Implementation boundaries

Proposed new units (names can follow repository conventions):

- `MyNodesSnapshot` / `MyNodesMetricState`: immutable data with per-metric evidence and timestamps.
- `MyNodesPolicy` / `MyNodesChangeCalculator`: pure, clock-injected rules with no network or UI.
- `MyNodesSnapshotStore`: serialized, versioned persistence with generation-safe clear and writes.
- `MyNodesRepository`: backend loading, actual-request budgets, cache coalescing and source guards.
- `MyNodesViewModel`: screen state, captured visit, rendered candidates, filters and selection.
- `MyNodesScreen` and small Explore summary / status / node row components.
- `CoreScopeAccountClient` / account session store: capability detection, login, secure credentials
  and account lifecycle, separate from public browsing.
- `AccountSelectionSync` / account selection store: verified remote mappings, revisions/generations,
  pending edits and explicit local import; separate from disposable caches and guest favorites.
- Equivalent Kotlin models, repository, reducer/calculator and Compose screen; shared JSON fixtures.

Keep analytics computation and persistence off the UI thread; publish narrow observable state on
iOS and lifecycle-aware state on Android. Follow Swift async/await and existing Android coroutine
ownership. Do not create a generic dashboard framework or rewrite Explore's unrelated features.
Consult relevant SwiftUI/accessibility skills and Apple documentation when implementing UI APIs.

## Exports

CSV and JSON export the currently displayed filtered snapshot without fetching more data. Include
schema/policy version, source/backend, generated time, baseline time, selected range, entity ID/name,
activity measurement time/state, every metric's value/unit/status, fetched time, covered range,
provenance, and change old/new values. JSON uses null plus status; CSV uses empty value plus status.
Show partial/comparison counts and cache state. JSON includes node and observer changes; CSV uses
record-type columns for node summaries and change records so nothing is silently omitted.
Use existing export/share utilities, ISO-8601 UTC dates and locale-independent numbers. Exclude
channel keys, tokens and unrelated cached data. Tests cover quoting, Unicode and unknown values.

## Milestones and executable task breakdown

Task status is tracked in [implementation progress](implementation.md). Within each milestone:
iOS implementation, Android port, parity validation, then proceed. Implementation was authorized
on 2026-10-16; the initial contract work is underway.

| ID | Work | Completion evidence / dependencies |
|---|---|---|
| A1 | Contract and semantics audit | Fixtures map actual activity, availability, SNR, coverage, status and telemetry fields for both backends; missing versus zero and timestamp units documented |
| A2 | Loading/budget experiment | Record cold/warm request counts, bytes and timing for representative favorites; select catalog/lookup strategy and prove actual-request budget cancellation |
| A3 | Finalize policy and screen states | Review proposed placement, 15-minute activity rule, 5 dB shift, seven-day baseline and wireframes; record any revisions here |
| A4 | Account contract and membership audit | Pin supported release/fixtures; verify capability, cookie/CSRF lifecycle, own-node versus favorites mapping, settings merge/delete/reset semantics and optional token support |
| B1 | Neutral snapshots and pure rules | Shared fixtures verify activity, comparable SNR, observer status, unknown/partial data and deterministic events |
| B2 | Baseline store | Atomic persistence, expiration, crash recovery, concurrent windows, corruption and cache-clear tests pass; favorites unchanged |
| B3 | Android foundation parity | Same fixtures produce identical semantic results; timestamp serialization agrees |
| B4 | Account/local selection isolation | Separate local/account identities, provenance, baseline namespaces and pending edits; upgrade preserves existing guest favorites |
| C1 | Bounded repository / adapters | Base and enrichment budgets, source guards, coalescing, paging and 429 tests pass; detail analytics behavior preserved |
| C2 | Live/data status integration | Shared demand lifecycle, connection versus freshness, recovery and foreground cancellation verified |
| C3 | Android data parity | Request budgets and lifecycle behavior match iOS on both backends |
| C4 | Account authentication on iOS, then Android | Native optional sign-in, website create/reset links, secure session restoration, expiry, cancellation, destination isolation and logout tests pass |
| C5 | Selection sync on iOS, then Android | Explicit import, preserved remote categories/unknown keys, offline edits, conflict/deletion/reset handling, throttling and account switching agree on both platforms |
| D1 | Explore entry and My Nodes screen | All list/loading/empty/error states, favorite management, stable filtering/sorting and metric explanations implemented |
| D2 | Changes and drill-downs | Visit behavior, event replacement, source-correct analytics/observer/map/packet/route navigation demonstrated |
| D3 | Android experience parity | Equivalent information and interactions on phone, tablet and foldable; no hidden feature gaps |
| D4 | Optional account experience | Guest path has no login gate; account settings, sync state, membership controls and sign-out copy choice verified on both platforms |
| E1 | CSV/JSON and storage controls | Shared expected export fixtures; snapshot cache counted/cleared; user favorites survive |
| E2 | Accessibility and adaptive layout | Large text, screen readers, non-color status, focus, reduced motion, light/dark and resize checks on both platforms |
| F1 | Regression and live acceptance | Both backend suites pass; measured budgets and all release scenarios below recorded |
| F2 | Release preparation | Version/build, store copy, screenshots, release notes, privacy review and both beta builds ready |
| F3 | Release closeout | User smoke test complete on both platforms; store submission status recorded separately from implementation status |

Dependencies: A -> B -> C -> D -> E -> F. Record results and exceptions in a new
`docs/my-nodes/implementation.md` as work begins, and update the Android parity matrix per milestone.
Split reviews/commits along these milestones; never check a task complete solely because it builds.

## Validation and release acceptance

Use Swift Testing for unit tests, XCUIAutomation for critical iOS interactions, and the existing
Android unit/UI tooling. Build iOS with Xcode BuildProject. Run applicable Android build, unit,
lint and UI-test compilation checks using the repository's current commands. No build is needed
for this planning-only change.

| Scenario group | Required assertions |
|---|---|
| First and repeated visits | First baseline, refresh without clearing, nested navigation, background, force quit, expired baseline and no-change copy |
| Activity edges | Exactly 15 minutes, missing/future timestamps, clock rollback, quiet node, fresh old timestamp, stale cache, 404 and capped catalog |
| Signal | 4 versus 5 samples, exactly 5 dB, negative SNR, mixed observers, observer replacement, nonfinite data, overlapping windows, partial history and Beacon aggregate-only behavior |
| Observer status | Explicit versus inferred status never conflated; missing status, lost coverage, identity reset, duplicate names and unavailable catalog |
| Persistence | Source A/B, same host/backend change, removed/re-added favorite, reorder, two windows, corruption, migration, clear during fetch/write and late responses |
| Account lifecycle | Accounts disabled/HTML fallback, guest/Beacon operation, wrong password, website create/reset, expired/revoked sessions, 429/5xx/offline, HTTPS and redirect isolation, sign-out without connectivity |
| Account sync | Same/different accounts per source, no automatic local upload, own-node/favorite distinction, category removal, duplicate identities, remote unknown keys, concurrent edits/deletions, generation reset, offline pending edits, cache clear, limits and late responses after logout |
| Loading | Zero/warm cache, 500 favorites, repeated cursor, exhausted budget, cancellation, shared loads, 429/Retry-After, 5xx and offline recovery |
| Navigation | All supported drill-downs, no fabricated routes, source change while detail open, existing deep links and return position |
| Export | Complete/partial/cached snapshots, filtered subset, null/zero, units/timestamps, escaping and equivalent Swift/Kotlin fixtures |
| Devices | iPhone/iPad portrait and landscape, narrow/wide iPad windows; Android phone/tablet/foldable and resize with selection |
| Accessibility | Maximum supported text sizes, VoiceOver/TalkBack reading and actions, contrast, non-color states, focus and reduced motion |
| Regression | CoreScope/Beacon analytics, Explore favorites and reordering, channels, map/live feed, diagnostics, source switching and storage clear |

Performance targets to measure in release configuration: cached content visible within 500 ms on
the selected representative test devices; base rows publish without waiting for analytics; a slow
request shows usable partial content and retry rather than an indefinitely blocking screen.
Record device, dataset, network and timing for every measurement. No new feature requests run while
backgrounded. No request budget or shared cooldown violation is acceptable.

Live acceptance uses at least one supported CoreScope source and the three established Beacon
sources (MeshTexas, Colorado, dev.meshcore.ca), respecting shared server limits. These checks are
future release work; this plan does not claim fresh live verification or completed tests.

## Risks and agreed fallbacks

| Risk | Planned resolution |
|---|---|
| No cheap bulk analytics | Bounded progressive enrichment with visible evaluated counts; explicit next batch |
| Beacon lacks receiver identity | Aggregate trend with caveat, no comparable-signal event; no invented coverage/availability |
| No node telemetry contract | Explain unavailable node telemetry; keep observer telemetry labeled and accessible |
| Analyzer ingestion is stalled | Separate fetch/measurement times and source-reported wording; never claim physical outages |
| Existing 15-minute rule is too short for some nodes | Describe recency honestly; defer personalized silence thresholds; revisit only with product evidence |
| Backend list is capped or incomplete | Never infer missing/offline from omission; bounded direct lookup or unknown state |
| Budget limits hide changes among many favorites | Explicit compared/total counts and manual continuation; no “all healthy” claim |
| Baseline drift or backend changes | Versioned policy, stable source namespace, per-metric timestamps and baseline reset |
| Account feature unavailable or server down | Full guest dashboard remains usable; sync failure is separate from network-data freshness |
| Remote settings schema or conflict semantics differ | Verify A4 contract before writes; preserve unknown data and pause unsupported sync with explanation |
| Account changes expose another user's selections | Separate namespaces, explicit import/copy choices, canceled pending work and account-specific baselines |

Planning is complete when this scope and its proposed defaults are reviewed. Implementation is
complete only when milestones A–F have recorded evidence on both platforms; store approval is
tracked independently.
