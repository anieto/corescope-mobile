# Saved Plans v3 portable result fields (draft v1)

Status: drafted November 15, 2026 from both engines' concrete types, for the [contract gate](saved-plans-v3-contract-gate.md); reviewed the same day (Codex answers folded in below). **Fields frozen for v1; byte limits remain provisional** until B measures real encoders. Codecs are deliverable B.

Conventions for both payloads:

- UTF-8 JSON, one object. Field names are camelCase and identical on both platforms.
- Strict readers: unknown fields and unknown enum values are rejected at every nesting level. Adding a field means a new payload version, independent of the library schema version. Swift's synthesized `Decodable` ignores unknown keys, so the Swift reader needs an explicit key check.
- A payload a reader can't decode is kept byte for byte. Its study's independently valid inputs still open. Never decode part of a payload and save that partial interpretation back.
- All doubles are finite. Decimal text must round-trip to the same binary64 bits; `-0.0` must stay negative. The shared vectors' `jsonNumbers` cases check this, and kotlinx.serialization and Foundation's JSONEncoder/JSONDecoder both pass.
- SI units in the names (`Meters`, `Db`, `Dbm`, `Dbi`, `MHz`). Timestamps are integer epoch milliseconds.
- Enums are the strings listed here, never platform ordinals. Readers reject unknown values in v1.
- `inputFingerprint` is the SHA-256 hex digest defined in the contract gate (`nodescope.coverage-inputs.v1` or `nodescope.link-profile-inputs.v1`). A reader recomputes it from the study's saved inputs and refuses to show the result as that study's prediction if it differs.
- Values that can be derived from stored values are not stored, except where noted for display.

## nodescope.coverage-result, version 1

Implemented in B (`PortableCoverageResult` on both platforms): new coverage results are saved in this format; results saved earlier keep their legacy format and still open on the platform that wrote them.

Engine sources: iOS `SavedCoverageCalculation`, `CoverageStudyResult`, `CoverageAreaResult`, `CoverageNodeRun`, `CoverageTerrainDescriptor`; Android `SavedCoverageCalculation.kt` and the same-named Kotlin types.

| Field | Type | Notes |
|---|---|---|
| `format` | string | `"nodescope.coverage-result"` |
| `version` | integer | `1` |
| `calculatedAtMilliseconds` | integer or null | When the batch that produced this result finished, including partial and checkpoint results. **Neither engine records this today**; both must capture it at completion. Opening or saving never changes it. Null for converted legacy results, shown as "Date unknown": neither the study's save time nor the conversion time is the calculation time. |
| `inputFingerprint` | string | Coverage inputs digest. |
| `inputs` | object | Below. Duplicates the study's inputs so the payload stands alone for export. |
| `limits` | object | `areaSamples`, `areaGeometry`, `nodeSamples`, `nodeGeometry`, `nodeModelCalls` (integers), validated as `CoverageStudyLimits` does: each sample and geometry field 0–150,000,000, `areaSamples + nodeSamples` and `areaGeometry + nodeGeometry` each at most 150,000,000, `nodeModelCalls` 0–50,000. |
| `model` | object or null | `sourceRevision`, `scenario` (strings). Null means an unqualified predictor; such results can't be continued. |
| `terrain` | array or null | Up to 64 descriptors, below. Null means unknown terrain; such results can't be continued. |
| `area` | object | Below. |
| `nodes` | array | Up to 200, in the captured catalog's order, below. |

`inputs`: `transmitterLatitude`, `transmitterLongitude`, `frequencyMHz`, `transmitHeightMeters`, `receiveHeightMeters`, `transmitPowerDbm`, `transmitGainDbi`, `receiveGainDbi`, `transmitLossDb`, `receiveLossDb`, `sensitivityDbm`, `reserveDb` (doubles); `grid` with `radiusMeters`, `spacingMeters`, `minEastMeters`, `maxNorthMeters`, `columns`, `rows` (integers; `columns × rows` at most 60,000); `catalogComplete` (boolean). The order matches the fingerprint and both engines' `inputValues`. Readers recompute `inputFingerprint` from this object and also from the enclosing study's inputs; both must match.

`area`:

