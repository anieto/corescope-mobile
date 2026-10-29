# Saved Link Profiles — implementation plan

Status: planned, not implemented. Added: 2026-10-27. Scope: v0.10.0 on iOS and Android.
Parent: [Node Planner release plan](plan.md). This adds release scope, not a completion claim.

## Product boundary

Saved Link Profiles preserves a specific A-to-B study. Candidate Sites models the coverage and
potential links of a proposed installation. Keep separate entry points and records while reusing
the terrain/RF foundation. Saved profiles must ship independently of candidate-site planning.

Initial scope: save, browse/search, reopen, update, rename, duplicate and delete named local profiles.
No new root tab. Defer cloud sync, import/export, folders, bulk actions and automatic node-coordinate
updates. Later, “Use endpoint as candidate site” copies assumptions into a deployment plan without
coupling either record's lifecycle. Profile export remains a separate future task.

## Existing code and behavior

- iOS: `Features/NodePlanner/LinkProfileScreen.swift` owns a private observable `LinkProfileModel`.
  Extract it into its own file for repository injection and testability. Reuse
  `NodePlannerLinkAnalyzer`, `NodePlannerRFEngine` and existing elevation services.
- Android: `feature/linkprofile/LinkProfileViewModel.kt` publishes StateFlow and uses SavedStateHandle
  for editor strings. Session restoration is not a durable profile library. Reuse LinkProfileEngine
  and LinkTerrainProviding.
- Both implementations currently debounce analysis after valid edits. The parent plan's older
  explicit-analysis wording differs from that behavior. Preserve automatic analysis after deliberate
  edits, but do not trigger analysis or terrain requests just by reopening a saved profile. This
  saved-profile restore rule takes precedence for this feature.
- Endpoint names alone are not stable identity. Capture optional node public key and source identity
  at selection time; never infer identity by matching names.

## UX contract

1. The Link Profile controls-panel header offers a **Saved** button with a notebook icon, next to
   the current profile identity/actions. On narrow layouts it may become icon-only with the
   accessibility label “Saved Link Profiles”. Keep it reachable in the collapsed panel summary.
   It opens a compact saved-profile sheet on phones and an anchored popover within one usable pane
   on wide/foldable layouts. Selecting a profile restores the existing workspace and dismisses the
   list, after resolving any unsaved edits. Do not add a floating map button or a separate Node
   Planner library row initially; Candidate Sites remains a separate Node Planner tool.
2. Save becomes available when both endpoints and required assumptions validate; no successful
   analysis is required. Suggest “A name → B name”, falling back to coordinates, and allow editing.
3. An opened profile shows its name and unsaved state. Save updates the same UUID; Save as Copy
   creates a new record. Saving never happens implicitly when analysis finishes.
4. The library sorts by last saved modification and searches profile names and endpoint labels.
   Rows show endpoints, distance and analysis date/status. Opening does not change the sort order.
5. Restore all inputs in one transaction, cancel old jobs and frame both endpoints once. Show a
   historical saved estimate when available and offer Recalculate. Never launch intermediate-input
   analyses while restoring fields.
6. Rename preserves analysis validity. Duplicate copies inputs and optionally the historical result,
   preserving its original calculation date. It receives a new UUID and independent future edits.
7. Back/close, opening another profile and starting over guard dirty edits with Save, Discard and
   Cancel. Invalid inputs explain why Save is unavailable; Discard and Cancel still work.
8. Delete asks for confirmation naming the profile. Deleting an open profile cancels its jobs and
   exits its saved identity. Late work cannot recreate it.
9. Failed saves retain the draft and expose retry. Display “Saved” only after durable completion.

Names are trimmed, nonempty and limited to 100 Unicode code points on both platforms. Duplicate
names are allowed; UUID is identity. Validate finite numeric values using the existing engine's
ranges. Unit changes affect presentation only, never stored coordinates or heights.

## Shared schema v1

Use equivalent Swift Codable and Kotlin serialization DTOs, stable wire keys and shared JSON
fixtures. Use UUID strings and UTC RFC 3339 timestamps. Persist numeric values in canonical units,
not localized input strings or UI-label map keys. Storage, engine and terrain versions are separate.

| Record | Contents |
|---|---|
| Profile | Schema version, UUID, name, createdAt, updatedAt, ordered endpoints, configuration, optional last analysis |
| Endpoint | Latitude/longitude degrees, height above ground meters, optional label, origin (manual/map/node/device), optional node key and source identity |
| Configuration | Frequency MHz, transmit power dBm, A/B gains dBi, A/B losses dB, sensitivity dBm, all model/refraction parameters needed to reproduce analysis |
| Preset | Optional stable ID/version plus explicit values; later preset changes never rewrite saved inputs |
| Analysis snapshot | Immutable analyzed inputs, fingerprint, timestamp, engine/version, terrain provenance/completeness, bounded samples and typed results needed to redraw the chart |

Inventory every consumed engine parameter before freezing schema; record currently fixed constants
with their model version. Preserve A-to-B direction: a predicted receive level does not imply the
reverse path has the same link budget. Swapping endpoints changes input identity.

