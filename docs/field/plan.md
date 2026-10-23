# NodeScope 0.10.0 — Field Tools

Status: planned. Prepared: 2026-10-22. Predecessor: 0.9.0 feature work is complete.
See [implementation progress](implementation.md) for milestone status and recorded evidence.

## Product thesis

Field Tools should help someone answer five questions while deploying, locating or
troubleshooting mesh equipment:

1. What relevant equipment is near this reference point?
2. Which target should I check next, and where is its reported position?
3. How fresh and trustworthy are the position and network observations behind that answer?
4. What coverage and existing-node links might a proposed installation provide?
5. How can I preserve and share the evidence or plan without sharing my own location?

This is a read-only investigation workspace, not RF direction finding. The phone receives no
MeshCore radio data and must never imply that compass guidance follows a radio signal. Distance
and bearing are geometric calculations from analyzer-reported coordinates. Signal, coverage and
activity are separate network observations whose timestamps and limitations remain visible.

Success means a person can enter Field Tools, choose **Nearby** or **Plan**, understand the
reference and evidence quality, select or scan a target, walk toward its reported position, model a
proposed installation, compare predicted links with deployed equipment, and export a useful
snapshot without leaving the workspace. The feature remains useful with denied location, reduced
accuracy, stale analyzer data, partial backend capability and intermittent connectivity.

## Release scope and decisions

| Decision | 0.10.0 behavior |
|---|---|
| Name | **Field Tools** is the user-facing destination; field investigation remains the release theme |
| Activities | **Nearby** works with deployed equipment; **Plan** models proposed sites |
| Entry points | Map toolbar, Explore card, map context action and node/observer detail actions; QR scanner is also available within Field Tools |
| Presentation | A dedicated full-screen workspace in the existing navigation hierarchy, not a permanent sixth tab and not a temporary sheet |
| Reference | Current device location when explicitly enabled, or a manually selected map point; manual reference is fully supported without location permission |
| Target | One node or observer with a valid reported coordinate; a launch from detail starts with that entity pinned |
| Nearby set | Nodes and observers with valid coordinates on the selected analyzer source; the existing region filter may narrow the set but never silently changes it |
| Default ordering | Distance from the active reference, with stale/unknown activity visibly disclosed rather than removed |
| Guidance | Straight-line distance and initial true-north bearing to the target; a rotating compass presentation appears only when usable device heading exists |
| Refresh | Foreground-only location and heading updates during an active device-reference session; analyzer refresh is explicit plus conservative foreground refresh |
| Evidence | Position age, activity age, signal evidence, observer coverage, GPS accuracy and analyzer freshness are independent facts, never collapsed into a synthetic confidence score |
| Snapshot | Versioned JSON plus a human-readable share summary; excludes device location by default and cannot include location history |
| QR | Scan and present interoperable node identifiers using existing NodeScope and MeshCore contact formats; no new proprietary payload is required |
| Planning | Drop a proposed-site pin, configure radio/antenna/installation assumptions, estimate terrain-aware coverage and rank deployed nodes that may hear it |
| Persistence | Preserve only lightweight user choices such as last sort/filter if useful; do not restore a live field session, active target, device coordinate or heading after exit/relaunch |
| Platforms | Define shared calculations and fixtures first; implement and validate iOS, then Android parity before release closeout |
| Monetization | Preserve existing access rules; no new entitlement or paywall behavior |

Required for 0.10.0: device/manual reference, nearby list and map context, node and observer
targets, guidance, evidence quality, proposed-site parameters, point-to-point link analysis,
terrain-aware coverage, predicted receiver ranking, supporting-detail navigation, snapshots, QR
scan/presentation, privacy behavior, accessibility, adaptive layouts including iPhone Duo,
CoreScope/Beacon degradation, Android parity, and field-device validation.

Explicitly excluded: background location, breadcrumb recording, turn-by-turn navigation, RF
ranging, Bluetooth or radio ingestion, continuous historical map playback, route image generation,
cloud plan/snapshot storage, automatic node configuration, cross-source aggregation, and backend
write operations.

## User journeys

### 1. Start from the map

1. The person chooses **Field Tools** from the map toolbar.
2. **Nearby** initially uses the map center as a manual reference, so content appears before any
   permission prompt.
3. A reference control offers **Use My Location**, **Choose on Map**, and **Enter Coordinates**.
4. Choosing device location explains the foreground-only use immediately before the system prompt.
5. The nearby sheet/list shows distance, reported-position age and activity freshness. Nodes and
   observers can be filtered without losing the reference point.
6. Selecting a row previews the target; **Pin Target** enters guidance.

### 2. Start from node or observer detail

1. A **Guide to Node** or **Guide to Observer** action is available when the entity has a valid coordinate.
2. Field Tools opens in **Nearby** with that entity pinned and the map framed around the reference and target.
3. If location permission is absent, the screen uses a manual reference and offers device location
   without blocking the target's details.
