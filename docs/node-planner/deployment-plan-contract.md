# Deployment Plan storage contract — version 1

This is the input model for the 0.10.3 local Candidate Sites workflow. Swift and Kotlin sources are
in the application targets, together with atomic disk repositories and a manual-coordinate editor.
No automatic terrain download or analysis is connected. The versioned JSON codec validates decoded
and encoded documents.

| Record | Fields |
|---|---|
| Document | Required schemaVersion = 1, ordered plans |
| Plan | Lowercase canonical UUID, name, created/updated Unix milliseconds, optional selectedSiteID, ordered sites |
| Site | Lowercase canonical UUID, name, latitude/longitude, assumptions |
| Assumptions | Optional antennaHeightMeters, frequencyMHz, transmitPowerDBm, antennaGainDBi, cableLossDB, additionalLossDB |

Missing or null assumption values mean **not specified**. Zero is a real entered value, not a
substitute for missing information. No radio preset, receiver assumption, analyzer source, terrain
elevation, coverage result or regulatory assertion is inferred. Future analysis must require its
own complete, validated input snapshot. Cable and other system losses remain separate terms.

Coordinates use WGS84 degrees: latitude −90 through +90, longitude −180 inclusive to +180 exclusive.
The editor must canonicalize +180 to −180 before saving. Names preserve their exact Unicode text,
must have a non-space character, contain no ASCII control characters, and fit 160 UTF-8 bytes.
IDs are unique within their parent collection; selectedSiteID must resolve within that plan.
Creation time must be nonnegative, update time cannot precede creation, and timestamps end at
253402300799999 milliseconds. Arrays preserve candidate order; names need not be unique.

Storage input guards: antenna height 0–10000 m; frequency 0.001–100000 MHz; transmit power −200–100
dBm; antenna gain −100–100 dBi; each loss 0–200 dB. All specified numbers must be finite. These
generous serialization bounds are **not** RF-engine validity ranges, recommended defaults or legal
operating limits. Later engines validate their own narrower domains.

Documents are bounded to 4 MiB, 100 plans, and 100 sites per plan. An unsupported schema or invalid
document fails as a whole; callers must not silently reset the library or overwrite unreadable data.
Unknown object keys are ignored within schema 1. Any future field that must survive an older app's
save requires a schema bump, since unknown keys are not preserved. JSON key order and omission vs
explicit null are not identity; decoded values and array ordering are the interoperability contract.

Validation: both platforms consume the same 30 cases in
[plan-v1-cases.json](../../shared/fixtures/candidate-sites/plan-v1-cases.json), round-trip accepted
documents, reject invalid documents, and test non-finite values and the byte limit. Swift tests
also run on macOS against the actual source with
`python3 tools/node-planner/run_plan_contract_tests.py`, without a simulator. Android uses
`./gradlew testDebugUnitTest` from `android/`.

## Atomic library storage

Swift's `DeploymentPlanStore` serializes calls with an actor; Android's store serializes synchronous
calls and must be invoked on an IO worker. Create one owner per library path. iOS uses Application
Support/CandidateSites/plans.json; Android wiring will use filesDir/CandidateSites/plans.json. Neither
library belongs in a purgeable cache. The editor must retain its own draft until save succeeds.

A successful load is required before saving. A missing file is an empty library; unreadable,
corrupt, oversized and future-version documents throw without deleting or resetting data. Reads
are bounded to the codec limit plus one probe byte. Save validates the complete snapshot, checks
the on-disk bytes against the loaded baseline, then replaces the file atomically. A changed or
deleted file requires reload rather than silently overwriting another snapshot. This is not a
cross-process transaction lock; the one-owner constraint still applies, and the editor must prevent
stale concurrent drafts from replacing each other.

Swift uses Foundation's atomic data write. Android writes a same-directory temporary file, syncs
the file and atomically moves it over the destination, with no non-atomic fallback and temporary
cleanup on failure. These mechanisms provide atomic replacement; power-loss durability and mobile
filesystem failure behavior have not been qualified. A failed injected writer leaves the previous
library and caller-owned draft unchanged. There is no automatic destructive recovery or reset API.
Deleting a plan means saving the remaining validated plans, independently of terrain/cache clearing.

