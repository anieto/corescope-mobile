# Saved Plans v3 portable result fields (draft v1)

Status: field lists drafted November 15, 2026 from both engines' concrete types, for the [contract gate](saved-plans-v3-contract-gate.md). **Not frozen**: size limits, the Link Profile measurements and the review are still open. Codecs are deliverable B.

Conventions for both payloads:

- UTF-8 JSON, one object. Field names are camelCase and identical on both platforms. Unknown fields are rejected in v1.
- All doubles are finite. Decimal text must round-trip to the same binary64 bits; `-0.0` must stay negative. The shared vectors' `jsonNumbers` cases check this, and kotlinx.serialization and Foundation's JSONEncoder/JSONDecoder both pass.
- SI units in the names (`Meters`, `Db`, `Dbm`, `Dbi`, `MHz`). Timestamps are integer epoch milliseconds.
- Enums are the strings listed here, never platform ordinals. Readers reject unknown values in v1.
- `inputFingerprint` is the SHA-256 hex digest defined in the contract gate (`nodescope.coverage-inputs.v1` or `nodescope.link-profile-inputs.v1`). A reader recomputes it from the study's saved inputs and refuses to show the result as that study's prediction if it differs.
- Values that can be derived from stored values are not stored, except where noted for display.

## nodescope.coverage-result, version 1

Engine sources: iOS `SavedCoverageCalculation`, `CoverageStudyResult`, `CoverageAreaResult`, `CoverageNodeRun`, `CoverageTerrainDescriptor`; Android `SavedCoverageCalculation.kt` and the same-named Kotlin types.

| Field | Type | Notes |
|---|---|---|
| `format` | string | `"nodescope.coverage-result"` |
| `version` | integer | `1` |
| `calculatedAtMilliseconds` | integer or null | When the run finished. **Neither engine records this today**; both must capture it at completion. Null for converted legacy results: the time is unknown. |
| `inputFingerprint` | string | Coverage inputs digest. |
| `inputs` | object | Below. Duplicates the study's inputs so the payload stands alone for export. |
| `limits` | object | `areaSamples`, `areaGeometry`, `nodeSamples`, `nodeGeometry`, `nodeModelCalls` (integers). Validated as `CoverageStudyLimits` does. |
| `model` | object or null | `sourceRevision`, `scenario` (strings). Null means an unqualified predictor; such results can't be continued. |
| `terrain` | array or null | Up to 64 descriptors, below. Null means unknown terrain; such results can't be continued. |
| `area` | object | Below. |
| `nodes` | array | Up to 200, in the captured catalog's order, below. |

`inputs`: `transmitterLatitude`, `transmitterLongitude`, `frequencyMHz`, `transmitHeightMeters`, `receiveHeightMeters`, `transmitPowerDbm`, `transmitGainDbi`, `receiveGainDbi`, `transmitLossDb`, `receiveLossDb`, `sensitivityDbm`, `reserveDb` (doubles); `grid` with `radiusMeters`, `spacingMeters`, `minEastMeters`, `maxNorthMeters`, `columns`, `rows` (integers; `columns × rows` at most 60,000); `catalogComplete` (boolean). The order matches the fingerprint and both engines' `inputValues`.

`area`:

| Field | Type | Notes |
|---|---|---|
| `stateCodes` | integer array | Length `columns × rows`, row-major from the north-west. 0 qualified, 1 nearSite, 2 outsideRadius, 3 missingTerrain, 4 accuracyUnresolved, 5 thresholdUnresolved, 6 workLimit, 7 notComputed. |
| `lossesDb` | array of number or null | Same length. A number exactly where the state is 0; null elsewhere. |
| `chargedSamples`, `chargedGeometry` | integers | At least 0. |
| `workLimited` | boolean | |

No margins: a reader computes each qualified cell's margin with the production `CoverageLinkBudget.reception(loss)`. Both platforms reproduce the shared margin vectors bit for bit. Readers check the near-site and outside-radius masks against the grid, as the native `restore` does.

