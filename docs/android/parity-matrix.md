# iOS → Android feature parity

0.9.0 repository checkpoint (2026-10-16): source-bound 24h analytics memory caches, 15-minute
reuse, coalesced two-worker batches, explicit deferred results and consumer/source cancellation
now match. Both backend factories and CoreScope Retry-After behavior have fixture coverage.
Dashboard lifecycle/UI, shared detail cache and persistent comparison baselines remain open.

0.9.0 transport checkpoint (2026-10-16): both platforms have opt-in budgeted HTTP clients
and a Beacon My Nodes history loader capped at one lookup plus three pages. Batch exhaustion
retains partial history. Cache/coalescing, dashboard UI and on-wire measurement remain open.

0.9.0 evidence checkpoint (2026-10-16): `MyNodesEvidence` and `MyNodesRequestBudget` now match
Swift behavior. Twelve shared evidence cases and budget/cancellation scenarios pass in five JVM
tests. HTTP integration followed in the transport checkpoint above; dashboard UI is still pending.

0.9.0 checkpoint (2026-10-16): optional CoreScope account wire models are at parity on Swift
and Kotlin. Both platforms consume the same populated/empty settings fixtures and pass four
contract tests covering raw-string preservation, category capability checks, generation consistency,
unknown schemas and malformed values. Login, sync and My Nodes UI are not implemented yet.
See [0.9.0 implementation progress](../my-nodes/implementation.md).

Baseline: iOS `cd98f8e`, inspected 2026-09-21. All rows below are **observed in
source**, not claims that every iOS behavior has been exercised on a device.
All Android parity statuses begin **unverified**; the earlier node-browser
prototype is not the implementation baseline. IDs are intended for issue and
acceptance-test references. Paths below are relative to `CoreScopeViewer/`.

