# Field Tools Milestone A findings

Status: in progress. Last updated 2026-10-22.

This is the evidence record for Milestone A. It separates facts observed in the repository from
assumptions that still require a controlled backend fixture or a product decision.

## Contract audit

### Pinned evidence

- CoreScope release `v3.14.0`, commit `9dbc287579a237ffa744dd0c91fa7227d09763ac`, with sanitized
  node, observer, analytics and live payloads in `shared/fixtures/`. The live fixture preserves
  heartbeat, missing-decoded, `observer_id`, observation-count and nullable resolved-hop behavior.
- Beacon minimum supported server `2.0.3`, with sanitized HTTP and WebSocket fixtures in
  `shared/fixtures/beacon/`. The matching released API contract is beacon-docs commit
  `9d1dae3bf989`; the newer audited contract is commit `541bc238a9d6b7c527d1362d47417446d91cb6e9`.
- Beacon 2.0.5 compatibility was previously measured for the contract additions used by NodeScope;
  Field Tools does not promote a newer-only field into its required contract.

These are repository-pinned compatibility fixtures, not a claim that every configured public source
runs the same build today.

| Source/entity | Position | Activity evidence | Position timestamp | Field Tools rule |
|---|---|---|---|---|
| CoreScope node | `lat`/`lon` in the node model | `lastSeen`, `lastHeard`, and first-seen/advert metadata | Not present in the observed model | Activity may be labeled; position age is **Unknown** |
| CoreScope observer | `lat`/`lon` in the observer model | `lastSeen`, status and telemetry timestamps; response server time | Not present in the observed model | Activity may be labeled; position age is **Unknown** |
| Beacon node | Browse/live latitude and longitude; live transport distinguishes unchanged, clear and coordinate | Region `lastHeard` and browse/live activity | No explicit coordinate-update timestamp | Region activity must not be presented as coordinate age |
| Beacon observer | Observer latitude and longitude | Observation `heardAt`, observer activity and `lastStatusAt` | No explicit coordinate-update timestamp | Activity may be labeled; position age is **Unknown** |

Beacon's in-memory merge ordering can associate an accepted position with the advert that delivered
it. That protects against stale live updates, but it is not a backend position timestamp and must
not be exported or displayed as one.

Coordinate rules for the neutral layer:

- Reject non-finite values and latitude/longitude outside their legal ranges.
- Treat an analyzer-provided zero/zero pair as unavailable at the backend projection boundary when
  that backend uses it as a missing-position sentinel.
- Keep zero/zero valid in pure geometry and manual input because it is a real coordinate.
- Never substitute entity activity, fetch time or live-message receipt time for coordinate time.
- Preserve missing coordinates in searchable catalogs, but exclude them from distance ordering.

## Identity and request boundaries

Every field entity identity must include the analyzer source session, entity kind and backend key.
A public key or observer ID alone is not globally unique enough for selection, caching or late-result
acceptance. Source/region changes cancel field work and reject results from the prior session.

The first request budget is deliberately simple:

1. Reuse cached node and observer catalogs immediately.
2. Refresh each base catalog no more than once per explicit or eligible foreground refresh.
3. Perform geometry, search, filtering and ordering locally; no row may initiate network work.
4. Load analytics, packets or routes only for the pinned target, with coalescing and cancellation.
5. Reuse the existing live transport; Field Tools must not create a second socket.

### Audited cache and request budget

Both apps already isolate cached HTTP data by analyzer and endpoint, retain list/catalog responses
for up to seven days as stale fallback, cancel/reject work when the source session changes and merge
all visible live consumers into one connection. iOS coalesces identical `APIResponseCache` refreshes;
Android keys response bodies by backend and full URL. Both live stores bound packets to 200 and
Beacon metadata to 1,000 entries.

The Field Tools v1 budget is now fixed in `FieldRequestBudget` on both platforms:

- do not automatically refresh a catalog more often than every 30 seconds;
- retain last successful catalogs for seven days as clearly labeled fallback;
- reuse pinned evidence for five minutes unless the person explicitly refreshes;
- permit at most one logical node collection and one logical observer collection per catalog refresh;
- CoreScope therefore uses at most two catalog HTTP requests per refresh;
- Beacon collection reuses the shared live recovery and its existing hard ceiling of 20 pages per
  entity kind. Reaching the ceiling is an error, never a silently complete or partial catalog;
- no per-row requests, no second WebSocket and only the pinned target may trigger detail evidence.

The iOS map node cache is currently private to `MapViewModel`, while observer data uses the general
response cache. Field Tools must not copy those private cache files or create another competing
catalog owner. The first Milestone B implementation task is therefore a shared field catalog
repository that can accept an already-loaded map snapshot, consume shared Beacon recovery state and
become the single owner of Field Tools refresh/coalescing. Android must use its existing full-URL
response cache and shared `LiveFeed` state under the same logical budget.