4. If the entity lacks a coordinate, the detail action instead explains why guidance is unavailable
   and offers QR presentation/copy where applicable.

### 3. Follow a pinned target

1. The target header always names the target and labels its coordinate as analyzer-reported.
2. The primary guidance shows straight-line distance and true bearing. With a calibrated heading,
   the compass arrow rotates relative to the phone; without one, the numeric bearing remains.
3. Accuracy and freshness sit beside the values they qualify. Reduced/poor location accuracy,
   invalid heading and stale target coordinates produce visible states, not silent fallback.
4. Quick actions open target detail, analytics, recent packets and routes when supported. Returning
   restores the in-memory field session while the source remains unchanged.
5. The person can switch targets, change reference, refresh evidence or close Field Tools.

### 4. Plan a proposed deployment

1. The person chooses **Plan a Site Here** from the map, or opens **Plan** in Field Tools and drops
   a proposed-site pin.
2. They select a MeshCore radio/modem profile and enter transmit power, frequency, antenna gain,
   cable loss, antenna height above ground, receiver sensitivity/height and bounded environment
   assumptions. Defaults are labeled assumptions, not detected hardware settings.
3. NodeScope downloads/cache-checks required terrain tiles and runs the propagation model locally
   where the selected engine and device support it.
4. The map shows estimated coverage with a clearly labeled heatmap or contours. Prediction styling
   is visually distinct from observed network routes, activity and signal evidence.
5. Analyzer-known nodes and observers are sampled against the result and ranked as **Likely**,
   **Marginal**, **Unlikely**, or **Unknown**, with predicted receive level/link margin and the
   assumed receiver profile.
6. Selecting a candidate opens point-to-point terrain profile, line-of-sight/Fresnel context and
   supporting observed evidence without claiming that a predicted link will work.
7. The person can compare candidate sites or export a deployment-plan snapshot. A proposed pin is
   always a local draft and never appears as a deployed analyzer node.

### 5. Scan or show a node QR code

1. **Scan Node Code** requests camera access only after a short explanation.
2. Accept a `nodescope://node/<public-key>` link, a compatible `meshcore://contact/add` link, or a
   bare 64-character hexadecimal public key. Reject unrelated URLs and malformed keys locally.
3. Resolve the key against the selected source. A match can be opened, favorited or pinned when it
   has coordinates. A missing key can still be copied or searched after an explicit refresh.
4. **Show Node Code** presents the interoperable MeshCore contact code when name and role permit;
   otherwise present the NodeScope node link. Show the encoded destination as text and offer copy/share.
5. Never follow arbitrary scanned web URLs or change analyzer source from QR contents.

### 6. Capture a field snapshot

1. **Capture Snapshot** freezes the currently displayed evidence and records its timestamps.
2. A preview lists every included field and warns about stale, partial or unsupported evidence.
3. Device coordinate, location accuracy and heading are off by default. If the person explicitly
   elects to include the current reference coordinate, mark it as user-device or manual reference
   and make the disclosure prominent. Never include a location trail.
4. Share a concise localized text summary or export versioned JSON. Sharing is user initiated.

## Information architecture and interaction model

Field Tools is one full-screen destination with two activities, **Nearby** and **Plan**, and three
presentation regions:

- **Context bar:** selected analyzer, region scope, reference type, reference accuracy/age and data
  refresh state. Tapping it opens reference and source explanations.
- **Map or compass canvas:** reference, target, nearby entities and the line between reference and
  target. With a pinned target the compact compass can replace or overlay the map; neither implies
  RF direction finding.
- **Tools panel:** nearby results and target evidence in **Nearby**; site assumptions and candidate
  receiver results in **Plan**. Its collapsed state retains the most important active facts.

On compact iPhone and the iPhone Duo outer display, use a map/canvas with a resizable bottom
presentation that has deliberate compact and expanded states. Do not put essential actions
exclusively behind the smallest detent. On an expanded iPhone Duo, use a two-region layout with
tools/results leading and map/detail trailing; when width genuinely permits, **Plan** may use three
regions for parameters, map and link results. On iPad, use an adaptive two- or three-region layout.
Rotation, folding/unfolding, multitasking and window resizing must preserve the activity, reference,
target/proposed pin, parameters, results and map camera for the lifetime of the destination.

Choose layout from available container space and content fit, not device-model checks. A compact
layout must remain fully functional; unfolding is never required. Reflow must not restart a
simulation, discard edits or duplicate location/network work, and essential content must avoid any
system-reported obscured/fold region.

### Existing NodeScope design integration

Field Tools is a new workspace, not a new visual language. Implementation must begin by composing
the app's existing shared navigation and presentation components rather than recreating similar
controls inside the feature:

