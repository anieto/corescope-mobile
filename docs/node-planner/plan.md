# NodeScope 0.10.0 — Node Planner

Status: active implementation. Prepared: 2026-10-24. Updated: 2026-10-25. Supersedes the 0.10.0
Field Tools plan.

## Product thesis

Node Planner helps someone evaluate a proposed mesh deployment before installing hardware.
It answers four questions:

1. If a node is installed at this candidate site, which known nodes might it reach?
2. What terrain or link-budget constraints affect each potential link?
3. What coverage might the proposed node provide under explicit radio and installation assumptions?
4. Which candidate site or configuration is the better deployment choice?

The normal map answers **where deployed nodes are**. Node Planner answers **what might happen if a
new node is placed here**. Proximity alone is not a planning result and is never presented as
reachability.

All output is predictive. Node Planner must display model, terrain, equipment and environmental
assumptions with every result and must not imply that a modeled link has been observed in the real
network.

## Product vocabulary

| Concept | User-facing term | Meaning |
|---|---|---|
| Feature | **Node Planner** | Workspace for modeling proposed deployments |
| Saved work | **Deployment Plan** | Named collection of candidate sites and shared assumptions |
| Proposed location | **Candidate Site** | A local draft coordinate for a node that does not yet exist |
| Existing endpoint | **Potential Link** | Modeled path between a candidate site and a known node |
| Map result | **Estimated Coverage** | Predicted service area under the selected assumptions |
| Equipment inputs | **Node Configuration** | Radio, modem, antenna, cable and installation assumptions |

Avoid **Nearby**, **Field Tools**, **reachable** without qualification, and synthetic certainty
scores. Use **Likely**, **Marginal**, **Unlikely** and **Unknown** only when their deterministic
thresholds and inputs are visible.

## Release scope

### Required for 0.10.0

- Open Node Planner from Explore and the Live Map without adding another root tab.
- Create a deployment plan and place one or more candidate sites by map tap, coordinates or the
  device's current location after an explicit foreground-only permission request.
- Configure proposed frequency, modem/radio preset, transmit power, antenna gain, cable loss,
  antenna height, receiver assumptions and maximum analysis range.
- Load versioned elevation data for the bounded analysis area with visible source, resolution,
  completeness and cache status.
- Analyze point-to-point paths from a candidate site to analyzer-known nodes with valid coordinates.
- Provide a secondary **Link Profile** tool that analyzes any two selected nodes or map points
  without requiring a saved deployment plan.
- Show distance, bearing, terrain profile, geometric line of sight, first-Fresnel-zone context,
  predicted receive level and link margin when supported by the selected engine.
- Classify and rank potential links using documented, tested thresholds.
- Render estimated coverage as a map overlay whose scale and styling cannot be confused with
  observed packets, routes or activity.
- Save plans locally, duplicate a candidate site, change one assumption and compare results.
- Export a concise plan summary containing inputs, provenance, limitations and selected results.
- Work with cached plans and terrain when offline; never silently substitute a less capable model.
- Support iPhone, iPhone Duo postures, iPad and Android adaptive layouts with the same calculations
  and terminology.

### Deliberately deferred

- Automatic site recommendation or optimization.
- Combined multi-transmitter coverage and interference modeling.
- Buildings, foliage, weather and high-resolution urban clutter unless an audited dataset and model
  are available.
- Live BLE/radio measurements, ping tests and drive-test ingestion.
- Cloud plan sync, collaboration and public plan sharing.
- Turn-by-turn navigation, compass guidance and AR direction finding.
- Field inspection checklists, photos, notes, QR exchange and investigation snapshots.
- Automatic hardware purchasing recommendations or device configuration.

## Primary user journey

1. The person opens **Node Planner** and creates a **Deployment Plan**.
2. They add a **Candidate Site** by tapping the map, entering coordinates or using their location.
3. They select a **Node Configuration** preset and review every assumed value.
4. NodeScope identifies known nodes inside the bounded analysis range. This is only the candidate
   set; distance does not imply a viable link.
5. The person runs the analysis. Terrain acquisition and computation expose progress and support
   cancellation.
6. The map displays **Estimated Coverage** and the results panel lists **Potential Links**.
7. Selecting a link opens its terrain profile, line-of-sight/Fresnel information, link-budget
   breakdown, model provenance and limitations.
8. The person duplicates the site or changes antenna height/configuration and reruns the model.
9. Comparison highlights changed assumptions and result deltas without declaring a winner unless
   the person chooses an explicit comparison goal.
10. The plan is saved locally and can be exported as a planning estimate.

