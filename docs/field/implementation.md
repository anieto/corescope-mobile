# NodeScope 0.10.0 — Field Tools implementation

Status: in progress. Implementation began on 2026-10-22.
Product scope and acceptance criteria are in [plan.md](plan.md).
Milestone A evidence is in [milestone-a-findings.md](milestone-a-findings.md).

This document records observed contracts, completed work, validation evidence, deviations and
remaining release gates. A checked item means its evidence is linked or summarized here; code
existence alone is not completion.

## Milestone status

| Milestone | Status | Evidence |
|---|---|---|
| A — Contract, architecture and field baseline | In progress | Contract audit, geometry v1 and shared catalog repository recorded |
| B — Workspace and Nearby, iOS then Android | In progress | iOS adaptive workspace shell and shared catalogs |
| C — Guidance and evidence, iOS then Android | In progress | iOS true-bearing and foreground true-heading guidance; shared rotation fixture |
| D — Deployment planner, iOS then Android | Not started | — |
| E — Snapshots and QR, iOS then Android | Not started | — |
| F — Joint release hardening | Not started | — |

## A — Contract, architecture and field baseline

- [x] Record CoreScope version/commit and node/observer/analytics/live fixtures.
- [x] Record Beacon version/commit and corresponding fixtures.
- [x] Determine whether coordinate-update timestamps exist and document exact semantics.
- [x] Record missing/null/zero-coordinate behavior for both backends.
- [x] Audit source-session cancellation, caches, live-feed reuse and request budgets.
- [x] Capture existing Map/Explore/detail visual patterns and shared UI primitives.
- [ ] Prototype compact iPhone, Duo outer/expanded and iPad Field Tools layouts using real shared components.
- [ ] Record design review confirming navigation, pane ordering, cards, map controls, colors,
  typography, spacing, loading/error states and accessibility match the current app.
- [x] Confirm iOS and Android deployment targets and scanner/location API availability.
- [ ] Define neutral models and source-qualified identities. Source/entity identity and snapshot
  envelope are implemented; catalog/evidence/planning projections remain.
- [x] Commit cross-platform geometry, QR and snapshot v1 fixtures.
- [ ] Measure small/large catalog and 30-minute sensor baselines on representative devices.
- [ ] Resolve every open decision in the plan with rationale.

### Contract observations

The current CoreScope and Beacon models expose coordinates and entity/observation activity, but no
verified coordinate-update timestamp. Field Tools therefore reports position age as unknown. Full
observations, projection rules and remaining fixture work are recorded in
[milestone-a-findings.md](milestone-a-findings.md).

### Approved decisions and deviations

- Use the IUGG mean Earth radius (6,371,008.8 meters), Haversine distance and true initial bearing
  as the cross-platform geometry v1 contract.
- Return no bearing for coincident coordinates; a fabricated north bearing would be misleading.
- Keep zero/zero valid in geometry/manual input and reject analyzer sentinel zero/zero only while
  projecting backend data.
- Qualify persistent field identities with backend, canonical analyzer origin, entity kind and
  normalized backend key. Keep ephemeral source-session UUIDs out of exported identity.
- Accept only bounded full public keys, exact NodeScope node/observer links and exact MeshCore
  contact links as Field QR input. Scanned input never configures a source or opens arbitrary URLs.
- Omit reference coordinates from snapshot v1 unless the person explicitly opts in for that export.

## B — Workspace and Nearby, iOS then Android

- [x] Field Tools routing from Map and Explore without a sixth root tab.
- [x] Eligible node and observer detail entry.
- [x] Manual map-point and entered-coordinate references.
- [x] Foreground device-location session with timestamp/accuracy.
- [x] Cached-first node/observer catalog and refresh/error states.
- [x] Distance ordering, filters and search without per-row network work.
- [ ] Adaptive iPhone, iPhone Duo outer/expanded/transition and iPad structure and map overlays.
- [ ] Unit/model/repository and permission-independent UI tests.
- [ ] Android-native workspace, reference, nearby, maps and adaptive layouts.
- [ ] Shared identity/coordinate/distance/ordering/filter/session fixtures pass on both platforms.
- [ ] Cross-platform parity gate recorded before guidance work begins.