- Use `AdaptiveListDetailNavigation`, `observesDuoHinge` and `DuoTwoPaneLayout` for the established
  compact/iPad/iPhone Duo transition and pane ordering. Extend these shared primitives only when a
  reviewed three-region planning layout genuinely requires it.
- Use `NodeScopeBackground`, `instrumentCard`, `nodeScopeFloatingGlass` and
  `nodeScopeSelectedGlass` for backgrounds, information cards, floating map controls and selection.
- Use existing `NodeScopeStyle` semantic colors: signal blue for primary network data/selection,
  activity amber for freshness or attention, and healthy green only for supported positive state.
  Prediction intensity needs a compatible map scale, but it must not redefine these meanings.
- Match the current Map toolbar/header, 44-point controls, map-style menu, annotation selection,
  region filter and data-status presentation. Field-specific actions are additions to those patterns,
  not a parallel control system.
- Match Node, Observer, Packet and Analytics screens for identity headers, metric tiles, disclosure
  rows, section spacing, export actions, loading indicators, cached/partial/error notices and
  `ContentUnavailableView` states.
- Use the existing tab and navigation behavior. Field Tools is pushed/opened from Map, Explore and
  details; closing it returns to the exact originating context. It does not introduce a custom root
  navigation bar, permanent side rail or feature-specific tab bar.
- Use system `Form`, menus, pickers, sheets, confirmation dialogs and file sharing with the same
  button hierarchy already used elsewhere. Advanced propagation inputs progressively disclose from
  reviewed presets rather than presenting a desktop-style parameter console by default.
- Reuse current typography, localized format styles, corner radii, padding rhythm, button styles,
  symbol weight and accessibility labeling. Do not introduce a separate type scale, branded color
  palette, icon family, dense map legend style or custom loading animation.

The outer iPhone Duo layout follows the existing compact iPhone presentation. The expanded Duo
layout follows the app's existing hinge-aware primary/secondary ordering instead of behaving like a
small iPad. iPad continues to use the established balanced `NavigationSplitView`. Field state is
shared across those presentations, but each region is a separate `View` type with narrow inputs so
location, heading and simulation progress do not invalidate unrelated panels.

Before feature implementation, create low-fidelity screens using real NodeScope components for:

1. compact **Nearby** with a pinned target;
2. compact **Plan** parameter entry and results;
3. expanded Duo **Nearby**;
4. expanded Duo **Plan** with controls and map separated by the hinge;
5. iPad two-/three-region **Plan**;
6. Dynamic Type accessibility sizes for each presentation.

Review these together with existing Map, Explore, Node Detail and Analytics screens. A design is not
approved merely because it functions on the new form factor; it must look like an adjacent part of
the same app.

The screen is composed from separate SwiftUI view types with narrow inputs: context bar, reference
summary, nearby list, row, target header, compass, evidence cards, action group and snapshot
preview. The high-frequency location/heading state must not invalidate the entire nearby list or
refetch network data. Rows use stable source-qualified entity identity; filtering and distance
sorting happen in the model layer, not inline in `ForEach`.

## Reference point and guidance semantics

### Reference types

- `device`: latest accepted foreground `CLLocation`/Android `Location`, retaining timestamp and
  horizontal accuracy. Heading may accompany it but is not part of the coordinate.
- `mapPoint`: coordinate chosen by long press or crosshair confirmation.
- `enteredCoordinate`: validated latitude/longitude entered or pasted by the person.

Manual references carry no invented accuracy. The UI labels them **Selected point** or **Entered
coordinates**, never **Your location**. Changing reference recalculates local geometry immediately
without a server request.

### Location session

- Begin only after an explicit **Use My Location** action and when Field Tools is visible.
- Use when-in-use authorization only. Stop updates when Field Tools exits, the scene backgrounds,
  the person switches to a manual reference, or the source changes.
- Keep complete `location`, `timestamp`, `horizontalAccuracy` and accuracy authorization state.
- Ignore invalid coordinates, negative accuracy, samples older than the accepted session start
  unless shown as cached, and clearly implausible jumps pending a newer sample.
- Prefer an energy-conscious accuracy suitable for walking guidance. Request temporary full
  accuracy only from an explicit action and only if reduced accuracy materially prevents the task;
  Field Tools must still work with reduced accuracy.
- Heading is optional. Start it only for pinned guidance, stop it with the location session, retain
  heading accuracy and reject invalid readings. Prefer true heading when valid and magnetic heading
  otherwise, labeled accordingly.
- No background mode, significant-change monitoring, visits, geofences or persisted samples.

Apple's current Core Location async update APIs should be evaluated during implementation rather
than extending the callback-only `MapLocationManager`. The existing map helper remains suitable
for one-shot map centering; Field Tools needs a separately testable session abstraction.

### Geometry

- Calculate geodesic surface distance, not map-screen distance.
- Calculate initial bearing in degrees normalized to `[0, 360)` and display cardinal direction.
- Distance formatting reuses the existing locale- and preference-aware formatter.
- The compass relative angle is `target true bearing - accepted device heading`, normalized to the
  shortest rotation. Manual references and invalid heading still show numeric true bearing.
