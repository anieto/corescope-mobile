# Named plans, portable results, and recovery

Status: approved to start the contract gate; v3 application implementation has not started. Gate evidence and outstanding checks are tracked in [saved-plans-v3-contract-gate.md](saved-plans-v3-contract-gate.md). Applies to iOS and Android. This document replaces the earlier incremental plan and its superseded scope decisions.

## 1. Release boundary and existing working-tree changes

0.10.6 is frozen at `c31936e`, with tester notes and store notes already written, iOS build 2 and Android versionCode 27, not yet uploaded. This work must not change those artifacts or release notes.

Work is on local-only `feature/saved-plans-v3`, created from `c31936e`. Commit `c149ac7` enables Android backup/device transfer only for `filesDir/CandidateSites`; preserve that scope. Target 0.10.7 or later. Each deliverable gets its own tester build, notes, and fresh build numbers. For the first 0.10.7 tester build, provisionally use iOS build 1 and Android versionCode 28, checking for intervening uploads first; increment subsequent builds.

The carried Link Profile work was reviewed and committed separately as `f0dbd73`. It comprises:

- Link Profile height presets, antenna presets and exact height entry on both platforms.
- Default/custom equipment summaries and the filled-blue Adjust antennas & radio action.
- Estimate coverage from here in endpoint cards and pin menus, including equipment transfer, navigation and opening coverage setup.
- Android coverage-handoff regression tests.

The ordinary-tap placement experiment was reverted; touch-and-hold behavior remains. These changes belong on the new branch, not in 0.10.6. Review against the branch baseline found these nine changed source/test files belong to this work. Xcode BuildProject, Android assembleDebug and Link Profile/Coverage unit tests passed on November 15, 2026. Device layout/navigation checks remain pending. They need new tester notes and builds; no release artifacts were replaced or uploaded.

## 2. Product model

A plan is a named collection for a deployment question. A study is an independent Link Profile or Estimated Coverage snapshot. Each study belongs to zero or one plan. Standalone studies remain available. Candidate Sites remains a separate feature; its existing DeploymentPlan records are not repurposed as these containers.

No nested plans, shared editable endpoints, automatic run history, or implicit saving. Calculating changes the working draft; Save records a snapshot. Save as new preserves alternatives. Opening, importing, moving or restoring a study never implicitly calculates.

Deliverables A1 and A2 preserve current result support: Link Profile inputs only, Coverage available legacy results. Deliverable B adds portable Coverage results and historical Link Profile results. The UI always identifies Inputs only versus Saved result accurately. Legacy Link Profiles never acquire fabricated historical results.

## 3. UI and navigation

### Saved Plans home

Use one vertically scrolling list:

1. Plans: folder icon, name, counts by study type and last updated date.
2. Standalone studies: type icon, name, result availability and last updated date.
3. Candidate sites: existing separate entry.

Toolbar + creates a plan. Sort by most recently updated with a stable ID tie-breaker. Search matches plan names, notes and study names. Matching contained studies appear directly with their plan name; users need not open a container to reach a search result. Duplicate names are allowed; type, containing plan and date provide context.

Use a short local-storage footer rather than a large introductory block. Empty state offers Create plan and explains that tools can also save standalone studies. No grid, nested folders, extra tabs or mandatory creation wizard.

Open from a tool uses this library with a visible type filter initially set to that tool. Users may switch to All. Show matching standalone studies and plans containing matching studies; retain the filter in plan details and show matching/total counts where useful. Hide Candidate Sites in the tool-specific picker context.

### Plan detail

Use the plan name as navigation title, optional notes, then one mixed study list with All / Link Profiles / Coverage filtering. Wrap names and use textual type labels, not color alone.

Add study offers New Link Profile, New Estimated Coverage, and Add existing study. New tools inherit the originating plan as their suggested save destination. Add existing moves the study, showing its current location; it never silently duplicates a result.

Plan actions: Edit name and notes; Delete plan. Study actions: Rename, Move to plan, Duplicate, Delete study. iOS swipe actions may supplement an accessible overflow menu. Do not permanently expose a red Delete button on every Android row.

Duplicate creates a new study ID and fresh record timestamps, suggests a Copy name, and preserves the independent snapshot and original calculation timestamp. Immutable result bytes may be shared internally. Rename/move only change metadata and never fetch terrain or recalculate.

