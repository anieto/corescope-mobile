# Saved Plans v3, deliverable A1: storage and migration

Status: implemented November 15, 2026 on the local branch `feature/saved-plans-v3` (Android `4da3e67`, iOS `48b203c`). Not released and not pushed. Device checks remain (below). Settings → Cache & Storage → Saved Plans shows result storage against the 512 MiB budget and what OS backups cover. Schema: [saved-plans-v3-schema.md](saved-plans-v3-schema.md).

## What changed

Both apps read saved-plan libraries of versions 1 to 3 and save version 3. Screens and the save flow are unchanged apart from the result messages.

- **Result files.** A saved coverage result moves out of `plans.json` into `CandidateSites/results/<sha256>.result`, byte for byte, labelled with the platform's legacy format. Studies with identical bytes share one file. Renaming or moving a study never rewrites result bytes.
- **Migration.** Opening a v1 or v2 library changes nothing on disk. The first save writes v3, after keeping the exact original: an identical existing `plans.json.v1-backup` is reused; otherwise the original goes to `migration-backups/<sha256>.json`. An existing different backup is never overwritten.
- **Save order.** Result files first, then the backup, then the manifest (atomic replace), then removal of result files nothing references. An interrupted save leaves at worst unreferenced files.
- **Revisions.** `documentRevision` advances on every save; each plan or study's `revision` advances only when that record changed. Saving from an editor whose study was changed elsewhere since it opened is refused; a study deleted while open is saved as a new one.
- **Strict reading.** Unknown fields, missing required fields (including nullable ones), inline results in v3 and any other schema version are refused; a refused library can't be saved over. Both apps pass the 38 shared cases.
- **Limits.** Manifest 16 MiB; each result up to 8 MiB; all referenced results 512 MiB. Reaching a limit refuses the save and leaves the previous one intact.

## Result states

| State | Shown as | Happens when |
|---|---|---|
| Available | The saved result, as before | The file is present and its length and SHA-256 match |
| Missing | "Saved result not on this device" | The file isn't there, for example after a cloud restore that excluded it |
| Unverified | "Saved result could not be verified" | The file's length or hash doesn't match |
| Unsupported | "Saved result needs another version of NodeScope" | A format this app can't show: the other platform's legacy result, or a portable result (from B) |
| None | Inputs only | No result was saved |

In every case the study's inputs still open, nothing is calculated automatically, and the reference is kept. A missing or unverified result stays attached through renames and moves, but not once the calculation inputs change.

## Backups

- **iOS:** `CandidateSites` stays in Application Support, included in iCloud and device backups.
- **Android cloud backup:** `CandidateSites/plans.json` only, so results and migration backups can't push the app over the 25 MB quota. After a cloud restore, saved coverage results show as missing.
- **Android device transfer (12+):** the whole `CandidateSites/` folder, results and backups included.
- **Android 8–11:** `plans.json` only.

## Still to do in A1

1. **Device checks** (below). Unit tests and file copies don't establish restore behaviour.

## Tester checklist

Record device, OS version, build and transport for each.

- [ ] Install over 0.10.6 with saved coverage and Link Profile studies: everything opens, coverage results restore, no calculation runs.
- [ ] Rename a migrated study: result still restores; `plans.json` no longer contains `calculatedResult`; the original is in `migration-backups/` or the existing `.v1-backup`.
- [ ] Android cloud backup and restore, with results below and above the budget: studies and inputs come back; coverage results show "not on this device".
- [ ] Android phone-to-phone transfer: results come back and restore.
- [ ] iOS device restore (iCloud or Finder backup): results come back and restore.
- [ ] A library written by a newer version (schema 4): the app refuses to save and leaves the file untouched.
- [ ] Open the same library on the other platform (via file copy): the other platform's legacy result shows as needing another version, and its inputs open.

## Automated coverage

- Android: `SavedPlansV3Test` (9), plus the updated `DeploymentPlanStoreTest`, `SavedStudyTest` and `SavedStudiesTest`; 585 tests with only the 11 known failures.
- iOS: `SavedPlansV3Tests` (8), plus the updated `SavedStudyTests` and `DeploymentPlanStoreTests`; all pass.
