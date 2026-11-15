# Saved Plans v3 contract gate

Status: started November 15, 2026; **not yet frozen or approved for A1 implementation**. This records investigation and candidate contracts under the [approved plan](grouped-saved-plans-plan.md). Production codecs, migrations, backup changes and new UI have not been implemented.

## Baseline and isolated carried change

Local branch feature/saved-plans-v3 starts at 0.10.6 tip c31936e. c149ac7 enables only CandidateSites backup/device transfer. Carried source changes are isolated in f0dbd73: Link Profile height/antenna presets, equipment summary and settings action, and A/B-to-Coverage handoff on both platforms. The action is currently blue on iOS and uses the Android theme's primary button color. No ordinary-tap placement change is included.

Validation on November 15: Xcode BuildProject passed; Android `:app:assembleDebug` and `:app:testDebugUnitTest --tests '*LinkProfile*' --tests '*Coverage*'` passed. Device appearance, keyboard, Back navigation and accessibility checks remain pending. These are development checks, not a newly numbered tester release. 0.10.6 artifacts, tester/store notes and version numbers remain unchanged.

## Findings from the implementations

- Both native Coverage payloads preserve 18 numeric input values, area status and work counters, five resource limits, optional model/terrain metadata, and node predictions. iOS writes binary plist; Android writes its own JSON with base64 embedding. Neither is the portable contract.
- CoverageGrid bounds allocations at 60,000 cells; saved results bound nodes at 200 and terrain descriptors at 64. These must remain independently enforced before allocation, irrespective of byte limits.
- Both existing predictionFingerprint functions use raw Double bit patterns for predictions and node coordinates. They are output diagnostics, not full saved-input fingerprints: grid integers and status text also appear in their framing. Define a new domain/version for saved-input hashes rather than changing diagnostic fingerprints.
- Both DeploymentPlanStore implementations use `plans.json.v1-backup`. A1 must discover and reuse that file by exact-byte checksum, including when no migration-backups directory exists.
- Missing result bytes must be representable independently of a valid manifest. Availability is derived locally; a cloud restore must not mutate the persisted reference to claim that the user saved inputs only.

## Candidate fingerprint wire contract

Use SHA-256 over a versioned binary stream. Prefix with UTF-8 `nodescope.coverage-inputs.v1` or `nodescope.link-profile-inputs.v1`, followed by a zero byte. Float fields are exactly eight bytes, IEEE-754 binary64, most-significant byte first (`Double.bitPattern` / `Double.toRawBits`). Preserve signed zero; reject NaN and infinity. Do not hash decimal text. Integers use fixed-width signed 64-bit big-endian encoding, booleans a single 0/1 byte. Optional fields have a one-byte absent/present tag. Strings are UTF-8 with a four-byte unsigned big-endian byte length; arrays have a four-byte count. JSON encoding itself need not have byte-for-byte identical key order; a file checksum covers its actual bytes.

Proposed Coverage numeric field order follows the existing inputValues order: transmitter latitude, longitude, frequencyMHz, transmitHeightMeters, receiveHeightMeters, transmitPowerDbm, transmitGainDbi, receiveGainDbi, transmitLossDb, receiveLossDb, sensitivityDbm, reserveDb; then integer radiusMeters, spacingMeters, minEastMeters, maxNorthMeters, columns, rows. Append catalogComplete and the ordered captured-node array (sourceID, nodeID, latitude, longitude). Node display names, study names and membership do not change predictions and are not numeric calculation inputs. Resource limits and algorithm identifiers must be bound to the result envelope and validated separately; gate review must ensure all continuation-affecting settings are covered.

Proposed Link Profile order: A latitude, longitude, heightMeters, gainDbi, lossDb; B same five fields; frequencyMHz, transmitPowerDbm, sensitivityDbm, refractionK; tagged optional sampleCount. Persist resolved sampling parameters with a result, so a future default cannot silently change its meaning.