Six storage tests per platform exercise first-save/reopen/replacement/deletion, corrupt/future-schema
protection, external changes/deletion, injected write failures, invalid drafts and oversized files.
Swift runs on the host without a simulator; Android tests run on the JVM. UI/device checks remain
with the user.

## First editor slice

Explore → Node Planner → Candidate Sites now opens the library on iOS and Android. Create/open/
rename/save/delete plans, add/edit/duplicate/remove sites, and select a site. Site Apply updates the
open draft; Save plan commits the full plan. Destructive actions and closing edited drafts require
confirmation. Save errors keep the editor open. Reload is available from the library after errors;
there is no automatic unreadable-file reset.

Enter latitude/longitude manually and optional antenna height, frequency, transmit power, antenna
gain, cable loss and additional loss. Decimal points and decimal commas are accepted, without
thousands separators; non-finite numbers, unit suffixes, malformed numbers and absent coordinates
are rejected. +180 longitude becomes −180 before validation. Blanks remain unknown. Swift and Kotlin
input tests cover these rules, including preserving explicit zero values.

The editor uses Monaspace titles/names/scannable values, shared cards, fixed mesh page backgrounds,
and nine shared glossary entries with Learn controls. iOS uses the shared instrumentCard style;
Android uses meshCardColors. Android draft form state is saveable across configuration recreation;
iOS holds drafts in sheet state. Only explicitly saved plans have durable persistence; this does
not promise recovery of unsaved drafts after process termination.

No simulator or UI tests were run. Light/dark mode, nested sheets, large text, compact/wide and
foldable layouts require user device checks. Build and host unit tests do not qualify those visuals.
## Map placement — 2026-11-01

Add/Edit site → Choose on map opens a dedicated picker on both platforms. Valid draft coordinates
frame the initial view; otherwise it opens a world view without selecting a location. Touch and
hold places or relocates a tentative pin. Use coordinates copies it into the site draft; Cancel
leaves the draft coordinates unchanged. Apply site and Save plan remain separate explicit steps.
Opening the map picker requests neither device-location permission nor terrain analysis. Manual coordinate entry remains
available when a map cannot load or touch-and-hold is unsuitable.

iOS reuses the Link Profile simultaneous long-press recognizer and the shared map-style preference.
Android reuses the MapLibre/Google engine hosts, including their TextureView/lifecycle handling,
saved camera state, provider/layer settings, loading/retry state and attribution. Actual map
workspaces have no decorative mesh. Tentative choices and camera state stay within the picker;
opening/reframing the map never writes the saved library.

Validation: iOS build-for-testing and Android debug assembly/unit suite passed. No simulator or UI
tests were run. Touch-and-hold, cancel/confirm behavior, annotations, attribution, nested sheets,
light/dark mode, large text and resize/fold layouts still require device validation.

## Current location and height units — 2026-11-02

Add/Edit site → Use current location requests foreground permission only after a tap, then copies
one position fix into the draft. It reuses the native map location helpers, supports cancellation,
and stops pending work when the editor closes or the app backgrounds. Denial, disabled services
and unavailable fixes leave existing coordinates intact and offer manual/map placement instead.
Coordinates and Apply are disabled while locating to prevent a late result overwriting new edits.
Approximate location is accepted; the success message asks the person to check the pin on the map.
Apply site and Save plan are still required to persist the chosen location.

Antenna height opens in feet or meters according to Settings → Units. The unit is fixed for the
open editor so changing a preference cannot reinterpret typed text; Android retains it across
activity recreation. Imperial input converts with exactly 0.3048 meters per foot before validation.
Version-1 storage stays metric, blank heights stay unknown, zero stays explicit, and applying an
untouched height preserves the exact stored value. The maximum remains 10,000 meters; imperial
UI describes its approximate feet equivalent without changing that storage guard.

Validation: iOS build, 11 Swift host plan/storage tests, and Android debug assembly/unit tests
passed. New tests cover decimal-comma feet, metric input, zero, blank, maximum height, out-of-range
and malformed input, and the metric persistence round trip. Permission prompts, cancellation,
approximate-location review, settings/reopen behavior and layouts require user device checks.
No simulator or UI tests were run.

Next: initial-region framing/search evaluation and device validation. Coverage/known-node analysis
stays outside this release slice.
