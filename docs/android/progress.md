# Android implementation progress

2026-09-21 — first foundation increment.

The user authorized deleting the earlier prototype and beginning implementation.
Its flat `Analyzer.kt`, `NodeViewModel.kt` and UI implementation were removed and
replaced. Build wrapper, app icon and independently useful repository plumbing
were retained. No iOS application source changed.

## Implemented in this increment

| Area | Evidence / current limit |
|---|---|
| Application structure | `app/`, `core/model`, `core/network`, `core/storage`, `core/design`, feature packages; explicit app container |
| Source setup | Onboarding, community registry, HTTPS validation, DataStore; bundled JSON shared from repository |
| Analyzer sessions | Coroutine cancellation and clean state on source/region change; same-source refresh recovery; optional map/region configuration |
| Native shell | Five typed top-level routes; phone navigation bar and expanded navigation rail; details/source routes |
| Appearance | Persisted system/light/dark choices with Material controls |
| Map feasibility code | MapLibre Native/OpenGL + CARTO vector styles, clustering/tap handling, debug synthetic route replay; locally configured restricted key |
| Reference/tests | Synthetic node fixture, protocol/geometry tests, source-switch/refresh tests, HTTP status/cancellation tests, Compose screen/navigation tests |

## Still open

Phase 0 remains open: map performance at scale and satellite/hybrid provider choice,
iOS visual reference capture, reviewed phone/tablet screen designs, and broader
packet/socket/crypto fixture coverage. Phase 1 is started, not complete: persistent
response caching, external deep-link routing, full process-death/rotation testing,
expanded detail panes and further error-state review remain. Later parity phases
have not been marked complete.

Map/Explore can load real analyzer nodes, but the remaining destinations explicitly
say they are unavailable. No fake analyzer traffic appears in normal screens.
The debug-only map lab is labeled synthetic and cannot be mistaken for live data.

## Validation

Verified on 2026-09-21:

- Debug APK builds successfully.
- 13 JVM tests pass: host normalization, encoded identifiers, optional/unknown
  JSON data, coordinates, route gaps/date-line interpolation, source/region
  cancellation, refresh recovery and HTTP cancellation/status handling.
- 5 Compose UI tests pass on the Galaxy S24 Ultra Android 17/API 37 emulator:
  community/custom source forms, appearance selection, node search, and restoring
  tab/detail navigation.
- Android lint: 0 errors, 19 warnings (dependency/target updates and presentation).
- `git diff --check` passes. No iOS source changes, commits or pushes.

The instrumentation dependencies were updated for Android 17 and aligned with the
app classpath. A non-exported debug-only test activity wakes the headless test
display; production activity behavior is unchanged. CI compiles these tests;
the connected test run above was local.

CARTO live rendering is verified below. Minimum-OS, physical-device,
expanded-window, process-death and complete parity validation remain outstanding.
This successful foundation pass does not close those gates.

## CARTO migration — 2026-09-22

- Replaced Google Maps dependencies and manifest credentials with MapLibre Native
  13.6.1 (OpenGL) and CARTO Voyager/Positron/Dark Matter vector basemaps.
- Key stored only in ignored local properties; build environment override supported.
  CARTO HTTPS requests include the key and installed app package/signing SHA-1.
  Unit tests verify host boundaries, nested resources and non-CARTO isolation.
- Native map lifecycle, saved camera, clustering, node identity selection, route
  overlays/replay and zoom controls retained. CARTO/OSM attribution links remain visible.
- Satellite/hybrid imagery remains a parity gap requiring a separate provider.
- Debug build and lint pass (0 errors, 19 warnings). All 16 JVM tests and 6 emulator
  tests pass, including live CARTO rendering for each style with overlays.
  The expanded live smoke test also passes cluster expansion and node-tap identity
  checks; its captured map image was visually inspected.
- The initial Vulkan backend failed on the emulator; the OpenGL artifact passed.
  Physical-device and minimum-OS coverage are still outstanding.

## Live packets and visual alignment — 2026-09-22

The user requested live traffic and a UI closer to the iOS client. The prior
Android foundation only loaded node snapshots, which explains the absent packets.

- Added a single shared foreground WebSocket stream at the analyzer root, HTTPS
  recent-packet seeding, observer lookup, ping checks, bounded buffering, and
  reconnect/backoff. A successful reconnect reconciles recent history. Collector
  cancellation closes the socket; source/region changes start an isolated feed.
- Accept current REST-shaped and older decoded-header/path packet frames. Ignore
  heartbeats, channel messages, malformed frames and unknown envelope types.
- Live Packets is accessible from Map and Explore, with explicit LIVE/RECENT
  labels, type filters, connection state and a return-to-latest action. Retain
  distinct observer/path observations and cap the feed at 200 entries.
- Map animates known live route segments, preserves unresolved gaps and declines
  ambiguous prefix matches. Trails expire after 12 seconds, with up to 40 recent
  observations; animation respects the lifecycle and disabled system animations.