Before freeze, enumerate both engines' complete request types against these lists. Add common binary/digest vectors for signed zero, fractional heights, negative coordinates, nullable sampling, node order and non-ASCII identifiers. Run vectors independently in Swift and Kotlin, including JSON encode/decode followed by fingerprint recomputation. This initial framing proposal is not evidence that those conformance tests already pass.

## Initial layout measurement

Run `python3 docs/node-planner/contract-gate/measure-coverage-layout.py`; recorded output is [coverage-layout-measurements.json](contract-gate/coverage-layout-measurements.json). Python standard-library tooling, deterministic inputs, compact UTF-8 JSON, and gzip with a fixed timestamp make this reproducible without app changes.

The comparison gives both layouts the same 200 synthetic nodes. Per-cell objects omit null values, matching Android's compact legacy behavior. Columnar layout uses integer state codes and equal-length loss/margin arrays with explicit nulls. Cases include fully computed numeric arrays, 75% pending cells, and long numeric spellings. These are synthetic size probes, not valid engine fixtures or upper-bound proofs; the full case deliberately measures a numeric value in every cell.

| 60,000 cells plus 200 nodes | Per-cell bytes | Columnar bytes | Raw ratio |
|---|---|---|---|
| Full numeric arrays | 4,800,957 | 2,400,989 | 2.00× |
| 75% pending | 2,315,325 | 1,175,357 | 1.97× |
| Long numeric spellings | 5,146,621 | 2,746,653 | 1.87× |

Columnar is the preferred candidate. These measurements do not support promising a several-fold reduction for every case. Repeated synthetic values compress unusually well; gzip sizes are informational, not capacity limits. Files will carry raw JSON, without legacy base64 expansion. No float quantization or precision reduction is proposed.

**Decision (November 15): store losses, not margins.** A qualified cell's margin is fully determined by its loss and the study's link budget, and both platforms already reject a restored cell whose stored margin differs from `budget.reception(loss).adjustedMarginDb`. The portable payload therefore carries `stateCodes` and `lossesDb` only, and readers recompute margins. That removes one of the three columns (about a third of the measured columnar size) and the possibility of a stored margin disagreeing with its loss. Condition for freezing: the Swift and Kotlin margin calculations must perform the same IEEE-754 operations in the same order, proven by shared vectors that compare recomputed margins bit for bit. If any vector differs, keep the margins column and the existing equality check. Code review (November 15): iOS `CoverageLinkBudget` and Android `CoverageLinkBudget.kt` already use identical left-to-right binary64 arithmetic: `power + txGain + rxGain − txLoss − rxLoss`, then `− sensitivity − reserve` for the maximum loss, then `maximumLoss − pathLoss` for the margin. Neither uses fused multiply-add or reassociation, so recomputation is expected to match; the vectors must still confirm it. Exact-node predictions stay structured, with their margins, since they are few.

Candidate state codes follow the current common states: 0 qualified, 1 nearSite, 2 outsideRadius, 3 missingTerrain, 4 accuracyUnresolved, 5 thresholdUnresolved, 6 workLimit, 7 notComputed. Never use language enum ordinals. Reject unknown codes for v1; require equal array lengths matching rows×columns and existing state/margin/mask invariants. Nodes remain a separate structured array.

Before freezing limits, add the full envelope, all 64 terrain descriptors with bounded strings, exact inputs, model/limits/provenance and worst-length finite numbers. Compare actual Swift and Kotlin encoders, decoded memory, partial results and valid engine-generated grids. An 8 MiB portable coverage cap appears plausible from this initial probe but is **not frozen**. Link Profile needs its own field inventory and size measurements. A1 cannot proceed with this work unresolved.

## Backup policy to settle before A1

The current broad CandidateSites include works for c149ac7 but cannot remain unchanged when results grow to hundreds of MiB. XML inclusion is path-based, not quota-based. Android's [Auto Backup documentation](https://developer.android.com/identity/data/autobackup) confirms the 25 MB cloud limit and separate cloud/transfer rules. An oversized migration original is another way to lose cloud protection for small records.

