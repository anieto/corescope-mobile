# NodeScope 0.10.0 — Field Tools implementation

Status: not started. Plan approved for implementation preparation on 2026-10-22.
Product scope and acceptance criteria are in [plan.md](plan.md).

This document records observed contracts, completed work, validation evidence, deviations and
remaining release gates. A checked item means its evidence is linked or summarized here; code
existence alone is not completion.

## Milestone status

| Milestone | Status | Evidence |
|---|---|---|
| A — Contract, architecture and field baseline | Not started | — |
| B — iOS reference and nearby foundation | Not started | — |
| C — iOS pinned guidance and evidence | Not started | — |
| D — iOS deployment planner | Not started | — |
| E — Snapshots and QR on iOS | Not started | — |
| F — Android parity | Not started | — |
| G — Release hardening | Not started | — |

## A — Contract, architecture and field baseline

- [ ] Record CoreScope version/commit and node/observer/analytics/live fixtures.
- [ ] Record Beacon version/commit and corresponding fixtures.
- [ ] Determine whether coordinate-update timestamps exist and document exact semantics.
- [ ] Record missing/null/zero-coordinate behavior for both backends.
- [ ] Audit source-session cancellation, caches, live-feed reuse and request budgets.
- [ ] Capture existing Map/Explore/detail visual patterns and shared UI primitives.
- [ ] Prototype compact iPhone, Duo outer/expanded and iPad Field Tools layouts using real shared components.
- [ ] Record design review confirming navigation, pane ordering, cards, map controls, colors,
  typography, spacing, loading/error states and accessibility match the current app.
- [ ] Confirm iOS and Android deployment targets and scanner/location API availability.
- [ ] Define neutral models and source-qualified identities.
- [ ] Commit cross-platform geometry, QR and snapshot v1 fixtures.
- [ ] Measure small/large catalog and 30-minute sensor baselines on representative devices.
- [ ] Resolve every open decision in the plan with rationale.

### Contract observations

Not yet recorded. Do not infer coordinate age from node/observer activity timestamps.

### Approved decisions and deviations

None yet.

## B — iOS reference and nearby foundation

- [ ] Field Tools routing from Map and Explore without a sixth root tab.
- [ ] Eligible node and observer detail entry.
- [ ] Manual map-point and entered-coordinate references.
- [ ] Foreground device-location session with timestamp/accuracy.
- [ ] Cached-first node/observer catalog and refresh/error states.
- [ ] Distance ordering, filters and search without per-row network work.
- [ ] Adaptive iPhone, iPhone Duo outer/expanded/transition and iPad structure and map overlays.
- [ ] Unit/model/repository and permission-independent UI tests.

## C — iOS pinned guidance and evidence

- [ ] Pin/switch/unpin target flows.
- [ ] Numeric true bearing and cardinal guidance.
- [ ] Optional heading-relative compass with accuracy/failure states.
- [ ] Pinned target signal/coverage/activity evidence and provenance.
- [ ] Detail, analytics, packet and route quick actions.
- [ ] Cancellation, source/region changes, cached fallback and rate limits.
- [ ] VoiceOver, Dynamic Type, Reduce Motion and physical-session validation.

## D — iOS deployment planner

- [ ] Propagation-engine and terrain-provider license/architecture decision.
- [ ] Validated MeshCore profiles and editable planning parameters.
- [ ] Proposed-site pin and in-memory candidate comparison.
- [ ] Terrain tile download/cache/storage/clear behavior.
- [ ] Coverage heatmap/contours with model and dataset provenance.
- [ ] Existing-node/observer receiver assumptions, ranking and point-to-point detail.
- [ ] Numerical golden fixtures, cancellation and resource/device baselines.

## E — Snapshots and QR on iOS

- [ ] Snapshot v1 model, builder and golden files.
- [ ] Privacy preview and default reference-coordinate exclusion.
- [ ] Localized text share and JSON export.
- [ ] Strict NodeScope/MeshCore/bare-key QR parser.
- [ ] QR presentation with text/copy/share alternative.
- [ ] Scanner and camera permission lifecycle.
- [ ] Valid, invalid, denied, not-found and duplicate-detection tests.

## F — Android parity

- [ ] Shared fixtures pass unchanged.
- [ ] Reference/location and nearby experience.
- [ ] Pinned guidance/evidence experience.
- [ ] Deployment planning and predicted-link parity.
- [ ] Snapshot/share/export experience.
- [ ] QR scan/presentation experience.
- [ ] Google Maps and MapLibre validation.
- [ ] Phone, tablet and foldable adaptive/accessibility validation.

## G — Release hardening

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
| — | — | — | — | — |

## Known limitations

- Coordinate timestamp availability is unverified. Until verified, position age is unknown.
- Field Tools uses analyzer-reported positions and is not RF direction finding.
- Deployment results are terrain-model predictions and are not guarantees of RF reception.
