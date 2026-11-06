# NodeScope Roadmap

NodeScope should remain a fast, native, read-only mesh observability tool. New
features should make live and historical network behavior easier to understand
without reproducing every desktop or operator control from CoreScope.

Every feature and release closeout must be verified on iPhone, iPhone Duo and iPad,
including navigation, safe areas, adaptive layout, popovers/sheets, and toolbar
content in portrait and landscape where applicable, and on Android phones,
tablets, and foldables once Android has the feature.

## Completed foundation — 0.2.0 through 0.7.0

- Native live map, animated packet routes, route details, and packet replay.
- Channels, locally monitored channel keys, observers, and observer analytics.
- Region-aware filtering across the primary browsing experiences.
- Global search, favorites, recently viewed items, and quick actions.
- Explore dashboard with configurable network summary cards and exports.
- Live packet feed with grouped observations and packet details.
- Analyzer diagnostics, capability checks, cache controls, and storage reporting.
- Shareable deep links for nodes, observers, channels, and packets.
- Liquid Glass interface updates, accessibility improvements, and reliability work.
- Node Analytics with selectable time ranges, health and signal metrics, charts,
  observer coverage, peer interactions, exports, and graceful capability checks.

## 0.8.0 — Beacon support (complete)

NodeScope now works with Beacon servers as well as CoreScope, without changing
the CoreScope experience. Implementation is tracked in [the Beacon plan](docs/beacon/plan.md).
Release marked complete on 2026-10-15; iOS and Android releases are pending
approval from Apple and Google, respectively.

- Phase 1: a backend abstraction so every screen talks to a CoreScope or Beacon
  adapter, with no CoreScope behavior change.
- Phase 2: Beacon detection, community sources with a server type, and browsing
  of the map, nodes, observers, packets, packet details, routes, and the live feed.
- Phase 3: Beacon neighbors, node analytics derived from observation history,
  observer activity and telemetry, server-decrypted channels with database-reset
  recovery, and expanded diagnostics. Implemented on iOS and Android; live
  acceptance passed on three servers. The release is complete; the
  [phase 3 tasks](docs/beacon/phase3-tasks.md) retain the implementation checklist.
- Beacon screens reuse the CoreScope design. Data Beacon can't provide is shown
  as unavailable or partial with an explanation, never as zero.

## 0.9.0 — My Nodes and network awareness (complete)

Marked complete on 2026-10-20. Final [store notes](docs/my-nodes/release.md) retain validation
and submission follow-ups.

Detailed scope, proposed behavior, milestones, and acceptance criteria are in the
[0.9.0 implementation plan](docs/my-nodes/plan.md). Implementation started on
2026-10-16 with the backend/account contract audit; see
[implementation progress](docs/my-nodes/implementation.md).

Make Favorites and Activity useful for everyday checks, with reliable optional CoreScope
account sync on iOS and Android. The approved 2026-10-19 scope in the implementation plan
supersedes the original broader analytics dashboard.

- Show device favorites and account nodes together, preserving separate memberships and ordering.
- Keep Activity focused on readable names, last heard, freshness, cached/error states and detail navigation.
- Validate account sync, switching, deletion/reset, expiry, offline behavior and sign-out.
- Finish Android parity and focused accessibility, adaptive-layout and performance checks.
- Complete backend regression, beta smoke tests and release preparation.

Defer the Changes summary, dashboard signal/availability/coverage/telemetry, live Activity updates,
extra drill-down controls and dashboard exports. Detailed Node Analytics and its exports remain
available. Background notifications remain out of scope.

## 0.10 — Node Planner

Help people evaluate proposed mesh deployments before installing hardware. The normal map shows
where deployed nodes are; Node Planner estimates what a new node at a candidate site might reach.

Detailed product behavior, analysis contracts, architecture, milestones, tests and release gates
are in the [Node Planner plan](docs/node-planner/plan.md). The previous Field Tools plan and
implementation record are retained as prototype history, not the release contract.

Node Planner ships in steps. Its full scope:

- Create locally saved deployment plans with one or more candidate sites.
- Configure explicit radio, modem, antenna, cable, receiver and installation assumptions.
- Use audited elevation data and a reviewed propagation engine for terrain-aware analysis.
- Analyze point-to-point paths to known nodes with terrain profile, line of sight, Fresnel context,
  predicted receive level, link margin and visible provenance.
- Include a secondary **Link Profile** tool for analyzing any two nodes or map points, built on the
  same terrain and RF foundation as planned potential-link analysis. Use one persistent map for
  selecting both endpoints, nearby known-node context, configuration and synchronized results.