Delete study confirms its name. Delete plan explicitly keeps its studies under Standalone and performs reassignment/deletion atomically. No delete-plan-and-all-studies option in this iteration.

### Save flow

Use a compact native sheet with a scrollable form on both platforms; replace Android's growing alert form. Primary fields: Study name and Save in. Destinations: Standalone, existing plans, Create new plan. Inline creation requires a name; notes can be edited later. New plan plus first study commits as one transaction, so Cancel creates neither.

New study: Save study. Reopened study: explicit Update existing / Save as new choice, initially Update existing. Display the existing name and destination. Save as new generates a new UUID and timestamps and suggests a distinct name. Confirmation wording reflects the selected action.

Deliberate behavior change: both platforms currently clear suggested names on first focus. Change both together to preserve the text on focus, permitting normal editing or selection. Do not erase existing or suggested names automatically.

Retain form state through Android configuration changes and recoverable errors. Prevent duplicate submissions. Dismiss only after successful persistence. Successful Save binds the tool to the exact saved record and revision returned, including a new identity after Save as new.

Destination priority: current library membership of an existing study; otherwise explicit originating plan; otherwise Standalone. Do not use a global last-used-plan default. If the target plan disappeared, retain the draft and request another destination. If the saved study disappeared, offer Save as new rather than resurrecting it. A changed record requires Reload or Save as new, not silent overwrite. An old editor must not undo a newer membership move.

### Link Profile to Coverage

Carry optional suggested plan ID through the equipment/location handoff. Prefer the saved Link Profile's current membership; otherwise its originating plan. Coverage is a new independent study: do not reuse the Link Profile study ID or save automatically. Open setup for review and require Calculate. Back preserves the Link Profile draft.

### Presentation

Use stable-ID navigation routes and list identity. Do not put result blobs into routes. Missing records show a recoverable not-found screen. iOS pushes details/tools and Android mirrors the back-stack behavior.

Reuse shared mesh backgrounds and readable surfaces; grouped iOS sections use instrumentListRows. Maps/charts retain their current treatment. Monaspace for names/titles and scan values; system font for notes, labels and instructions. Use lazy lists. Check large text, VoiceOver/TalkBack, keyboard behavior, light/dark modes and compact/wide layouts.

## 4. Contract gate before deliverable A1

Define and review portable result contracts before implementing the v3 library. Codec implementation belongs to B, but format identifiers, versioning, required fields, units, size limits and result-reference semantics must be settled before A1.

Current Coverage payloads are not portable:

- iOS SavedCoverageCalculation uses a binary property list.
- Android uses JSON identified by `nodescope-android-json`, base64-encoded in calculatedResult.
- Each platform rejects the other's result payload and can open the inputs alone.

Neither legacy encoding is the new common format. Define shared UTF-8 JSON payloads:

| Format identifier | Initial version |
|---|---|
| nodescope.coverage-result | 1 |
| nodescope.link-profile-result | 1 |

Both platforms read and write the same schema. Require explicit SI units, stable enum strings (or explicitly assigned raster state codes), finite numeric values, explicit nullability, epoch-millisecond timestamps, model/algorithm identifiers, exact inputs and available provenance. Fingerprint every numeric input using its raw IEEE-754 binary64 bit pattern in a fixed documented field order, following the bit-pattern approach in CoverageRunDiagnostics.predictionFingerprint on both platforms. Define byte order, optional-value tags, integer encoding and string framing. Never hash decimal-formatted doubles or arbitrary serializer output. Payload JSON numbers must round-trip to the same binary64 values; the input fingerprint is independent of JSON formatting.

Measure columnar raster JSON (parallel state-code, loss and margin arrays, with a separate node array) against per-cell objects before freezing payload limits. Use representative partial/full grids, the 60,000-cell bound, node/terrain overhead and worst-length finite values. Record raw and compressed bytes; limits apply to decoded bytes, not compressed size.

Coverage preserves the information required to restore raster, node predictions, calculation status and continuation eligibility. Link Profile preserves the request, sampled terrain, bearing, verdict, chart/path outputs and calculation metadata. Enumerate fields against both concrete engines before freezing the specification; do not serialize platform UI objects.