- Applied the iOS blue/slate visual system, compact map tools, role colors, a
  Network at a Glance card, grouped settings and improved node rows. Material
  controls/navigation remain native Android. Automatic map styling follows the
  selected light/dark theme unless the user explicitly selects a map style.

Validation: debug and instrumentation builds pass; 24 JVM tests pass (including
actual WebSocket upgrade, frame filtering, reconnection, cancellation, history
merge, bounded state, regional filtering and route ambiguity). Seven routine UI
checks pass. A separate opt-in emulator test received real MeshTexas packets,
loaded 1,288 nodes and 62 observers during the run, verified visible node/cluster
geometry, and opened Map, Explore and Live Packets in light/dark appearances.
Captured screenshots were visually inspected in `android/app/build/reports/previews/`.
Lint: 0 errors, 21 warnings. No commits/pushes or iOS application changes.

Still open: full iOS parity, persisted packet history/favorites, packet detail and
manual replay workflows, channel/decryption and observer screens, satellite imagery,
physical/minimum-OS device coverage and performance at higher traffic rates.

## Route motion and lighter UI — 2026-09-22

- Replaced the 50 ms animation timer with native Choreographer display callbacks.
  Receipt times are anchored to elapsed realtime, so wall-clock adjustments do not
  change in-flight progress. Incoming packets no longer restart the animation job.
- Cache route geometry and projected segment lengths. Particles move at constant
  Mercator distance along the rendered line, including across unequal hops and
  the date line. Static lines update only when the active route collection changes;
  small particle GeoJSON updates use MapLibre's synchronous mode. The loop stops
  requesting frames when there are no active routes and pauses with the lifecycle.
- Reduced line/marker weight and cluster size; removed the heavy map card border.
  Explore now uses an open metric layout, borderless node rows and a filled search
  field. Neutral surfaces and quieter role icons reduce the blue-on-blue chrome.
  Native navigation, touch targets and accessible control labels are retained.

Physical-device frame pacing and high-traffic profiling remain to be measured;
frame scheduling changes alone do not establish a sustained frame-rate guarantee.

Validation: debug APK and instrumentation APK build; all 26 JVM tests and eight
emulator UI tests pass, including the opt-in real-network test. Captured and
reviewed dark Map, Explore, Live Packets and light Map screens with actual traffic.
Lint remains at 0 errors and 21 warnings. The first live UI run exposed a Compose
idling timeout; moving native map scheduling to Choreographer resolved it on the
full rerun. No physical-device smoothness claim is made from these functional tests.

## iOS layout and Explore behavior review — 2026-09-22

Compared the Android implementation directly with iOS MapScreen, ExploreScreen,
and RootTabView. This pass corrects information placement rather than treating
visual styling alone as parity:

- Removed the live packet preview/navigation card from Map. Packet route animation
  remains on Map; the feed opens from Explore, matching the iOS information layout.
- Map owns its compact Live Map/status toolbar with refresh, search and map style.
  Region and role filters sit together at the top left of the map. The bottom
  shows a small node-count pill, zoom controls and required map attribution.
- Explore is now a scrollable dashboard and saved-item home, with search and add
  actions, a four-metric summary, an empty-state guide, Favorite Nodes and Recently
  Viewed. Node browsing lives in a native bottom sheet rather than below the
  dashboard at all times. Counts describe loaded/recent data rather than claiming
  iOS active-window statistics that have not been implemented.
- Added persisted, analyzer-scoped favorite-node snapshots and the last 20 unique
  viewed nodes. Save/remove works in node details and Explore; recent history can
  be cleared independently. Snapshots let saved node details open even when the
  current regional result does not contain the node.

Still not full iOS parity: channel/observer browsing and their favorites, global
cross-entity search, richer node health/analytics, dashboard customization/export,
current-location control, activity/observer map filters, individual packet detail
and replay, satellite imagery, and source-specific persisted camera state.
Native Android navigation and controls remain deliberate platform adaptations.

Validation: both APKs build; 29 JVM tests and 10 distinct emulator UI tests pass
across the full and targeted runs. Tests cover no packet entry on Map, search
sheet navigation, add/remove favorites, recent-history clearing, persistence and
analyzer isolation, saved-coordinate map centering, and actual live traffic.
Dark Map/Explore screenshots were inspected. Lint reports 0 errors, 22 warnings
(the additional warning suggests the optional SharedPreferences KTX helper).

## Clustered route hops and region framing — 2026-09-22

- Active routes now have an unclustered marker overlay for their resolved nodes
  and real observer endpoints. Known-node markers retain public-key identity and
  take tap priority over nearby cluster bubbles. Overlay geometry follows the
  same 12-second lifetime as the route; the normal clustered layer stays intact.
  Route geometry and markers share identity resolution, including explicit null
  gaps and ambiguous-prefix rejection.