- If the target and reference are within their combined uncertainty, say **Within location
  accuracy** rather than presenting a falsely precise direction.
- Do not estimate radio range, elevation, walking distance or arrival time.

Shared iOS/Android fixtures cover antimeridian crossings, poles, coincident points, invalid
coordinates, unit thresholds, heading normalization and uncertainty overlap.

## Nearby entities and freshness

The candidate catalog comes from the normal source-scoped node and observer repositories. Only
valid, non-placeholder coordinates participate in distance sorting. Entities without coordinates
remain discoverable through search/QR and show **No reported position** instead of `0 m`.

Default nearby results:

- include nodes and observers;
- obey an explicitly visible existing region filter;
- sort by distance, then display name, then stable identifier;
- initially render a bounded nearest set (proposed 50) with an explicit expansion/search path;
- support type filter, favorites/My Nodes filter, activity freshness and text search;
- never trigger one analytics request per visible row.

Freshness is presented as independent timestamped evidence:

- **Reported position:** coordinate plus the best defensible position timestamp. The current models
  do not carry coordinate timestamps, so Milestone A must verify whether either backend exposes one.
  If not, label position age **Unknown**; do not substitute `lastSeen` as coordinate age.
- **Activity:** node latest activity or observer last seen.
- **Analyzer data:** fetch/cache timestamp and offline/partial state.
- **Signal:** most recent suitable observation sample and its observer/timestamp.
- **Coverage:** observer count and covered interval where backend analytics supports it.
- **Device reference:** device location timestamp, horizontal accuracy and reduced/full accuracy.

Suggested activity labels are **Recent** (15 minutes), **Earlier today** (24 hours), **Older** and
**Unknown**, matching existing concepts where practical. Position freshness must not adopt these
thresholds until a real coordinate timestamp contract is verified. Exact relative timestamps remain
available to VoiceOver and in expanded evidence.

## Backend capability and data-honesty matrix

Milestone A records tested payloads and server versions before UI work depends on them.

| Evidence | CoreScope | Beacon | Required fallback |
|---|---|---|---|
| Node coordinates/activity | Existing node catalog/detail | Existing node browse/live catalog | Cached values labeled with fetch time; missing stays unavailable |
| Observer coordinates/activity | Existing observer catalog | Existing observer catalog/live status | Missing coordinates excluded from distance order but remain searchable |
| Coordinate timestamp | Unverified | Unverified | Show **Position age unknown**; never infer from entity activity |
| Recent node signal | Node Analytics SNR trend | Derived observation history | Load only for pinned target; show provenance and covered interval |
| Node observer coverage | Node Analytics coverage | Not equivalent/available per current roadmap | Explain backend limitation; never show zero |
| Observer telemetry | Observer analytics | Derived/supported where available | Optional supporting evidence, not target direction |
| Packets/routes | Existing detail/feed routes | Existing observation-derived behavior | Capability-gated quick actions |
| Live changes | Existing live feed | Beacon browse snapshots/live transport | Update catalogs through shared services; no second socket |

Field Tools adds no unverified server endpoint. Network loading is layered:

1. Show cached/base catalogs immediately with their original timestamps.
2. Refresh the shared node and observer catalogs once per explicit/eligible foreground refresh.
3. Fetch analytics or supporting packet evidence only for the pinned target.
4. Coalesce identical work, cancel on source/session change, honor Beacon paging/rate limits, and
   reject late results with the existing source-session guard.
5. Preserve last usable evidence during a transient error while labeling it stale/cached.

## Proposed neutral models and services

Names are provisional but responsibilities are release requirements.

| Component | Responsibility |
|---|---|
| `FieldEntityID` | Stable source-qualified node/observer identity |
| `FieldEntity` | Small neutral row projection: identity, name, kind, coordinate, activity and favorite state |
| `FieldReference` | Device/manual reference with coordinate, timestamp, accuracy and disclosure category |
| `FieldTargetEvidence` | Pinned-target geometry and optional signal/coverage/packet evidence with provenance and timestamps |
| `FieldSessionModel` | `@MainActor @Observable` owner of destination state, prepared rows, selection, errors and cancellable tasks |
| `FieldLocationService` | Foreground location/heading stream behind a protocol with deterministic test doubles |
| `FieldCatalogRepository` | Reuses backend/cache/live services to assemble source-safe nodes and observers without per-row analytics |
| `FieldEvidenceRepository` | Loads one pinned target's capability-gated evidence and coalesces/cancels requests |
| `FieldGeometry` | Pure, platform-matched distance, bearing, cardinal and uncertainty calculations |
| `FieldSnapshot` | Versioned, Codable/serializable evidence or deployment-plan envelope |
| `FieldQRCode` | Strict parser and generator for approved NodeScope/MeshCore/public-key forms |
| `DeploymentPlan` | Local draft containing proposed coordinate, radio/antenna/receiver/environment assumptions and comparison sites |
| `PropagationEngine` | Cancellable terrain-aware coverage and point-to-point computation behind a platform-neutral contract |
| `TerrainRepository` | Downloads, validates, caches, accounts for and clears bounded elevation tiles without coupling them to analyzer caches |
| `PredictedLink` | Proposed-site-to-deployed-entity result with receive level, margin, classification, assumptions and model provenance |

