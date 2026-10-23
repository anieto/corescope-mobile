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

## 0.10.0 — Field Tools

Make NodeScope useful while deploying, locating, or troubleshooting physical
mesh equipment without turning the phone into a source of RF telemetry.

Detailed product behavior, architecture, milestones, tests and release gates are in the
[0.10.0 implementation plan](docs/field/plan.md). Track observed contracts and progress in
[the implementation record](docs/field/implementation.md).

- Add a dedicated full-screen Field Tools workspace from Map, Explore and contextual
  detail actions rather than another permanent primary tab. Use **Nearby** for deployed
  equipment and **Plan** for proposed sites.
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
- Allow a proposed-site pin with MeshCore radio, antenna, receiver and installation
  assumptions; estimate terrain-aware coverage and point-to-point links.
- Rank analyzer-known nodes and observers that may receive the proposed site, while
  clearly separating modeled predictions from observed network evidence.
- Keep location use foreground-only, on-device, and optional. Do not upload or
  persist the user's location history, and clearly distinguish stale reported
  coordinates from a device's current physical location.
- Treat lightweight historical context around a selected incident as optional;
  do not require continuous map playback for Field Tools.

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

Prepare the next milestone, 0.10.0 Field Tools. Version 0.9.0 feature work is complete;
store distribution and recorded validation follow-ups remain in its release notes.