Target: one coherent manifest/small-record selection under 20,000,000 bytes, with a provisional 16 MiB manifest ceiling. Preserve the 32 MiB legacy reader and never truncate an oversized migration. **Decision (November 15): path rules, no custom backup agent.** A quota-aware BackupAgent is more code to maintain and test than the gain justifies. Use the XML rules instead:

| Data | Android cloud backup | Android device transfer | iOS |
|---|---|---|---|
| `CandidateSites/plans.json` (manifest and small records) | Included | Included | Included |
| `CandidateSites/results/` | Excluded | Included | Included |
| Migration backups, including `plans.json.v1-backup` | Excluded | Included | Included (local backups) |
| Everything else (settings, caches, terrain, analyzer data) | Excluded | Excluded | Unchanged |

A study restored from Android cloud backup shows “Saved result not on this device” with its inputs, as required below. Keep the manifest under the 20,000,000-byte target so cloud backup never stops for size. Do not duplicate every result just to form a backup staging tree. Android 8–11 behavior needs explicit validation; Android 12+ rules alone are insufficient. Transport overhead, concurrent writes, missing results and migration-backup exclusions must be tested before selecting an implementation.

Migration backups, including the legacy sibling filename, stay locally available and travel with device transfer, but are excluded from Android cloud backup: a pre-upgrade copy of a large library could exceed the 25 MB limit on its own and stop backup of everything else. Their retention rules must avoid duplicate copies of identical bytes. c149ac7 currently includes the whole folder in both; A1 narrows the cloud rule as above, with the matching Android 8–11 `backup_rules.xml`. iOS retains Application Support eligibility for the library and results.

Required restore display: “Saved result not on this device” with preserved inputs/reference. Settings in A1 and the export screen in D explain actual coverage. OS restore replaces the data before launch and runs normal validation; export/import merge is a separate user flow.

## Fixes since the gate started

- Android Link Profile → Coverage handoff (f0dbd73) filled coverage fields with raw `toString()`, showing "20.0 ft", "22.0" and "32.808398950131235 ft". It now uses the same formatting as a reopened study (`heightEditorText`, `editorText`). SavedStudiesTest covers both unit systems. iOS stores the numbers directly and was unaffected.

## Exit checklist and staged evidence

- [ ] Final portable Coverage and Link Profile field specifications, versions, enums, nullability, timestamps (unknown legacy calculation dates stay unknown), provenance and continuation rules.
- [ ] Swift/Kotlin fingerprint vectors match, including bit-exact numeric JSON round trips.
- [ ] Complete payload sizing and bounded manifest sizing justify frozen limits.
- [ ] Final v3 schema/reference fixture: revisions without baseRevision, provenance, missing-result behavior and strict future-version refusal.
- [x] Backup selection and migration-original policy chosen (path rules, table above). Remaining: implement in A1 for Android 8–11 and 12+, and specify the acceptance procedure for actual cloud and device transfers.
- [ ] Margin recomputation vectors match bit for bit on Swift and Kotlin, or margins stay in the payload.
- [ ] Legacy fixture inventory includes both native formats and existing v1 backups. Portable fixture contract defines iOS→Android→iOS and Android→iOS→Android; production codec round trips pass in B.
- [ ] Gate reviewed before A1 runtime changes begin.

A1 then proves upgrade over 0.10.6 and real backup/restore with today's tool/save UI. A2 adds plan UI/save flow/context. B implements portable codecs and historical Link Profile snapshots. C adds durable drafts and fixes Android source-switch/process-death loss. D adds export/import after B. Every stage has a separately numbered tester build and its own acceptance record. Actual Android cloud restore, phone-to-phone transfer and iOS device restore remain unperformed, not inferred from unit tests.
