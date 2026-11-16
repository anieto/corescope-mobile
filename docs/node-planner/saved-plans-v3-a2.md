# Saved Plans v3, deliverable A2: plans and the save flow

Status: implemented November 16, 2026 on the local branch `feature/saved-plans-v3` (iOS `ec420e9`, Android `5c6aa64`, Candidate Sites visibility `27fddc3`, inputs-only confirmation in the commit that adds this note). Not released and not pushed. Product design: [grouped-saved-plans-plan.md](grouped-saved-plans-plan.md) §3. Storage: [saved-plans-v3-a1.md](saved-plans-v3-a1.md).

## What changed

- **Saved Plans home.** Plans (folder, counts by study type, last updated), then standalone studies, then Candidate Sites. Sorted by last update, ties by ID. Search covers plan names, notes and study names; a matching study shows the plan it's in. Opened from a tool, a type filter starts on that tool and Candidate Sites is hidden. New plan: toolbar + on iOS, a floating button on Android.
- **Plan screen.** Notes, an All / Link Profiles / Coverage filter with counts, and the plan's studies. + offers New Link Profile, New Estimated Coverage (both suggest saving into this plan) and Add existing study (moves, never copies). Plan actions: Edit name and notes; Delete plan, which keeps its studies as standalone. A deleted plan shows "Plan not found".
- **Study actions.** Rename, Move to plan, Duplicate ("Name copy", then "Name copy 2") and Delete study. iOS: long-press menu plus swipe for Delete and Move. Android: each row's ⋮ menu; the always-visible red Delete button is gone. Renaming and moving never change inputs or results. A duplicate shares the original's result file, even one this device can't read.
- **Save.** A sheet on both platforms (Android's growing dialog is replaced by a bottom sheet). A study already in the library offers Update existing (default) or Save as new, which suggests a distinct name. Save in: Standalone, a plan, or New plan… (the plan and its first study are saved together, so Cancel creates neither). The default destination is where the study is now, else the plan the tool was opened from, else Standalone. The sheet closes only after a successful save and, on Android, survives rotation.
- **Deliberate behaviour change.** Tapping the name field no longer erases the suggested or existing name, on both platforms.
- **Stale edits.** Tools now pass the revision they opened. An Update is refused if the study was changed, moved or deleted since; Save as new still works. (Before this, the A1 check never fired, because tools didn't pass the revision.)
- **Inputs-only confirmation.** If a study's saved result can't be shown here (not on this device, unverified, or another version's format) and its calculation inputs changed without a new result, Update requires "Save inputs only". Save as new keeps the original and its result reference. Metadata-only changes keep the reference.
- **Link Profile → Coverage** carries the Link Profile's current plan (or the plan it was opened from) as the suggested destination. Coverage stays a new, unsaved study; Back returns to the Link Profile.
- **Candidate Sites** appears on the home only when at least one candidate-site plan exists. The data and screen are unchanged.

## Tester checklist

Both platforms unless noted.

1. Create a plan from the home; it appears under Plans with "No studies".
2. In the plan, + → New Link Profile, place points, Save: Save in shows the plan. Save; the plan lists it.
3. Reopen it, change a height, Save: Update existing is selected; Update. Same study, plan's date updates.
4. Reopen, change something, Save as new: suggested name is "… copy" (or "… copy 2"); both studies exist, the original unchanged.
5. Open a saved study in a tool, tap Open, switch the filter to All and move that same study to another plan. Go Back to the tool and Update: refused as changed elsewhere; Save as new works.
6. Save with Save in → New plan… and a name: plan and study appear together. Repeat and Cancel: neither appears.
7. Delete a plan with studies: the dialog says they stay; they appear under Standalone studies.
8. Duplicate, Rename, Move, Delete from a study's menu; Delete asks with the study's name.
9. Search for a plan's notes text and for a study name inside a plan; the study row shows its plan.
10. From a tool's Open: the filter starts on that tool; switch to All. Candidate Sites isn't listed.
11. From a Link Profile in a plan, Estimate coverage from A: the coverage Save sheet suggests the same plan; Back returns to the Link Profile with its inputs. Nothing calculates on its own.
12. Tap the name field in the Save sheet: the name stays.
13. Android: rotate with the Save sheet open (name edited, a plan chosen): everything is kept.
14. A study whose result is missing (restore a backup without results, or the shared reference library): rename it and Update, no prompt; change an input and Update, "Save inputs only" is required.
15. With no candidate sites saved, Candidate Sites doesn't appear on the home; with one, it does.

## Known issues

- **iOS Link Profile route (not caused by A2).** After Calculate on a reopened study, the route sometimes stays dark until the map is panned or tapped. Intermittent, from plans and from standalone studies alike. Unique overlay IDs, cached polylines and a different navigation path were each tried and reverted; none fixed it.

## Automated coverage

`StudyPlanLibraryTests` (iOS) and `StudyPlanLibraryTest` (Android), eight tests each: new plan and first study in one save; stale update refused while Save as new works; an old editor can't undo a later move; Update keeps identity and advances the revision; saving into a missing plan keeps the draft; deleting a plan keeps its studies; duplicates are independent and named distinctly; changing inputs under an unreadable result needs confirmation (shared reference library). Android's `SavedPlansV3Test` also checks that Update refuses a deleted study and Save as new then succeeds.