## Secondary tool: Link Profile

**Link Profile** is a focused point-to-point tool layered on the same terrain and RF foundation as
Node Planner. It does not replace candidate-site planning, potential-link ranking or estimated
coverage.

The person can open Link Profile from the Live Map, Node Planner or a node's contextual actions,
then select Point A and Point B from known nodes, map points or entered coordinates. They can edit
antenna height and the applicable RF assumptions, run the analysis and inspect:

- terrain elevation profile;
- geometric line of sight and earth-curvature/refraction assumptions;
- first-Fresnel-zone clearance;
- obstruction locations and worst clearance;
- free-space and supported diffraction/path-loss components;
- distance, bearing, predicted receive level and link margin when a complete configuration exists;
- result classification, provenance and limitations.

A Link Profile can be used ephemerally, shared as an estimate, or promoted into a Deployment Plan
by turning either endpoint into a Candidate Site. Opening a Potential Link from a plan uses this
same detail presentation with its endpoints and configuration prefilled.

### Link Profile workspace and selection contract

Link Profile uses one persistent map and one session for endpoint selection, configuration,
analysis and results. It does not present a separate map for Point A and then recreate another map
for Point B. Camera position, visible known nodes and endpoint state survive every inspector,
result and adaptive-layout transition.

- The initial map displays analyzer-known nodes with valid coordinates in the current bounded
  region. Their presence indicates reported location only, never predicted reachability.
- The empty state prompts for Point A. Selecting a known-node marker or an empty map location
  assigns Point A and advances the same workspace to Point B.
- Selecting Point B keeps Point A, the known-node context and the current camera visible. Once both
  endpoints exist, the map fits the proposed path without discarding the person's camera history.
- Confirmed endpoints have unambiguous A/B markers distinct from ordinary known-node markers. A
  tentative map location is visually different and is not committed until confirmed.
- Both endpoints can be relocated, swapped or cleared. When both are already set, selecting an
  unrelated node or location asks which endpoint to replace rather than silently replacing one.
- Coordinates can also be entered manually, and current location remains an explicit foreground
  action rather than an automatic persisted endpoint.
- Analysis is explicit. Endpoint or configuration changes invalidate the visible result and expose
  what changed; they never silently analyze with stale assumptions.
- The analyzed path, clearance classification and obstruction locations return to the same map.
  Terrain-profile inspection can highlight the corresponding map coordinate and vice versa.

The inspector groups the complete workflow without hiding modeled assumptions:

1. **Points:** A/B identity, coordinates, source, antenna height, relocate, swap and clear.
2. **Radio setup:** region/frequency, modem preset, transmit power, endpoint antenna gains,
   endpoint cable/system losses, receiver sensitivity and refraction/model assumptions.
3. **Analysis:** primary analyze/cancel action, progress, stale-input state, failures and provenance.
4. **Results:** classification, distance/bearing, terrain/LOS/Fresnel profile, path-loss breakdown,
   receive level, link margin, obstruction details and limitations.

Presets populate reviewed values but every value remains inspectable. Advanced values can be edited
without forcing a novice to configure the complete link budget manually.

MeshCore One publicly licenses its source under GPLv3. Avi0n has approved the explicit request to
modify the LOS/RF source, translate relevant portions to Kotlin, distribute the derived work through
the App Store and Google Play, and apply NodeScope's Application Store Distribution Exception,
provided GPLv3 is followed. Preserve the response and full request scope in the
[reuse record](../legal/meshcore-one-los.md). Apply required credit
**“MeshCore One by Avi0n”** from the first imported or translated file in both apps'
acknowledgements, repository README/NOTICE and substantially derived source files, with the upstream
link, imported commit and modifications recorded.

## Information architecture

Node Planner has four persistent regions that adapt to available space:

- **Plan bar:** plan name, analyzer/source snapshot, save state and analysis status.
- **Map:** candidate sites, known nodes, selected paths and estimated-coverage overlay.
- **Configuration:** coordinate, node preset and editable radio/installation assumptions.
- **Results:** potential-link ranking, coverage legend, provenance and limitations.

On compact widths, Map is the primary canvas and Configuration/Results use deliberate sheets or
navigation destinations. On wider windows, use a map plus one side panel; do not require three
simultaneous panes on a foldable phone. Layout derives from container space and preserves all edits,
selection, camera and running-analysis identity across resize and fold transitions.

## Adaptive UI contract

Node Planner and Link Profile share one observable session model; device-specific compositions are
views over that state, not separate workflows. Reflow must never recreate the session, restart an
analysis, discard endpoint edits or change the selected candidate/link.

