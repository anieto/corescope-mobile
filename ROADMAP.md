# NodeScope Roadmap

NodeScope should remain a fast, native, read-only mesh observability tool. New
features should make live and historical network behavior easier to understand
without reproducing every desktop or operator control from CoreScope.

## Completed foundation — 0.2.0 through 0.6.0

- Native live map, animated packet routes, route details, and packet replay.
- Channels, locally monitored channel keys, observers, and observer analytics.
- Region-aware filtering across the primary browsing experiences.
- Global search, favorites, recently viewed items, and quick actions.
- Explore dashboard with configurable network summary cards and exports.
- Live packet feed with grouped observations and packet details.
- Analyzer diagnostics, capability checks, cache controls, and storage reporting.
- Shareable deep links for nodes, observers, channels, and packets.
- Liquid Glass interface updates, accessibility improvements, and reliability work.

The completed 0.6.0 work will ship as part of 0.7.0 rather than as a separate
App Store release.

## 0.7.0 — Node intelligence

Make individual nodes substantially easier to evaluate over time.

- Add a native Node Analytics screen from Node Details.
- Support 24-hour, 7-day, 30-day, and all-time ranges where the analyzer permits.
- Show availability, signal grade, packets per day, relay percentage, observer
  count, and longest silence.
- Add activity, SNR/RSSI, packet-type, and hop-count charts.
- Add an observer-coverage ranking and peer-interaction summary.
- Handle older analyzers with clear capability detection and an informative
  unavailable state.
- Preserve pull-to-refresh, cached-data indicators, exports, Dynamic Type, and
  VoiceOver support throughout the new experience.

## 0.8.0 — Historical map playback

Extend the live map from individual packet replay into historical exploration.

- Add a map time-window selector for recent historical traffic.
- Add VCR-style play, pause, scrub, restart, and playback-speed controls.
- Replay a continuous sequence of transmissions rather than only one packet.
- Clearly distinguish Live, Paused, Replaying, and Historical states.
- Preserve region, node-role, activity-age, and observer filters during playback.
- Suspend expensive animation work when the app is backgrounded or Reduce Motion
  is enabled.
- Keep controls compact on iPhone and out of the way of the floating tab dock.

## 0.9.0 — Network analysis and comparison

Add a curated subset of CoreScope's network-wide analytics instead of copying
its full desktop analytics workspace.

- Add observer comparison for coverage, packet rate, unique nodes, signal
  quality, and recent availability.
- Add focused network views for topology, common relays, route patterns, and
  distance/range.
- Add time-range and region controls shared by these analyses.
- Add drill-down links from charts and rankings into nodes, observers, routes,
  and the map.
- Add hash-collision information to Analyzer Diagnostics for operators, without
  making it a primary consumer-facing screen.
- Export useful comparisons and summaries as CSV or JSON.

## 1.0.0 — Investigation tools and product maturity

Complete the core read-only investigation workflow and prepare NodeScope for a
stable long-term public release.

- Expand packet details with raw hex, decoded fields, propagation timing, and a
  clearer per-observer observation timeline.
- Add grouped and observation-level packet views where they improve diagnosis.
- Add useful packet filters such as time range, multiple observers, packet type,
  and favorites/My Nodes.
- Add a node advert timeline and optional public-key QR presentation.
- Complete analyzer-version compatibility testing and graceful degradation for
  unsupported endpoints.
- Complete large-network, long-running live-feed, offline-cache, accessibility,
  and performance regression passes.
- Establish localization infrastructure before translating the interface.
- Finalize onboarding, privacy documentation, App Store materials, and the 1.0
  release checklist.

## Deliberately deferred

These may be revisited after 1.0 if user demand justifies them.

- Spotlight indexing and App Shortcuts.
- Favorite-node activity or stale-state notifications.
- Saved network-view presets.
- Route image generation.
- Android client.

## Out of scope

These CoreScope features do not fit NodeScope's focused, read-only mobile role.

- MQTT broker, ingestion, retention, and server administration.
- API-key-protected write operations or packet injection.
- Theme designers, Matrix mode, and other desktop visual-effect modes.
- Desktop-style resizable packet tables.
- Uploading mobile RF telemetry or location tracks.

## Current priority

Build 0.7.0 Node Analytics first. Treat `/api/nodes/:pubkey/analytics` as an
optional analyzer capability and design the screen to remain useful when only a
subset of analytics fields is available.