- Rank **Potential Links** without equating proximity with reachability.
- Render clearly labeled **Estimated Coverage** that cannot be confused with observed network data.
- Compare candidate sites or configurations and export an assumption-rich planning summary.
- Keep plans local, location optional and foreground-only, and cached/offline limitations explicit.
- Deliver matching calculations and terminology across iOS and Android with adaptive layouts for
  iPhone, iPhone Duo, iPad and Android foldables.

### 0.10.1 — Link Profile (first public release)

0.10.0 builds (iOS 7, Android versionCode 18) were made but never submitted; 0.10.1 is the first
public release of the 0.10 work. Builds: iOS 0.10.1 (8), Android 0.10.1 (versionCode 19), archived
and built on 2026-10-30 from `6b8bd79`. Store copy is in the
[0.10.1 release notes](docs/node-planner/v0.10.1-release.md).

- **Link Profile:** terrain between two points, line of sight, Fresnel zone clearance and an
  estimated link margin from adjustable radio and antenna settings. Touch & hold the map or a known
  node to place each point (Move A/B here, pin options, Undo); the analyzed path is a rounded
  dashed line colored by clearance.
- **Find a node:** distance and compass heading to any node or observer with a location.
- CoreScope region groups in the region filter, the optional background texture, better iPad and
  dual-screen layouts, and quicker live feed reconnects.
- iOS fixes: live map route and region menus no longer jump while scrolling; returning to live
  after a replay keeps the map view; on iPad, the map tab is placed correctly on first launch.
- Not yet device-tested at release time: Android touch & hold.

### 0.10.2 — Learn layer and refresh

Builds: iOS 0.10.2 (1), Android 0.10.2 (versionCode 20), from `5480e54` on 2026-10-31. Store copy,
which covers all of 0.10, is in the [0.10.2 release notes](docs/node-planner/v0.10.2-release.md).

- **Learn layer:** an ⓘ beside terms newcomers meet (hops, SNR/RSSI, observers, node roles,
  channels, Fresnel zone, link margin…) opens a short plain-language explanation, with a Glossary
  under Settings for the full list. The text lives once in `glossary.json`, shared by both apps.
  Settings → Display → Term Explanations hides the buttons.
- **Typography:** Monaspace Neon for titles, names, identifiers and scanned values; body text stays
  in the system font. The rule is in `AGENTS.md`.
- Settings grouped into Display, Analyzer, and Help & Support; more contrast between cards and the
  page (iOS); Explore shows three Recently Viewed items until expanded.
- Fixes: Android map no longer freezes when a foldable folds or unfolds (MapLibre TextureView
  mode); on iPhone Duo, Live Packets fills the right screen on iOS 27.1, map controls keep clear of
  the crease when half folded, and the node count stays on the left screen.

### 0.10.3 — Live map options (beta testing)

Built on `feature/live-map-options` for both apps; merge to `main` once testers have tried it.

- **Realistic Propagation** (Settings → Live Map): holds each packet's observations for the
  analyzer's propagation buffer (`propagationBufferMs` from `/api/config/client`, 0.5–30 s, 5 s
  when not published), then animates all of its paths together, like CoreScope's "Realistic".
- **Group Nearby Nodes** (Settings → Live Map): off draws every node on its own. iOS keeps it fast
  by redrawing markers only when they change and drawing one dot where several would overlap
  when zoomed out; tapping such a spot zooms in. iOS also fans out nearby nodes close in, with
  dashed lines back to where they are.
- **Replays:** All Routes, now every replay's default (packet details' route picker offers it too,
  chosen by default), plays every route together, leaving a short trail; each observer stays on its shortest route. Hops run at a steady 90 km/s
  (0.45–1.5 s), bow slightly, and observers are ringed markers named only once zoomed in. A
  replay waits for the map to frame it, then a short lead-in; it pauses while the map is panned.
- **Live traffic:** each observer's first copy only (no echoes), no guessing of hops the analyzer
  left unresolved (dashed bridges join the pieces instead), hop timing by real distance, slight
  curves; on iOS traffic to grouped nodes is drawn to their bubble.
- **Pause Live** in the live packets panel holds the list and the map's traffic; new packets are
  counted, and the map's title shows Paused (tap to resume).
- **Map Filters** regrouped into Nodes Shown, Node Roles and Routes, with a **Map Key** opened from
  Filters or the map's ⓘ.
- **Channels:** with a region chosen, previews show the latest message heard in that region (the
  rule the conversation uses), not the network-wide latest. Android: region groups now filter
  conversations.
- iOS fixes: ⓘ explanations open above or below and are no longer clipped; the iPhone Live Packets
  sheet closes from the trailing corner; Settings has a Live Map card.
- Builds: iOS 0.10.3 (2), Android 0.10.3 (versionCode 22). The numbers skip past 0.10.3 (1) and
  versionCode 21, which the Candidate Sites branch used.