- Load the optional `/api/iata-coords` directory alongside analyzer configuration.
  Region selection frames its center/radius (45 km when omitted, matching iOS's
  normal framing). Older analyzers fall back to their region-filtered node bounds.
  All regions restores analyzer defaults; ordinary data refreshes preserve panning.
- Validation: both APKs build, all 32 JVM tests pass, and both map instrumentation
  tests pass. New checks cover a tappable route node while clustered, marker
  expiry, DFW selection and return to defaults, optional directory failure and
  ambiguous-hop marker rejection. Lint: 0 errors, 22 warnings.

## Unset GPS positions — 2026-09-22

Match iOS by treating the exact GPS pair (0, 0), including signed zero, as an
unset position for nodes and observers. These records remain browsable but do not
create markers, route anchors, observer endpoints, or node-based camera targets.
An unset hop breaks the route rather than connecting its neighbors. Map counts
now include only nodes with usable positions. A single zero axis remains valid;
explicit map configuration centers are unaffected. Debug build and all 34 JVM
tests pass, including route gaps and zero-axis regression cases.

## Outlier-resistant region camera — 2026-09-22

Region directory centers/radii already avoid node-coordinate outliers. When that
directory is unavailable, fallback camera bounds now use a regional majority:
median-centered geodesic distances with a median-absolute-deviation threshold and
50 km margin. Fewer than three samples are retained; exclusion requires a strict
majority. Nodes and routes are unchanged, and explicitly opening a node still
uses its reported position. No Texas-specific geographic boundary is applied.
Debug build and 38 JVM tests pass, including the reported Gulf coordinate,
small/equal groups, colocated samples, and date-line-aware outlier selection.

## Channels and Observers — 2026-09-23

Implemented by Claude Code; map work remains with Codex. Covers parity rows C01–C04 and
O01–O02 except the items listed as open below.

- Channels (C01–C03): server list scoped by the shared region; search; source (all,
  device, server), one-hour activity and recent/message-count/name sorting with Public
  first, as on iOS. Conversations are read-only, oldest-to-newest with alternating
  speakers, web links, heard/hop/SNR chips and region filtering through observer names.
  A burst of live GRP_TXT packets triggers one refresh after a 1 s quiet period.
- Monitoring (C03–C04): hashtag, 32-hex private key and locally generated key. The list
  is sealed with a non-exportable Keystore AES-GCM key, never enters instance state,
  logs or requests, and is excluded from backup/transfer; an unreadable store is
  reported with a re-add prompt. Copying a generated key is explicit and marked sensitive.
- Crypto: Kotlin port of iOS `ChannelCrypto`, verified byte-for-byte against three live
  analyzer-decrypted packets before synthetic shared vectors were generated.
- Observers (O01–O02): list with region, 15-minute activity, hardware model and
  recent/hourly/total/name sorting; missing battery, noise and counts stay unknown.
  Details show identity/telemetry and Canvas charts with text summaries for TalkBack.

Deviations from iOS found while checking the live API, both fixed on Android only:

- iOS requests `/api/packets?payloadType=5`; CoreScope ignores that name and filters
  on `type=5`, so iOS's 1,000-packet window is mostly non-channel traffic.
- iOS derives the Public key as SHA-256("Public"), channel hash 29. Live Public traffic
  uses MeshCore's fixed PSK `8b3387e9…`, hash 17. Android uses the fixed key. iOS still
  shows Public messages that the analyzer already decrypted.

Still open: channel/observer favorites and recents, "View packet"/replay from messages
(needs packet detail P02), observer CSV/JSON export (X03), persistent response caching,
deep links, and emulator/device UI coverage for these screens.

Validation: debug, instrumentation and lint builds pass; 60 JVM tests pass, including
crypto vectors, tampered-MAC rejection, packet parsing, filter/sort semantics, loader
source isolation and sealed storage. Lint: 0 errors, 23 warnings. The one new warning is
the SharedPreferences KTX hint that `NodeLibrary` already has. No emulator or device
runs were made for this increment. No commits or iOS changes.

## Lighter node clustering — 2026-09-23

Clustering was much heavier than iOS: a 50 px radius, 2-node clusters, and clusters
kept up to zoom 14. It now follows the iOS rules: 30 px radius, at least 3 nodes, and
individual nodes from zoom 10 (iOS stops clustering below a 0.8° span, about zoom 9 on
a phone). Debug, unit and instrumentation builds pass. The map UI tests' 3-node sample
still clusters at zoom 7 and separates at zoom 14, but they were not rerun on a device.

## iOS 0.7.2 route animation and co-located nodes — 2026-09-23

- Live routes follow iOS 0.7.2 (`130fc69`). Hops within a resolved subchain travel one
  at a time (0.66 s each) and draw progressively: a 6 dp halo at 18% opacity under a
  2 dp core at 85%, with a small white head. An arrival ring expands at each hop end
  (0.8 s); finished hops hold for 0.45 s and fade over 0.55 s. Isolated positions get a
  3 s double ring. Only the 20 newest routes animate. The old 4 s whole-route dot and
  12 s static lines are gone, and temporary route markers last as long as their route.
  With system animations off, hops appear complete and nothing moves.