## Platform and design inventory

The iOS target is iOS 18, Swift 6, and supports iPhone and iPad. The Android app compiles against
API 36 and supports API 26 and later.

The target uses `LaunchScreen.storyboard`, the iPad orientation declaration includes all four
orientations and the app does not opt out of resizing. These project-level prerequisites are ready
for iPhone Duo and other resizable iOS 27 scenes.

Field Tools will reuse these existing presentation contracts:

- `AdaptiveListDetailNavigation` for compact/split navigation.
- `DuoTwoPaneLayout` and `observesDuoHinge` for hinge-aware reflow, not width heuristics.
- `NodeScopeBackground`, `instrumentCard`, semantic signal/activity/health colors and existing
  loading/error/empty-state treatments.
- Existing 44-point floating glass map controls and native menus.
- Existing Map, Explore and detail routes; Field Tools remains a full-screen destination rather
  than a sixth root tab.

The destination owns its session state. Shared repositories may be injected, but a sensor-driven
field session will not be placed in the global environment unless a later milestone proves that
multiple simultaneous destinations require it. This follows the current SwiftUI data-flow rule of
keeping observable state at its narrowest useful lifetime.

## Location and scanner availability

- iOS can use Core Location's async live-update sequence for a foreground-only session. The existing
  one-shot map helper is insufficient because it drops accuracy and timestamp.
- Camera scanning can use `DataScannerViewController` where supported and available, with explicit
  authorization, availability handling and start/stop lifecycle. A non-camera paste/type path is
  required.
- Android already has platform location plumbing and ZXing generation. Scanner selection and its
  permission/lifecycle integration remain open; no scanner dependency has been selected yet.
- Neither platform needs background location for the approved scope.

## Propagation feasibility and licensing

Meshtastic's site planner is useful architecture evidence: it runs terrain-aware calculations in a
client, processes cancellable radial chunks and uses numerical golden tests. Its repository is GPLv3,
so its implementation cannot be copied into NodeScope without an explicit compatible licensing
decision. The engine choice remains open pending a separately licensed implementation, clean-room
work, or legal approval.

The planner references the public AWS Terrain Tiles dataset. Before provider selection, Milestone A
must record the exact dataset license, required attribution, resolution, cache/storage cost and
offline behavior. An available public bucket is not, by itself, a completed product/license review.

Primary references:

- [Meshtastic site planner architecture](https://github.com/meshtastic/meshtastic-site-planner/blob/main/ARCHITECTURE.md)
- [Meshtastic site planner GPLv3 license](https://github.com/meshtastic/meshtastic-site-planner/blob/main/LICENSE)
- [AWS Terrain Tiles registry entry](https://registry.opendata.aws/terrain-tiles/)

## Implemented shared contracts

`geometry-v1.json` fixes the shared Earth radius, Haversine distance, true initial bearing, date-line
behavior, coincident-point behavior and 16-point cardinal mapping. iOS and Android implementations
and tests use the same values. A coincident point has zero distance and no meaningful bearing.

This geometry is guidance, not RF direction finding. Heading-relative UI will be a separate transform
that incorporates device heading accuracy and never changes the true bearing stored in evidence.

`identity-qr-v1.json` fixes source-qualified identity encoding and the accepted Field QR surface.
Persistent identities include backend, canonical analyzer origin, entity kind and normalized backend
key; the ephemeral source-session UUID remains a cancellation guard and is never exported. QR input
is limited to 2,048 UTF-8 bytes and accepts only a full 64-hex public key, an exact NodeScope
node/observer link or an exact MeshCore contact link. Unsupported destinations, duplicate query
fields, arbitrary URLs and source configuration are rejected.

`snapshot-v1.json` fixes the initial nearby snapshot envelope, including source, target timestamps,
true geometry, completeness and limitations. Reference coordinates are absent by default and are
encoded only after explicit per-export opt-in. Decoders tolerate unknown additive fields but reject
unknown schema versions. Evidence and planner-specific nested records can evolve additively without
changing the privacy default.

## Remaining Milestone A gates

- Measure the worst observed Beacon catalog page counts and bytes on representative sources; the
  existing 20-page safety ceiling is not a performance target.
- Complete the remaining catalog, pinned-evidence and planning projections around the implemented
  source-qualified identity, snapshot v1 and QR v1 contracts.
- Produce and review the six component-based adaptive layouts using existing UI primitives.
- Measure catalog/map and 30-minute foreground sensor performance on representative devices.
- Select scanner architecture and propagation/terrain licensing approach.
- Validate MeshCore radio presets and regional constraints before shipping defaults.