- Not ported to Android (it groups far less, and its maps hide colliding labels): drawing traffic
  to group bubbles, and fanning nearby nodes with lines back to where they are.

### 0.10.4 — Estimated Coverage and saved planning work (in progress)

- **Direction revised 2026-11-02:** Estimated Coverage is a standalone peer of Link Profile:
  select a point, enter radio/antenna and receiver assumptions, and calculate a terrain-aware
  map with known nearby nodes. Green means predicted above threshold, red below threshold;
  unknown, stale and outside-analysis nodes are explicitly distinct. No saved plan is required.
- **Saved Plans:** optional saving/review from both tools; candidate sites are locations within
  plans. Extend the site-only schema with typed studies and a tested migration after the unsaved
  coverage flow works. Pause further Candidate Sites polish. See the authoritative
  [product and delivery contract](docs/node-planner/estimated-coverage.md).
- **Coverage implementation:** native tile readers and geographic sampling now compile on both
  platforms; 18 shared geographic fixtures cover seams, date-line handling and missing terrain.
  Cache identity and bounded preflight now match shared reference vectors on both platforms.
  Decoded-tile caches now support locked ownership, verified publication, pinned readers and
  offline reuse. Bounded gzip decoding now passes 22 shared fixtures and cache integration tests.
  Cancellable downloads and serialized terrain preparation now connect decode/cache publication,
  offline reads, bounded retries and pipeline disk-budget checks. Confirmed corrupt payloads can
  now be repaired online; owned workspaces recover abandoned download/decode files. Radius/halo
  tile selection and source validation now feed prepared geographic mosaics. Provider discovery,
  RF/scheduler/UI integration remain next. Geographic crossing discovery now feeds terrain-backed
  profiles, preserving cell crossings/interior peaks with bounded work and explicit failures. Native
  convergence now enforces stable losses, threshold guards and cumulative per-path budgets; the
  production RF engine remains unbundled pending packaging/distribution resolution. Explicit
  radio link-budget arithmetic now matches shared fixtures on both platforms. Known-node status
  resolution now checks snapshot identity and run tokens, retaining neutral/stale states without
  leaking obsolete margins. A convergence adapter now preserves threshold guards and verifies
  budget-consistent margins before status resolution. Node runs now freeze catalogs, disclose
  processing exclusions, reject late results and discard cancelled partial work; map/catalog
  integration remains outstanding. Exact-node orchestration now derives paths and connects profile
  callbacks to convergence/status, skipping unsupported or out-of-radius work. Prepared mosaics
  now bind directly to this evaluator with cancellation and reader-ownership tests. A serialized
  node coordinator shares terrain/geometry/model budgets across the batch and withholds partial
  runs on cancellation. Native area layouts now preserve selected spacing/refinement bounds and
  reject oversized grids. Area-work preflight now checks minimum per-path and aggregate budgets
  with 64-bit totals. Eligible grid samples now map to geographic terrain paths, including date-line
  wrapping. Native area execution now preserves sample masks and unresolved states under shared
  budgets. A combined prepared-terrain operation now reserves node work independently and returns
  area/node results together. Execution-token publication guards now reject superseded and
  cancelled completions; production RF binding, screen wiring and rendering remain pending. See the
  [native integration report](docs/node-planner/native-terrain-integration.md). Regional JSON catalog
  decoding now validates pinned records and resolves required study tiles on both platforms;
  bounded cancellable HTTPS catalog transport is also implemented. A configured trusted catalog
  publisher and production-provider qualification remain outstanding. A reproducible eight-tile
  Canadian Rockies study catalog now exercises real metadata selection and download/disk preflight
  in both native test suites; it is not a deployed service or global coverage.
- **Coverage setup UI:** Estimated Coverage now opens beside Link Profile on iOS and Android.
  It supports confirmed map placement, manual coordinates, explicit radio/receiver inputs,
  radius/detail selection and neutral known-node markers/list. New terms have shared glossary
  entries. Nearby-node selection now ranks the complete available catalog by transmitter distance
  before applying the 200-marker cap, updates on relocation, and clears without a valid location.
  Both editors now validate coordinate/radio fields, expose missing fields through Review inputs,
  and check radius/detail against the absolute work ceiling off the UI thread. These are preliminary
  checks, not a promise of convergence, terrain availability or a completed study. Native Web Mercator
  rasterization now maps completed samples to translucent reception tiles with unresolved hatching;
  PNG tile encoding is implemented on both platforms. Map-view adapters and on-device rendering
  qualification are still pending.
  Calculate remains disabled: provider discovery, production RF binding, worker wiring
  and result overlays are not connected. Setups are not yet saved.
- **Release gate:** coverage integration and physical-device qualification remain incomplete.
  Do not ship the site library as a substitute for coverage or silently relax model fidelity.