### iPhone — compact portrait

- Full-screen map is the stable background and primary selection surface.
- A compact plan bar sits inside current safe-area and container-corner bounds; it never spans the
  full width merely to display a title.
- A resizable bottom presentation has three intentional states: summary, working and expanded.
- Summary shows the active candidate or A/B endpoints, analysis state and one primary action.
- Working exposes configuration or ranked potential links without hiding the selected map geometry.
- Expanded contains the full configuration, terrain profile, result breakdown and limitations.
- Candidate placement and A/B selection remain possible with the sheet at its smallest useful state.
- Keyboard presentation, Dynamic Type and VoiceOver may promote the sheet to full screen.

### iPhone — landscape and narrow resizable windows

- Use the compact map-plus-presentation composition unless measured content width supports a panel
  without obscuring essential map controls.
- Do not switch layouts using orientation, device name or a regular size class alone.
- A side panel is allowed only when both the map and panel meet their documented minimum content
  widths; otherwise retain the bottom presentation.

### iPhone Duo — outer display

- Use the compact iPhone composition with all required actions reachable on the outer display.
- Unfolding is an enhancement, never a prerequisite for editing or running an analysis.

### iPhone Duo — expanded and partially folded

- Choose composition from the current container proposal, safe areas, corner insets and system-
  reported fold/obscured geometry. Hinge presence or horizontal size class alone is insufficient.
- Default to a hinge-safe two-region layout: the persistent map occupies the leading usable region,
  while the endpoint/configuration/results inspector occupies the trailing usable region alongside
  the app's vertical rail. This matches NodeScope's other Duo map workspaces, keeps the cleaner and
  larger region available to map content and places editable controls closer to the likely hand.
- The trailing inspector accounts for the rail in its own safe content width. The map does not
  reserve rail clearance that belongs to the other region.
- The terrain profile is part of the trailing results inspector by default. It may temporarily
  expand within one unobscured region, but it never spans the hinge.
- Never place a title, toolbar group, chart label, sheet grabber or primary action beneath or across
  the hinge. No visual element uses negative safe-area padding to bridge panes.
- In a partially folded posture where either usable region falls below its minimum width, collapse
  to the compact map-plus-presentation composition instead of squeezing two panes.
- A full-width overlay is prohibited unless geometry proves a continuous unobscured area. Controls
  are positioned within their local region rather than against assumed symmetric screen edges.
- Folding, unfolding and changing posture preserve plan, candidate, endpoints, configuration,
  selected result, map camera, sheet state where meaningful and the identity of in-flight work.

### iPad and wide resizable windows

- Use a two-region composition: a flexible persistent map and a 320–420 point trailing inspector.
- The inspector switches between Plan, Configuration and Results without nesting permanent columns.
- Link Profile retains the map while the trailing inspector moves between endpoint selection,
  radio setup, analysis state, terrain profile and result detail.
- A transient inspector or bottom panel may show a selected link breakdown, but three simultaneous
  columns are not required for v0.10.0.
- iPad multitasking and Stage Manager collapse through the same measured-width rules as iPhone; the
  app never assumes that iPad implies a wide window.

### Shared map-control placement

- Use one map-control inventory and priority order across Node Planner and Link Profile.
- Header, close/back, map style, candidate/endpoint selection and analysis controls occupy explicit
  nonoverlapping regions. Secondary status content flows below the primary header rather than using
  an independently positioned overlay.
- Use container-local safe-area and corner geometry. Hard-coded offsets may provide normal spacing
  but cannot be the only protection from rounded corners, the hinge or system overlays.
- Every floating control has a 44-point minimum hit target, a nonvisual label and a list/sheet
  equivalent when map interaction is inaccessible.

### Required design artifacts before implementation

Milestone A must approve low-fidelity component layouts for:

1. compact iPhone Node Planner;
2. compact iPhone Link Profile;
3. iPhone landscape/narrow resizable window;
4. Duo outer display;
5. Duo expanded flat posture;
6. Duo partially folded posture;
7. iPad full screen;
8. iPad one-third and one-half multitasking widths.

Each artifact covers empty, editing, running, result, error and large-text states. Implementation
does not begin from a single full-screen mockup and infer the remaining layouts later.

## Analysis contract

### Inputs

- Candidate latitude, longitude and ground elevation.
- Frequency and bandwidth/modem parameters required by the engine.
- Transmit power, antenna gain and cable/system loss.
- Transmitter antenna height above local ground.
- Receiver sensitivity, gain, loss and antenna height assumptions.
- Maximum analysis radius and output resolution.
- Propagation-model parameters that have reviewed defaults.