| ID / phase | Implemented iOS scope | Android acceptance | Evidence |
|---|---|---|---|
| A01 / 1 | First-launch onboarding; analyzer selection | First launch selects a bundled/community/custom HTTPS analyzer; completion persists; relaunch enters app | `Features/Onboarding/OnboardingScreen.swift`, `App/CoreScopeViewerApp.swift` |
| A02 / 1 | Remote source registry, cached and bundled fallback | Registry failure leaves usable sources; custom source remains possible; selected source is visible | `Networking/AnalyzerSourceRegistry.swift`, `Features/Settings/AnalyzerSourcePicker.swift` |
| A03 / 1 | Five destinations; remembered selection; reselect resets destination | Android navigation preserves tab state and supports a deliberate return to root; back/rotation/process recreation work | `App/RootTabView.swift` |
| A04 / 1 | Source change reconnects and resets regions | Cancel old requests/events; clear displayed old-source data; reload defaults/regions; show connecting/success/failure | `Features/Settings/SettingsScreen.swift`, `Networking/RegionFilterStore.swift` |
| A05 / 1–2 | Shared region selector and region-centered map | Selecting a region updates all applicable data; invalid region resets after source change; no Texas-specific hardcoding | `Networking/RegionFilterStore.swift`, `Networking/ObserverRegionLookup.swift` |
| M01 / 2 | Analyzer map defaults, nodes, role colors, clusters, search | Correct camera bounds and markers; cluster tap zooms; search locates a node; missing coordinates do not crash or create false markers | `Features/Map/MapViewModel.swift`, `Features/Map/MapScreen.swift`, `Features/Map/NodeRoleStyle.swift` |
| M02 / 2 | Standard/satellite/hybrid, zoom, current location | All three map modes or an explicitly agreed deviation; locate is optional and denial preserves map use | `Features/Map/MapScreen.swift`, `Features/Map/MapLocationManager.swift` |
| M03 / 2 | Region, roles, activity age, route observer filters; persisted map state | Same filter meanings and reset action; activity choices: any, 15m, 1h, 24h, 7d; restore source-specific camera/filter state | `Features/Map/MapScreen.swift` |
| M04 / 3 | Recent/live route rendering; animated pings; route hit testing/details | Correct route subchains and observer endpoints; unresolved/colliding prefixes never create invented connections; selectable message/route details | `Features/Map/MapScreen.swift`, `Features/Map/ActivePing.swift` |
| M05 / 3 | Individual packet replay and return to Live | Replay from packet details changes to Map, frames route, animates and supports return to live without losing filters | `Networking/PacketReplayStore.swift`, `Features/Map/MapScreen.swift` |
| N01 / 2 | Node identity, copy/share, favorite, map action, health, reach, links, paths, observer summaries | Matching available fields and drill-down actions; partial endpoint failure leaves other sections usable | `Features/NodeDetail/NodeDetailScreen.swift`, `Features/NodeDetail/NodeDetailViewModel.swift` |
| N02 / 5 | Node analytics 24h/7d/30d/1y; summary, activity, UTC heatmap, signal, types, hops, coverage, peers | Match calculations/units/timezone; range changes cannot show stale responses; 404 becomes unavailable, missing sections stay meaningful | `Features/NodeDetail/NodeAnalyticsScreen.swift`, `Features/NodeDetail/NodeAnalyticsViewModel.swift`, `Models/NodeAnalytics.swift` |
| E01 / 5 | Dashboard metrics: active nodes, online observers, hourly packets, average SNR; hide/reorder and drill-down | Same metric definitions/sample windows as iOS; configuration persists; counts lead to corresponding filtered screens | `Features/Explore/ExploreScreen.swift` |
| E02 / 5 | Global search over nodes/observers/channels/packet hashes; recent searches | Search categories, identifiers, history and result routing work; expose loaded-data limits rather than promising whole-server search | `Features/Explore/ExploreScreen.swift`, `Networking/SearchHistoryStore.swift` |
| E03 / 5 | Favorites for nodes/observers/channels; reorder/remove; recently viewed entities and quick actions | Saved items scoped by analyzer; returning to a source restores its items; copy/map/open actions work | `Networking/FavoritesStore.swift`, `Networking/RecentItemsStore.swift`, `Features/Explore/ExploreScreen.swift` |
| P01 / 3 | Live packet feed with grouping, region/type filters and details | Snapshot/live reconciliation avoids duplicate packets but retains distinct observations; ordering and filters match | `Features/PacketFeed/PacketFeedScreen.swift`, `Models/LiveFeedMessage.swift` |
| P02 / 3 | Packet payload, raw data, observations, routes, share and replay; hash detail lookup | Preserve available fields across feed and fetched detail shapes; unknown packet types display a fallback; route actions work | `Features/PacketFeed/PacketFeedScreen.swift`, `Features/NodeDetail/PacketDetailScreen.swift`, `Features/NodeDetail/PacketDetailViewModel.swift` |
| C01 / 4 | Server and locally monitored channels; search, source/activity/sort filters | All/local/server filters; one-hour activity; recent/message-count/name sort; region behavior agrees with iOS | `Features/Channels/ChannelsListScreen.swift` |
| C02 / 4 | Channel conversations and live refresh; message→packet navigation | Read-only conversations; correct sender/time ordering and available route actions; live updates do not disrupt scrolling | `Features/Channels/ChannelDetailScreen.swift`, `Features/Channels/ChannelsViewModel.swift` |
| C03 / 4 | Hashtag monitoring, PSK import, secure private-key generation, removal | `Public` normalization and 32-hex-character keys match; labels persist; removal does not affect the server | `Features/Channels/MonitorChannelSheet.swift`, `Networking/ChannelMonitorStore.swift` |
| C04 / 4 | Local packet authentication/decryption and local summaries | Shared known-answer vectors match Swift byte-for-byte; bad MAC/key/payload fails safely; key never enters logs, URLs or requests | `Networking/ChannelMonitorStore.swift`, `Features/Channels/ChannelsViewModel.swift` |
| O01 / 4 | Observer list, search, status/model/sort/region filters; telemetry | Same status and filter meanings; missing battery/noise/location values remain unknown, not zero | `Features/Observers/ObserversListScreen.swift`, `Models/Observer.swift` |
| O02 / 5 | Observer detail, packet/time/type/node/SNR charts, recent packets, copy/share/favorite | Matching chart inputs and detail links; refresh/partial errors/cached indicators work | `Features/Observers/ObserverDetailScreen.swift`, `Features/Observers/ObserversViewModel.swift` |
| S01 / 1 | System/light/dark appearance and connection status | Explicit appearance override survives relaunch; text and map overlays remain legible | `Features/Settings/AppearanceSettings.swift`, `Features/Settings/SettingsScreen.swift` |
| S02 / 5 | Analyzer diagnostics and feature probes | Distinguish network/TLS, authentication, unavailable endpoint, malformed response; retry and report display | `Networking/AnalyzerDiagnosticsService.swift`, `Features/Settings/AnalyzerDiagnosticsScreen.swift` |
| S03 / 5 | Cache usage and clear; About/how-to/privacy | Clear downloaded data without deleting favorites/settings/channel keys; document Android-specific storage and map provider | `Networking/CacheStorageService.swift`, `Features/Settings/StorageSettingsScreen.swift`, `Features/Settings/AboutScreen.swift` |
| X01 / 1–5 | Cached responses, fresh/stale/error indicators, refresh and coalesced requests | Cache keys include analyzer and relevant parameters; stale data never presented as live; clear/source switch defeats in-flight stale writes | `Networking/APIClient.swift`, `Networking/*Cache.swift`, `Features/Shared/DataLoadStatusView.swift` |
| X02 / 3–5 | `nodescope://node`, `observer`, `channel`, `packet` links | Encode reserved characters correctly; route on cold/warm launch and after onboarding; missing entity explains active source | `Networking/NodeScopeDeepLink.swift`, `Networking/AppNavigationStore.swift` |
| X03 / 5 | Dashboard/node/observer CSV and JSON exports | Equivalent fields, ranges, source and units; Android share sheet/FileProvider or system save picker; correct CSV quoting | `Utilities/StatisticsExport.swift`, relevant analytics screens |
| X04 / every | Accessibility, reduced motion, adaptive layouts | TalkBack, large text, non-color status cues, touch targets, keyboard focus, motion settings and resizable windows verified | `Features/Shared/`, accessibility modifiers in feature screens |