- Co-located nodes follow iOS `f6a7d46`. Nodes sharing an exact position fan out on a
  circle about 18 dp in radius, larger for groups over six, recomputed when zoom settles.
  Taps choose the nearest marker. Route lines still end at the true position.
- Tests: timing, fade, subchains, pulses, reduced motion, the transient cap and
  zoom-independent spread distance. The route-marker UI test uses a three-hop route so
  its tap fits within the shorter marker lifetime; not rerun on a device.
- Node names (iOS `130fc69`): a label layer below each marker appears only when the view
  is at most 0.08° tall and holds at most 60 nodes. It uses the CARTO style's Montserrat
  glyph stack with a theme-matched halo; colliding labels are hidden. Emoji in names do
  not render with map glyph fonts.
- Recent history (iOS `processHistoricalPackets`): REST history packets under 12 s old
  appear as already-arrived, complete routes that fade together over 12 s from 0.66 s
  before their timestamp. Travel is not replayed and isolated points do not pulse. This
  relies on the device and analyzer clocks roughly agreeing, as on iOS.

## Map density and header spacing — 2026-09-23

From device feedback on a San Antonio view: clustering was still too heavy at zoom 9 and
bubbles too large. Clusters now need 3+ nodes within 24 px, stop from zoom 9, and are
24 dp across (was 32 dp; iOS uses 26 pt). The map's own top bar no longer adds a second
status-bar inset on top of the app shell's Scaffold, which caused the empty band above
"Live Map". Builds pass; not rechecked on a device.

## Region camera framing — 2026-09-23

Device feedback: selecting a region zoomed to the wrong place, and "All regions" did not show
all of Texas. Checked against live MeshTexas data:

- `/api/iata-coords` has centers for only 2 of the 12 configured regions (AUS, DFW). The
  fallback framed each region's node list, but those are nodes *heard by* the region's
  observers. CRP framed Austin/San Antonio, AMA framed Lubbock, TXK framed about 7°×12°,
  and ACT/ELP/MFE/SJT have no nodes, so the camera did not move.
- Fix: region codes are IATA codes. `assets/iata-airports.csv` (9,054 codes from
  OurAirports, public domain) supplies centers for regions the analyzer omits; analyzer
  data still wins. iOS geocodes airports with MKLocalSearch instead. Node framing
  remains only for codes the table lacks.
- "All regions" now frames, in order: the source registry viewport (`mapCenter`,
  `mapRadiusKm`; newly decoded on Android, as on iOS), all configured regions (MeshTexas:
  El Paso–Texarkana, McAllen–Amarillo), the analyzer map default, then any node.
  Bounds leave room for the top controls and bottom count/attribution.
- Tests use the real bundled table and the live region list. Not rechecked on a device.

## Node details (N01) — 2026-09-23

Replaced the basic node view with the iOS `NodeDetailScreen` layout (`feature/nodes/`):
identity card with role icon/color, relative last-seen, full public key, first/last seen
dates and actions (save, show on map, copy key, share `nodescope://node/<key>` as on iOS);
Mesh reach (neighbors, two-way links, observers, window); Health (transmissions, today,
avg SNR, observations, avg hops); Heard by (opens observer details); Links (opens linked
node details) and Known paths, each showing 3 rows with a "Show all" expander.

- Health, reach and paths load independently; the page shows an error only when all
  three fail. The health response supplies the node when it isn't in the current region's
  results, so linked nodes outside the region open correctly.
- Tests use synthetic fixtures (`node-health/paths/reach.json`). A one-off check also
  decoded 75/75 real MeshTexas responses (25 nodes × 3 sections); that data was not kept.
- `FoundationUiTest` updated for the new public-key layout. It also still expected the old
  Channels placeholder text, which the Channels work changed; now fixed. Not rerun on a device.
- Not yet ported: recording nodes opened via links as recents.

## Node analytics (N02) — 2026-09-23

`feature/nodes/NodeAnalyticsScreen.kt`, opened from a "Node analytics" card on node details
(iOS layout): 24h/7d/30d/1y range; identity header; At a glance (availability, signal
grade, packets/day, relayed %, observers, longest silence, with iOS duration formatting);
Activity (time-scaled area chart, so sparse buckets keep real gaps); Activity by time
(7×24 UTC heatmap with legend and a TalkBack summary of the busiest slot); Signal (SNR/RSSI
toggle when RSSI exists, one line per observer plus a small legend iOS hides); Packet
types; Hop count; Observer coverage (opens observer details); Peer interactions.

- HTTP 404 shows "Analytics unavailable", as on iOS; other failures show retry. Each
  request is keyed by range, so a late response for a previous range cannot replace the
  current one. Charts moved to `core/design/Charts.kt` and gained `TimeChart`.
