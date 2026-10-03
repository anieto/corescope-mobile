# NodeScope Roadmap

NodeScope should remain a fast, native, read-only mesh observability tool. New
features should make live and historical network behavior easier to understand
without reproducing every desktop or operator control from CoreScope.

Every feature and release closeout must be verified on both iPhone and iPad,
including navigation, safe areas, adaptive layout, popovers/sheets, and toolbar
content in portrait and landscape where applicable.

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

## 0.8.0 — My Nodes and network awareness

Turn favorites and Node Analytics into a useful daily health view without
requiring users to inspect every node individually.

- Add a My Nodes dashboard for favorite nodes with last-heard state,
  availability, signal trend, observer coverage, and available telemetry.
- Summarize meaningful changes since the previous visit, including nodes newly
  active or silent, observer availability changes, and significant signal shifts.
- Add direct drill-down from health and change summaries into analytics, packets,
  routes, observers, and the map.
- Surface analyzer health, live-feed state, capability support, and data freshness
  so network changes are not confused with stale or unavailable analyzer data.
- Keep alerts in-app for this release; defer background notifications until user
  demand justifies their lifecycle and delivery complexity.
- Preserve analyzer scoping, cached-first loading, exports, Dynamic Type, and
  VoiceOver throughout the experience.

## 0.9.0 — Field investigation

Make NodeScope useful while deploying, locating, or troubleshooting physical
mesh equipment without turning the phone into a source of RF telemetry.

- Add an optional Field Mode from the map and node details rather than another
  permanent primary tab.
- Show nearby nodes and observers relative to the current location or a manually
  selected reference point.
- Show distance, bearing, last-heard state, recent signal quality, observer
  coverage, coordinate age, GPS accuracy, and network-data freshness.
- Allow one node or observer to be pinned as the active target with compact
  compass-style guidance and direct access to its analytics, packets, and routes.
- Add investigation snapshots that package the relevant packet, route, nodes,
  observers, timestamps, and analyzer source for sharing or export.
- Support public-key QR scanning and presentation for quick node lookup and
  favoriting in the field.
- Keep location use foreground-only, on-device, and optional. Do not upload or
  persist the user's location history, and clearly distinguish stale reported
  coordinates from a device's current physical location.
- Treat lightweight historical context around a selected incident as optional;
  do not require continuous map playback for the initial Field Mode.

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
- Field survey breadcrumb recording and coverage heatmaps.
- Route image generation.

## Out of scope

These CoreScope features do not fit NodeScope's focused, read-only mobile role.

- MQTT broker, ingestion, retention, and server administration.
- API-key-protected write operations or packet injection.
- Theme designers, Matrix mode, and other desktop visual-effect modes.
- Desktop-style resizable packet tables.
- Uploading mobile RF telemetry or location tracks.

## Android implementation

Android development is active alongside iOS. The initial prototype was replaced
with a native Kotlin/Compose foundation following the [Android parity plan](docs/android/README.md).
See [implementation progress](docs/android/progress.md) for completed work and
remaining map, lifecycle, feature-parity and device-validation gates.

## Current priority

Build 0.8.0 My Nodes and network awareness on the completed Node Analytics
foundation. Prioritize a concise answer to what changed, whether favorite nodes
are healthy, and whether the configured analyzer data is current and complete.
