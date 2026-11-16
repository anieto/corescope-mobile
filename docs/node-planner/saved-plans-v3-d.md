# Saved Plans v3, deliverable D: export, import and sharing

Status: implemented November 16, 2026 on the local branch `feature/saved-plans-v3` (format and cases `4ad507a`, reader and writer `9fd3aa8`, study origin fix `1dc0405`, export `bee5dd1`, import `38f023b`, round trips and these notes in the commit that adds them). Not released and not pushed. Format: [saved-plans-v3-package.md](saved-plans-v3-package.md). Design: [grouped-saved-plans-plan.md](grouped-saved-plans-plan.md) §11.

## What changed

- **`.nodescope` files.** A study, a plan, or the whole saved library (plans, standalone studies and candidate sites) exports to one file that both apps read. Unfinished studies, caches, credentials and settings are never included.
- **Export.** Study menu → Export…, plan menu → Export plan…, Saved Plans → Back up library… (iOS: the toolbar's ••• menu; Android: the button above the storage note). Choose **Include available results** (on for a backup; for a study or plan, on only when every result can travel) and **Include plan notes**. The preview lists what the file reveals: precise locations, node names and IDs, analyzers and regions, plan notes, results and their size, and any result left out with the reason. Locations aren't redacted. Share it, or save it (iOS: Save to Files in the share sheet; Android: Save to device).
- **Older results convert on the way out.** Each platform converts its own 0.10.6 coverage results to the shared format, so they open on the other platform. One saved in the other platform's old format is left out and listed.
- **Import.** Open the file from Files, Mail, Messages, AirDrop, a file manager or another app's share sheet, or use Import… in Saved Plans. The whole file is read first and previewed: where it came from, each plan, study and candidate site list (New / Already in your library / N not in your library yet), results included, results left out when exported, and results that can't be used here.
- **Copies, never overwrites.** Imported records get new IDs, plans are remapped, names, inputs, dates, analyzer and results are kept, and each study remembers the record it was copied from. A clashing name gets " 2". A library file skips what's already here unchanged; a study or plan already here imports as a copy ("Import copy"). Everything is added in one save.
- **Results that can't be used** (changed inputs, a newer result version, damaged contents) are left out unless you switch on **Import these as inputs only**.
- **Limits.** Over 100 studies, plans or candidate site lists, or over the 512 MiB result budget: nothing is imported, with the reason. A damaged, foreign, oversized or newer-version file imports nothing and says so. Importing never calculates, fetches terrain or changes the analyzer.
- **Fixed along the way: study source.** New studies never recorded their analyzer (A2), so everything showed Unknown source. Coverage now records the analyzer, region and time its nodes were loaded, keeping a reopened study's source while its saved nodes are in use; Link Profile records the analyzer unless neither endpoint moved. Studies saved before this stay Unknown source until their inputs are captured again (Refresh nodes, or moving an endpoint): the source is never guessed.

## Tester checklist

1. **Round trip, iPhone → Android.** Back up the library on iPhone with results; AirDrop or email it to the Android phone; open it. The preview lists everything as New; Import. Plans, studies and candidate sites appear; reopening a study shows its result with "Saved result · calculated …" and nothing loads.
2. **Round trip, Android → iPhone.** Same in reverse, including an Android Coverage study saved on 0.10.6 (its result now opens on iPhone).
3. **Restore onto the same device.** Import your own backup: everything shows Already in your library and nothing is ticked. Tick a plan anyway and Import: "Everything selected is already in your library", and nothing changes.
4. **Divergent copies.** Export a plan, then edit one of its studies and save. Import the plan file: it's offered as a copy ("Import copy"); afterwards both versions exist, nothing replaced.
5. **Study twice.** Export a study and import it twice: "Name" and "Name 2".
6. **Notes.** Export a plan with notes off: the preview drops the notes line; the imported plan has no notes.
7. **Inputs only.** Export with results off: the recipient sees inputs only and Calculate.
8. **Missing result.** A study whose result isn't on the device: results start off for that study or plan; switching them on lists it under Results not included.
9. **Not a NodeScope file.** Import a photo or a renamed zip: "Can't import this file", nothing changes.
10. **Cancel.** Cancel the file picker, the share sheet, Save to device and the import preview: Saved Plans doesn't change.
11. **Analyzer line.** Save a new Coverage study and a new Link Profile, then export: the Analyzer line shows your analyzer and region.
12. **No unrelated studies.** Export one study from a plan: the recipient gets only that study, standalone, with no plan.

## Known limits

- Android's Import… picker shows all files: most storage apps don't know the `.nodescope` type. The preview refuses anything else.
- iOS can't convert Android's 0.10.6 coverage results (and the reverse), so a library on a device migrated from the other platform travels with those results left out and listed.
- A cloud provider may still be uploading after Save to Files or Save to device finishes; the apps don't track it.
- Android's Link Profile still has no refraction or sample-count controls (B), so an iOS study using them imports with its result but loses them if saved again on Android.
- Android unit test `MyNodesBaseRepositoryTest.observerPagesShareBudgetAndWarmCache` is intermittently failing (a request-budget race, outside saved plans); the 11 earlier known failures are unchanged.

## Automated coverage

- **Format:** 68 shared cases (`shared/fixtures/saved-plans/packages/package-cases-v1.json`, from `make-package-fixtures.py`) through `SavedPlansPackageTests` / `SavedPlansPackageTest`, including path tricks, duplicate entries, a compression bomb, checksums, ZIP64 and encryption; every valid package writes and reads back identically.
- **Export:** `SavedPlansExportTests` / `SavedPlansExportTest`: scopes, notes, omissions with reasons, own-platform legacy conversion, the preview's counts.
- **Import:** `SavedPlansImportTests` / `SavedPlansImportTest`: library restore with results, skipping identical records, repeated study import, unusable results, capacity.
- **Round trips:** `CrossPlatformPackageTests` / `CrossPlatformPackageTest`. Each platform records `packages/<platform>-library-v1.nodescope` from the reference library and restores the other's; the Python reference also validates both files. The iOS file is recorded the first time the iOS test runs.
- **Study source:** `SavedStudyRestorationTests` / `SavedStudiesTest`, `studiesRecordWhereTheirInputsCameFrom`.