## Contracts to capture as fixtures

Beacon groundwork, 2026-10-10: both platforms now select a CoreScope backend through a factory and
declare matching capability sets. iOS extracts a live-feed transport; Android reuses its existing
`PacketSource`/`LiveSignal` boundary. Automated regression passes on both platforms; manual parity
checks remain pending. See [phase 1 implementation](../beacon/phase1-implementation.md).

Beacon phase 2, 2026-10-12: Android now matches the iOS Beacon phase 2 work (detection, browse
readers, live feed and screens). See the Android port checkpoint in [the Beacon plan](../beacon/plan.md).

Beacon phase 3, 2026-10-14: Android ports C3 (neighbors) and D4 (node analytics) from the iOS
commits 0d90072, 0c4a320 and 8d12ef2: the same neutral models (`AnalyticsContext`,
`NeighborInsight`), the `NEIGHBORS` capability, the Beacon neighbors and bounded observation-history
readers, and the analytics builder. Shared fixtures produce the same aggregates on both platforms.
The same day Android also ported E4 (observer activity and telemetry), F5 (server-decrypted
channels) and G3 (diagnostics) from the iOS working tree, which was not yet committed then. See
[phase 3 tasks](../beacon/phase3-tasks.md) and the Android progress log for the deliberate differences.