Each platform retains its own legacy Coverage reader. Converting a local legacy result for portable export means decoding it and encoding the common format, not wrapping incompatible original bytes. Do not discard unknown legacy payloads during metadata-only operations. Cross-platform reading of the other platform's legacy format is not required for the new portable workflow.

Shared fixtures cover iOS write → Android read/write → iOS read, Android write → iOS read/write → Android read, each native legacy format → portable format, and invalid/mismatched/future-version payloads. Assert semantic values, samples, classifications and original calculation times, not just successful JSON parsing.

## 5. V3 schema: revisions and provenance

Retain candidate-site `plans` and `studies`; add `studyPlans` and optional SavedStudy `planID`. Membership is represented only by planID, not a second list of owned IDs.

Include the following metadata in v3 now:

| Field | Purpose |
|---|---|
| revision | Monotonically advancing local record revision |
| origin | Captured analyzer host/identifier, region and capture time where known |
| importOrigin | Original record identity and revision for independent imported copies, where applicable |
| resultRef | Optional immutable result reference |
| documentRevision | Revision of the complete manifest transaction |

Study-plan records have stable IDs, name, optional notes, revisions and creation/update timestamps. Mutable Candidate Sites records receive equivalent revision protection. Use matching Swift/Kotlin field names and stable lowercase UUID strings. Initial migrated revisions are deterministic. Omit baseRevision from v3: the current stale-editor check needs only the revision captured on open. Distributed ancestry is future sync design unless the contract gate establishes a present use.

Legacy origin is Unknown source unless recoverable from trustworthy stored metadata. Never infer it from the active analyzer. Preserve calculation provenance separately from record creation/save events.

Open editors retain an expected record revision. Update is conditional on matching the current revision. Renames, moves and payload changes advance revisions. Mutations update relevant plan timestamps; moving updates both source and destination. Opening does not modify timestamps. Resolve current membership from the library rather than overwriting it from stale editor state.

These fields support local concurrency and future evolution, not an implemented sync protocol. Device identities, distributed revision reconciliation, tombstones and remote conflict handling remain design-only.

## 6. V3 storage: immutable results and atomic manifest

Move result bytes out of the whole-document file in deliverable A1. Keeping an 8 MiB result embedded in a 32 MiB library is not acceptable for plans containing multiple alternatives, and renames must not rewrite every result.

Proposed layout:

```
CandidateSites/
    plans.json
    results/
        <sha256>.result
    migration-backups/
        ...
```

The manifest contains metadata, inputs, membership and result references. A reference identifies format/version, byte length, checksum and input fingerprint where available. Result files are immutable and content-addressed. Identical payloads may be shared by independent studies; editing one never mutates shared bytes.

Commit sequence:

1. Validate the proposed manifest and payloads.
2. Write new payloads to temporary files.
3. Flush and atomically install payload files.
4. Atomically replace the manifest.
5. Publish the committed state to UI.

Before manifest replacement, failure leaves the old library valid. Orphan files are safe and cleaned separately; committed references must never point to partially written files. Garbage collection respects current manifests, retained backups and, once C exists, draft references. Do not delete results on the basis of one screen's stale state.

A1 migrates legacy payload bytes to immutable files, labeling their real format, without requiring codec conversion. B adds conversion and writes portable payloads for new calculations. Rename/move preserves unconverted historical bytes.

Capacity policy:

- At most 100 study-plan containers and 100 studies for this iteration.
- Existing 160 UTF-8-byte name limit; plan notes at most 4096 UTF-8 bytes.
- V3 manifest and all cloud-included small records together must remain below 25 MB. Target a 16 MiB manifest limit with a 20,000,000-byte total cloud selection budget, leaving transport headroom. Validate maximum captured-node/string sizes at the gate. Keep the legacy 32 MiB read limit for lossless migration; if an extracted legacy manifest exceeds the new write limit, retain the old library intact and offer explicit remediation, never truncate it.
- Retain legacy format limits, including their existing 8 MiB result bound.
- Freeze portable per-payload limits at the contract gate after measuring JSON/base64 expansion and representative worst-case fixtures.
- Initially budget 512 MiB for referenced saved-result files; show usage. Account for retained migration backups separately in A1, and drafts in C, and check physical free space for transactional writes.

Capacity errors leave previous saves unchanged. Example: “There isn’t enough storage to save this result. Your previous save is unchanged. Free space, remove an older study, or save inputs only.” Never silently omit results. Replacing a historical result with inputs-only data requires explicit acknowledgement.