Use SHA-256 over a shared canonical analysis-input encoding, with fixtures specifying numeric
representation, negative-zero normalization and ordering. Include ordered coordinates, heights,
radio/model values and relevant source identity. Exclude name, UUID, display units and timestamps.
Never use Swift hashValue or Kotlin hashCode as persisted fingerprints. Engine and terrain revision
are additional result-validity keys.

## Historical results and offline behavior

- No analysis: “Not analyzed”; the profile remains editable and saved.
- Matching snapshot: “Saved estimate · calculated [date]”, with its original provenance.
- Input or model changes: stale immediately. Historical results retain original assumptions and
  must not be drawn against the edited path as though current.
- A known terrain revision change marks results stale. Offline checks cannot certify remote data
  freshness; show recorded provenance without claiming it is current.
- Save bounded path samples/results so the historical chart can open after terrain-cache eviction.
  This snapshot is not a tile cache. Recalculation requires cached terrain or connectivity and must
  never silently substitute flat terrain.
- Source changes and missing/moved known nodes do not alter saved coordinates. Surface unavailable
  context; a future explicit coordinate-refresh action may update them.
- Capture immutable inputs and session/revision tokens for analysis and save. Reject results after
  profile load, edit, deletion or source changes. Save during analysis writes valid inputs plus only
  a matching earlier result, if present. A new result remains unsaved until the next explicit save.

## Durable storage and recovery

Keep a device-wide library in app-private persistent storage, separate from analyzer and terrain
caches. Source provenance belongs to endpoints. Use one bounded JSON record per UUID; derive the
library index from records to avoid a second authoritative file becoming inconsistent.

Serialize mutations and atomically replace records. Failed writes/migrations preserve originals.
Preserve unsupported newer schemas and corrupt records for recovery, reporting failures per record
rather than resetting the whole library. Cache-clearing actions must not delete profiles or their
historical snapshots. Report saved-profile storage separately and document existing OS backup
behavior. Do not introduce remote uploads or an analyzer write API.

Before schema freeze, measure the largest supported existing path and define shared byte/sample
limits. Reject oversized records with an actionable error; do not silently truncate numerical data.
Only explicitly placed and saved device coordinates enter the library, never location history.

## Platform work

| Stage | iOS | Android |
|---|---|---|
| Models | Add Models/NodePlanner/SavedLinkProfile.swift, Codable DTOs and validation | Add matching serialization DTOs and validation |
| Repository | Add actor-isolated SavedLinkProfileStore with atomic files, migration and recovery | Add SavedLinkProfileRepository with IO dispatcher, Mutex and AtomicFile or verified equivalent |
| Session | Extract/inject LinkProfileModel; add loaded UUID, revision, dirty state and atomic restore | Adapt LinkProfileViewModel; typed snapshots, stable field IDs, draft restoration and atomic load |
| Library | Add SavedLinkProfilesScreen and UUID navigation | Add Compose library and typed UUID route |
| Editor | Save/update/copy, name/delete sheets and navigation guards | Matching save/copy/name/delete flows and navigation guards |
| Integration | Observable library state and storage accounting | Application-container injection, Flow library state and storage accounting |

Reuse each existing persistent map editor. Keep repository work off the UI thread and avoid changing
RF algorithms. iOS library/sheets use meshPageBackground; Android uses shared mesh/card helpers.
Keep chart surfaces readable. Preserve edits, selected profile, camera and job identity across
resize/fold transitions. SavedStateHandle remains draft restoration, not library persistence.

## Delivery order and acceptance

1. Freeze schema, canonical fingerprints and limits; add shared round-trip fixtures.
2. Implement and test both repositories before UI integration.
3. Add session save/load/dirty-state handling and stale-work guards.
4. Add library, editor actions and navigation guards on both platforms.
5. Complete offline, accessibility, lifecycle and device validation before marking this implemented.

Use Swift Testing and XCUIAutomation on iOS; retain the existing Kotlin unit and Compose
instrumentation frameworks on Android. Required cases:

- Save before analysis, full input round trip, shared fingerprints and unit-conversion stability.
- Reopen without network requests; historical chart survives terrain-cache deletion.
- A delayed A result cannot overwrite B; delete during work cannot resurrect a record.
- Failed saves preserve dirty edits; duplicate and rename preserve correct identity/validity.
- Corrupt/newer-schema records, interrupted writes and failed migrations preserve recoverable data.
- Source/node unavailable, old engine/terrain versions and mismatched result fingerprints.
- Relaunch and process recreation, dirty-navigation guards and independent duplicate editing.

Visual matrix: light/dark, texture on/off, large text and screen-reader labels; compact portrait/
landscape, iPad multitasking, Duo postures and Android tablets/foldables. Resize during edits,
dialogs and analysis. Verify chart readability, visible primary actions and stable map/session.
Report unperformed checks explicitly.

Release walkthrough on both platforms: save a named profile, terminate, reopen offline, change
height, save a copy, confirm the original is unchanged, and delete the copy. Build and relevant
tests must pass; implementation is incomplete until these acceptance checks are resolved.
