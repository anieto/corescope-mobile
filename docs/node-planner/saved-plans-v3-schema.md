# Saved Plans v3 library schema (draft)

Status: drafted November 15, 2026 for the [contract gate](saved-plans-v3-contract-gate.md), with a reference fixture; reviewed the same day (Codex answers folded in). **Frozen for A1.** Nothing here is implemented in either app; A1 implements it. Result payload contents are in [saved-plans-v3-result-fields.md](saved-plans-v3-result-fields.md).

## Files

```
CandidateSites/
    plans.json                 the manifest (schemaVersion 3)
    results/<sha256>.result    immutable result payloads, named by the SHA-256 of their bytes
    migration-backups/         pre-upgrade originals (and the existing plans.json.v1-backup)
```

Result files are content-addressed. Two studies with identical result bytes share one file; changing a study never edits a file, it writes a new one and moves the reference. Committing follows the plan's sequence (payload files first, manifest last, atomically). If a file with the destination's hash name already exists, verify its bytes before reusing it; a corrupt existing file is never taken as a successful install.

Garbage collection deletes a result file only when no root references it: the current validated manifest, retained migration backups and, from C, recovery drafts. It runs serialized with library writes, protects files being installed, and skips all deletion if any root can't be read. The manifest that removes or replaces a reference is committed before any file is deleted. Deleting one study never deletes a file another study still shares.

The existing `CandidateSites/plans.json.v1-backup` is recognized where it is, as well as backups under `migration-backups/`. Identical bytes are reused, a different v2 original gets its own backup, and an existing different backup is never overwritten. Android's cloud-backup exclusion covers both locations; device transfer keeps both.

## Manifest

```json
{ "schemaVersion": 3, "documentRevision": 12, "plans": [...], "studyPlans": [...], "studies": [...] }
```

All objects are strict: unknown fields are rejected, and every listed field is required unless marked optional. IDs are lowercase UUID strings. Names are 1–160 UTF-8 bytes, not only whitespace, with no control characters (the existing rule). Timestamps are epoch milliseconds, 0 to 253,402,300,799,999, with `updatedAtMilliseconds ≥ createdAtMilliseconds`. Revisions are positive signed 64-bit integers that advance on every change to that record; an increment that would overflow is refused. `documentRevision` advances on every committed manifest.

Each record's nested contents (candidate sites and their assumptions, study inputs, captured nodes) keep their v2 validation unchanged, as both apps implement it today.

| Object | Fields | Rules |
|---|---|---|
| Candidate-site plan (`plans[]`) | The v2 fields unchanged (`id`, `name`, timestamps, `sites`, optional `selectedSiteID`), plus `revision`. There is no plan-level `notes` field | At most 100, each with at most 100 sites; `selectedSiteID` names one of its sites. Kept separate from study plans. |
| Study plan (`studyPlans[]`) | `id`, `name`, optional `notes`, timestamps, `revision` | At most 100. Name 1–160 UTF-8 bytes; notes at most 4096 UTF-8 bytes. |
| Study (`studies[]`) | The v2 fields (`id`, `name`, timestamps, `kind`, and `linkProfile` or `coverage`), plus `revision`; optional `planID`, `origin`, `importOrigin`, `resultRef` | At most 100. Exactly the payload its kind names. |

Study fields new in v3:

- `planID`: null or absent means standalone. Otherwise it must name an existing study plan. It's the only record of membership; plans don't list their studies. Counts are derived. Moving changes one study; deleting a plan clears its studies' memberships; duplicating creates new IDs. Each such change advances the affected records' revisions and plan timestamps in the same manifest commit.
- `origin`: where the study's inputs were captured. Either `{host, region, capturedAtMilliseconds}`, or null for "Unknown source". `host` is null or a string of up to 253 bytes, `region` null or up to 64 bytes, and `capturedAtMilliseconds` null or a valid timestamp. Legacy studies migrate with null; it's never inferred from the current analyzer.
- `importOrigin`: for an imported independent copy, `{studyID, revision}` of the **immediate** source record. It isn't an ancestry chain, and it doesn't prove two records are identical: copies edited independently can share it, so D compares content before deduplicating. Otherwise null or absent.
- `resultRef`: null or absent means inputs only. Otherwise `{format, version, byteLength, sha256, inputFingerprint}`:

| `format`, `version` | For kind | `inputFingerprint` |
|---|---|---|
| `nodescope.coverage-result`, 1 | coverage | Required |
| `nodescope.link-profile-result`, 1 | linkProfile | Required |
| `legacy.ios-plist-coverage`, 1 | coverage | null: a migrated iOS 0.10.6 binary plist, kept as is |
| `legacy.android-json-coverage`, 1 | coverage | null: migrated Android 0.10.6 JSON (base64-decoded), kept as is |