| Contract observed | Notes to preserve |
|---|---|
| `GET /api/config/map` | Analyzer-provided camera defaults; use a documented fallback if unavailable |
| `GET /api/config/regions`, `/api/iata-coords` | Region names, centers/radii; optional radius; no hardcoded region list |
| `GET /api/nodes?limit=5000[&region=…]` | Current map list cap; unknown fields tolerated; preserve missing coordinates and roles |
| `GET /api/nodes/{publicKey}/health`, `/paths`, `/reach` | Independently optional node-detail sections |
| `GET /api/nodes/{publicKey}/analytics?days=1|7|30|365` | Capability-sensitive; test partial payload and 404 separately from outages |
| `GET /api/packets` | Caller-specific limits/filters. Local channel cache requests `limit=1000&payloadType=5` plus optional region |
| `GET /api/packets/{hash}` | Observation/route detail shape differs from list/live events |
| `GET /api/channels[?region=…]`, `/api/channels/{identifier}/messages` | Encode identifiers as individual path segments, including `#` and `/` |
| `GET /api/observers`, `/api/observers/{id}/analytics` | Region lookup, optional telemetry and analytics |
| `wss://{host}` | One shared stream; data-less heartbeats ignored; optional decoded fields; bounded recent history (iOS currently 200) |
| Community registry JSON | Shared `CommunitySources/us-sources.json`; cached/bundled fallback; remote update is a separate network dependency |

These are observed call sites, not a claim that all CoreScope server endpoints are
required or available. Capture exact query semantics and representative response
fixtures in Phase 0. Do not invent additional backend requirements.

## Deferred from the parity release

Continuous historical timeline playback/scrubbing/speed controls, network-wide
comparison/topology work, the remaining future investigation enhancements,
notifications, platform shortcuts, and saved network presets. Revisit after
parity. Existing raw packet inspection and individual replay remain in scope.

Server administration, MQTT configuration, write APIs, packet injection, radio
transmission, and uploading location tracks remain out of scope.


### 0.9.0 activity foundation — 2026-10-17

Swift and Kotlin activity assessment/change rules pass the same 22 synthetic fixture cases,
including the exact recency/freshness/skew boundaries, successful-fetch confirmation, expired
baselines, clock rollback and net-state comparison. This is foundation parity only; the dashboard,
visit persistence and signal/observer comparisons are still pending.


### 0.9.0 signal and observer foundation — 2026-10-17

Swift/Kotlin pure comparison rules pass the same 25 signal and 18 observer fixtures, plus
nonfinite SNR checks and the 22 existing activity cases. Matched-receiver medians, complete-history
gates, nonoverlapping visit windows, mixed-observation suppression and explicit-versus-inferred
observer status agree. Persisted summaries, event identity and dashboard integration remain pending.


### 0.9.0 compact baselines and visit storage — 2026-10-17

Swift and Kotlin now use compact signal summaries and deterministic event IDs, and read the same
version-1 millisecond snapshot fixture. Twelve targeted tests pass per platform, including eight
store tests for recovery, window interleaving, stale/late writes, removal, reset, observer limits,
namespace isolation and I/O failure. App-level store ownership, cache controls and dashboard lifecycle
are still pending; this checkpoint establishes model/store parity only.


### 0.9.0 snapshot cache ownership — 2026-10-17

Both apps now own a shared snapshot store and include its files in cache usage/clear. Android's
application container injects the owner into cache controls; broad cache cleanup skips the snapshot
directory to preserve store serialization. Storage screens disclose snapshot reset and show deletion
failure without a success timestamp. Ten Swift / eleven Android targeted tests pass. Dashboard,
source-session and background lifecycle integration remains pending.


### 0.9.0 foreground visit owner — 2026-10-17

Both platforms have a source-bound visit owner that rejects obsolete results/writes and cancels
only its analytics waiter. Store clear now cancels attached visit work. Three lifecycle tests per
platform pass alongside persistence/cache regressions (13 Swift, 14 Android total). Actual dashboard
navigation/background hooks and shared live-feed demand are not connected yet.


### 0.9.0 selected-node metadata repository — 2026-10-17

Swift/Kotlin bounded base repositories now support source-scoped catalog seeding, five-minute reuse,
progressive exact-key rows, joined refreshes and explicit unavailable/deferred/error states. CoreScope
and Beacon adapters use budgeted HTTP. Ten Swift / eleven Android targeted tests pass, including the
0/1/20/100/500 matrix. Observer catalog loading must still share the base budget; production cache
seeding/ownership and dashboard UI integration remain pending.