| Field | Type | Notes |
|---|---|---|
| `stateCodes` | integer array | Length `columns × rows`, row-major from the north-west. 0 qualified, 1 nearSite, 2 outsideRadius, 3 missingTerrain, 4 accuracyUnresolved, 5 thresholdUnresolved, 6 workLimit, 7 notComputed. |
| `lossesDb` | array of number or null | Same length. A number exactly where the state is 0; null elsewhere. |
| `chargedSamples`, `chargedGeometry` | integers | Non-negative 64-bit. |
| `workLimited` | boolean | |

No margins: a reader computes each qualified cell's margin with the production `CoverageLinkBudget.reception(loss)`. Both platforms reproduce the shared margin vectors bit for bit. Readers check the near-site and outside-radius masks against the grid, as the native `restore` does.

`nodes[]`: `sourceID`, `nodeID` (strings), `latitude`, `longitude`, `distanceMeters` (doubles), and `prediction`: either `{"kind": "qualifiedMargin", "marginDb": number}` or `{"kind": "unavailable", "reason": string}`. Node margins are kept: they're few, and node evaluation has its own path. The ordered identities and coordinates must equal the study's captured catalog, with unique `(sourceID, nodeID)` pairs, valid coordinates and non-negative distances.

Unavailable reasons are the values the evaluators actually write, which are the same eight on both platforms: `outsideRadius`, `invalidInput`, `missingTerrain`, `unsupportedPath`, `accuracyUnresolved`, `thresholdUnresolved`, `processingLimit`, `pathWorkLimit`. Writers emit only these; readers reject any other value, including status-only reasons such as `predicted`, `noResult`, `changedNode`, `changedInputs`, `missingAssumptions` and `cancelled`. Display status is still derived by `CoverageNodeStatus.resolve` (distance before prediction), not stored.

`terrain[]`: `dataset`, `revision`, `tile`, `url`, `verticalDatum`, `attribution`, `sourceType`, `originURL` (strings), `compressedSHA256`, `sourceETag` (string or null), `packedBytes`, `side` (integers). Each must pass `CoverageTerrainDescriptor` validation.

Continue eligibility is derived, not stored. Pending work means state-7 cells or `processingLimit` nodes; `workLimit` cells and `pathWorkLimit` nodes alone are not pending. Non-null `terrain` and a `model` equal to the running engine's are necessary, not sufficient: Continue also requires the checks `CoveragePreparedStudy` makes (matching inputs, the same node selection and `catalogComplete`, and the retained terrain descriptors resolving against the required footprint), and may need to download those exact terrain files. A result that fails any of them can still be shown, but not continued.

## nodescope.link-profile-result, version 1

Engine sources: iOS `NodePlannerLinkAnalysis`, `NodePlannerLinkAnalysisRequest`, `NodePlannerPathResult`, `NodePlannerTerrainProfile`; Android `LinkAnalysis`, `PathRequest`, `PathResult`, `TerrainProfile`.