`byteLength` is 1 to 8 MiB: that's the existing decoded legacy-payload limit, frozen for A1. Portable payload limits are set in B. `sha256` is 64 lowercase hex digits. v3 coverage studies no longer carry the inline `calculatedResult`; migration moves its bytes into a result file.

A legacy format's `version` identifies the wrapper contract, not the native payload. Migration preserves the bytes even when this app can't restore them (for example, the other platform's legacy format). The legacy identifier already names the platform. v2 had no study revisions, so none is invented. A1 also preserves portable references untouched, without needing B's codecs to show them.

Removed from the earlier plan: `baseRevision` and `resultRef` on candidate-site plans. An open editor keeps the revision it opened (in memory, and from C in its recovery draft), and Update succeeds only if that revision is still current, checked inside the serialized library transaction. Stored ancestry waits for a sync design.

## Reading rules

- Only `schemaVersion` 3 is read as v3. Valid v1 and v2 files are read without rewriting; the backed-up migration happens on the first successful v3 change. Anything else, including 4, or any invalid manifest, blocks writes: the library is read-only rather than overwritten (0.10.6 already behaves this way with v3).
- A result file is looked up by `sha256`. A missing file shows the study's inputs with **"Saved result not on this device"**; a length or hash mismatch shows **"Saved result could not be verified"**. Either way the manifest isn't rewritten automatically and the reference is kept, so a later restore or device transfer can bring the file back.
- A missing or unverifiable result keeps its reference through metadata-only changes (rename, move). Once the study's calculation inputs change, the old result can't stay attached as theirs: Update needs a matching new result, an explicitly acknowledged inputs-only save, or Save as new to keep the original. This matters most for legacy references, which carry no fingerprint to catch a mismatch.
- A portable payload is shown as this study's result only if its own `inputFingerprint`, the reference's and the study's recomputed fingerprint all match.
- Legacy results open through each platform's existing reader. Neither platform reads the other's legacy format.

## Writing in A1

A1 keeps today's encoders: a new coverage calculation is saved with the platform's native 0.10.6 encoder, its bytes stored as a result file referenced with that platform's legacy format. Portable payloads start in B. If a later app writes a result larger than A1's 8 MiB ceiling, A1 keeps the reference and shows "Saved result needs a newer version of NodeScope", never truncating or dropping it.

Aggregate budgets, frozen for A1: the manifest at most 16 MiB, everything Android cloud-backs up (the manifest and small records) under 20,000,000 bytes, and all referenced result files 512 MiB in total, shown in Settings. Reaching a budget refuses the save with the plan's capacity message and leaves the previous save unchanged.

## Reference fixture

Generated by `shared/fixtures/saved-plans/make-v3-fixtures.py`, which validates everything it writes with a reference validator (Python):

- `v3/reference/plans.json`: one candidate-site plan; two study plans (one with notes, one empty); six studies:
  1. a coverage study in a plan, with a portable result;
  2. a Link Profile in the same plan, with a portable result;
  3. a standalone coverage study migrated from 0.10.6, with Android's legacy bytes and unknown origin;
  4. an inputs-only Link Profile with adaptive sampling;
  5. a coverage study whose result file is **deliberately absent** (a cloud restore);
  6. an imported copy of study 1 that **shares its result file** by content.
- `v3/reference/results/`: the three result files that exist.
- `v3/v3-cases.json`: 38 cases (7 valid, 31 invalid), each with the reason a reader should refuse it, plus the reference's expectations (missing, shared, legacy, inputs-only and unknown-origin studies).

The Python validator covers the v3 manifest rules only. Nested contents keep their v2 validation, which the apps implement, so a passing case here doesn't by itself establish full validity.

Result payload values are synthetic, not engine output, but follow the field lists and carry correct input fingerprints.

Legacy samples, from the apps' real 0.10.6 code with the same inputs as reference study 3:

- `shared/fixtures/saved-plans/legacy/ios-0.10.6-coverage.plist`: recorded by iOS `LegacyCoverageFixtureTests` from the production encoder (1,706 bytes). Swift's synthesized encoding writes predictions as `{"unavailable": {"_0": reason}}`, which is why it's recorded rather than hand-made. The same test restores it with the production reader and checks every value.
- The Android legacy bytes in the v3 reference (`legacy.android-json-coverage`) restore with Android's production 0.10.6 reader (`LegacyCoverageFixtureTest`), which also refuses the iOS plist, as neither platform reads the other's legacy format.
- v1 and v2 documents for migration: the existing `shared/fixtures/candidate-sites/plan-v1-cases.json` and `plan-v2-cases.json`.
