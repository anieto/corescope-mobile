# iOS → Android feature parity

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