- `parseInstant` now also accepts offset timestamps (`timeRange` uses `-05:00`), which
  `Instant.parse` rejects on older Android.
- Tests: synthetic `node-analytics.json`, 404/500 handling, range URL, signal series,
  heatmap and formatting. A one-off check decoded 32/32 real responses (8 nodes × 4 ranges),
  with every timestamp parsed; that data was not kept. Not checked on a device.
- Not ported: CSV/JSON export (X03).
- Follow-up: Activity and Signal first spanned the whole requested range (e.g. a full week),
  so recent-only data looked like a sliver of mostly empty history. Both apps request the
  same data; Swift Charts fits its time axis to the data. Android now does the same, with
  an hour of padding for a single timestamp and labels chosen from the span shown.
- Signal redesign (deliberate deviation from iOS): one line per observer was unreadable on
  busy nodes (21 observers, hundreds of samples, lines jumping across gaps). The card now
  shows the median across all observers per time slice (15 min–1 week, at most 48 slices)
  with the middle 50% shaded. Missing slices break the line rather than bridging it. Below
  it, observers are ranked by median with a 10th–90th percentile bar on a shared scale and
  their reading counts (top 5, expandable). iOS draws per-observer lines and would benefit
  from the same change.
- Signal, second revision (user direction: plain language first, numbers for advanced
  users): the mixed across-observer trend line is gone because the set of listeners changes
  over time. The card leads with a summary ("Heard by 21 observers · 14 strong · …" plus
  the best link and its margin). Each observer row shows a quality label, median SNR in
  dB, typical range and reading count ("few readings" under 5), and a bar on a shared
  scale marked with the decode limit. Tapping a row shows median, typical range
  (10th–90th percentile), min/max, margin above the decode limit, median RSSI, readings,
  last heard, and that observer's own trend. The SNR/RSSI toggle is removed.
- Quality = median SNR minus the decode limit for the observer's own spreading factor
  (from `radio`, e.g. `910.525,62.5,7,5`; Semtech limit −7.5 dB at SF7, −2.5 dB per SF).
  If an observer's SF is unknown, the analyzer's most common SF is used; with no radio
  data, only numbers are shown. Bands: near limit < 5 dB margin, weak 5–10, good 10–15,
  strong 15+. On 60 MeshTexas repeaters (7d) most observers are strong, with a realistic
  tail of good/weak/near-limit links. SNR saturates near +12 dB for close observers, so
  RSSI in the details is what separates the strongest links.
- Signal, third revision: the per-observer trend sliced sparse data (e.g. 23 readings into
  2-hour slices), which fragmented it, and auto-scaled to the observer's own range, which
  made <1 dB of noise look dramatic. It now plots every reading as a dot on the card's
  shared scale, over faint quality zones with a dashed decode-limit line, colored by each
  reading's quality. The slice-banding code and `BandChart` were removed.

## Explore color cues — 2026-09-23

Matched iOS `ExploreScreen`: Network at a Glance tiles use the iOS metric colors (nodes green,
observers blue, packets orange, SNR purple) for the value, with a detail line under each.
Tappable tiles get a faint tint of their color and a chevron beside the label; Average SNR
is informational, as on iOS (it previously opened the packet feed). The Observers tile now
opens the Observers tab, as on iOS. Light-theme tones are darkened for contrast on white.
Favorite and recent node rows show the role icon in white on a circle of the role's map
color. Build and lint pass; not checked on a device.

## Live Packets aligned with iOS — 2026-09-23

Rebuilt the feed on iOS `PacketFeedScreen`:
- Observations of the same packet (same hash within 30 s of the first) are one
  transmission. Rows show a payload-type icon, name and left accent (adverts green,
  messages blue, trace/path orange), a hop badge, "heard N×" badge, region badge, a preview
  (message text, advert name, channel, else observer) and relative time.
- Header: "Listening for live traffic" status, scope, transmission and observation counts.
  Filter sheet: shared region control plus every packet type.
- Deliberate Android difference: history-only transmissions carry a small "Recent" tag so
  they are never presented as live. The per-row LIVE label is gone.
- New Packet details screen: type, hash, payload version, observations, received time,
  region; payload; every observation (observer, SNR, RSSI, hops); route hop by hop with
  node names from the loaded map data; raw hex; Replay on map (M05). Replay uses iOS timing
  (0.85 s per hop, one at a time, 0.75 s hold, 0.65 s fade together), frames the route,
  and is disabled when the route's nodes aren't in the current region's map data.
- `LivePacket` gained payload text/name/channel, payload version and raw hex; the type
  table gained GRP_DATA, MULTIPART and RAW_CUSTOM.
- Tests: grouping window, hashless packets, type filter, region lookup, counts, preview
  priority, replay route selection, subchain breaks, replay timing and payload parsing.
  `PacketUiTest`/`LiveNetworkUiTest` updated for the new UI; not run on a device.