## 7. Migration and forward compatibility

Read v1/v2 without rewriting on load. First v3 mutation backs up exact original bytes, extracts legacy results, adds membership/revision/provenance defaults and atomically commits v3. Preserve Candidate Sites, study IDs, timestamps, inputs, captured nodes and original result bytes.

Support direct upgrades from v1 or v2. Interrupted migration must be retryable; verify an existing backup instead of overwriting a different one. Both platforms already write `plans.json.v1-backup` during v1→v2 migration. Recognize that exact path alongside migration-backups/, index backups by checksum and reuse identical bytes in place. Do not create a second backup of the same bytes. A distinct v2 source still needs its own exact-byte backup. Never replace a differing existing backup or confuse a v1 backup with the v2 migration source. Preserve the original library until payload installation and manifest commit can succeed. Migration never recalculates.

Keep the compatibility protection already present in 0.10.6: unknown schema versions refuse to load, and a library that failed to load cannot save. Thus 0.10.6 cannot overwrite v3. V3 must likewise refuse future v4 data without modifying it. Update every Candidate Sites and study writer so none drops new fields or downgrades the manifest.

Use one shared library owner and serialized atomic mutations. Explicitly test external file changes, stale editors and missing/corrupt result files. A damaged payload must not trigger an implicit rewrite or silent deletion of unrelated good records.

## 8. Deliverables and tester gates

| Deliverable | Scope | Acceptance for its tester build |
|---|---|---|
| A1 — V3 storage and migration | Schema, revisions/provenance, immutable results, library operations, migration and backup handling; today’s study/tool screens and save flow remain unchanged | Install over 0.10.6; preserve all legacy records/results and backups; rename leaves result bytes untouched; all writers retain v3 fields; future versions block writes; missing results show explicit status; Android cloud restore, phone transfer and iOS device restore pass |
| A2 — Named plans and save flow | Plan library UI, explicit Update/Save as new, deliberate name-focus change on both platforms, plan context through Link Profile → Coverage | Stale updates rejected; Save as new preserves originals; plan deletion ungroups studies; current membership wins; handoff preserves context and Back; nothing implicitly calculates |
| B — Portable results | Common Coverage codec, native legacy readers/conversion, portable Link Profile snapshots, offline reopening and explicit Recalculate | Bidirectional platform fixtures pass; original values/dates preserved; open makes no terrain request/calculation; mismatched results rejected; inputs-only replacement explicit |
| C — Recoverable drafts | Durable editing sessions, source-switch flush, Android navigation teardown fix, process-death restoration | Source A → B → A preserves work; reopened Coverage survives process death; failed source resolution/writes preserve recoverable state; no catalog mixing or implicit calculation |
| D — Export/import | Portable study/plan/library files, privacy preview, native sharing, validated copy/merge import | iOS ↔ Android round trips; complete saved-library restore; duplicate/conflict handling; bounded decoding; no silent overwrite or omitted results |

Contract gate precedes A1. A2 follows A1; B follows A2. C uses A1's immutable-file/reference infrastructure and must support B's result formats when available. D follows B; ship the staged deliverables with independent reviews, tester notes, fresh builds and recorded results rather than treating them as one release-sized project.

Carry the existing local Link Profile enhancements as a separate reviewed change on the new branch. Keep their acceptance distinct from the new library milestones.

## 9. Historical Link Profile snapshots (deliverable B)

Add a portable optional calculated result preserving exact endpoint coordinates, antenna heights, frequency, link budget, refraction/sampling parameters, terrain samples and distances, verdict/chart/path outputs, bearing, calculation timestamp, model version and available terrain provenance. Unavailable provenance is unknown, never invented.

Opening a supported snapshot displays the stored result offline with Saved result, date and Recalculate. Base-map tiles may require connectivity; stored chart and details do not. No implicit elevation fetch or recalculation occurs on opening/restoration.

Recalculate creates a working result; the persisted original remains unchanged until Update existing succeeds. Save as new preserves the original alternative. Edits invalidate the association between the current inputs and the saved result immediately. Existing automatic calculation for active edits may remain, but must never mislabel historical outputs as predictions for edited inputs.

