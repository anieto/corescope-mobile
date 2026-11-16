# Saved Plans v3 export package (deliverable D)

Status: drafted November 16, 2026 as D's first step. The format, limits and reader rules come before either app's parser, as [grouped-saved-plans-plan.md](grouped-saved-plans-plan.md) §11 requires. Reference reader and cases: `shared/fixtures/saved-plans/make-package-fixtures.py` → `packages/package-cases-v1.json` (68 cases).

## File

A `.nodescope` file: a ZIP archive (UTI `org.nodescope.saved-plans`, conforming to `public.data`; MIME `application/vnd.nodescope.saved-plans+zip`). It holds exactly:

```
manifest.json                  nodescope.saved-plans, version 1
results/<sha256>.result        one per distinct result, named by the SHA-256 of its bytes
```

There are no other entries, folders or internal paths, so nothing in a package names a location on the device. A result shared by several studies travels once.

Packages are written by NodeScope only; a file re-zipped by another tool (for example with `__MACOSX/` entries) is refused rather than guessed at.

## Limits

| | Limit |
|---|---|
| Package file | 512 MiB |
| Entries | 1 to 101 (the manifest and at most 100 result files) |
| Central directory | 1 MiB |
| `manifest.json` | 16 MiB expanded (the library manifest's own limit) |
| A result entry | its format's limit: coverage 8 MiB, Link Profile 64 KiB (from B) |
| All entries expanded | 512 MiB (the library's result budget) |
| A compressed entry | at most its expanded size + 1 KiB + 0.1% |

Records keep the library's own counts: at most 100 study plans, 100 studies and 100 candidate-site plans.

## Reading a package

The reader is strict and stops at the first failure. Local data is never touched until the whole package has been read and the user has confirmed the import.

1. **Container.** Size within limits. The end-of-central-directory record is the one whose comment ends exactly at the end of the file (nothing appended). Refused: ZIP64, multiple disks, encryption (flag bits 0, 6, 13), any flag other than deflate options, data descriptor and UTF-8 names, any method other than stored (0) or deflate (8), and stored entries whose sizes differ.
2. **Names.** Each entry name is ASCII and exactly `manifest.json` or `results/` + 64 lowercase hex digits + `.result`. That alone rules out `..`, absolute paths, backslashes and folders. No name appears twice.
3. **Sizes before reading.** Every declared size is checked against the limits before anything is expanded.
4. **Local headers.** Each entry's local header has the same name, flags and method as its central record, and no entry's data runs into the next entry or the central directory.
5. **Expanding.** Inflating stops at the declared size: the data must end exactly there, consume exactly its compressed size, and match its CRC-32. A small entry can't expand into a large one.
6. **Manifest.** Strict JSON, as in B: duplicate fields, non-finite numbers and unknown fields are refused. `format` must be `nodescope.saved-plans`. A `version` above 1 stops here with **"This file needs a newer version of NodeScope"**, before any other check.
7. **Records.** Exactly the v3 library rules ([saved-plans-v3-schema.md](saved-plans-v3-schema.md)) and each record's nested v2 validation, plus the scope rules below.
8. **Results.** Every `resultRef` has its entry, every entry is referenced, and each entry's length and SHA-256 match its reference. Only portable formats travel; a legacy reference refuses the package.
9. **Result contents.** Each result is read with B's strict reader and must match its study's recomputed input fingerprint. A result that fails here, or has a newer result version, doesn't refuse the package: that study is offered **inputs only**, which the user must acknowledge.

Outcomes, as the cases record them: `valid`, `resultUnusable` (with the study IDs), `future` and `invalid` (with the reason).

## Manifest

```json
{
  "format": "nodescope.saved-plans", "version": 1,
  "scope": "study" | "plan" | "library",
  "exportedAtMilliseconds": 1794790000000,
  "exportedBy": { "platform": "ios" | "android", "appVersion": "0.10.7" },
  "includesResults": true, "includesNotes": true,
  "candidateSitePlans": [ ... ], "studyPlans": [ ... ], "studies": [ ... ],
  "omittedResults": [ { "studyID": "...", "reason": "notOnDevice" } ]
}
```

- Records are v3 library records exactly as stored: IDs, names, historical dates, revisions, `origin` and `importOrigin` included. An optional field may be absent or null (iOS omits it, Android writes null); readers treat both alike. `exportedBy` is provenance only, and `appVersion` is 1 to 32 characters from `0-9 A-Z a-z . + -`.
- **Scope.** `study`: exactly one study, without `planID`, and nothing else. `plan`: exactly one study plan and only studies whose `planID` is that plan, no candidate sites. `library`: everything the saved library holds (candidate-site plans, study plans, every study including standalone ones), each `planID` naming a plan in the package.
- **Notes.** With `includesNotes` false, no study plan carries `notes` (absent or null). Plan notes are the only free-text notes in the library.
- **Results.** With `includesResults` false, no study carries `resultRef`. Otherwise a study whose result couldn't be included has no `resultRef` and appears once in `omittedResults` with why: `notOnDevice` (its file is missing), `couldNotBeVerified` (length or hash mismatch), `needsNewerVersion` (a result this app can't read), or `notReadableHere` (a legacy result this platform can't convert). Nothing is dropped silently.
- **Legacy results** are converted to the portable format on export when this platform can read them: iOS converts its own plist results and Android its own JSON results, the way B converts them on the next save.

## Exporting

- From a study, a plan (its menu) or the Saved Plans home (Back up library).
- Choices: **Include available results** or **Inputs only**. A library backup defaults to results, the others too when every result is available. Then **Include plan notes** (default on).
- **Preview before sharing**, listing what the file reveals: the number of studies and plans; precise coordinates (each study's sites or endpoints); node names and IDs captured in Coverage studies; analyzer and region (`origin`); plan notes, if included; and results that won't be included, with why. Coordinates aren't redacted: a study without its coordinates couldn't be reproduced, so the preview says so instead.
- Never included: recovery drafts, terrain and other caches, credentials, tokens or app settings.
- iOS: the share sheet and Save to Files. Android: the share sheet and Save to device (the system file picker). Finishing the file operation doesn't mean a cloud provider has finished uploading, and the app doesn't say so.
- File names: `<name>.nodescope` for a study or plan, `NodeScope saved plans <date>.nodescope` for a library.

## Importing

- Opening a `.nodescope` file (from Files, the share sheet, or **Import** in Saved Plans) reads it completely and shows a preview: what it holds, what's already in this library, which results are included and which can't be.
- **Copies, never overwrites.** Imported records get new IDs; memberships are remapped to the new plan IDs in the same library transaction; each study's `importOrigin` is the package record's `{studyID, revision}`; revisions start at 1. Names, inputs, historical dates, `origin` and results are kept exactly. A name already in use gets the library's usual unique suffix. Nothing is matched by name or ID alone.
- **Identical records.** A package study is identical to a local one when their kind, name, inputs, historical dates, `origin` and result (SHA-256, or none) are equal and the local one sits in the plan the import maps its plan to. A plan is identical when name, notes and dates are equal. A **library** import skips identical records by default and imports everything else as copies; a study or plan import shows the match and lets the user import a copy anyway. There's no Replace entire library.
- **Results.** Result files are installed before the manifest commit, under the library's existing rules: by content, reusing an identical file, and kept by cleanup only once referenced.
- **Inputs only.** A study whose result can't be imported (`resultUnusable`) is listed; importing it as inputs only needs an explicit acknowledgement, otherwise it's left out.
- **Capacity.** If the import would exceed the library's counts or 512 MiB result budget, nothing is imported and the preview offers a smaller selection or Inputs only. There's no partial success.
- Importing never calculates, fetches terrain, loads nodes or changes the analyzer.

## Steps

1. This document and its cases.
2. Package reader and writer on both platforms, passing the shared cases.
3. Export: choices, preview and sharing.
4. Import: preview, copies and merge, capacity, opening files from other apps.
5. Tests and tester notes, including iOS ↔ Android round trips.
