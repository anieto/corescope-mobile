# Node Planner RF engine contract v1

Status: implementation contract drafted 2026-10-25.

This contract defines the smallest deterministic core shared by the iOS and Android implementations.
It is intentionally independent of SwiftUI, Compose, Core Location, map SDKs and network clients.

## Version identity

Every result carries:

- `engineID`: `meshcore-one-los-compatible`
- `engineVersion`: `1`
- upstream project and pinned commit from the source audit
- NodeScope app version
- terrain dataset/provider identity and fetch or package version
- calculation timestamp

A change to formulas, thresholds, sampling interpretation or output semantics increments the engine
version. Display-only changes do not.

## Input model

`PathAnalysisRequest` contains:

- ordered `ElevationPoint` values with distance from A and elevation above mean sea level in meters;
- endpoint antenna heights above ground level in meters;
- frequency in megahertz;
- effective-Earth-radius factor `k`;
- optional `LinkBudget` containing transmit power, endpoint antenna gains, endpoint cable/system
  losses and receiver sensitivity, all in dB/dBm units named explicitly.

Coordinates and terrain-provider metadata belong to the enclosing Link Profile request. The pure RF
kernel consumes distances and elevations so identical numerical input behaves identically on both
platforms.

## Output model

`PathAnalysisResult` contains:

- distance, free-space path loss, peak diffraction loss and total modeled path loss;
- worst first-Fresnel-zone clearance percentage;
- `clear`, `marginal`, `partialObstruction` or `blocked` classification;
- zero or more obstruction samples in full-path distance coordinates;
- the frequency and refraction factor actually used;
- when a link budget was supplied, predicted receive level and link margin; and
- engine and terrain provenance.

The UI must call the result an estimate and must not present clearance classification as proof that
a real-world MeshCore link will work.

## Validation contract

Reject, with typed errors, requests having fewer than two samples, non-finite values, non-increasing
sample distances, invalid coordinates in the enclosing request, non-positive frequency or
refraction factor, negative antenna heights, or an unsupported terrain gap. Do not turn invalid
user input into a plausible zero-valued analysis.

An upstream-compatible degenerate-result function may exist only for fixture parity; application
orchestration uses the stricter validation boundary.

## Link-budget extension

After terrain path loss is calculated:

`receivePowerDBm = transmitPowerDBm + gainADB + gainBDB - lossADB - lossBDB - totalPathLossDB`

`linkMarginDB = receivePowerDBm - receiverSensitivityDBm`

This extension must remain separately testable from terrain clearance. Future fade margin or
environmental models must be additional, visibly named terms rather than hidden constants.

## Determinism and comparison

- Use IEEE-754 binary64 (`Double`) on both platforms.
- Do not round within the engine; round only for display.
- Preserve input order and full-path distance coordinates.
- Compare fixture scalars with absolute tolerance `1e-9` for primitive formulas and `1e-8` for
  path outputs unless a fixture specifies otherwise.
- Compare enums, array lengths and obstruction ordering exactly.
- Treat `NaN` and infinity as validation failures.

Shared fixtures live in [`fixtures/rf-golden-v1.json`](fixtures/rf-golden-v1.json). Both platform
test targets must load the same file or generated copies verified by an identical SHA-256 digest.

## Layer boundary

The feature is split into:

1. Pure RF kernel: deterministic math and classifications.
2. Terrain sampler: coordinates to versioned elevation profile.
3. Analysis coordinator: cancellation, caching, hashing and stale-result rejection.
4. Node Planner domain: plans, candidates, configurations, known-node targets and comparisons.
5. Adaptive UI: Link Profile and planning workflows for each supported size/posture.

Only layers 1 and the shared fixture schema are derived from the audited MeshCore One behavior.