Only attach a completed result matching current inputs when saving. Pending, failed, cancelled or stale results are not valid snapshots. Saving inputs only is explicit when no matching result is available, particularly when replacing a saved historical result. Calculation time remains unchanged through rename/move/duplicate/export.

Legacy Link Profiles decode as Inputs only and can explicitly calculate/save later. Unknown payload versions offer independently valid inputs without pretending the result was restored. Preserve unknown payload bytes during safe metadata changes; otherwise block the operation rather than discard data. Invalid snapshots never display as valid predictions.

## 10. Analyzer switching and draft recovery (deliverable C)

Saved studies and Candidate Sites are already device-wide, outside analyzer caches. No source-switch library deletion was identified. Android's `key(preferences.host, sourceStatus.session.id)` in NodeScopeApp.kt recreates navigation and drops tool state. Reopened Coverage studies also lack complete process-death recovery today. Both are explicit C acceptance requirements, not assumed solved by SavedStateHandle.

Keep named plans/studies global across analyzers. Original coordinates and captured node snapshots remain fixed. Refresh nodes from current source is explicit and invalidates affected working results; it does not update the saved record until Save. Record origin as provenance, not an access restriction.

Create a separate durable draft store outside caches, with one UUID per editing session. Persist raw fields including incomplete text, presets, source/region, originating plan, saved-study identity and expected revision, and compatible completed result references. Do not overwrite named studies automatically. Multiple sessions must not overwrite one another.

Debounce after edits and flush before source teardown. Durable draft ownership and restoration must live beyond the host/session-keyed navigation composition. Persist enough route/session metadata to recover a reopened Coverage draft after process death, including unsaved changes rather than merely reopening its original saved inputs.

Cancel in-flight calculations on switching; preserve the last consistent draft, not a fake completed result. Failure to connect to the new source cannot destroy recovery. Restoring never starts RF work. Preserve captured Coverage catalogs until an explicit refresh.

Show Continue unfinished study at the top of Saved Plans; multiple drafts open an Unfinished studies list with tool, location/name, source and edited time. Resume opens the draft; Discard is explicit. Successful Save clears its recovery draft, while subsequent edits create recovery state again. Bound storage without silently evicting recoverable work.

Do not show routine source-change warnings when recovery succeeds. If flushing fails, hold the switch and offer Retry, Cancel, or Switch without saving, identifying affected work/results. Saved plans remain intact. Test source switching during calculation, failed source resolution, offline recovery, multiple drafts, process restart, cache clearing and disk failures on both platforms.

## 11. Export/import and sharing (deliverable D)

Export one study, one named plan, or the complete saved library including standalone studies and Candidate Sites. Recoverable drafts are excluded from ordinary sharing; describe exactly what a full saved-library backup covers.

Use a versioned interchange manifest and separate result payload entries, independent of internal filesystem paths. Portable payloads come from B. Validate schema, checksums, input fingerprints, references, finite numbers, counts and compressed/expanded size limits; reject unsafe paths and duplicate archive entries. Define these package limits before implementing the parser.

Native file/share interfaces permit local storage, file providers or recipients without a NodeScope account. Completing file export does not claim a cloud provider finished uploading. A user saving a backup into cloud storage is not application-managed sync.

Offer Inputs only or Include available results; full-library backup defaults to available results. Preserve exact inputs, historical dates, model versions and provenance. Never include terrain caches, credentials, tokens or unrelated account settings. Show an export preview identifying precise coordinates, node names/IDs, analyzer provenance and notes. Allow omitting notes. Do not imply coordinate-redacted inputs remain reproducible.

Import previews contents and result support before commit. Default to independent copies, assigning new IDs and remapping membership atomically while retaining import provenance. Never overwrite by name or UUID alone. Full-library restore offers explicit merge handling: skip genuinely identical records or retain divergent copies; no destructive Replace entire library in this iteration.

Validate the requested import before committing. Unknown future formats leave local data untouched. Unsupported results may offer an explicitly acknowledged inputs-only import when inputs are independently valid. Never calculate, download terrain or switch sources on import. Capacity failures offer a smaller selection or explicit inputs-only import, not partial success disguised as complete recovery.

Acceptance includes backup/restore across platforms, corrupt/oversized/future formats, same and divergent duplicates, interrupted/cancelled file operations, full storage, note omission and no exposure of unrelated studies.

## 12. Sync groundwork — design only