### 0.9.0 shared observer catalog allowance — 2026-10-17

Swift/Kotlin base repositories share one 20-attempt budget between observer catalogs and node metadata.
Beacon traversal stops after four pages with explicit partial completeness; CoreScope completeness
remains unknown. Both preserve timestamps on warm reuse/failure, support observer-only batches and
stop node scheduling on catalog 429. Ten Swift and fourteen Android targeted tests pass. Dashboard
rendering, catalog continuation UI and production ownership/cache seeding remain pending.


### 0.9.0 application-owned My Nodes repositories — 2026-10-17

Both storage owners now share base/analytics pairs per source session and provide CoreScope/Beacon
factories. Settings cache clear invalidates these disposable generations, rejects late results and
allows fresh acquisition without invalidating the shared source/feed lifetime. Seven Swift and eight
Android ownership/visit tests pass. Dashboard acquisition, cache seeding and UI lifecycle hooks remain
pending.


### 0.9.0 initial activity dashboard — 2026-10-18

Explore opens a source-scoped My Nodes activity screen on both platforms, with node recency, original
fetch times, explicit cache/error/deferred states, observer catalog evidence, Refresh/Load More and
existing detail navigation. Lifecycle-owned waiters and visible cache-clear invalidation use the
shared repositories. Thirteen Android base/ownership tests pass and Android UI tests compile; the
new warm-cache/cache-clear UI test awaits a connected device. iOS build and empty-state simulator
navigation checks pass. Visit comparisons, analytics, exports and full screen acceptance remain open.


### 0.9.0 reviewed comparison states — 2026-10-18

Swift/Kotlin snapshot stores and visit sessions distinguish unavailable, first-visit, expired,
unchanged and changed comparison results while preserving fresh-render persistence rules. Sixteen
tests pass per platform, including shared signal evidence and fixed-baseline visit regressions.
The Changes UI and visible-row/navigation lifecycle integration remain pending.

### Dedicated Favorites navigation — 2026-10-18

Explore now opens a dedicated Favorites page. Saved channels, nodes, and observers, adding nodes, removal, and reordering moved to that page. Activity opens from Favorites. Existing source-scoped storage remains intact. CoreScope account sync and a Changes UI remain unimplemented. iOS build-for-testing and Android instrumentation compilation passed.

### Favorites Changes — 2026-10-19

Both platforms provide a dedicated Changes page for node and saved-observer recency, fixed foreground-visit baselines, visible-only review persistence, and explicit unavailable/first-visit/expired/unchanged/changed states. Android Pixel 10 instrumentation passed for visible-only persistence and Favorites add/open/remove. Account sync and signal comparison UI remain pending.

### Activity simplification — 2026-10-19

Separate Changes navigation removed on both platforms; comparison groundwork retained. Activity now uses a compact source header, role-colored icons, whole-card navigation, primary last-heard text, secondary freshness details, About activity help, and conditional Load More.

### 2026-10-19 account isolation follow-up

Account selections now use a separate source/user namespace on both platforms. Device favorites
are imported or copied only by explicit actions; logout purges account state and offers an explicit
copy. Server generation changes pause sync for a user choice. Favorite/claimed categories remain
independent, pending edits survive refresh/conflicts, and settings writes use a shared one-minute
throttle with the existing CoreScope cooldown. Android account JVM tests (14), debug build and
instrumentation compilation pass; account device validation and controlled-server writes remain open.
The iOS account page also adopts the app's existing background/card design.

### 2026-10-19 combined account nodes

Favorites and Activity now project device plus active-account favorites/claims on both platforms.
Storage and removal memberships remain separate; account-only rows follow device order. Account
node names and shortened-key presentation are now implemented on Android. Account/settings/library
JVM suites pass 23 tests; debug build and instrumentation compilation pass. User-owned device checks
and controlled-server account acceptance remain open. The narrowed release scope is in plan.md.
