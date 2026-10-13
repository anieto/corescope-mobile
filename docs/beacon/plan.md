# Beacon support plan

Status: phase 0 done (see [phase0-findings.md](phase0-findings.md), which wins where the two differ).
Phase 1 backend extraction implemented on iOS and Android; automated checks pass and the user reports
manual testing works. See [phase1-implementation.md](phase1-implementation.md). Phase 2 step 1 groundwork
is implemented on both platforms; manual regression of these changes is pending. Phase 2 step 2 has
started with the iOS transport, packet, node, observer and region readers. Last updated 2026-10-12.

WebSocket protocol, socket lifecycle and filtered REST recovery now connect through a Beacon
adapter to the shared live-feed service. The packet sidebar now consumes browse snapshots;
factory/source activation and the remaining screen integration are pending.

## Phase 2 execution checkpoints

Source-list preparation checkpoint (2026-10-12): iOS now parses typed v2 entries, skips unknown
or missing v2 types, rejects unsupported schema versions/empty supported lists, and validates cached
and downloaded documents through the same decoder. Typed caches use a separate defaults key;
legacy v1 entries still resolve as CoreScope. Startup no longer trusts a Beacon registry entry as
CoreScope. Parser tests cover legacy compatibility, type filtering, cache round trips and invalid
documents. The live endpoint and bundled file remain v1; v2 bundle registration, endpoint activation,
explicit Beacon-only selection enforcement, Android integration and release publication remain pending.

Listed-type enforcement checkpoint (2026-10-12): startup now requires Beacon for a listing marked
Beacon, including when a legacy/default-host hint disagrees. A missing info endpoint cannot trigger
CoreScope fallback for that listing. Regression coverage checks this against an otherwise valid
CoreScope fixture. A minimal `CoreScopeViewer/Resources/sources-v2.json` fallback is prepared,
matching the existing bundled MeshTexas entry with an explicit CoreScope type. It still needs Xcode
resource membership before the loader/endpoint switch. Colorado remains release-gated; neither
the public legacy list nor the remote endpoint has changed.

iOS v2 loader checkpoint (2026-10-12): after user registration of the bundled resource in Xcode,
the app now requests `CommunitySources/sources-v2.json` and starts from its separately persisted v2
cache or bundled v2 fallback. V1 cache/remote documents are rejected on this path; failed refreshes
retain the last good list. Tests verify the actual bundled resource, successful refresh/persistence,
unknown-type filtering and preservation after 404/429/503, malformed JSON and legacy responses.
All 199 tests pass on rerun; an existing node-recovery test timed out on the first run. No live
requests or public registry publication occurred. Until publication, fresh installs fall back to
the bundled MeshTexas listing (custom hosts remain available). Android v2 integration, Colorado's
release listing/icon and cross-platform release validation remain pending.

Use this order, iOS first and then Android:

1. **Backend groundwork:** cursor-based pages, neutral models that preserve unknown values and
   uncertain route hops, source-session isolation and server-type cache namespaces, capability gates
   including old favorites/deep links, and shared ownership of live-feed subscriptions/configuration.
2. **Beacon backend:** detection, nodes, observers, regions, packet browsing/replay, WebSocket recovery,
   diagnostics and shared rate-limit handling. Follow phase 0 findings over earlier assumptions below.
3. **Source list:** cached/bundled sources-v2 fallback and unknown-type filtering. Publish Colorado's
   Beacon entry only at release; leave the legacy CoreScope-only source list unchanged.
4. **Android parity and 2R:** both-platform regression/fixture/live checks, beta distribution, store
   notes and rollback readiness before 0.8.0 release.

### Step 1 progress

Both platforms now have opaque packet cursors and pages with optional totals. Packet consumers
collect pages in server order, deduplicate by hash and enforce a request budget rather than silently
stopping after one server-capped response. CoreScope retains its existing initial request sizes and
uses offset paging inside its adapter; Beacon's future adapter must return its server-provided cursor.
Cursor validation is scoped to source session and filters.

The remaining groundwork is implemented:

- **Neutral models:** observer packet totals remain unknown when omitted, including aggregate display
  and iOS CSV export. Numeric CoreScope packet IDs are optional; iOS packet/message identity uses the
  packet hash. Android packet records/live packets can also represent an absent numeric ID.
- **Route uncertainty:** replay excludes any path containing unknown or blank hops. Such a path is
  not compacted into a fictitious connection or attributed to a confirmed route. This deliberately
  conservative rule applies to CoreScope as well as future Beacon mappings.
- **Source isolation:** iOS source invalidation cancels registered requests; view-model/cache guards
  reject late results, shared refreshes are session-scoped, and clearing a shared cache cannot resurrect
  its canceled refresh. Android retains selection-scoped coroutine cancellation and checks cancellation
  again before a keyed loader publishes restored, fresh or failed results. Cache keys include backend
  type on both platforms.
- **Capabilities:** iOS checks node sections, node/observer analytics, map defaults and channel features
  before requests/cached display. Android wraps browse access with capability checks, including saved
  responses. Direct navigation to an unsupported feature reports unavailability rather than fetching it.
- **Live-feed ownership:** both platforms expose full subscription/configuration controls through one
  shared owner. Visible foreground maps register independent demands and release them when hidden;
  one consumer cannot disable another's requested flags. CoreScope sends no new wire frames. The
  existing general packet subscription remains active; Beacon-specific filtering is adapter work.

**Manual checkpoint:** on both apps, browse CoreScope, open packet/channel/observer details, switch
sources while loads are pending, switch away from/back to the map, and background/foreground the app.
On iOS also check two windows with one map hidden. Unknown totals should read "Unknown"; unresolved
paths should not be offered as confirmed replay routes.