| Field | Type | Notes |
|---|---|---|
| `format` | string | `"nodescope.link-profile-result"` |
| `version` | integer | `1` |
| `calculatedAtMilliseconds` | integer | Both engines record it (iOS `calculatedAt`, Android `calculatedAt`). |
| `inputFingerprint` | string | Link Profile inputs digest. |
| `engine` | object | `id` `"meshcore-one-los-compatible"`, `version` `1` (both engines' constants). |
| `inputs` | object | The exact saved Link Profile inputs, so the payload stands alone: `pointA`, `pointB` (each `latitude`, `longitude`, `heightMeters`, `gainDbi`, `lossDb`), `frequencyMHz`, `transmitPowerDbm`, `sensitivityDbm`, `refractionK`, and `sampleCount` (integer or null: null means adaptive). Endpoint names are labels and stay with the study. Readers recompute `inputFingerprint` from this object and from the study; both must match. |
| `resolvedSampleCount` | integer | 2–100. The count actually used. Equals `inputs.sampleCount` when that is set. It never substitutes for the requested value in the fingerprint. |
| `terrain` | object | Below. |
| `result` | object | Below. |
| `bearingDegrees` | double | Finite and in [0, 360). Shown as saved; not recomputed for comparison. Android stores it; iOS must capture and display the saved value. |

`terrain`: `samples` (2–100, equal to `resolvedSampleCount`), each with `latitude`, `longitude`, `distanceMetersFromA`, `elevationMeters`. Both engines compute distances as a running haversine sum. **Android currently keeps only distances and elevations**; it must retain the sample coordinates it requested. `provenance`: `provider` (`"open-meteo"`), `dataset` (`"copernicus-dem-2021-glo-90"`), `nominalResolutionMeters` (`90`), `fetchedAtMilliseconds`, `origin` (`network`, `cache` or `mixed`; Android already uses these strings). `fetchedAtMilliseconds` is the oldest contributing sample's fetch time, as both providers report it today.

`result`: `distanceMeters`, `freeSpacePathLossDb`, `peakDiffractionLossDb`, `totalPathLossDb`, `worstClearancePercent` (doubles); `clearanceStatus` (`clear`, `marginal`, `partialObstruction`, `blocked`); `obstructions` (each with `distanceFromAMeters`, `obstructionHeightMeters`, `fresnelClearancePercent`); `receivePowerDbm` and `linkMarginDb` (both present or both null, as the engines' optional link budget gives them). `obstructions` holds at most 98 entries (100 samples less the two endpoints). Frequency and refraction are not repeated; they come from the inputs.

Not stored, recomputed on open: the clearance stretches that colour the route and chart. iOS indexes them by sample and Android by metres; the two forms convert into each other, so storing them isn't needed. Recompute them with the saved engine version's rules, from the **stored sample distances**, never by rerunning the analysis or replacing the stored verdict. iOS currently rebuilds distances from coordinates (`NodePlannerLinkAnalysis.clearanceStretches` and the chart); restored results must use the stored distances instead, since platform haversine differences could move a classification near a threshold. Shared fixtures should cover the 60% and 80% clearance boundaries and the one-metre endpoint exclusion. Route drawing differs by platform, so identical pixel placement isn't promised.

Validation on read: finite values; distances strictly increasing from 0, with the last equal to `result.distanceMeters`; sample count equal to `resolvedSampleCount`; a matching `inputFingerprint`. The first and last sample coordinates must match the endpoints within 1e-6 degrees (about 11 cm) in latitude and in longitude compared modulo 360, not exact equality, because the samplers interpolate (rounding at the far end, and ±180° longitudes that are equivalent). Set in B; provisional until both engines' real output is checked against it.

## Engine changes these lists require (deliverable B)

1. Both platforms: record `calculatedAtMilliseconds` when a coverage batch completes, including partial and checkpoint results. Opening and saving don't refresh it.
2. Android Link Profile: keep the actual requested sample coordinates with `TerrainProfile`.
3. iOS Link Profile: capture `bearingDegrees` with the analysis and display the saved value.
4. Android: explicit mappings from `ClearanceStatus` (`CLEAR`, …, `PARTIAL_OBSTRUCTION`) to the portable strings. iOS raw values already match.
5. iOS Link Profile: keep and use the stored sample distances for restored results, as Android does, instead of rebuilding them from coordinates.

## Shared cases (B)

`shared/fixtures/saved-plans/results/result-cases-v1.json`, generated by `make-result-fixtures.py`, which checks every case with a reference validator (Python) before writing: 68 cases (15 valid, 53 invalid) across both payloads. Each pairs a payload with the study inputs it must match and, when invalid, the reason a reader must refuse it: unknown or missing fields at each level, wrong format or version, unknown enum values (cell states, prediction kinds, status-only reasons, clearance status, terrain provenance), limits, area shape and losses, node catalog order and positions, sample counts and distances, endpoint tolerance, half link budgets, bearing range, and fingerprints that don't match the payload's own inputs or the study's. Valid cases include a converted-legacy shape (null time, model and terrain), every cell state, every unavailable reason, adaptive sampling, no link budget, 2 and 100 samples and each clearance status. Not covered: full `CoverageTerrainDescriptor` validation and the near-site/outside-radius masks (platform code; the base grid has them right) and number round-tripping (the contract vectors' `jsonNumbers`). Payload values are synthetic.

## Export note for D

A coverage result has no node names. A user-facing study export (D) must include the study's captured node catalog with the result, validate that its ordered identities and coordinates match, and take names only from that snapshot, never the current analyzer. A raw result payload alone is not a complete study export.

## Sizes

Coverage: see the contract gate's measurements. Dropping margins removes one of three columns. Limits are not frozen.

Link Profile: at most 100 samples of four doubles, up to 98 obstructions and a few dozen fields. Roughly 10–20 KB of JSON, estimated, not measured. 256 KiB stays provisional until B measures the complete encoder output at those maxima. Bound each collection and string as well as the total.