- **Candidate Sites progress:** merged the 0.10.2 baseline from `main`; development release
  versions remain 0.10.2 until release preparation. Isolated terrain studies now include a bounded
  coverage scheduler, a download/cache contract, and Swift/Kotlin leased terrain readers. The
  readers pass host tests against 576,505 reference samples each; native cache/network preparation
  is now integrated, while full coverage execution and mobile validation remain outstanding. See the [reader study](docs/node-planner/native-terrain-reader-study.md).
- **Candidate Sites data model:** Swift and Kotlin now share a versioned plan/site contract and
  30 validation fixtures. Radio and installation assumptions remain unspecified until entered;
  atomic save/reopen now protects unreadable libraries and detects changed files. The plan/site
  editor is connected on both platforms, with manual coordinates, site duplication and glossary
  explanations. Confirmed map placement/relocation, on-demand foreground location and preferred
  height units are connected on both platforms; device permission and layout checks remain.
- **iPad map layout:** replace the Map tab's `NavigationSplitView` with an app-drawn two-pane
  layout (as the Duo already does), then remove the 0.10.1 launch workaround
  (`RootTabView.settleIPadMapLayout()`). On first launch iPadOS doesn't merge the top tab bar into
  the split view's bar, which pushed both panes 64 pt down until the tab was revisited.
- Builds: iOS 0.10.4, Android versionCode 23 or later (the branch's 0.10.3 numbers were passed to the
  live map release). The live map branch was merged in on 2026-11-06.

Still deferred: comparing/exporting plans, multi-transmitter coverage and advanced receiver overrides.
Saving Link Profile studies belongs in the shared Saved Plans collection, not a separate library.

### Node preview card (planned)

Tapping a node on the map opens a small preview instead of going straight to the full node page, so
a quick look ("who is this, when was it last heard") doesn't take the user off the map or interrupt a
replay. Its own small branch, separate from the live map options.

- Shows name, role colour and short key; last heard with a fresh/stale badge; battery and SNR/RSSI
  when known; region; and whether it's a favourite.
- Actions: **Details** (the full node page, as today), **Favourite**, **Center on Map**.
- iPhone: a short sheet that can be pulled up to the full page. iPad and iPhone Duo: a popover
  anchored to the node. Android to match.

## 1.0.0 — Investigation depth and product maturity

Complete the core read-only investigation workflow and prepare NodeScope for a
stable long-term public release.

- Expand packet details with raw hex, decoded fields, propagation timing, and a
  clearer per-observer observation timeline.
- Add grouped and observation-level packet views where they improve diagnosis.
- Add useful packet filters such as time range, multiple observers, packet type,
  and favorites/My Nodes.
- Add a node advert timeline where analyzer data supports it.
- Make cached nodes, packets, routes, and analytics deliberately browsable as an
  offline investigation workflow with clear snapshot timestamps.
- Complete analyzer-version compatibility testing and graceful degradation for
  unsupported endpoints.
- Complete large-network, long-running live-feed, offline-cache, accessibility,
  and performance regression passes.
- Establish localization infrastructure before translating the interface.
- Finalize onboarding, privacy documentation, App Store materials, and the 1.0
  release checklist.

## Deliberately deferred

These may be revisited after 1.0 if user demand justifies them.

- Full historical map playback with continuous VCR-style controls and multiple
  playback speeds.
- Network-wide observer comparison, topology, common-relay, route-pattern, and
  distance/range workspaces.
- Spotlight indexing and App Shortcuts.
- Favorite-node activity or stale-state background notifications.
- Saved network-view presets.
- Field survey breadcrumb recording.
- Route image generation.
- Locally monitored (on-phone decrypted) channels on Beacon sources. Blocked until
  Beacon can list channel payloads without one request per packet.

## Out of scope

These CoreScope features do not fit NodeScope's focused, read-only mobile role.

- MQTT broker, ingestion, retention, and server administration.
- Operator/API-key-protected write operations or packet injection. Optional
  personal account-selection sync is in scope for 0.9.0.
- Theme designers, Matrix mode, and other desktop visual-effect modes.
- Desktop-style resizable packet tables.
- Uploading mobile RF telemetry or location tracks.

## Android implementation

Android ships alongside iOS on Google Play as a native Kotlin/Compose app
following the [Android parity plan](docs/android/README.md). Features land on iOS
first and are ported to Android before the next feature starts; Beacon phases 2
and 3 are at parity. See [implementation progress](docs/android/progress.md).

## Current priority

0.10.2 (Learn layer, Monaspace Neon, Settings refresh, foldable fixes) is uploaded to the App Store
and Google Play. 0.10.3 (live map options) is in beta testing; next is 0.10.4: standalone Estimated
Coverage, optional shared Saved Plans, and the separately tracked iPad two-pane map layout. Android touch &
hold still needs a device test.