OS-managed backup/device transfer is in scope for A1 as described below. Application-managed cloud saves, live device synchronization and collaborative editing are not implemented in A1–D. Do not choose a vendor or add accounts now. The v3 revisions, provenance, stable IDs and separated immutable results support later design but do not constitute sync.

Before sync, specify device identity, distributed edit ancestry, deletion tombstones, retention/compaction, offline queues, retries, revoked sessions and conflict resolution for plan deletion versus study edits/moves. Timestamps alone are not conflict resolution. Preserve divergent edits as recoverable alternatives instead of silent last-writer-wins.

Sharing creates an independent copy, not a shared live relationship. Future application-managed cloud backup needs explicit account ownership, encryption, quota, retention and restore/deletion behavior. Placing the mutable manifest in a cloud-synced folder is not a synchronization implementation.

Deferred presentation enhancement: shareable result image/PDF for recipients without NodeScope. Render the chosen historical/current result accurately, identify predictions, assumptions, calculation date/model version and provide a privacy preview. This artifact is not a restorable study.

## 13. OS backup and device restore (A1)

iOS keeps CandidateSites under Application Support, eligible for iCloud and device backups. Do not relocate it into Caches or exclude its user-owned results. OS backup depends on the user's device settings and available storage; it is not live sync.

Android commit `c149ac7` sets allowBackup=true and includes only filesDir/CandidateSites through data_extraction_rules.xml (Android 12+) and backup_rules.xml (Android 8–11). Preserve exclusion of unrelated settings, terrain, caches and analyzer data. Android cloud backup stops for the entire app when its 25 MB quota is exceeded; device transfer is not subject to that cloud cap. See [Android Auto Backup](https://developer.android.com/identity/data/autobackup).

A1 must separate small records from immutable result files and retained migration backups. Keep small-record backup comfortably under quota. Include results in device transfer. Cloud results are optional only within the bounded cloud budget; XML alone cannot dynamically select by byte size. The gate must choose and validate a quota-aware backup implementation or explicit cloud exclusion of result files. Do not ship broad CandidateSites inclusion that lets accumulated results or a 32 MiB migration backup disable backup of the manifest. Migration originals remain locally retained; their cloud/transfer policy must be explicit, including the existing plans.json.v1-backup path.

When a valid result reference has no local file after restore, preserve the study/reference and show **Saved result not on this device**, with usable inputs. Distinguish absent, corrupt and unsupported results; no broken chart or silent fallback presented as a restored result. Opening/restoring does not calculate. Missing files never cause automatic rewriting or deletion of otherwise good records, and metadata edits retain their references.

OS restores replace the library before first launch. Apply the normal schema, field, reference and integrity checks at that first load; a failed manifest load cannot save. Missing optional result bytes are a per-study availability state, not a failed whole-library load. Restore is distinct from D's explicit merge import. Do not promise results survive a cloud restore when cloud rules excluded them.

Settings must explain actual OS backup coverage in A1; D repeats it on the export screen and recommends Include available results for a full portable backup. Describe results excluded from cloud, device transfer coverage and excluded unsaved work truthfully. These status/help changes are the narrow exception to A1's unchanged screens; plan organization and save-flow redesign wait for A2.

A1 acceptance requires Android cloud backup/restore below and above the result budget, actual phone-to-phone transfer, and iOS device restore. Verify IDs, membership, inputs, dates, revisions, original result bytes where included, missing-result status where excluded, and no implicit calculation. Include old backup filenames, interrupted migrations, missing/corrupt files and unknown future schema. Record OS versions, transports and device/build identifiers; unit tests or a local file copy do not establish real restore behavior.

## 14. Cross-cutting verification

- Shared Swift/Kotlin schema fixtures, legacy migration and portable results in both directions.
- Crash-safe file installation/manifest replacement, orphan cleanup and backup retention.
- Conditional updates, deleted/moved/renamed records, multiple open drafts and library capacity.
- Historical results never mislabeled after input edits, migrations or recalculation.
- Device-wide saved data survives analyzer changes, failed connections, process death and cache clearing.
- Nested navigation, save sheets, Back preservation and no implicit calculations.
- Light/dark, compact/wide, large text, keyboard, VoiceOver/TalkBack and file-provider flows.
- Separate tester builds and truthful validation notes for A1, A2, B, C and D; never claim device checks that were not performed.