The session model stores prepared display rows so high-frequency reference changes do not make row
views reach through a large catalog on every body evaluation. Recalculate geometry off the main
actor for a large catalog, publish source/session-checked results on the main actor, and throttle
display-only heading updates enough to remain smooth without needless invalidation.

Do not add Field Tools to the global environment unless multiple independent destinations genuinely
need one shared live session. The destination owns its session; repositories/services may be shared.

## Deployment planning and propagation model

The planner is a prediction workspace, not a second source of observed truth. The initial engine
target is ITM/Longley-Rice or an equivalently reviewed terrain-aware model suitable for LoRa. The
license, native/Wasm integration, numerical behavior and mobile resource cost must be audited in
Milestone A before implementation is selected.

Required inputs include proposed coordinates, frequency, transmit power, antenna gain, cable loss,
antenna height above ground, receiver sensitivity, receiver antenna height/gain, maximum range and
bounded terrain/environment assumptions. Provide reviewed MeshCore-oriented presets, but keep every
value inspectable and editable. Do not copy Meshtastic defaults without validating them for the
selected MeshCore radio/modem configuration and legal region.

Terrain tiles are fetched directly from a documented provider, cached with source/version/resolution,
included in storage reporting and removable through cache controls. Plans remain usable without an
account. A cached terrain result may be rerun offline; a missing tile produces a clear incomplete
state rather than silently switching to free-space propagation.

Coverage output includes model/version, terrain dataset/resolution, calculation time, bounds,
signal grid or contours, minimum/maximum scale and completeness. Point-to-point output includes
terrain profile, geometric line of sight, Fresnel-clearance context when supported, predicted receive
level and link margin. Buildings, vegetation, local noise, mounting quality, polarization and
coordinate error remain disclosed limitations.

Existing nodes/observers are evaluated with an explicit receiver profile. Because analyzer models
do not currently expose their installation/radio settings, never imply that NodeScope detected the
remote antenna, height or sensitivity. Allow a shared receiver assumption and optional per-candidate
override. Classification thresholds are deterministic, tested and visible; **Unknown** is used when
required inputs or terrain coverage are missing.

Multiple candidate sites may be compared in memory. Persistent named project libraries, cloud sync
and collaboration are deferred unless validation demonstrates they are essential to the workflow.

## Field snapshot contract

JSON uses a top-level schema version, a `nearby` or `plan` activity, and additive evolution. Version
1 contains the applicable subset of:

- capture timestamp and app version;
- selected analyzer stable identity, display name, backend type and region scope;
- target kind, stable identifier, display name, role/type and reported coordinate;
- target coordinate timestamp only when verified, activity timestamp and catalog fetch timestamp;
- computed distance and true bearing with the reference category used;
- optional signal sample(s), coverage summary and their evidence intervals/provenance;
- optional packet and route identifiers selected by the person;
- proposed-site coordinate and radio/antenna/receiver/environment assumptions;
- propagation engine/model and terrain dataset provenance;
- coverage bounds/summary and selected predicted-link results, without requiring a large raster;
- completeness, cached/partial/unsupported flags and human-readable limitations;
- optional reference coordinate only after explicit opt-in for that export.

Never include credentials, account email, monitored channel keys, decrypted messages, complete
packet payloads by default, device heading history, location history, or hidden analytics fields.
The snapshot is evidence, not a claim that the target physically remained at the reported point.

Text shares are concise and localized. JSON field names remain stable English contract keys.
Snapshot fixtures must decode on both platforms, and unknown additive fields must be tolerated.
The release does not include an in-app snapshot library; capture/export is ephemeral.

## QR and camera design

iOS should prefer the current system scanning experience available for the deployment target,
with an AVFoundation fallback only if availability requires it. Android uses the established camera
and barcode stack selected during Milestone A. Both platforms must:

- ask for camera permission only when Scan is chosen;
- provide paste/manual-key entry and Photos/clipboard alternatives where practical;
- keep scanning on device and never upload frames;
- stop the camera when dismissed, backgrounded or after a valid result;
- throttle duplicate detections and require confirmation before changing field target;
- validate the full 64-hex key and supported URL host/path/query exactly;
- render QR codes locally at sufficient contrast with a quiet zone and text alternative;
- disclose when a MeshCore contact QR can add a contact in another app versus merely opening the
  corresponding NodeScope entity.