- Replay mode (iOS `isReplayMode`): "Replay on map" enters a mode that pauses live traffic
  and shows two pills above the node count. "Replay" (while the route is traveling) or
  "Play again" replays in place, and "Live" exits. "Route N of M" appears when several
  routes were resolved, next to "Route only" (default on), which shows only the route's
  nodes. New replays frame the route above the controls; switching routes re-frames only
  when the new route is off screen. Changing region leaves replay mode. The frame loop now
  wakes for a replay scheduled slightly ahead instead of waiting for another redraw.
- Map bottom layout (device feedback, second pass): node count centered just above the
  MapLibre logo, whose bounds are measured after layout (it is the wide ImageView MapLibre
  adds to the MapView; there is no public reference), falling back to the bottom left; OpenStreetMap/CARTO attribution on one line at the bottom right; zoom
  just above the attribution; replay pills centered near the bottom, inset 72 dp on each
  side so they stay centered and clear of the zoom column.
- Region switching (device feedback): the map was wrapped in `key(region)`, so each region
  change built a new MapView. It showed the default world camera while nodes loaded and lost
  a manually chosen map style. The wrapper is removed; the map detects region changes itself,
  frames the new region immediately from the analyzer's already-loaded region coordinates
  (without stale nodes), and animates the move. Camera, style and filters now persist
  across regions.

## Channel message packets (C02/P02) — 2026-09-23

Chat bubbles now have "View packet" (iOS `ChatBubbleRow`) opening a Packet screen modeled on
iOS `PacketDetailScreen`, loaded from `/api/packets/{hash}`: the message (sender/text passed
from the conversation, because locally decrypted messages are still encrypted on the
analyzer); packet type and hash with observation/hop/SNR tiles; route selection with the
resolved path by node name and Replay on map (same replay mode as Live Packets); observers
with region, RSSI and SNR (3 shown, expandable); and collapsible decoded JSON/raw hex. Its
menu copies the hash or shares `nodescope://packet/<hash>`, and the channel menu gained
"Share channel link" (`nodescope://channel/<identifier>`), both matching iOS. Route
de-duplication is shared with Live Packets (`distinctRoutes`). Tests use a synthetic
`packet-detail.json`; a one-off check decoded 15/15 real message packets (13 with
replayable routes); that data was not kept. Not checked on a device.
Still missing from iOS Channels: channel favorites and recents (Explore), and opening
links in an in-app browser (Android opens the system browser).
- Known issue, deferred by the user (both platforms): replay route lists drop unresolved
  (null) hops before de-duplicating, so `X → (unresolved) → Y` replays as a direct X→Y line.
  The live map breaks the line at an unresolved hop instead. Planned fix: split replay
  routes at nulls on iOS and Android together.

## Observer packet types as a donut — 2026-09-23

Observer details now show packet types as a donut like iOS (`SectorMark`, 58% inner radius,
small gaps) and the CoreScope site: total in the center, legend with each type's count and
share (a tiny slice reads "<1%", never "0%"). `DonutChart` lives in `core/design/Charts.kt`.
Node analytics keeps its bar chart, matching iOS.

## Settings aligned with iOS (S01–S03) — 2026-09-23

Settings rebuilt on iOS `SettingsScreen`: header with analyzer status; Appearance; Analyzer
source; Connection (live status, session error) with Analyzer diagnostics; Storage; About &
how to use.
- Source picker (iOS `AnalyzerSourcePickerScreen`): the default source is starred and the
  current one checked; from Settings a tap applies immediately, a custom host has its own
  action; onboarding keeps choose-then-Continue. After a change, a banner reports
  Connecting → Connected / Couldn't connect (8 s budget), surviving the shell rebuild via
  `AppViewModel.sourceChangedAt`.
- Diagnostics (iOS `AnalyzerDiagnosticsService`): the same five read-only probes, levels and
  messages (HTTPS connection, API compatibility, per-capability timing/404/auth/format),
  live stream status, re-run. Android network errors map to the iOS wording.
- Storage: measures what Android actually caches (MapLibre ambient tile cache via
  `FileSource.getResourcesCachePath` plus the app cache directory) and clears them
  (`OfflineManager.clearAmbientCache`), then refreshes analyzer data. Favorites, recents,
  channel keys and preferences are never touched.
- About: icon, version (name and code), what it is, how to use (including Explore and Live
  Packets), project links, GPLv3 license; plus an Android map-data credit section (CARTO,
  OpenStreetMap, MapLibre, OurAirports).
- Support development (iOS `SupportDevelopmentScreen`): Google Play Billing Library 9.1.0
  (`billing-ktx`), products `com.btdev.nodescope.tip.coffee/lunch/dinner` (same IDs as iOS),
  in-app one-time products consumed after purchase so tips can repeat and unlock nothing.
  Pending purchases enabled; completed tips left unconsumed are finished on the next load.
  Messages mirror iOS (thank you, pending approval, unavailable, retry). No server receipt
  check (no entitlement), as on iOS. Package stays `org.nodescope.android`.