Presets accelerate entry but never hide their values. Remote-node installation details are unknown
unless the analyzer supplies a verified contract; otherwise a clearly labeled shared receiver
profile is used.

### Point-to-point output

- Great-circle distance and initial true bearing.
- Sampled terrain profile and dataset gaps.
- Endpoint elevations and effective antenna elevations.
- Geometric line-of-sight state.
- Fresnel clearance where supported.
- Free-space and modeled path loss.
- Predicted receive level and link margin.
- Classification, model/version, terrain provenance and calculation timestamp.

### Coverage output

- Geographic bounds and resolution.
- Predicted receive level or link-margin grid/contours.
- Fixed, labeled legend with units and threshold meanings.
- Terrain completeness and missing-cell representation.
- Model/version, inputs and calculation timestamp.

Coverage is an estimate, not a guarantee. Buildings, foliage, local RF noise, antenna pattern,
polarization, mounting quality, coordinate error and unmodeled diffraction remain visible
limitations where applicable.

## Architecture

| Component | Responsibility |
|---|---|
| `DeploymentPlan` | Stable local plan identity, name, source snapshot and ordered candidate sites |
| `CandidateSite` | Proposed coordinate, configuration, result references and comparison metadata |
| `NodeConfiguration` | Validated transmitter, antenna, receiver and model inputs |
| `TerrainRepository` | Bounded download, validation, versioning, caching, storage accounting and deletion |
| `PropagationEngine` | Cancellable point-to-point and coverage calculations behind a platform-neutral contract |
| `PotentialLink` | Result, classification, input hash, provenance and limitations for one known endpoint |
| `CoverageResult` | Bounded grid/contours, legend, completeness, provenance and input hash |
| `LinkProfile` | Two endpoints, antenna/configuration assumptions and reusable point-to-point result |
| `PlanRepository` | Atomic local persistence and schema migration; no credentials or location history |

Before selecting an engine, audit license, supported propagation behavior, numerical parity,
native/Wasm integration, cancellation, memory, energy and offline operation. ITM/Longley-Rice is a
candidate, not a preselected dependency. Do not implement a free-space-only result and label it
terrain-aware.

Every calculation uses an immutable input snapshot and content hash. Late results are discarded if
the candidate, configuration, terrain version or analyzer source changed. Coverage and link work run
off the main actor/thread and publish bounded display data only after identity checks.

## Data, caching and privacy

- Analyzer data supplies known-node identity and reported coordinates; it does not supply proof of
  RF reachability.
- Terrain is fetched from a documented provider with license/attribution reviewed before adoption.
- Terrain cache is separate from analyzer response caches and appears in storage controls.
- Plans persist only coordinates intentionally added by the person. Current device location is not
  recorded until explicitly added as a candidate site.
- Location remains when-in-use, foreground-only and optional.
- No plan, coordinate, terrain request or result is uploaded to the selected analyzer.
- Export is user initiated and previews included candidate coordinates and assumptions.

## Failure states

The UI must distinguish and recover from:

- No candidate site or invalid coordinate.
- Missing/invalid configuration value.
- No known nodes with coordinates inside the analysis radius.
- Terrain unavailable, incomplete, corrupt or offline.
- Unsupported frequency/model configuration.
- Analysis cancelled, superseded, resource-limited or failed.
- Cached result produced with older inputs, engine or terrain data.
- Analyzer source changed or its known-node snapshot became stale.
- Coverage unavailable while point-to-point analysis remains usable, and vice versa.

Never replace **Unknown** with **Unlikely**, missing terrain with flat terrain, or missing remote
configuration with detected/default hardware claims.

## Delivery milestones

### A — Product and numerical feasibility

- Select representative real-world plans and expected questions as validation fixtures.
- Produce and approve every adaptive-layout artifact in the UI contract using shared components.
- Audit propagation engines and terrain providers for license, accuracy, mobile cost and parity.
- Define node-configuration schema, legal ranges, presets and receiver assumptions.
- Establish numerical reference cases against at least one independent trusted tool.
- Prototype one point-to-point terrain profile and one bounded coverage run on representative iOS
  and Android hardware.
- Set time, memory, energy, terrain-download and cache-size budgets from measurements.

Exit: engine/provider decisions and numerical tolerances are documented; no production UI proceeds
if mobile coverage computation is not viable.

### B — Plans and candidate sites