## Privacy, permissions and security

- Keep `NSLocationWhenInUseUsageDescription`; do not request Always authorization or enable
  background location. Review the description to name Field Tools and manual fallback.
- Add camera usage text only when QR scanning ships, explaining on-device node-code scanning.
- Location and camera permission prompts are preceded by contextual UI and never appear at launch.
- Denial is recoverable: manual reference and manual/pasted key remain functional, with a Settings
  shortcut only after denial.
- Device coordinates remain in memory. They are not logged, cached, added to analytics, sent to the
  selected analyzer or included in snapshots/shares without explicit per-export opt-in.
- Analyzer-reported equipment coordinates are network data and may be exported as disclosed.
- QR parsing is non-executing input handling: bound payload length, reject unsupported schemes,
  never open arbitrary URLs, and never accept source/credential configuration from a code.
- Update the privacy manifest/store copy only if the implemented SDK/data behavior requires it;
  record the decision and evidence during release preparation.

## Accessibility and localization

- Dynamic Type through accessibility sizes with no clipped distance, freshness or action text.
- VoiceOver combines each nearby row into a concise entity/name/distance/freshness summary and
  exposes accuracy/limitations without requiring visual badges.
- The compass has a nonvisual equivalent such as “Target northeast, 320 meters”; motion or arrow
  animation is never the only information channel.
- Do not communicate freshness, entity kind, accuracy or selection with color alone.
- Minimum touch targets, sufficient contrast, Reduce Motion support, Reduce Transparency review,
  and Switch Control/Voice Control labels are release gates.
- Use localized format styles for distance, coordinates, dates and numbers. Use
  `LocalizedStringResource` for user-facing status carried outside views; avoid assembled sentences.
- Layout uses leading/trailing semantics and is tested with long pseudo-localized strings.

## Performance and resource budgets

- First useful render uses cached catalogs and should not wait for analytics or precise location.
- No per-row network requests. Pinned-target evidence is the only automatic detail request.
- At most one active catalog refresh and one target-evidence load per source/session.
- Heading/location changes update guidance and prepared distances without rebuilding the map's
  packet layers, reconnecting live feeds or invalidating every unrelated screen section.
- Bound visible annotations and nearby rows; reuse existing map clustering/filtering behavior.
- Stop sensors and camera promptly. Measure energy during a 30-minute active guidance session.
- Record cold/warm load, sort/recalculation and scrolling measurements with representative small
  and large catalogs on physical devices; set numeric acceptance thresholds from the baseline in A.

## Failure and empty states

Every state offers a next action and preserves usable evidence:

- location permission not determined, denied, restricted or services disabled;
- reduced accuracy, poor/stale fix, invalid heading or unavailable compass;
- no reference selected;
- no entities with coordinates in scope;
- target has no coordinate or loses it after refresh;
- source changes or target no longer exists;
- cached-only/offline catalog;
- partial Beacon history or rate limiting;
- analytics unsupported or failed while base target data remains available;
- camera denied/unavailable, invalid QR, valid key not found on this source;
- snapshot export/share failure.

Source change ends the active field session rather than resolving the same key against a different
network silently. Region change keeps the reference but asks before unpinning a target outside the
new scope; the pinned target remains visible long enough to explain the mismatch.

## Milestones and execution order

### A — Contract, architecture and field baseline

- Capture CoreScope and Beacon fixtures for node/observer coordinates, activity, analytics and live
  updates; record server versions and coordinate timestamp availability.
- Inventory existing iOS/Android location, map, cache, navigation, favorites, live-feed, share/export
  and QR-link behavior. Confirm minimum OS/API availability for scanner and async location APIs.
- Inventory and capture the existing NodeScope navigation, Duo pane ordering, instrument cards,
  floating map controls, typography, spacing, semantic colors, status states and interaction patterns.
- Produce the six component-based low-fidelity layouts in the design-integration section and record
  review decisions before creating feature-specific presentation primitives.
- Define neutral models, source/session identity, repository request budgets and capability matrix.
- Implement shared geometry/snapshot/QR fixtures before UI depends on them.
- Measure current large-map/catalog behavior and choose explicit performance budgets.
- Resolve the product decisions listed under **Open decisions**.

Exit: fixtures are committed, unsupported facts are identified, shared contracts are reviewable,
and no screen assumes a coordinate timestamp or backend field that was not observed.

### B — iOS reference and nearby foundation

- Add Field Tools routing from Map, Explore and eligible detail screens, including **Nearby** and
  **Plan** activity state without a sixth root tab.
- Implement device/manual reference selection and foreground location lifecycle.
- Assemble cached-first node/observer catalog, local geometry, filters/search and data-status UI.
- Build adaptive compact iPhone/iPhone Duo outer, expanded Duo two-/three-region and iPad structure
  with state-preserving reflow, plus map annotations without target analytics.
