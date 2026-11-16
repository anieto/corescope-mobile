# Saved Plans v3, deliverable C: recoverable drafts

Status: implemented November 16, 2026 on the local branch `feature/saved-plans-v3` (draft store `4d6bef4`, autosave `a1acbba`, resume and discard `6df2cc2`, source switching `b2d3b99`). Not released and not pushed. Design: [grouped-saved-plans-plan.md](grouped-saved-plans-plan.md) §10.

## What changed

- **Drafts.** Each Link Profile or Estimated Coverage session keeps its unsaved work in its own file, `CandidateSites/drafts/<id>.json`, beside the library and outside every cache (clearing caches never touches it). A draft holds the raw editor values (on Android, including text that isn't a valid number yet), the analyzer and region, the plan the tool came from, the saved study it edits and the revision it opened, Coverage's captured node catalog, and any calculated result as an ordinary result file. Sessions never overwrite one another.
- **Autosave.** Shortly after changes, when the app leaves the foreground and when the tool's screen goes away. A draft exists only while something is placed and differs from what was opened or last saved; a successful Save removes it, and later edits start a new one. Nothing calculates because of a draft.
- **Continue unfinished study.** At the top of Saved Plans: one draft resumes from there; several open an Unfinished studies list (tool, name or place, analyzer, edited time). Discard asks first. Drafts whose tool is still open aren't listed. Resuming restores the values, catalog, plan and result without fetching terrain or calculating; Update still refuses a study changed since, and a study deleted since is offered Save as new.
- **Source switching.** Choosing another analyzer first writes every open tool's work to its draft. If that fails, the switch waits: "Unsaved work couldn't be kept" with Retry, Cancel or Switch without saving. When it succeeds there's no prompt. Both apps still close open tools on a switch (Android rebuilds navigation, iOS resets its tabs); the work comes back from Saved Plans.
- **Android process death.** A tool entry that had a draft session restores from it, so a Coverage study killed in the background returns with its unsaved changes.
- **Storage bound.** At most 50 drafts; a new one beyond that is refused, never by evicting another. Library cleanup keeps every result file a draft refers to, and deletes nothing while any draft can't be read.
- **Also fixed (iOS).** Deleting a saved study by swiping crashed the list: the swipe button's destructive role removed the row before the confirmation deleted the study. Delete and Discard confirmations are now centred alerts.

## Tester checklist

Both platforms unless noted.

1. Analyzer A: open a Link Profile, place both points, change a height; don't save. Switch to analyzer B: no prompt. Switch back to A, open Saved Plans: "Continue unfinished study" shows it. Resume: the points, height and result are back; nothing loads or calculates.
2. Same with an Estimated Coverage study reopened from Saved Plans and edited: its captured nodes stay as saved (no mixing with the new analyzer's nodes) until Refresh nodes.
3. Save the resumed study: the Continue entry goes away. Edit again and leave: it comes back.
4. Leave three tools with unsaved changes: Saved Plans shows "Unfinished studies · 3"; the list is newest first. Discard one (swipe on iOS): it asks, then it's gone.
5. Open a tool, edit it, then open Saved Plans from that tool's Open button: its own draft isn't offered.
6. Switch analyzer while a coverage calculation is running: the calculation stops; the resumed draft has the last finished result or none, never a half-shown one.
7. Switch to an analyzer that can't be reached, or go offline: drafts still resume, with their saved node catalog and result.
8. Android: edit a Coverage study, background the app with "Don't keep activities" on (or kill it from Android Studio), reopen: the study comes back with the edits.
9. iOS: edit a study, force-quit the app, relaunch: Saved Plans offers to continue it.
10. Settings → Cache & Storage → clear caches: drafts and saved plans are unaffected.
11. Swipe-delete a saved study (iOS): the confirmation is centred and the app doesn't crash.

## Known limits

- The "couldn't be kept" prompt appears only when the device can't write (for example, full storage); it's covered by automated tests rather than an easy manual check.
- iOS tools hold numbers, not text, so an incomplete number being typed isn't kept on iOS; Android keeps the raw text.

## Automated coverage

`StudyDraftStoreTests` (iOS, 8) and `StudyDraftStoreTest` (Android, 6): independent sessions; raw text and catalog round trip; a draft's result survives library cleanup until the draft goes; an unreadable draft stops cleanup; 50-draft limit without eviction; a failed write is reported to the source switch while other sessions save. Tool-level tests (Android `SavedStudiesTest`, iOS `StudyDraftStoreTests`): drafts appear only with unsaved placed work, Save clears them, results and catalogs are kept, and Link Profile and Coverage drafts resume exactly without terrain requests.