- Replace Field Tools naming and routes with Node Planner.
- Implement local plan/site/configuration models and atomic persistence.
- Add candidate placement, editing, duplication and deletion.
- Build adaptive Map, Configuration and Results structure using existing NodeScope components.
- Preserve state across resize/fold and source changes with explicit stale-result handling.

Exit: a person can create and reopen a deployment plan with multiple configured candidate sites,
but the UI does not yet imply that analysis has run.

### C — Potential-link analysis

- Implement terrain repository and bounded point-to-point engine calls.
- Add the standalone Link Profile entry points and two-endpoint selection workflow.
- Evaluate known nodes, calculate results and classify with deterministic thresholds.
- Add ranking, terrain profile, link-budget breakdown, provenance and limitations.
- Add cancellation, cache identity, offline/incomplete and source-change behavior.
- Validate shared golden fixtures on iOS and Android.

Exit: potential links are explainable, reproducible and numerically within the approved tolerance.

### D — Estimated coverage

- Add bounded coverage calculation, progressive status and cancellation.
- Render performant grid/contour overlays and an accessible legend.
- Keep predictions visually distinct from observed map data.
- Validate numerical output, map alignment, memory, energy and thermal behavior.

Exit: a saved candidate site can produce a provenance-rich coverage estimate within measured mobile
budgets, including clear incomplete/offline states.

### E — Comparison, export and release hardening

- Compare sites/configurations and show assumption/result deltas.
- Export a concise human-readable plan report and versioned machine-readable summary.
- Complete accessibility, localization, privacy, cache controls and help content.
- Validate iPhone, Duo postures/transitions, iPad multitasking and Android foldables.
- Run beta comparisons against independent planning tools and selected real deployments.

Exit: release acceptance criteria pass on supported physical devices and known limitations are in
the exported report, help and release notes.

## Test strategy

- Unit tests for validation, geodesy, elevation sampling, Fresnel geometry, link budgets,
  classifications, hashing, migrations and stale-result rejection.
- Golden numerical fixtures shared across platforms and compared within documented tolerances.
- Repository tests for partial downloads, corruption, version changes, eviction and offline use.
- UI tests for plan/site lifecycle, invalid inputs, analysis progress/cancellation, result selection,
  standalone Link Profile, promotion into a plan, comparison and export.
- Accessibility tests for nonvisual map equivalents, result summaries, legends, Dynamic Type,
  contrast and reduced motion/transparency.
- Resize tests that change size/posture during edits and active analysis without losing state or
  duplicating work.
- Screenshot and interaction tests for compact iPhone portrait/landscape, Duo outer/expanded/
  partially folded, iPad full screen and iPad multitasking at one-third and one-half widths.
- Performance tests for representative short/long links, small/large candidate sets and bounded
  coverage areas on minimum supported hardware.

## Release acceptance criteria

- A new user can place a candidate, choose a preset, run analysis and explain one result without
  external instruction.
- No screen equates geographic proximity with reachability.
- Every prediction exposes its inputs, engine, terrain source, timestamp and material limitations.
- Numerical fixtures pass on iOS and Android within approved tolerances.
- Cancelled or superseded work cannot overwrite current results.
- Offline behavior never invents terrain or silently downgrades the model.
- Plans survive relaunch and schema migration without losing candidate coordinates/configuration.
- Fold/resize transitions preserve the active plan and do not overlap, clip or hide essential UI.
- Export clearly labels all results as estimates.

## Migration from the Field Tools prototype

Reusable implementation work includes coordinate validation, geometry, foreground location,
catalog caching, source-session cancellation, map selection, evidence timestamps and adaptive-map
lessons. These are implementation inputs, not reasons to preserve the current Nearby-first UI.

Remove or defer the Field Tools Nearby browser, compass guidance, target pinning, QR and field
snapshot flows unless they directly support candidate placement or potential-link explanation.
Existing tests and fixtures should be retained only when they cover a surviving contract; rename
them as they migrate so the release contains no ambiguous Field Tools terminology.

## Open decisions required in Milestone A

1. Propagation engine and integration strategy.
2. Terrain provider, resolution, license, attribution and offline-cache limits.
3. Initial supported regions/frequencies and reviewed MeshCore presets.
4. Classification thresholds and comparison with independent reference results.
5. Maximum radius/resolution that meets mobile performance budgets.
6. Whether 0.10.0 persistence supports multiple named plans or one active plan plus duplicates.
7. Whether Android coverage rendering can ship with numerical and visual parity in the same release.
8. Record the exact MeshCore One commit and files reused or translated for each platform before
   implementation begins.