- Tests: diagnostics against MockWebServer and classification cases; settings-mode source
  tap; `FoundationUiTest` updated. UI tests not run on a device.

## Explore aligned with iOS (E01–E03) — 2026-09-23

- Library (`NodeLibrary`, per analyzer): favorites for nodes, observers and channels (newest
  first, reorderable within their kind), recently viewed nodes/observers/channels/message
  packets (20 kept, 10 shown, removable individually), and 10 recent searches. The old
  node-only format migrates on first load; saved node snapshots refresh from the analyzer.
- Explore covers the entire network (iOS behavior), loading all nodes, observers, channels
  and the latest 1,000 packets itself. Dashboard uses the iOS definitions: active nodes and
  observers online within 15 minutes, packets and average SNR in the last hour ("1000+" when
  the sample is saturated; MeshTexas currently is). Metrics can be hidden/reordered per
  analyzer and exported as CSV/JSON through the system file picker (same fields as iOS).
  Favorites summary shows active favorites and a per-kind breakdown and scrolls to them.
- Global search (iOS `GlobalSearchSheet`): channels (monitored first), nodes, observers and
  packet hashes, 20 per section, favorite stars inline, recent searches with remove/clear.
- Empty state gains Browse Channels / Browse Observers. "Observers online" opens Observers
  filtered to active ones; "Active nodes" opens the Map (no map activity filter yet).
- Channel and observer pages gained a favorite star; observers gained Share link
  (`nodescope://observer/<id>`). Observers, channels, message packets and nodes opened from
  links are recorded as recents.
- Tests: library (kinds, order, bounds, migration, searches), dashboard windows, favorites
  summary, search, preferences, exports. `FoundationUiTest` updated; not run on a device.

## Deep links (X02) — 2026-09-23

- `nodescope://node|observer|channel|packet/<identifier>` (iOS `NodeScopeDeepLink` format)
  registered on `MainActivity` (singleTop, VIEW/BROWSABLE). `NodeScopeLink` parses
  case-insensitively, decodes the identifier (multi-segment, `%23`, and an unencoded `#`
  that arrives as a fragment) and builds links for every share button, so all four encode
  identically; built links round-trip.
- Cold and warm launches: `AppViewModel.pendingLink` holds the link until the shell exists,
  so it waits through onboarding, then waits for navigation's first destination. Tabs match
  iOS: node and packet → Explore, observer → Observers, channel → Channels.
- Difference from iOS: iOS opens an item only if its already-loaded list contains it and
  otherwise does nothing. Android opens the detail screen directly (each loads by
  identifier) and says when the item isn't on the selected analyzer, since links carry no
  source. The packet 404 now reads "This packet isn't on <host>…".
- Tests: parser/builder cases and round trips; a UI test that a pending link opens node
  details and is marked handled (not run on a device). Also: split the manifest's link-filter
  data tags and used `SharedPreferences.edit {}` (lint 23 → 21 warnings).

## Disk caching — 2026-09-23

- `ResponseCache` (iOS `APIResponseCache`/`MapResponseCache`): last successful response
  bodies under `cacheDir/api-responses`, one file per full URL (SHA-256 name), written
  atomically, pruned past 7 days at launch; Settings → Clear cache already empties it.
- Repositories read through a `BodySource`; network reads save, and `saved(maxAge) { … }`
  re-runs the same parsing against saved bodies only. Lifetimes follow iOS: lists and map
  configuration 7 days; node sections, node/observer analytics and packet details 24 hours.
- `KeyedLoader` shows the restored value (with its real `updatedAt`) while downloading; a
  restore younger than the loader's max age skips the download; pull to refresh still
  downloads. Offline, `LoadStatus` shows "Showing the last loaded data from …".
- The map session emits the saved snapshot for the selected analyzer/region first.
- Not cached, as on iOS: channels, channel messages and the channel packet feed (memory
  only). Also not cached: the live feed's recent packets, so offline map history is empty.
- Tests: expiry and URL keying, save-then-restore without the network, channels never
  saved, loader restore/replace/offline/fresh-skip, session offline snapshot.

## Map filters and route details — 2026-09-23

- `MapFilters` (iOS `MapNodeFilterSelection`): activity (any / 15 min / hour / 24 h / 7 days),
  multi-select roles (repeater, room, companion, sensor) and a route observer. Saved per
  analyzer (nothing stored while unfiltered); the observer clears on region change or when
  it isn't among the region's observers once they load. Activity windows re-check every minute.
- Activity and roles choose markers and the node count; the observer only limits live and
  recent routes. Filter button reads "N active" when filtering; the sheet has Reset all and
  a searchable observer picker (name, ID, region).