- Add pure/model/repository tests and focused UI automation for permission-independent flows.

Exit: a person can use either reference type, browse honest nearby results and survive source,
region, scene and layout changes without leaked sensor work or stale cross-source results.

### C — iOS pinned guidance and evidence

- Add target pinning, true-bearing/numeric guidance and optional heading-relative compass.
- Add accuracy/freshness/unsupported states and quick links to details, analytics, packets/routes.
- Load one target's evidence with coalescing, cancellation, rate limiting and cached fallback.
- Validate motion/accessibility behavior and a 30-minute physical-device session.

Exit: guidance remains truthful across good, reduced, stale and unavailable data, and sensor/network
work stays within measured budgets.

### D — iOS deployment planner

- Add proposed pin and editable, validated MeshCore radio/antenna/receiver/environment assumptions.
- Integrate the audited terrain repository and cancellable propagation engine.
- Render clearly differentiated heatmap/contours with provenance and limitations.
- Evaluate analyzer-known nodes/observers, rank predicted links and show point-to-point detail.
- Support in-memory candidate comparison, cached terrain, incomplete/offline states and storage clear.
- Add numerical golden fixtures, engine cancellation/resource tests and physical-device baselines.

Exit: a person can model a proposed site and understand likely/marginal/unknown existing-node links
without confusing predictions with observations or detected remote-node settings.

### E — Snapshots and QR on iOS

- Implement versioned snapshot builder, preview, text share and JSON export with privacy toggles.
- Implement strict QR parse/generate, scanner permission lifecycle, fallback entry and target actions.
- Add schema/golden-file/parser/privacy tests and UI automation for valid/error/denied paths.

Exit: exported fixtures round-trip, no device location leaks by default, and QR flows interoperate
with existing NodeScope/MeshCore links on physical devices.

### F — Android parity

- Port the approved shared contracts and fixtures before platform UI.
- Implement reference/location, nearby, pinned guidance/evidence, deployment planning, snapshots and
  QR with platform-native permission and adaptive-layout behavior.
- Validate Google Maps and MapLibre engines, phones, tablets and at least one foldable posture.
- Compare output against shared geometry, ordering, QR and snapshot fixtures.

Exit: supported facts and limitations match iOS; platform differences are documented rather than
hidden behind divergent calculations.

### G — Release hardening

- CoreScope and Beacon regression, offline/cache, source switching and live-feed coexistence.
- Compact iPhone, iPhone Duo outer/expanded/transition/rotation, iPad multitasking and Android
  phone/tablet/foldable validation.
- VoiceOver/TalkBack, Dynamic Type/font scale, contrast, Reduce Motion and denied-permission passes.
- Long session energy/thermal, large-network performance, camera lifecycle and rate-limit tests.
- Privacy manifest, usage descriptions, store privacy answers, onboarding/help and support copy.
- Beta field exercises for deployment, locating, troubleshooting, QR exchange and snapshot sharing.

Exit: recorded device evidence passes the acceptance criteria and known limitations appear in the
release notes. A simulator build alone is not release evidence.

## Testing strategy

### Unit and shared-fixture tests

- coordinate validation, geodesic distance, bearings/cardinals and uncertainty overlap;
- distance/freshness formatting at locale/unit/time boundaries;
- stable source-qualified identity, deterministic ordering and filter combinations;
- field session transitions, cancellation and rejection of late source results;
- location/heading acceptance, staleness and accuracy states using deterministic streams;
- CoreScope/Beacon capability projection and missing-versus-zero handling;
- QR parsing, length bounds, percent encoding, role/type mapping and hostile inputs;
- snapshot schema, privacy defaults, redaction and cross-platform golden files.
- propagation parameter validation, terrain decoding, numerical engine fixtures, receiver ranking,
  incomplete coverage, cancellation and prediction/observation separation;

Use Swift Testing for iOS unit tests and the existing Android test stack. No unit test accesses real
location, camera, network or wall-clock time.

### UI and device tests

- entry from map, node and observer; back/return preservation;
- manual reference without permissions;
- location/camera first request, allow, deny, restricted and Settings recovery;
- nearby search/filter/sort, pin/switch/unpin and target-outside-region behavior;
- compass nonvisual output and Reduce Motion;
- cached/offline/partial/error refresh states;
- QR scan/show/copy and invalid/not-found results;
- snapshot preview, default redaction, explicit coordinate inclusion and export failure;
- compact iPhone, Duo outer/expanded/fold transition, iPad multitasking, large text and screen readers.

### Field exercises

Run scripted tests at known coordinates with at least one fresh and one intentionally stale node:

1. Approach a reported target from two directions and compare displayed distance/bearing with a
   trusted reference, recording device accuracy and target coordinate limitations.
