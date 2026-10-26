# MeshCore One LOS source audit

Audit date: 2026-10-25

Upstream: **MeshCore One by Avi0n**

Repository: <https://github.com/Avi0n/MeshCoreOne>

Pinned commit: `9fba8e45ca14c38f4ed20e5ede157944fa1c246d`

This audit identifies the upstream behavior that may seed Node Planner's Link Profile engine. It
does not import upstream code. Permission and compliance requirements are recorded in
[`../legal/meshcore-one-los.md`](../legal/meshcore-one-los.md).

## Reuse boundary

The initial derived implementation should be limited to the RF domain types and calculations in:

- `MC1Services/Sources/MC1Services/RF/ClearanceStatus.swift`
- `MC1Services/Sources/MC1Services/RF/ElevationSample.swift`
- `MC1Services/Sources/MC1Services/RF/ObstructionPoint.swift`
- `MC1Services/Sources/MC1Services/RF/PathAnalysisResult.swift`
- `MC1Services/Sources/MC1Services/RF/RFCalculator.swift`
- `MC1Services/Sources/MC1Services/RF/RelayPathAnalysisResult.swift`
- `MC1Services/Sources/MC1Services/RF/SegmentAnalysisResult.swift`

The following upstream tests are behavioral references and candidates for adapted test cases:

- `MC1Services/Tests/MC1ServicesTests/RFCalculatorTests.swift`
- `MC1Services/Tests/MC1ServicesTests/RFPathAnalysisCharacterizationTests.swift`

Every substantially derived Swift or Kotlin file must identify MeshCore One, Avi0n, the pinned
commit, the GPLv3 license and NodeScope modifications. Record the final imported subset here when
implementation begins.

### NodeScope files derived as of 2026-10-25

- `CoreScopeViewer/Models/NodePlanner/NodePlannerRFEngine.swift`
- `CoreScopeViewer/Features/NodePlanner/LinkProfileScreen.swift` — the
  `LinkProfileTerrainCanvas` section only

The initial Swift implementation derives the RF formulas, thresholds and analysis behavior from
the seven upstream RF-domain files listed above. It does not copy the upstream SwiftUI workspace or
elevation networking implementation.

The terrain-profile renderer subsequently adapts the drawing approach from:

- `MC1/Views/Tools/LineOfSight/TerrainProfileCanvas.swift`
- `MC1/Views/Tools/LineOfSight/ChartCoordinateSpace.swift`

NodeScope replaces MeshCore One's view model, map framework, repeater workflow, theme, localization
surface and surrounding layout. The adapted section consumes NodeScope analysis types and retains
only the purpose-built profile concepts needed for terrain fill, Fresnel bands, obstruction marks,
line of sight, endpoint markers, axes and scaling.

## UI reuse boundary

- Do not copy the complete `MC1/Views/Tools/LineOfSight/` workspace. Its navigation, sheets, map,
  contact/repeater state and editing workflow are coupled to MeshCore One. NodeScope implements its
  own persistent-map workspace and adaptive iPhone, iPad and Duo composition.
- Purpose-built components may be adapted when they outperform a generic NodeScope prototype. Each
  adapted upstream file and NodeScope destination must be added to this audit, carry source-level
  attribution when substantially derived and remain inside the approved GPLv3 distribution model.
- `MC1/Services/ElevationService.swift`: it combines Open-Meteo networking, retry policy, sampling
  and Core Location types. NodeScope needs an explicit provider boundary, cache provenance and the
  same behavior on iOS and Android before choosing an elevation provider.
- `MC1/Views/Tools/LineOfSight/LineOfSightViewModel.swift`: orchestration and UI state should depend
  on NodeScope's plan and analysis models instead of being translated wholesale.

## Upstream behavior to preserve initially

- SI base units: meters, megahertz and decibels.
- Speed of light: `299792458 m/s`.
- Mean Earth radius: `6371 km`.
- First Fresnel radius from wavelength and distances to both endpoints.
- Effective-Earth-radius bulge using an explicit refraction `k` value.
- Free-space path loss using the upstream `-27.55` constant.
- Single knife-edge diffraction approximation with zero loss at `v <= -0.78`.
- Peak diffraction loss is the single worst sampled obstruction, not a cumulative loss.
- Clearance classification: clear at 80% or greater, marginal at 60% or greater, partial
  obstruction from 0% through less than 60%, and blocked below 0%.
- Samples within one meter of a segment endpoint are excluded from obstruction analysis.
- Degenerate profiles return a blocked, zero-distance result rather than throwing.

These rules describe compatibility with the pinned version, not a scientific endorsement. Model
changes require a versioned engine identifier and new fixtures rather than silently changing saved
results.

## Gaps Node Planner must add

MeshCore One's kernel reports terrain clearance and path loss. Node Planner additionally needs:

- transmitter power, antenna gains, cable/system losses and receiver sensitivity;
- predicted receive level and link margin;
- input validation with typed failures instead of relying on zero-valued invalid calculations;
- terrain provider, dataset/version, resolution, fetch time and cache provenance;
- immutable analysis requests and versioned results suitable for persistence and comparison;
- cancellation and stale-result rejection outside the pure calculation kernel; and
- equivalent Swift and Kotlin implementations verified against shared fixtures.

## Audit conclusion

Port the RF kernel behavior behind NodeScope-owned domain models. Keep elevation acquisition and UI
outside the kernel. Start with Link Profile, validate both platforms against the golden fixtures,
then reuse the same engine for candidate-to-known-node Potential Links.