- Explore's "Active nodes" now opens the map with the 15-minute filter (iOS `.activeNodes`).
- Route details (iOS `MapRouteDetailsSheet`): tapping a live or recent route line opens
  summary (hops, age, observer, SNR, RSSI, packet), message text, and each stop; resolved
  nodes open node details, unresolved hops show their prefix (never guessed), the observer
  is last. Share sends the iOS share text. Tap order: a marker directly under the finger,
  then a cluster, then a route, then the nearest marker. Replay routes have no details.
- Tests: filter matching, count, observer options, route stops and share text.

## Map framing for other analyzers — 2026-09-24

- Bug: switching analyzer never reframed (only first load / region / focus did), and
  "All regions" unioned every region center. Colorado's analyzer publishes a center only for
  DEN; unlabeled `RNB`/`YQB` resolved via the bundled airport table to Sweden and Quebec, so
  the union spanned the Atlantic (a world view). Selecting `RNB` framed Sweden.
- `regionCenters` sets aside region centers far from the analyzer's other regions (same
  median/MAD test as node framing). A selected outlier region frames its nodes instead.
  The all-regions union is used only if it contains the analyzer's `/api/config/map`
  center; otherwise that center (and zoom) is used — the center iOS relies on.
- The map reframes (instantly) whenever the analyzer changes; region snapshots kept for
  framing are per analyzer, and the map only receives a snapshot for the selected host.
- iOS for comparison: all regions → registry viewport, else `/api/config/map` center with a
  250 km radius; a region → analyzer IATA center, else an MKLocalSearch airport lookup.
- Checked against live configs for all five registry analyzers (TX, SoCal, Gulf, Comchan,
  Colorado): each frames its own area; outliers dropped: RDU (Gulf, Comchan), RNB/YQB (CO).

## Support link replaces tips — 2026-09-24

- Settings → Support development ("Buy me a coffee") now opens
  https://buymeacoffee.com/anieto in the browser (open-in-new icon instead of a chevron).
- Removed Google Play Billing: `TipStore.kt`, the tip screen and route, `TipOutcomeTest`,
  the `billing-ktx` dependency and the unused title string. The Play Console tip products
  (`com.btdev.nodescope.tip.*`) are no longer used by the app.
- iOS is unchanged (still StoreKit tips).

## Units setting (Android + iOS) — 2026-09-24

- Settings → Units: Imperial (mi) / Metric (km), default Imperial, on both platforms
  (Android DataStore `distance_unit`, provided as `LocalDistanceUnit`; iOS
  `@AppStorage("distanceUnit")`).
- Shared rule (`formatDistance` / `DistanceUnit.format`): one decimal mi or km; under
  0.1 mi shows feet, under 1 km shows meters, rounded to 10. The only distance either app
  shows today is the neighbor link distance on Node Details, which now uses it.
- Tests: `DistanceFormatTest` (Android) and `DistanceFormatTests` (iOS, same cases).
  iOS built with build-for-testing only; the pbxproj entries were added by hand.

## Node analytics packet types as a donut (Android + iOS) — 2026-09-24

- Node Analytics → Packet types now uses the same donut as an observer's packet types on
  both platforms (was ranked bars). Android reuses `DonutChart` with the same ordering,
  labels and description; the now-unused `RankedBars` was removed. iOS reuses the
  observer's `PacketTypeDatum` (made internal), so types read "Channel Msg"/"Direct Msg"
  instead of raw `GRP_TXT`/`TXT_MSG`, and uses the same `SectorMark` styling.

## Community source logos (Android + iOS) — 2026-09-24

- Registry entries gain an optional `icon`: a path inside `CommunitySources/`
  (`icons/<id>.png`, 256×256 PNG). Both apps accept only `icons/<name>.png`, so the registry
  can't point phones at other servers. The source pickers show the logo (rounded square),
  falling back to the previous star/antenna symbol.
- Sources: MeshTexas (user-supplied art, cropped to the Texas mark), WCMesh (crop of
  wcmesh.com's social image; no square asset exists), Gulf Coast (analyzer branding logo,
  same art as their site), Comchan (comchan.net icon, 400 px analyzer copy), Colorado
  (coloradomesh.org icon; their analyzer uses a different generic logo).
- Android: the whole registry folder is bundled as assets, so all icons work offline; new
  ones download once from raw.githubusercontent and are kept in the cache directory
  (`SourceIcons`). iOS: MeshTexas is bundled (`SourceIcon-meshtexas` asset); others load via
  `AsyncImage` from the registry. Tests: path rule on both, registry icons exist (Android).
- Follow-up: Android now bundles only the default source, matching iOS
  (`app/src/main/assets/us-sources.json` = iOS `Resources/us-sources.json`, plus
  `assets/icons/meshtexas.png`); the `CommunitySources` asset srcDir was removed, so delisted
  sources never appear from the bundle. `meshtexas.png` quantized to a 256-colour palette
  (96 KB → 32 KB, visually identical). Test: the two bundled registries and icons match.
