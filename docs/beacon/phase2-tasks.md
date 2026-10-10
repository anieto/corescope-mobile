# Beacon phase 2 tasks

Goal: Beacon servers work for **browsing** in NodeScope 0.8.0 on iOS and Android (map, nodes, node
detail, observers, packets, packet detail, route replay, live map, basic diagnostics), with no change
for CoreScope users. Phase 3 (Beacon node analytics, observer telemetry, Beacon channels) is out of
scope: on Beacon those screens are hidden or explained.

Read first, in this order. Where they disagree, the later document wins.
1. [plan.md](plan.md): architecture, feature mapping, release gate
2. [phase1-implementation.md](phase1-implementation.md): what the backend layer looks like now
3. [phase0-findings.md](phase0-findings.md): **binding rules** for identity, paging, live feed,
   budgets and `sources-v2.json`, with measured evidence

Status key: `todo` · `doing` · `review` · `done`. Update the status column as work lands.

---

## Working rules

- **Branches:** one branch per milestone off `main` (`beacon/p2-foundation`, `beacon/p2-ios-rest`,
  `beacon/p2-ios-live`, `beacon/p2-android`), merged by pull request after review. Rebase on `main`
  before opening the pull request.
- **Commits:** stage files by explicit path, never `git add -A`; other tools edit this checkout too.
  Leave unrelated changes alone (for example Android Studio's edits to `android/settings.gradle.kts`).
  No AI attribution lines (`Co-Authored-By`, "Generated with") in commits or pull requests.
- **CoreScope must not change.** `CoreScopeBackendTests` (19 request variants) and the Android
  `AnalyzerBackendTest` pin every CoreScope request. Those tests may gain cases, but existing
  expectations change only when a task below says so.
- **iOS builds need Xcode 27.1** (the Duo APIs): `DEVELOPER_DIR=~/Downloads/Xcode.app/Contents/Developer`.
  The system default is 27.0 and fails in `DuoLayoutContext.swift`.
- **Tests:** unit tests run against `shared/fixtures/beacon/` (sanitized recordings of server 2.0.3;
  see `shared/fixtures/README.md`). Add Swift tests reading the same files as the Kotlin tests. No UI
  automation or simulator driving; the user does hands-on testing.
- **Live servers** for manual checks: `beacon.meshtexas.org` (ours, now 2.0.5), `map.meshcore.coloradomesh.org`
  (Colorado, 2.0.3), `dev.meshcore.ca` (Beacon's own, 2.0.5). Mind their rate limits (300 requests/min,
  5 WebSockets and 10 WebSocket connects/min per IP). `tools/beacon-contract/check_contract.py <host>`
  re-checks the API assumptions in about 12 requests.
- **Never read `/info.minAppVersion`.** It is BEACON Mobile's floor (now `2.0.0` on 2.0.5 servers)
  and would lock NodeScope 0.8.0 out.

---

## Milestone A: foundation (iOS) — `beacon/p2-foundation`

Must merge before B and D start; it changes interfaces every screen uses. No visible change.

| ID | Task | Done when | Status |
|---|---|---|---|
| A1 | **Paging.** Add an opaque continuation to list operations (`packets`, and `nodes` internally). Callers ask for "up to N items"; the backend walks pages. Today the app requests 1,000 packets at once in `ExploreScreen.swift:377` and `PacketFeedCache.swift:47`, and 200 in `MapViewModel.swift:202`; Beacon clamps every page to 200. | CoreScope sends exactly today's first-page requests (contract tests unchanged); a fake backend proves multi-page walks stop at N, stop at `hasMore == false`, and surface partial results on a failed later page as incomplete, not complete | todo |
| A2 | **Neutral semantics where Beacon differs.** Extend the app models only where needed: optional "last heard" (Beacon derives it from `iatas[].lastHeard`), unknown versus zero counts, and a route hop that can be `confirmed`, `ambiguous` (several candidates) or `unresolved`. Prefix-only hop keys must never be treated as a node identity or used for Add to MeshCore. | CoreScope decodes to identical values (existing decoding tests pass); new model states have unit tests | todo |
| A3 | **Source session.** Add a session value (host, backend type, generation id). Cache keys include the backend type (`cacheIdentifier` today is the base URL only). View models drop results whose generation no longer matches. Switching source cancels in-flight work and clears live events, cursors and learned id mappings. Changing cache keys makes existing caches miss once, which is acceptable. | Tests: a late response from source 1 never reaches source 2's state; the same host reported as a different backend type gets separate caches | todo |
| A4 | **Capability gating hooks.** A small helper that screens use to hide a card or show an "isn't available on this server" state from `backend.capabilities`. Wire it into node detail (health, reach, paths cards), node analytics, observer analytics, the channels tab, monitored channels, and map defaults. Entry through favorites, recents and deep links must hit the same gate. CoreScope has every capability, so nothing changes yet. | A test backend without capabilities produces the explained state for each gated entry point | todo |
| A5 | **Live feed control.** Screens register demand (regions, needs route data). `LiveFeedService` merges every screen's demand and hands the full set to the feed; one owner, because Beacon's `configure` replaces all flags at once. Add a neutral live packet/observation event that both feeds map into, and move the map and packet feed onto it. The CoreScope feed ignores demand. | CoreScope live behavior is unchanged (existing live tests pass); a test shows two screens' demands merge, and removing one doesn't drop the other's | todo |
| A6 | **Diagnostics through the backend.** Move the five CoreScope probe URLs out of `AnalyzerDiagnosticsService.swift` into a backend-provided probe list. | Diagnostics on a CoreScope host is unchanged | todo |

## Milestone B: Beacon backend (iOS) — `beacon/p2-ios-rest`, then `beacon/p2-ios-live` for B6

| ID | Task | Done when | Status |
|---|---|---|---|
| B1 | **Detection and backend type.** Store a backend type per source. For custom hosts, probe `/api/v1/info` (Beacon) and fall back to CoreScope's `/api/stats`. Only a valid identifying response picks or changes the type. Timeouts, TLS errors, 429 and 5xx keep the known type and report a connectivity problem. Registry entries carry their type and skip detection. | Tests for every outcome: Beacon, CoreScope, both failing, flaky after a known type, and malformed JSON | todo |
| B2 | **Beacon HTTP client.** Epoch-ms dates, `{"error": {"code", "message"}}` errors, `Retry-After` honored with one shared backoff per source (not per screen), and a `NodeScope/<version>` User-Agent. | Unit tests for error mapping, 429 backoff sharing, and date decoding | todo |
| B3 | **DTOs and mappers** from the fixtures: nodes page, node by key, node detail, neighbors, observers page and detail, iatas, regions (members need `/regions/{id}`; cache them), packets page, packet detail, group-text packet detail, errors. Coordinates are spelled three ways (`lat`/`lng`, `lat`/`lon`, `latitude`/`longitude`). | Each fixture file decodes and maps; mapping tests cover nulls versus missing fields | todo |
| B4 | **`BeaconBackend`** for the browse operations: nodes (walk 200-item pages, about 5 requests for 900 nodes), node detail via a cached `publicKey → uuid` lookup (`/nodes?pubkey=`), observers, regions, IATA coordinates, packets (opaque cursor; keep server order and never re-sort region-filtered lists), and packet detail (on demand only; about 50 KB each). There's no map-defaults capability; frame on the selected region's IATA coordinates. Unsupported operations throw a typed "unsupported" error and are absent from `capabilities`. | Fixture-backed tests per operation; request counts per screen open stay within the phase0-findings budgets (≤ about 20) | todo |
| B5 | **Identity rules.** Beacon observers are referenced as (source, uuid). Learn `uuid ↔ publicKey` from observer detail and live events, never by fetching every observer. A key-based observer favorite, recent or deep link opened on a Beacon source uses a learned mapping or shows "not available on this server". Nodes and packets keep public key and hash. Never merge stored items across sources. | Tests: fresh install plus key-based observer link gives the explained state with zero fan-out requests; a learned mapping resolves it | todo |
| B6 | **`BeaconLiveFeed`** per phase0-findings: on `hello`, subscribe with region filters, then send `configure` with the merged demand (route data only while a visible map needs it), ping every 30 s. Backoff 1, 2, 5, 10, 30 s with jitter; handle close code 1013 and 429. Close on background; on foreground reopen, resubscribe, reconfigure and refresh. On reconnect or any `lagged` notice, refetch the first `/packets` page with the same filters and merge by packet hash (larger `observationCount`, later `lastHeardAt`, server order). Group by hash and key observations by (hash, observerId); don't rely on `isFirstObservation`. Accept both event shapes right after a flag change. | Tests replaying `shared/fixtures/beacon/ws-session.json`, plus synthetic lagged and reconnect sequences, with no duplicate rows and no double counting | todo |
| B7 | **Route replay with uncertainty.** Map Beacon `resolvedPath` hops to the A2 hop states. Ambiguous hops are drawn as uncertain (or skipped, with a note); never as a confirmed route. | Tests with the packet-detail fixture (13 of 13 hops match their key prefixes) and synthetic ambiguous and unresolved hops | todo |

## Milestone C: source list v2 (both platforms, with B)

| ID | Task | Done when | Status |
|---|---|---|---|
| C1 | Read `sources-v2.json` (live, then cached, then bundled) per phase0-findings: top-level `version: 2`, per-source `type`, missing type means CoreScope, **an unknown type skips that entry only**. Bundle a v2 file in both apps. Live URLs: iOS `AnalyzerSourceRegistry.swift:36`, Android `AppViewModel.kt:53`. Keep reading nothing else from `us-sources.json`; it stays CoreScope-only for old apps. Version the persisted registry cache separately from v1. | Tests: unknown type, missing type, malformed entry and stale cache; the Android `SourceIconsTest` covers the v2 file | todo |
| C2 | Prepare (don't publish) Colorado's entry: restore `CommunitySources/icons/colorado-mesh.png` from history (deleted in `6f2aca4`) and use `docs/beacon/sources-v2.draft.json`. Publishing to `CommunitySources/sources-v2.json` happens in milestone F. | Icon restored; bundled v2 matches the draft | todo |

## Milestone D: screens (iOS, with B)

| ID | Task | Done when | Status |
|---|---|---|---|
| D1 | Apply A4 gating everywhere listed there, with wording that doesn't read as an error ("This server doesn't provide node analytics"). | Each gated screen is reachable on Beacon and shows the explained state | todo |
| D2 | Source picker and custom host: show the server type; detection states (checking, CoreScope, Beacon, unreachable); switching back to CoreScope always works. | Matches B1 outcomes | todo |
| D3 | Empty states for sparse Beacon servers (nodes appear as they advert, every 12–47 h for repeaters) and for history limited to 7 days. | Copy reviewed by the user | todo |
| D4 | Diagnostics shows the server type and version and runs the backend's probes. | Works for both types | todo |

## Milestone E: Android port — `beacon/p2-android`

Port A–D through the existing `AnalyzerRepository`, `BrowseRepository` and `PacketSource` interfaces,
using the same capability names, identity rules and live recovery rules as iOS. Kotlin tests use the
same fixtures. Update `docs/android/parity-matrix.md`.

| ID | Task | Status |
|---|---|---|
| E1 | Foundation: paging, neutral hop states, source session and cache keys, capability gating, live demand (A1–A6) | todo |
| E2 | Beacon REST backend, detection, identity (B1–B5, B7) | todo |
| E3 | Beacon live feed (B6) | todo |
| E4 | Source list v2 (C1) | todo |
| E5 | Screens (D1–D4) | todo |

## Milestone F: 0.8.0 release gate ("2R")

| ID | Task | Status |
|---|---|---|
| F1 | Both platforms pass CoreScope regression and Beacon browse tests; the user completes a manual pass on CoreScope and on our Beacon, Colorado and dev.meshcore.ca | todo |
| F2 | `check_contract.py` passes on all three servers; refresh fixtures if a server change matters (`capture.py` → `make_fixtures.py` → `check_leaks.py` must report 0) | todo |
| F3 | Publish `CommunitySources/sources-v2.json` with Colorado's entry and icon; confirm `us-sources.json` is unchanged | todo |
| F4 | Build numbers: iOS `CURRENT_PROJECT_VERSION`, Android `versionCode` (12 or higher). Version names are already 0.8.0 | todo |
| F5 | Store text and screenshots say "CoreScope and Beacon"; review notes give a Beacon host to test against; TestFlight and Play beta before production | todo |
| F6 | Rollback ready: pull a listing from `sources-v2.json`; users on a removed or broken custom host get a recoverable error and can switch back | todo |

---

## Suggested order

1. A1–A6, one pull request, reviewed before merging (the riskiest change for CoreScope).
2. B1–B5, B7, C1–C2 and D1–D4 together: Beacon browsing over REST on iOS.
3. B6: the Beacon live feed on iOS.
4. E1–E5: Android.
5. F1–F6: release.

After each milestone, add a short section to `phase1-implementation.md`'s sibling,
`docs/beacon/phase2-implementation.md`: what changed, decisions made, and what's still open.