The initial iOS workspace shell now composes the existing adaptive list/detail and Duo arrangement,
background, instrument-card and floating-glass primitives. Map supplies its visible center as the
initial manual reference; Explore opens the same destination from a dedicated card. Nearby displays
source-catalog nodes and observers with usable analyzer coordinates, and Plan is present as an
explicitly incomplete activity pending the propagation-model slice.

The reference controls now support map taps, bounded latitude/longitude entry and an explicitly
explained When-In-Use device-location session. Device samples retain and display timestamp and
horizontal accuracy, stop when the scene leaves the foreground or the workspace closes, and never
request Always authorization. Eligible node and observer details open the workspace with that
entity selected; zero/zero analyzer sentinel positions do not expose the action.

Nearby now prepares its visible collection in the observable model: distance-first when a reference
exists, deterministic name ordering otherwise, with local node/observer filters and search. The
selected region is applied to the node catalog request and observer IATA metadata before display.
Selection and pinning use stable source entity IDs; a pinned target adds a distinct dashed geometric
line and compact summary with distance/cardinal direction, and can be switched or unpinned without
additional network work.

## C — Guidance and evidence, iOS then Android

- [x] Pin/switch/unpin target flows.
- [x] Numeric true bearing and cardinal guidance.
- [x] Optional heading-relative compass with accuracy/failure states.
- [x] Pinned target signal/coverage/activity evidence and provenance.
- [x] Detail, analytics, packet and route quick actions.
- [ ] Cancellation, source/region changes, cached fallback and rate limits.
- [ ] VoiceOver, Dynamic Type, Reduce Motion and physical-session validation.
- [ ] Android-native guidance, compass, evidence, accessibility and lifecycle behavior.
- [ ] Shared geometry/freshness/capability/provenance fixtures pass on both platforms.
- [ ] Matching scripted iOS/Android physical-field results recorded before planning work begins.

Pinned iOS targets now show localized distance, numeric initial true bearing and cardinal direction.
When the person explicitly uses the foreground device reference, a narrowly scoped compass view
rotates the target arrow relative to a valid Core Location true-heading sample and reports heading
accuracy. It never substitutes magnetic heading. Unsupported hardware and pending calibration have
explicit fallback states, while numeric guidance remains available for manual references and when
heading is unavailable. Location and heading updates stop together on background or workspace exit.

Pinned targets also have an initial catalog-evidence card. It projects only fields already present
in the cached node or observer record: latest reported activity and, when supplied, advertisement
count, explicit observer status, packets in the last hour and noise floor. The card identifies the
analyzer host and catalog fetch time, and explicitly says that signal and receiver coverage are not
part of the catalog record. This catalog layer makes no RF direction or coverage inference.

The target-specific layer loads one day of node analytics or the source's recent observer analytics
for only the pinned target. Node evidence reports recorded packet count, distinct receivers, latest
SNR/RSSI and the strongest average receiver when those measured values exist; observer evidence
reports its recorded traffic and latest measured signal values. Responses use a
source-and-target-qualified shared cache with a five-minute fresh lifetime, in-flight request
coalescing and seven-day stale fallback. Target/source task identity prevents late results from
crossing selections. The UI labels fetched, saved and stale-fallback states and continues to avoid
turning analyzer observations into direction-finding or predicted coverage claims.

Pinned-target quick actions reuse the existing native destinations. Nodes expose detail and
analytics actions only when the selected backend advertises analytics support. Observers open their
combined detail/analytics destination. If and only if observer analytics returns a nonempty packet
hash, Field Tools offers the latest packet and its recorded route in the existing packet detail
screen. Unknown observation and hop counts remain visibly unknown until packet detail loads; Field
Tools does not synthesize packet or route identifiers for node analytics that do not provide them.

## D — Deployment planner, iOS then Android

- [ ] Propagation-engine and terrain-provider license/architecture decision.
- [ ] Validated MeshCore profiles and editable planning parameters.
- [ ] Proposed-site pin and in-memory candidate comparison.
- [ ] Terrain tile download/cache/storage/clear behavior.
- [ ] Coverage heatmap/contours with model and dataset provenance.
- [ ] Existing-node/observer receiver assumptions, ranking and point-to-point detail.
- [ ] Numerical golden fixtures, cancellation and resource/device baselines.
- [ ] Android planning UI and Google Maps/MapLibre prediction overlays.
- [ ] Identical cross-platform coverage/point-to-point numerical fixtures pass.
- [ ] Cross-platform planner parity gate recorded before snapshot/QR work begins.