Validation: iOS simulator build and all 82 tests pass; Android debug build and all 171 unit tests pass.
The iOS run destination was restored to Boss iPhone 17 Pro. No new live-server checks were run.

**Step 2 integration requirements:** Beacon DTOs must populate these neutral models without fabricated
numeric IDs/counts, map uncertain hops to unresolved values, and return server-provided cursors. Its
live adapter must apply each complete merged control state on connect/reconnect, not send per-screen
partial settings. Detection must renew the iOS source session when a host's backend kind changes
without its hostname changing, and select the matching Android cache namespace. No Beacon adapter,
source-list publication, or live Beacon validation is included in this groundwork.

NodeScope talks only to CoreScope today. Some communities are moving to
[Beacon](https://github.com/MeshCore-Beacon/beacon-docs) (Colorado Mesh already has, and dropped out of
our source list because of it). This plan adds Beacon as a second server type on iOS and Android
without changing anything for CoreScope users.

Beacon API reference: `docs/api-contract.md` in beacon-docs, plus Swagger at `/swagger/index.html` on
any server. Test servers: `beacon.meshtexas.org` (ours, MeshTexas data), `map.meshcore.coloradomesh.org`
(Colorado), `dev.meshcore.ca` (Beacon's own, Canadian data).

---

## 1. What we verified

### Step 2 implementation checkpoint (2026-10-11)

The first iOS increment is intentionally **not connected to the backend factory or source picker**:

- `BeaconAPIClient` handles HTTPS `/api/v1/` requests, epoch-ms decoding, structured errors and
  source-session cancellation. A shared per-origin cooldown honors `Retry-After` on 429; requests
  during the cooldown fail with a retry time rather than hammering the server or retrying forever.
- `BeaconServerInfo` checks the supported server-version floor without decoding `minAppVersion`.
  `BeaconServerCatalog` persists positive identification only. Failed probes leave known information
  unchanged; CoreScope fallback and custom-host UI integration are still pending.
- `BeaconPacketReader` maps packet summaries/detail into neutral app models without numeric packet
  IDs or fabricated raw packet bytes. Pages cap at 200, preserve server order and carry the actual
  continuation cursor inside a source/session/filter-scoped token. Missing continuation metadata
  fails instead of silently truncating the list.
- Route mapping keeps only a single high-confidence candidate with a full 64-hex public key.
  Ambiguous, missing, prefix-only or malformed candidates remain unresolved.
- `BeaconNodeReader` reads cursor-based node pages and walks the complete list within a hard
  20-request budget, preserving server order and deduplicating case-insensitive keys. Exhausting
  the budget or repeating a cursor fails rather than returning an apparently complete map.
  Detail uses exact public-key lookup followed by the returned server UUID (at most two requests,
  no list scan); both responses are identity-checked. Missing lookup results remain unavailable.
- Node records retain optional first/last-seen dates and multibyte capabilities; last-heard is the
  newest regional timestamp, not an invented first-seen date. Unknown node types stay unknown.
  Mapping these records into the shared node UI remains part of backend activation.
- Packet and node continuation tokens are endpoint-, source-, session- and filter-scoped.
- `BeaconObserverReader` adds bounded, ordered observer paging with the same cursor isolation
  and server UUID identity. Summary dates, public keys and hardware metadata remain optional.
  Beacon's legacy observation counter is deliberately not mapped to packet count.
- Observer detail learns UUID/key associations in memory within that reader's source session.
  Unknown key links return unavailable without issuing any request; known keys fetch only their
  learned UUID and verify the returned key. A 404 evicts the association, whereas temporary server
  errors preserve it. UUID/key changes replace both sides of the association.
  A session-checked learning entry point is ready for live events, but live-feed wiring, shared
  reader ownership and observer UI integration remain pending. Mappings are not persisted.
- `BeaconRegionReader` loads IATAs, region summaries and each group's membership in server order.
  Successful complete catalogs are cached for five minutes within the reader/source session;
  concurrent loads share one task and explicit refresh bypasses the cache. Loading is capped at
  20 requests (two lists plus at most 18 details); failures never publish a partial catalog.
  Duplicate identities and mismatched detail IDs/slugs are rejected.
- Region catalogs expose existing app name/coordinate shapes without inventing coverage radii.
  Pure map-framing data encloses valid selected coordinates with padding, including groups crossing
  the dateline. Missing/invalid coordinates remain unavailable; a group's valid center hint is used
  only when no member coordinates are available. The one-degree minimum span is an app framing
  policy, not a Beacon map-default setting. Group zoom hints are retained for later UI integration.
- Region tests use recorded IATA/summary fixtures and explicitly synthetic member-detail examples
  matching the [Beacon 2.0.3 region schema](https://github.com/MeshCore-Beacon/beacon-server/blob/v2.0.3/internal/api/regions.go).
  Phase 0 did not retain region-detail fixtures. Map UI wiring and live verification remain pending.
- `BeaconLiveProtocol` encodes subscribe/unsubscribe, full three-flag configure and JSON ping
  commands. Its subscription state tracks pending acknowledgements, removes subscriptions whose
  consumers disappeared before acknowledgement, avoids duplicate requests, and restores desired
  subscriptions/configuration after a connection reset.
- Live protocol decoding covers packet observations and observer-status snapshots. Every packet
  observation is retained regardless of `isFirstObservation`; optional key/route data may come and
  go, and route confirmation uses the same strict rules as REST. Observation identity is
  `(packetHash, observerId)`. Unknown events are ignored, unsupported protocol versions fail, and
  `lagged` produces an explicit recovery signal.
- `BeaconLiveTransport` now provides the WSS `/ws` connection loop, serialized command sends,
  30-second JSON pings, a hello watchdog, acknowledgement deadlines and capped reconnect backoff.
  It restores subscriptions/configuration on hello, rejects obsolete connection callbacks, and
  closes on source invalidation. HTTP 429 and close-code 1013 share the REST cooldown gate;
  buffered-event overflow, hello and server lagged notices emit recovery signals.
- `BeaconLiveRecovery` fetches the first packet page with its fixed region/payload filters,
  merges by hash in server order, keeps larger counts/later hearing times, and preserves newer
  live-only rows arriving during REST. Live-only first-seen dates remain unknown. Rows are bounded
  to 200; refresh requests during recovery coalesce into a follow-up fetch. Failed refreshes keep
  prior rows and propagate errors; invalidated-source results are rejected. The possibly-incomplete
  flag remains conservative because one page cannot prove recovery of every missed observation.
- `BeaconLiveFeed` now implements `AnalyzerLiveFeed`, owns one transport and routes each incoming
  observation/recovery signal to matching region/payload projections. Multiple payload types expand
  into individually filtered REST requests; at most 20 projections are accepted. Removing a demand
  cancels its recovery work and removes its snapshot. Late responses cannot restore removed projections.
- The shared service applies merged demands before connecting and stores Beacon browse snapshots
  separately from legacy CoreScope envelopes, without fabricated numeric packet IDs or timestamps.
  Recovery errors remain visible independently of socket connectivity and clear after successful
  recovery/removal. Source-session changes clear old data even when the hostname is unchanged.
- Live packet keys now populate the adapter's session-bound observer reader. Backend activation
  must share that same reader for observer links/details. Node-update/observer-status UI handling,
  route animation integration and factory selection remain pending.
- Live metadata checkpoint (2026-10-12): subscriptions now include `nodeUpdate`; the adapter forwards
  node updates and observer status to the shared service. Node positions distinguish omitted
  coordinates (unchanged) from null/invalid/zero-pair coordinates (clear). Node UUIDs and full public
  keys are validated. Incremental IATA memberships merge without removing prior regions or rolling
  back known hearing times. Observer status replaces the entire snapshot, clearing absent fields.
  Metadata is session-local, bounded to 1,000 recently updated nodes and observers each, and cleared
  on service disconnect; stale feed generations cannot repopulate it. These are partial live overlays,
  not complete node/observer catalogs. Node and observer catalog recovery are implemented below.
  Packet recovery does not recover metadata.
- Shared node-model checkpoint (2026-10-12): `MeshNode` now preserves absent first/last-seen dates,
  including through cache serialization. Beacon REST nodes map to the shared model without inventing
  advert counts, path widths or dates; invalid/missing/zero-pair coordinates do not become map pins.
  Activity filtering uses the latest supplied seen/heard date and excludes unknown activity when an
  activity filter is enabled. Node date metrics and map search rows display unknown dates safely.
  Beacon region hearing times remain separate from first/last-seen dates. This supplies the shared
  model prerequisite for live map overlays.
- Live node map integration checkpoint (2026-10-12): the map applies session-checked live overlays
  after node loads and on live node revisions. Existing server ordering is preserved; new nodes
  append deterministically and enter a filtered map only with matching known IATA membership.
  Incremental memberships do not remove existing nodes. Coordinate clears remove node positions;
  omitted coordinates preserve them. Per-field hearing times prevent an older cached position/name
  from overwriting a newer REST record, including when a later event omits that field. Undated fields
  conservatively leave dated records unchanged. First/last-seen dates and REST-only counters remain
  untouched. Overlays do not enter the persistent REST cache. Node loads now reject superseded
  region requests, and overlays wait for a matching loaded region. Factory activation, live device
  verification and automatic backend selection remain pending.
- Node recovery checkpoint (2026-10-12): hello/reconnect and dropped-event notices invalidate live
  overlays and observer status. While a map requests route resolution, one shared recovery task
  reloads the complete paged node catalog through the existing 20-page budget and rate limiter.
  Multiple windows share the refresh; repeated gaps during a request discard that result and
  coalesce into a follow-up. Hidden maps do not start recovery requests (an in-flight request may
  finish). Live node changes received during the successful refresh remain available as overlays.
  The recovered catalog replaces the map baseline in server order with local IATA filtering;
  source/session checks reject obsolete results. Each catalog is applied once per map region so
  later REST loads are not repeatedly overwritten by an older recovery snapshot. Failed recovery
  keeps existing map data, shows an outdated-data warning and offers retry. Disconnect cancels
  recovery; shared 429 cooldown remains authoritative. Observer status is cleared, not fabricated
  from node recovery; observer recovery runs independently as described below.
- Observer presentation checkpoint (2026-10-12): shared observer first/last-seen dates are optional,
  round-trip through caches, sort unknown dates after known dates and never imply recent activity.
  Beacon observer DTOs map to the shared model with server UUID identity, unknown packet counts and
  no fabricated coordinates or battery millivolts from percentage values. Live status is projected
  onto matching list/detail observers through a session-checked accessor; online/offline and status
  time remain separate from seen dates. Each full status replaces its optional name, IATA, radio,
  battery and uptime fields instead of retaining stale values. List filters refresh with status
  revisions, and source-session changes clear observer selection. Live overlays are not persisted
  into the REST baseline, so gap/disconnect invalidation removes them. Date labels and CSV exports
  handle unknown dates. Automatic backend selection remains pending; this does not activate a Beacon source.
- Observer recovery checkpoint (2026-10-12): visible foreground observer screens register a merged,
  local-only recovery demand. One shared task walks the paged observer list (20-page budget, shared
  rate limiter), without detail fanout, after hello/reconnect or dropped events. Gaps or refreshes
  during a request discard that result and coalesce a follow-up; hidden screens do not start new
  recovery requests. The screen consumes the session-scoped catalog in server order before its local
  filtering/sorting. Known REST online/offline values restore basic status; all omitted dates, signal,
  battery and packet-count fields remain unknown until actually supplied. Live status arriving during
  refresh is preserved as an overlay. Invalidation removes stale live snapshots and marks retained
  catalog online/offline values unknown until recovery completes. Failures keep the catalog, display
  a stale-status warning and allow retry; pull-to-refresh explicitly requests a new catalog. Source
  changes/disconnects reject late results. Removed observers cannot remain selected through a stale
  detail snapshot. CoreScope loading is unchanged, and no new recovery flag is sent on the wire.
- Observer detail checkpoint (2026-10-12): opening an observer requests its detail by server UUID
  through the active Beacon feed and the same session-bound observer reader used for live key
  learning. No list/detail fanout or CoreScope fallback is used. The detail screen retains its summary
  while loading, shows retryable errors, displays supplied hardware/firmware and seen dates, and
  projects current live status over the detail baseline. Exports use the displayed detail. Unsupported
  analytics are explained without requesting them. Source changes clear loaded details and disable
  old-source actions; feed-generation checks reject results arriving after disconnect. CoreScope's
  analytics path remains unchanged. This is still behind the inactive Beacon feed/factory boundary.
- Backend adapter checkpoint (2026-10-12): `BeaconBackend` now implements the shared backend interface
  using the node, observer, packet and region readers. Complete node walks produce known totals and
  role counts; basic node detail preserves unavailable recent-advert history as nil. Observer server
  time remains nil when not supplied, rather than using the device clock. Packet pagination/detail
  and region names/coordinates delegate to the existing session-checked readers. Unsupported
  analytics, node diagnostics, channels and map-defaults calls fail explicitly without HTTP requests;
  no unsupported capability is advertised. An explicit `makeBeacon` factory creates backend/feed
  together with the same observer reader, preserving UUID/key learning across REST and live traffic.
  Reader injection rejects a different host/session. The default settings factories remain CoreScope;
  positive detection, session renewal on type change, shared instance ownership and UI activation are
  still pending. No source list is published or switched by this checkpoint.
- Detection preparation checkpoint (2026-10-12): the positive server catalog now resolves supported
  Beacon information from its remembered origin or `/api/v1/info`, coalescing overlapping probes
  within an origin/source session. Explicit rechecks propagate errors without erasing the remembered
  type. Recognized unsupported versions remain identified as Beacon and fail the minimum-version
  gate rather than falling back to CoreScope. Cached results also require a current source session.
  `prepareBeacon` binds detection and backend/feed construction to the settings session captured
  before the request and rejects a source change before returning the prepared pair. Preparation
  neither connects the socket nor changes active settings/default factories. Committing a backend
  selection, renewing the session on type change and refreshing host-keyed UI tasks are still pending;
  no automatic activation or source-list publication occurs yet.
- Session-safe selection checkpoint (2026-10-12): `detectAndSelectBeacon` now commits a positively
  resolved version to settings only if the captured source session is still current. Backend
  construction succeeds before the previous session is invalidated. A type/version change creates
  a new session and shared backend; selecting the same supported version is idempotent. Both default
  factories honor the selection. Every live reconnect creates a fresh feed using the selected
  backend's observer reader, rather than reusing an ended stream. Same-source session renewal rebuilds
  the Beacon readers without falling back to CoreScope; unexpected reconstruction failures fail
  closed. Failed probes and unsupported versions leave the active selection/session unchanged.
  App live/region loading, map scope, tab resets, Explore, channels and diagnostics now key refreshes
  on session identity, including same-host type changes. Region/observer lookups clear old state when
  reconfigured for a new session. Changing hosts clears the previous host's in-memory selection.
  This commit path is available and fixture-tested but is not yet called by settings save/startup:
  that UI integration, persisted-detection restoration, diagnostics and live validation remain next.
  No sources-v2 publication or automatic rollout is included.
- Diagnostics checkpoint (2026-10-12): selected Beacon sources now use a read-only `/api/v1/info`
  version check through their existing client/session/rate limiter, rather than sending CoreScope
  capability probes. Diagnostics display backend type and last-reported server version; unsupported
  versions fail compatibility while a failed recheck remains a warning with the known type/version
  retained. Browse capabilities are explicitly labeled adapter-supported but not probed; channels
  and map defaults are labeled unavailable for this release. Diagnostics never activate a source,
  update its selected version or read Beacon mobile minAppVersion. Report publication checks source
  session and run identity so old-source results cannot overwrite a newer run. Settings-save and
  startup activation/restoration still remain pending; this checkpoint removes a false-diagnostics
  blocker before those paths are enabled.
- Startup/settings checkpoint (2026-10-12): browsing and live connections now wait for source
  resolution. Custom hosts probe Beacon info; remembered positive Beacon detections restore before
  screens open, even when the host is in the legacy registry. Known legacy sources need no probe.
  Only a missing Beacon endpoint (404), followed by a valid CoreScope map-defaults response, allows
  an unknown custom host to use CoreScope. Timeouts, malformed responses, unsupported versions and
  other HTTP failures stay gated with retry/source-selection actions rather than guessing a type.
  Settings changes use the same gate and session-bound resolution discards obsolete results.
  This enables custom Beacon browsing without publishing sources-v2 or changing the legacy list.
- The packet sidebar now switches explicitly between CoreScope's existing observation groups and
  Beacon browse snapshots, including before the first snapshot arrives. Beacon rows use packet hashes
  as identity, show hearing times/counts and expose a local summary with unknown first-seen dates
  preserved. Region/payload filters register scoped demands; disappearing views remove their demand.
  Recovery errors and possibly-incomplete history are visible rather than appearing as empty success.
  Opening a row now fetches packet detail through the active Beacon feed, never the CoreScope
  factory. The screen shows recorded observations, hearing dates, signal readings and retryable
  errors while retaining the summary. First-seen dates stay unknown until supplied by REST.
  Fully resolved routes can be selected and sent to the shared map replay store; uncertain hops
  are excluded. Replay returns to the map workspace using the existing presentation callbacks.
  Detail requests are checked against both the source session and feed generation before publishing;
  source changes clear detail/routes, and old-source replay is disabled.
  CoreScope's existing sidebar behavior is preserved. No Beacon source is selectable yet.
- Tests use sanitized Phase 0 packet/node fixtures and intercepted HTTP responses. The node capture
  contains six samples plus nine page descriptors, not all 878 nodes; pagination tests replay those
  descriptors with overlapping samples to verify traversal and deduplication.
  Observer tests also cover unknown links without fanout, detail/live learning, source/session
  isolation, stale identities, UUID changes, 404 eviction and temporary failures.
  Live tests replay the recorded Phase 0 WebSocket session and cover full configure payloads,
  subscription changes before/after acknowledgement, reconnect restoration and lagged notices.
  Fake-socket tests cover command delivery, hello timeout/reconnect, pings, source cancellation and
  shared 429 cooldown. Recovery tests cover fixture merging, overlapping REST/live data, errors
  and late source responses.
  Adapter/service tests cover shared demands on one socket, filtered recovery, duplicate observations,
  live observer-key learning, demand removal, visible recovery failures and stale-session rejection.
  Presentation-mode regression coverage verifies Beacon is selected before snapshots arrive and
  resets when switching to a CoreScope feed. Detail tests cover on-demand Beacon routing, uncertain
  paths, missing packets, source changes and results arriving after disconnect.
  Metadata tests cover coordinate clearing, incremental membership merging, subscription events,
  socket-to-service delivery, full status replacement and source-session isolation.
  Node-model tests cover unknown-date cache round trips, hearing-based activity, fixture mapping,
  invalid coordinates and preservation of supplied detail dates.
  Map overlay tests cover region admission, coordinate clears, unknown dates, idempotent merging,
  out-of-order updates and newer REST records.
  Recovery tests cover demand gating, repeated gaps, concurrent live updates, errors/retry,
  disconnected results, recovered catalog filtering and one-time catalog application.
  Observer presentation tests cover optional-date round trips and ordering, full status replacement,
  identity matching, separate online/activity semantics and unknown-count/battery mapping.
  Observer recovery tests cover foreground-demand aggregation, gap coalescing, concurrent status,
  retained stale catalogs, retry, disconnect/source isolation and list-only fixture requests.
  Observer detail tests cover UUID-only fixture requests, detail-driven key learning, missing records,
  retry, source changes and late results after disconnect.
  Backend tests cover shared observer identity ownership, host/session injection guards, supported
  versions, fixture-backed node/list/detail/region projection and unsupported-call rejection.
  Detection-preparation tests cover remembered offline resolution, failed explicit rechecks, host
  isolation, obsolete sessions, unsupported positive versions and unchanged active source selection.
  Selection tests cover atomic factory switching, fresh reconnect feeds with shared identity state,
  idempotent selection, failed/unsupported rechecks, renewal and stale-session rejection.
  Diagnostic tests cover Beacon-only info requests, reported version/type, unsupported versions,
  failed rechecks and unchanged selected source/session.
  Startup tests cover remembered restoration, legacy offline selection, retry after detection failure,
  unsupported remembered versions and positive CoreScope validation after a missing Beacon endpoint.
  The iOS build and all **191 tests pass**. Manual UI and end-to-end map replay verification are pending.
  No live Beacon requests were made and Android was not changed in this
  increment.

Next: remaining shared model/screens and map live-event handling,
then diagnostics and safe source activation. Continue shared model/UI
integration for the readers above. Port the completed adapter to Android
before the release gate. This checkpoint is not completion of step 2 and does not publish sources-v2.

- **Packet hashes are identical** across both servers. Five recent Beacon packet hashes all resolved on
  our CoreScope (`/api/packets/{hash}` → 200). Identifiers carry over; availability still depends on
  the selected server and its retention window.
- **Nodes and observers both expose `publicKey`** on Beacon (observer key only on the detail endpoint),
  and Beacon can look a node up by key (`/nodes?pubkey=`). So the public key stays the app-wide
  node identity. Observer identity remains a phase 0 validation gate: a cached UUID mapping alone
  does not solve public-key lookup on a fresh install.
- **Observer lookup by public key: confirmed missing** (beacon.meshtexas.org, server 2.0.3,
  2026-10-10). `/observers/{publicKey}` returns 400 (UUIDs only); `?publicKey=` and `?pubkey=` on
  `/observers` are ignored, not rejected; `/observers` and `/observers/directory` summaries omit
  `publicKey`; observers that never advert (e.g. MeshHub-bot) aren't in `/nodes?pubkey=` either. Our
  server has 71 observers, so a key lookup would mean up to 71 detail requests. Phase 0 must pick a
  design: source-scoped UUID references for Beacon observers, plus asking upstream to add `publicKey` to
  observer summaries or a key filter (both additive under `/api/v1/`).
- **Deep links** (`nodescope://node/<pubkey>`, `observer/<id>`, `packet/<hash>`) carry no host, so they
  preserve their syntax, but cannot guarantee that the selected server has the referenced item.
  New links should support optional source information; retain old links and ask before switching
  to an unfamiliar host. Channel links need a collision-safe mapping (section 5).
- **Favorites, recents and search history already store a `source`** (the host) per item, so they
  separate cleanly by server.
- **`/api/v1/info`** answers `{"minAppVersion": null, "serverVersion": "2.0.3"}` on ours; that's how we
  detect a Beacon server.
- **Raw packets are kept for 7 days** by default (`packets.retention: 168h`, ours included). Stats rollups
  go back 90 days.

## 2. Where each app stands

| | iOS | Android |
|---|---|---|
| Size | ~18.2k lines Swift | ~10.6k lines Kotlin |
| Server access | `APIClient.get(path)` called directly from 12 files, 17 CoreScope endpoints | Two interfaces, `AnalyzerClient` and `BrowseRepository`, with CoreScope implementations behind them |
| Models | Decoded straight from CoreScope JSON | Same, in `core/model` |
| Live feed | `LiveFeedService` (CoreScope WebSocket) | `LiveFeed.kt` |
| Tests | 7 test files | ~30 test files, including protocol and JSON tests |

**Android is already structured for a second backend; iOS isn't.** On iOS, the main cost is moving those
17 endpoint calls behind an interface first.

## 3. Architecture

One idea on both platforms: **screens talk to an `AnalyzerBackend`, not to URLs.**

- `AnalyzerBackend` (iOS protocol, Android interface) exposes what screens need, e.g. `nodes(region:)`,
  `nodeDetail(publicKey:)`, `packets(...)`, `packetDetail(hash:)`, `observers()`, `channels()`,
  `channelMessages(...)`, `regions()`, `mapDefaults()`, `liveFeed()`, `capabilities`.
- **Domain models stay the app's existing models** (`MeshNode`, `MeshObserver`, `PacketDetail`, ...).
  CoreScope keeps decoding into them. Beacon gets its own small DTOs plus mappers into the same models.
  No screen sees a Beacon type. Reuse models only where meanings match; extend neutral models where
  needed rather than inventing zero values or treating observer hearings as unique packets.
- **`capabilities`** is a set (e.g. `.nodeHealth`, `.nodeReach`, `.nodePaths`, `.nodeAnalytics`,
  `.observerAnalytics`, `.observerTelemetry`, `.channelDecryptOnPhone`, `.mapDefaults`). Screens hide
  or swap cards based on it, instead of checking server type.
- **`CoreScopeBackend`** wraps today's calls exactly. **`BeaconBackend`** handles UUID lookup (cached
  `publicKey → uuid`), epoch ms, camelCase, cursor paging (200-item pages), and 429 + `Retry-After`.
- **Live feed:** a `LiveFeed` interface emitting neutral packet/observation updates and connection or
  recovery state; two implementations. Define deduplication and reconnect recovery before implementation.
- **Caches** are already namespaced by host; also add the server type to cache keys so a host that
  switches from CoreScope to Beacon (Colorado's case) can't reuse stale decoded data.
- **Source lifecycle:** identify each active source session with a generation token. On a host or
  backend change, cancel old requests/subscriptions, discard late results, and clear live events,
  cursors and UUID mappings. Revalidate region selections and open detail screens. Scope persisted
  mappings by source/backend and invalidate stale UUIDs after a server rebuild or failed lookup.
- **Shared connection ownership:** coordinate subscriptions across all visible screens/windows using
  one connection per active source where possible. A hidden map must not disable path resolution
  while another visible map still needs it; backgrounding one window must not disconnect another.

## 4. Feature mapping

| Feature | CoreScope today | Beacon source | Effort | Notes |
|---|---|---|---|---|
| Node list, map pins | `/api/nodes?limit=5000` | `/nodes` paged (200/page, ~8 pages for 1,500 nodes) | Easy | Cache aggressively; load first page fast, then fill |
| Node detail (basic) | `/api/nodes/{pk}` | `/nodes?pubkey=` → `/nodes/{uuid}` | Easy | Beacon adds regions heard, clock drift, capability flags |
| Health / reach / paths cards | `/health`, `/reach`, `/paths` | `/neighbors`, `/observations`, `/routes/search` | Medium | Neighbors (with SNR) replaces most of reach; hide the rest at first |
| Node analytics screen | `/api/nodes/{pk}/analytics` | Computed on the phone from `/nodes/{uuid}/observations` | Medium | Activity, heatmap, packet types, hops, SNR all work; per-observer coverage needs a packet-detail call per packet, so cap it or skip it; 7-day max |
| Observers list | `/api/observers` | `/observers` | Medium | Public-key identity and fresh-install lookup must pass phase 0; no per-row detail fan-out |
| Observer analytics | `/api/observers/{id}/analytics` | `/observers/{id}/activity` + `/telemetry` | Medium | Beacon is richer (battery, noise floor, airtime); new cards possible later |
| Regions / picker | `/api/config/regions` + `/api/iata-coords` | `/iatas` (with coordinates) + `/regions` | Easy | Beacon regions are groups of IATAs, so they can appear as picker groups |
| Map defaults | `/api/config/map` | none | Easy | Frame on the selected region's IATA coordinates (`RegionFraming` already does this) |
| Packet feed | `/api/packets` | `/packets` (cursor = epoch ms) | Medium | |
| Packet detail | `/api/packets/{hash}` | `/packets/{hash}` | Medium | Beacon gives per-hop `resolvedPath` with confidence and coordinates |
| Route replay | CoreScope `resolved_path` | Beacon `resolvedPath` | Medium | Ambiguous hops need a rule (show all candidates, or skip) |
| Live map | CoreScope WebSocket | `/ws`: subscribe + `configure resolvePath` + 30s ping | Medium | Only turn on `resolvePath` while the map is visible (1–2 KB per event) |
| Server-decrypted channels | `/api/channels`, `/messages` | `/channels?keyKnown=true`, `/channels/{id}/messages` | Medium | Later release; source-scoped identity and migration must be defined, not hash + name |
| Your own monitored channels (decrypted on the phone) | Packet list includes encrypted payload | Only packet detail has `rawPayload`, one request per packet | Hard | Unsupported on Beacon initially; server-decrypted channels follow in phase 3; ask Beacon for a list-level payload or channel-hash filter |
| Add to MeshCore QR | node name, key, type | same fields | Easy | |
| Diagnostics screen | probes CoreScope endpoints | probes Beacon endpoints | Easy | Show server type and version |
| Favorites / recents / search | keyed by public key + source | nodes retain keys; observer/channel mapping needs validation | Medium | Preserve existing entries; migrate identity without merging unrelated items |

## 5. Things that are easy to miss

1. **Old app versions read the live source list.** If a Beacon server is added to `us-sources.json`,
   every installed copy of NodeScope will show it and fail against it. **Fix:** keep `us-sources.json`
   CoreScope-only forever, and add a new `sources-v2.json` that new app versions read, with a `type`
   field (`"corescope"` / `"beacon"`). Old apps never see Beacon entries.
2. **Ignore Beacon's `minAppVersion`.** It refers to BEACON Mobile's version numbers, not ours. Use
   `serverVersion` to set a minimum supported Beacon version instead (2.0.x today).
3. **Rate limits are per IP: 300 requests/min, 5 WebSockets, 10 WebSocket connects/min.** Phone carriers
   put many users behind one IPv4 address (CGNAT), so heavy request patterns could get several NodeScope
   users throttled together. Rules: page lazily, cache, never fan out per-row requests in lists, respect
   `Retry-After`, and close the WebSocket when backgrounded (Beacon's own guidance). Worth raising with
   the Beacon maintainers early.
4. **Custom hosts need auto-detection.** Probe `/api/v1/info` (Beacon) and fall back to CoreScope's
   `/api/stats` when appropriate. Require a valid identifying response before choosing a backend.
   A timeout, TLS failure, 429 or server error is not evidence of CoreScope: retain a known backend
   and report connectivity trouble. Re-detection may change the backend only on positive evidence.
5. **Identity mapping.** Full public keys remain node IDs; normalize hex case consistently without
   changing existing favorites silently. Validate an efficient observer-key lookup on a fresh install
   before committing to public keys for every observer. If none exists, explicitly design a neutral,
   source-scoped observer reference and legacy-link behavior. Missing keys must not trigger fetching
   every observer's details. Beacon UUIDs are implementation details wherever a stable key is available.
6. **Times and units.** Beacon uses epoch ms, CoreScope ISO strings; `radio` is `"freq,bw,sf"` text in
   some places and separate fields in others.
7. **History depth.** Anything beyond 7 days on Beacon comes from hourly rollups or not at all. Time-range
   pickers should cap or relabel on Beacon.
8. **Empty states.** Beacon learns nodes from adverts only (repeaters advert every ~12–47h), so a new
   server looks sparse. Word empty states so they don't read as errors.
9. **Licensing.** Beacon is AGPL, but we only call its public API, so nothing in NodeScope changes. Don't
   copy code from BEACON Mobile (its repo isn't public anyway).
10. **App Store.** No new data collection or permissions. Update the description and screenshots to say
    "CoreScope and Beacon", and mention it in review notes so a reviewer can test against a Beacon host.
11. **Courtesy.** Tell the Beacon maintainers a second client is coming (and about the rate-limit
    question), and ask server operators before listing them.
12. **Channel identity.** Beacon's channel hash is one byte, not a unique identifier. Hash + display
    name is not guaranteed unique or stable either. Define a source-scoped identity, persistence rules
    for server channel IDs, and migration of existing favorites/links before phase 3. Never merge
    channels based on a hash alone; handle renames, collisions and server database resets explicitly.
13. **Model semantics.** Preserve unknown versus zero, unsupported versus empty, incomplete history
    versus quiet periods, and observations versus unique packets. Distinguish omitted fields (keep
    existing data) from explicit nulls (clear data) in node updates. A resolved-hop key may be a prefix:
    do not use it as a full node identity or QR contact. Preserve uncertainty in route replay rather
    than drawing an inferred route as confirmed.
14. **Live recovery.** Handle dropped-event notices and reconnect gaps, not just socket reconnection.
    Live events do not provide observation IDs, so do not assume a live event supplies a REST backfill
    cursor. Validate how to obtain recovery checkpoints or use a bounded REST refresh with overlap and
    deduplication. Distinguish new packets, observation updates and transient repeat events. Use bounded
    exponential backoff with jitter, honor throttling, and restore subscriptions/configuration after
    reconnecting. Serialize connection-wide configuration so flags do not overwrite each other.
15. **Pagination and resource budgets.** Keep cursors opaque to screens and follow each endpoint's
    contract, including tie-breakers where offered. Test timestamp ties, repeated cursors, changing
    lists, cancellation and partial page failures. Deduplicate without hiding incomplete results;
    establish bounds for pages, memory, bytes and time-to-first-content, not just request counts.

These review additions draw on the published
[Beacon API contract](https://github.com/MeshCore-Beacon/beacon-docs/blob/main/docs/api-contract.md)
and current iOS code. They are not additional live-server verification. Phase 0 must pin the contract
revision and server versions used for fixtures; newer documentation may describe features absent on
the supported deployments.

## 6. Phases

| Phase | What | Visible change | Rough size |
|---|---|---|---|
| 0. Contract validation | Record versioned fixtures; prove observer lookup, channel identity rules, pagination and live recovery; draft `sources-v2.json` schema | None | Small, gates architecture |
| 1. Backend layer (iOS) | Introduce `AnalyzerBackend` + `CoreScopeBackend`; migrate all 17 calls feature-by-feature with regression tests; capabilities and neutral live-feed interface | None (pure refactor) | Large, riskiest |
| 1. Backend layer (Android) | Rename/extend existing interfaces to match; add capabilities and live-feed interface | None | Small |
| 2. Beacon browse | `BeaconBackend`: detection, nodes, basic node detail, observers, regions, map framing, packets/detail, replay, live map and basic diagnostics | Beacon servers work for browsing | Large |
| 2R. 0.8.0 release | Capability-gate unsupported features; validate both platforms; publish `sources-v2.json`, store text and review notes; TestFlight / Play beta first; prepare rollback | Public browse support | Small |
| 3. Beacon depth (later releases) | Computed node analytics, observer activity/telemetry, server-decrypted channels, neighbors card, expanded diagnostics | Most screens filled in | Medium |
| Later | Monitored (on-phone decrypted) channels on Beacon, if Beacon adds a way to list payloads cheaply | | Depends on upstream |

**Phase 0 exit gate:** record the chosen identity and recovery rules with fixtures and small contract
tests before the large refactor. Resolve unsupported assumptions rather than relying on a warm cache.
Channel rules must be understood now, but channel implementation remains outside 0.8.0.

**0.8.0 release gate:** both platforms pass CoreScope regression and Beacon browse tests; unsupported
analytics/channels are hidden or clearly explained, including entry through old favorites/deep links.
Ship a v2 bundled registry and separately version its persisted cache; unknown source types must not
break the entire list. Prepare removal/disablement of a problematic listing, retain last-known-good
registry data, and allow switching back to CoreScope. Removing a listing alone does not disable an
already selected custom host, so retain a recoverable error path for those users.

## 7. Platform order

Work each phase **iOS first, then Android right after, and release them together.**

- iOS needs the big refactor; Android doesn't. Doing iOS first settles the interface, capability names and
  mapping rules, and Android then follows a finished design. That's mostly translation, made easier by
  its existing interfaces.
- Don't finish all of iOS before starting Android. The two would drift apart, and `docs/android/parity-matrix.md`
  exists to prevent that. Phase by phase keeps them in step.
- Release both together, since `sources-v2.json` turns Beacon on for both at once.

## 8. Testing

- **Unit tests per platform** with recorded Beacon JSON (nodes page, node detail, observations, observers,
  observer detail, packet list/detail, channels, messages, info, WebSocket frames): mapping, paging,
  epoch ms, identity mapping, capability gating.
- **Regression:** CoreScope behaviour must not change in phase 1. The existing tests plus a manual pass on
  analyzer.meshtexas.org before and after. Add regression coverage as each feature moves behind the
  backend interface rather than waiting for the entire refactor to finish.
- **Live checks** against beacon.meshtexas.org (same mesh as our CoreScope, so results can be compared
  side by side), Colorado, and dev.meshcore.ca.
- **Rate-limit check:** count requests per screen on Beacon; no screen should need more than ~20 on open.
  Also record bytes, peak memory and time-to-first-content on a busy server; set measurable budgets
  from phase 0 results. Share throttling/backoff per source to avoid retries from every screen at once.
- **Identity and upgrades:** fresh-install observer links, missing keys, case normalization, channel
  hash collisions/renames, UUID changes, legacy favorites and source-bearing/legacy deep links.
- **Source transitions:** switch during REST loading, paging, reconnect and replay; simulate a backend
  change at the same host. No late response or cached event may populate the new source's UI.
- **Pagination and semantics:** timestamp ties, duplicate/repeated cursors, partial failures, unknown
  fields/types, missing versus null values, prefix-only hop keys, incomplete history and observation
  versus packet counts. Verify partial results are labeled, not presented as a complete dataset.
- **Live lifecycle:** dropped-event notices, overlap between REST and live data, reconnect gaps,
  throttling, subscription restoration, foreground/background transitions, and multiple visible
  screens/windows. Recover without duplicate rows, corrupted counts or reconnect storms.
- **Detection and registry:** offline first launch, TLS failures, malformed responses, 429/5xx,
  unknown source types, stale registry caches and rollback. Connectivity errors must not flip backend.
- The user does the hands-on app testing; builds must compile and unit tests must pass before handoff.

## 9. Decisions

Decided 2026-10-10:

1. **v1 scope:** browse first. 0.8.0 ships phases 1–2 through the phase 2R release checkpoint;
   phase 3 follows in later releases. Basic diagnostics are part of phase 2.
2. **Listed servers:** Colorado Mesh (`map.meshcore.coloradomesh.org`) in `sources-v2.json`. They've already
   agreed. beacon.meshtexas.org stays a test server, reachable as a custom host.
3. **Version:** phase 1 folds into 0.8.0 with the Beacon work, no separate point release.

Rate limits are per server, set in each operator's `config.yaml` (`ratelimit.*`, `websocket.*`), with
the defaults in section 5. Ours keys them by each visitor's real IP (Cloudflare → Caddy → `X-Real-IP`, with
`trusted_proxies` set). On a server whose proxy isn't configured that way, every visitor shares one
budget, which would hit NodeScope users first. Colorado runs behind nginx and its settings are unknown;
check how NodeScope behaves against it during testing.

Still open:

- ~~Phase 0 contracts~~ and ~~compatibility and budgets~~: resolved in
  [phase0-findings.md](phase0-findings.md) (server 2.0.3+, contract `9d1dae3`, identity, paging,
  live recovery and budget rules). How partial history and uncertain routes are *presented* is a UI
  decision for phases 2–3.
- **Contact the Beacon maintainers** about a second client, `publicKey` in observer summaries (or a
  key filter), and a cheap way to read group-text payloads.