2. Deny precise location and confirm the experience remains honest/useful.
3. Lose connectivity mid-session and verify cached evidence, timestamps and recovery.
4. Switch CoreScope/Beacon sources and prove no target/evidence crosses the session boundary.
5. Exchange QR codes between two devices and with MeshCore where applicable.
6. Share a snapshot and inspect it for accidental device/account/private-channel data.

## Release acceptance criteria

- Field Tools is reachable from Map, Explore and eligible node/observer details on both platforms.
- Field Tools reuses the existing NodeScope background, cards, glass controls, semantic colors,
  navigation, map interactions, status states and adaptive Duo/iPad behavior; any new primitive has
  a recorded need and consistency review.
- Manual reference provides a complete non-permission path; denied location never blocks the feature.
- Distance and bearing match shared fixtures and never claim RF direction, route distance or live
  physical truth.
- Device accuracy, analyzer freshness, activity age, coordinate age and analytics completeness are
  independently represented; unavailable values never become zero or “healthy.”
- Location, heading and camera stop outside their visible foreground workflows.
- No device location is persisted, uploaded, logged or exported by default.
- Nearby browsing makes no per-row analytics requests and remains responsive at the measured large
  catalog baseline.
- Pinned evidence is source-safe, capability-gated, cancellable and rate-limit aware.
- QR input is strict and interoperable; arbitrary URLs/configuration cannot execute.
- Snapshot v1 round-trips across iOS and Android and passes redaction fixtures.
- A proposed site can produce provenance-labeled terrain coverage, point-to-point detail and a
  deterministic ranked receiver list without changing the deployed-node catalog.
- Accessibility, iPhone Duo, adaptive-layout, offline, large-network, long-session and both-backend matrices have
  recorded physical-device results.
- Store/privacy copy accurately describes actual location and camera behavior.

## Open decisions for Milestone A

These are intentionally unresolved until evidence or a small prototype supports the choice:

1. Whether the primary pinned presentation defaults to map-plus-card or compass-plus-card on compact iPhone.
2. The bounded nearby result count and whether distance bands improve scanning better than one list.
3. Whether either backend exposes a defensible coordinate-update timestamp.
4. Exact refresh intervals/request budgets after measuring current repositories and Beacon limits.
5. Minimum OS support and the final system scanner implementation on each platform.
6. Whether explicit reference-coordinate inclusion belongs in 0.10.0 snapshots or should remain
   unavailable for the first release; privacy defaults do not depend on this choice.
7. Whether observer QR presentation has an interoperable external format; scanning and presentation
   are node-first unless one is verified.
8. Propagation engine/library choice, license compatibility, terrain provider terms, model fixtures
   and mobile execution strategy (native versus reviewed embedded Wasm).
9. Approved MeshCore profiles and legal-region defaults; unverified deployed-node settings remain assumptions.
10. Numeric performance/energy/storage thresholds based on representative iPhone, iPhone Duo, iPad
    and Android device baselines.

## Existing implementation to reuse or separate

| Existing component | Plan |
|---|---|
| `CoreScopeViewer/Features/Map/MapScreen.swift` | Add entry and reuse map visual language; avoid coupling sensor/session state to the large existing map body |
| `CoreScopeViewer/Features/Map/MapLocationManager.swift` | Keep one-shot centering behavior; do not stretch its coordinate-only callback contract into Field Tools |
| `CoreScopeViewer/Features/Map/MapViewModel.swift` | Reuse catalog/map conventions where appropriate; Field Tools gets its own narrowly scoped session model |
| `CoreScopeViewer/Models/Node.swift` / `Observer.swift` | Project into neutral field rows; do not treat activity timestamps as coordinate timestamps |
| `CoreScopeViewer/Networking/AnalyzerBackend.swift` | Use existing capability and source-session boundaries; add no speculative endpoint |
| Node/observer analytics | Load only for the pinned target and retain `AnalyticsContext` provenance/completeness |
| `CoreScopeViewer/Networking/NodeScopeDeepLink.swift` | Reuse NodeScope links and MeshCore contact generation; add strict scan parsing tests |
| `CoreScopeViewer/Features/Shared/DistanceFormat.swift` | Reuse user/locale-aware display formatting |
| Favorites/account selection stores | Reuse membership facts; never sync device location or field-session state |
| Live feed services | Join existing shared demand; never open a duplicate live connection for Field Tools |
| Android map engines and `UserLocation.kt` | Reuse engine overlays; replace one-shot coordinate-only behavior with a field-session abstraction without regressing map centering |

## Documentation and release artifacts

- Keep implementation evidence and deviations in `implementation.md`; update this plan only for
  intentional product/contract changes.
- Add concise in-app help explaining reported positions, straight-line guidance, stale evidence,
  location privacy and backend limitations.
- Release notes should describe Field Tools as nearby guidance plus modeled deployment planning,
  distinguishing analyzer observations from predictions and from device radio detection.
- App Store/Play screenshots must not expose real private coordinates, account identity, keys or
  channel content; use controlled fixtures.