`nodes[]`: `sourceID`, `nodeID` (strings), `latitude`, `longitude`, `distanceMeters` (doubles), and `prediction`: either `{"kind": "qualifiedMargin", "marginDb": number}` or `{"kind": "unavailable", "reason": string}`. Node margins are kept: they're few, and node evaluation has its own path. Reasons: `predicted`, `noResult`, `changedNode`, `changedInputs`, `outsideRadius`, `invalidInput`, `missingTerrain`, `unsupportedPath`, `missingAssumptions`, `accuracyUnresolved`, `thresholdUnresolved`, `cancelled`, `processingLimit`, `pathWorkLimit`. These are the same strings on both platforms today.

`terrain[]`: `dataset`, `revision`, `tile`, `url`, `verticalDatum`, `attribution`, `sourceType`, `originURL` (strings), `compressedSHA256`, `sourceETag` (string or null), `packedBytes`, `side` (integers). Each must pass `CoverageTerrainDescriptor` validation.

Continue eligibility is derived, not stored: pending cells (state 7) or `processingLimit` nodes, plus non-null `terrain` and a `model` equal to the running engine's.

## nodescope.link-profile-result, version 1

Engine sources: iOS `NodePlannerLinkAnalysis`, `NodePlannerLinkAnalysisRequest`, `NodePlannerPathResult`, `NodePlannerTerrainProfile`; Android `LinkAnalysis`, `PathRequest`, `PathResult`, `TerrainProfile`.

| Field | Type | Notes |
|---|---|---|
| `format` | string | `"nodescope.link-profile-result"` |
| `version` | integer | `1` |
| `calculatedAtMilliseconds` | integer | Both engines record it (iOS `calculatedAt`, Android `calculatedAt`). |
| `inputFingerprint` | string | Link Profile inputs digest. |
| `engine` | object | `id` `"meshcore-one-los-compatible"`, `version` `1` (both engines' constants). |
| `resolvedSampleCount` | integer | 2–100. The count actually used, even when the saved input was null (adaptive). |
| `terrain` | object | Below. |
| `result` | object | Below. |
| `bearingDegrees` | double | For display only, not validated. Android stores it; iOS computes it and must capture it. |

`terrain`: `samples` (2–100, equal to `resolvedSampleCount`), each with `latitude`, `longitude`, `distanceMetersFromA`, `elevationMeters`. Both engines compute distances as a running haversine sum. **Android currently keeps only distances and elevations**; it must retain the sample coordinates it requested. `provenance`: `provider` (`"open-meteo"`), `dataset` (`"copernicus-dem-2021-glo-90"`), `nominalResolutionMeters` (`90`), `fetchedAtMilliseconds`, `origin` (`network`, `cache` or `mixed`; Android already uses these strings).

`result`: `distanceMeters`, `freeSpacePathLossDb`, `peakDiffractionLossDb`, `totalPathLossDb`, `worstClearancePercent` (doubles); `clearanceStatus` (`clear`, `marginal`, `partialObstruction`, `blocked`); `obstructions` (each with `distanceFromAMeters`, `obstructionHeightMeters`, `fresnelClearancePercent`); `receivePowerDbm` and `linkMarginDb` (double or null). Frequency and refraction are not repeated; they come from the inputs.

Not stored, recomputed on open: the clearance stretches that colour the route and chart. iOS indexes them by sample and Android by metres, so neither form is portable.

Validation on read: finite values, distances strictly increasing from 0, sample count equal to `resolvedSampleCount`, the first and last sample coordinates equal to the endpoints, and a matching `inputFingerprint`.

## Engine changes these lists require (deliverable B)

1. Both platforms: record `calculatedAtMilliseconds` when a coverage run completes.
2. Android Link Profile: keep the sampled coordinates with `TerrainProfile`.
3. iOS Link Profile: capture `bearingDegrees` with the analysis.
4. Map Android's `ClearanceStatus` (`CLEAR`, …, `PARTIAL_OBSTRUCTION`) to the portable strings. iOS raw values already match.

## Sizes

Coverage: see the contract gate's measurements. Dropping margins removes one of three columns. Limits are not frozen.

Link Profile: at most 100 samples of four doubles plus a few dozen fields. Roughly 10–20 KB of JSON, estimated, not measured. A 256 KiB cap is proposed, to be confirmed when B writes a real encoder.