## E — Snapshots and QR, iOS then Android

- [ ] Snapshot v1 model, builder and golden files.
- [ ] Privacy preview and default reference-coordinate exclusion.
- [ ] Localized text share and JSON export.
- [ ] Strict NodeScope/MeshCore/bare-key QR parser.
- [ ] QR presentation with text/copy/share alternative.
- [ ] Scanner and camera permission lifecycle.
- [ ] Valid, invalid, denied, not-found and duplicate-detection tests.
- [ ] Android-native share/export, QR and permission behavior.
- [ ] Snapshot and QR fixtures round-trip between physical iOS and Android devices.
- [ ] Cross-platform parity gate recorded before release hardening.

## F — Joint release hardening

- [ ] CoreScope and Beacon regression matrix.
- [ ] Offline/cache/source-switch/live-feed matrix.
- [ ] iPhone, iPhone Duo, iPad and Android device/layout matrix.
- [ ] VoiceOver/TalkBack, text scaling, contrast and motion matrix.
- [ ] Long-session energy, thermal and performance results.
- [ ] Location/camera/privacy manifest and store disclosure review.
- [ ] Controlled beta field exercises and recorded outcomes.
- [ ] Known limitations and store release notes.

## Validation log

| Date | Platform/device | Build/source | Scenario | Result/evidence |
|---|---|---|---|---|
| 2026-10-22 | Android JVM | API 26+ source contract | Field geometry parity tests | 5 passed |
| 2026-10-22 | iOS / Xcode 27.1 | Active Xcode destination | Build for testing | Passed |
| 2026-10-22 | iOS / Xcode 27.1 | Active Xcode destination | Field geometry parity tests | 5 passed |
| 2026-10-22 | iOS / Xcode 27.1 | Active Xcode destination | Field identity and QR contract tests | 6 passed |
| 2026-10-22 | Android JVM | API 26+ source contract | Field identity and QR contract tests | 6 passed |
| 2026-10-22 | iOS / Xcode 27.1 | Active Xcode destination | Field snapshot v1 tests | 3 passed |
| 2026-10-22 | Android JVM | API 26+ source contract | Field snapshot v1 tests | 3 passed |
| 2026-10-23 | iOS / Xcode 27.1 | Active Xcode destination | Shared CoreScope live fixture | Passed |
| 2026-10-23 | Android JVM | API 26+ source contract | Shared CoreScope live fixture | Passed |
| 2026-10-23 | iOS / Xcode 27.1 | Active Xcode destination | Shared Field catalog repository and coalescing tests | 2 passed; build-for-testing passed |
| 2026-10-23 | Android JVM | API 26+ source contract | Shared Field catalog repository tests | 2 passed |
| 2026-10-23 | iOS / Xcode 27.1 | Active Xcode destination | Field Tools adaptive workspace and Map/Explore routing | Build-for-testing passed; repository tests 2 passed |
| 2026-10-23 | iOS / Xcode 27.1 | Active Xcode destination | Field reference controls, foreground location lifecycle and detail routing | Build-for-testing passed |
| 2026-10-23 | iOS / Xcode 27.1 | Active Xcode destination | Nearby ordering, local search/filter and target selection/pinning | 9 focused tests passed; build-for-testing passed |
| 2026-10-23 | iOS / Xcode 27.1 | Active Xcode destination | Numeric true-bearing and heading-relative target guidance | 6 geometry tests passed; build-for-testing passed |
| 2026-10-23 | Android JVM | API 26+ source contract | Relative true-heading rotation parity | 6 geometry tests passed |
| 2026-10-23 | iOS / Xcode 27.1 | Active Xcode destination | Pinned-target catalog evidence and provenance projection | 4 presentation tests passed; build-for-testing passed |
| 2026-10-23 | iOS / Xcode 27.1 | Active Xcode destination | Target analytics evidence, receiver observations and cached fallback states | 5 presentation tests passed; build-for-testing passed |
| 2026-10-23 | iOS / Xcode 27.1 | Active Xcode destination | Capability-aware detail/analytics and exact packet/route quick actions | 6 presentation tests passed; build-for-testing passed |

## Known limitations

- Coordinate timestamp availability is unverified. Until verified, position age is unknown.
- Field Tools uses analyzer-reported positions and is not RF direction finding.
- Deployment results are terrain-model predictions and are not guarantees of RF reception.
