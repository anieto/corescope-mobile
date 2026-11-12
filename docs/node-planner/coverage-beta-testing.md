# Estimated Coverage beta testing

The 0.10.4 development build now supports an in-app calculation from setup through coverage overlay
and known-node predictions. Open **Explore → Node Planner → Estimated Coverage**. This is a
terrain-only inland estimate, not measured reception; coast/sea paths, buildings and tree clutter
are not modeled. Accept the displayed assumptions before Calculate becomes available.

On iOS (2026-11-11) the screen is laid out like Link Profile: the map fills the screen, and the setup
lives in a bottom panel that collapses to a status header (iPad and the Duo use a side column). Touch
and hold the map to place the transmitter, on a known node's marker to use that node; an Undo capsule
appears for a few seconds. The header carries Calculate, Cancel, the progress stage and the verdict.
Presets, after meshpeaks.com, fill the radio (region/modem preset → frequency, power and a sensitivity
derived from SF and bandwidth), antenna types (gain) and antenna heights; editing a preset value marks
the radio custom. The opening values are the USA/Canada MeshCore preset, a 1/2-wave dipole at both ends,
a 6 m (20 ft) rooftop transmitter, a 1.5 m handheld receiver, 1 dB losses and a 10 dB planning reserve.
Open the setup (drag the panel up) to change any of them or to type coordinates.

Start with a 5 km radius and 2 km sample spacing. To reproduce the earlier numbers, customize the radio
to the table below rather than using a preset. One reproducible inland smoke-test location is latitude
50.5, longitude -115.5, using these *test assumptions*:

| Field | Test value |
| --- | --- |
| Frequency | 915 MHz |
| Transmit power | 22 dBm |
| Transmitter antenna height | 10 m (32.8084 ft) |
| Receiver antenna height | 1.5 m (4.9213 ft) |
| Transmitter / receiver gain | 2 dBi each |
| Transmitter / receiver loss | 1 dB each |
| Receiver sensitivity | -130 dBm |
| Planning reserve | 5 dB |

Use the height units shown in the form. These are numerical test inputs, not recommended equipment
settings. The first run downloads approximately 16 MB for this tile. A run may download up to
500 MB; the terrain cache is bounded at 1 GB. Keep the screen open and app foregrounded.

Expected checks:

1. Calculate shows terrain preparation followed by path calculation. The UI remains responsive.
2. A completed result shows green above-threshold areas, a legend and sample counts. Gray hatching
   indicates unknown or unsupported near-site terrain. Clear areas inside the radius are below
   threshold; areas outside the radius are not analyzed. With coarse spacing, gray hatching can
   also appear just inside the study boundary where the nearest sample lies outside the radius.
   These unsampled areas do not increase the unresolved sample count. Zooming adds no prediction samples.
3. Known nodes show predicted status/margin or an explicit reason for being unknown/not evaluated.
   The list contains at most 200 nearest available nodes and is not a complete radio inventory.
4. Changing any input removes the overlay and requests a new calculation. Changing source/region
   must not reuse predictions from the old catalog.
5. Cancel stops publication; a later run works after cleanup. Leaving/backgrounding during a run
   cancels it. No partial study is presented as complete.
6. An unavailable connection, missing terrain or insufficient cache space gives retry guidance.
   Discovery currently needs a connection even when tile bodies are cached.

Both app builds and host test suites pass. The live AWS → RF → raster test passed on Swift/macOS
and Android/JVM, with four matching qualified path losses. This does not measure mobile latency,
memory, thermals or outdoor accuracy. No Android device was connected during this implementation.
On 2026-11-08, the iPhone 18 Pro Max simulator completed the setup → terrain download → calculation
→ green overlay flow. The 5 km / 2 km test produced 6 above-threshold, 10 below-threshold and zero
unresolved samples. Editing spacing to 100 m removed the overlay and marked the result stale.
Cancelling the next run showed “No partial result was published.” No crash or obvious portrait
layout defect was observed. The app remained portrait after simulator rotation, so landscape was
not verified. The simulator used the existing height-unit setting; this is a UI smoke result,
not a numerical golden for the test-input table above. Actual smoke-test heights were 30 m / 5 m
(98.4252 / 16.4042 ft). The smoke test preceded the final actionable-error, keyboard-dismissal and
persistent-cancel refinements; those refinements were build-checked. The observed below-fold Cancel
control was addressed with an iOS navigation-bar button and an Android map-footer control.

Remaining qualification: physical iPhone/Android runs, Android Google/MapLibre switching, dark/light
mode, larger text, compact/wide layouts, long-running cancellation, offline failure and broad terrain
edge cases. iOS can save input setups to Saved Plans; results are not persisted. Android saving remains pending.
Clean iOS builds require the [shared framework build step](../../shared/rf-engine/README.md).

## Integration follow-up — 2026-11-11

The compact iOS map now extends beneath the status bar, setup panel and tab bar. Known-node
markers remain available before placing a transmitter and after clearing it (up to 200 available
catalog entries); choosing a transmitter then ranks the catalog by distance before applying that
cap. Invalid catalog coordinates are excluded. Unsupported map placements preserve the previous
transmitter and Undo state and show the latitude-limit message in the collapsed header.

Validation: Xcode build-for-testing passed. The 112-test host coverage suite completed successfully;
a temporary host harness also compiled the actual screen model, node type and preset sources with
the existing suite and all seven preset/model tests, for a successful 119-test run. Live AWS tests
remained opt-in and were skipped. No simulator, physical-device or visual checks were performed
in this follow-up; the new map layout still needs light/dark, compact/wide and sheet checks.

### Saved Plans follow-up — 2026-11-11

The iOS tools now expose Save/Open in their expanded panels. Explore → Node Planner → Saved Plans
opens the shared study library, with the existing Candidate Sites editor below it. Link Profile
saves both endpoints and radio/antenna assumptions; Estimated Coverage saves transmitter, generic
receiver, radius/spacing and captured known-node identities, locations and last-seen context.
Saved results are not included. Reopening explicitly displays inputs-only state and requires
Calculate; Link Profile also pauses automatic terrain requests while reviewing reopened inputs.
Coverage keeps the saved node catalog until Refresh nodes from current source is selected.

Version 1 remains unchanged on read. The first explicit study save writes version 2 to the same
`CandidateSites/plans.json`, after preserving the exact prior bytes in `plans.json.v1-backup`.
Existing sites and unknown assumptions survive. Failed replacement leaves the original readable;
a retry verifies and reuses the backup. Corrupt/unsupported files and conflicting backups block
writes; there is no automatic fallback that could silently lose newer studies. A backup is a
recovery artifact, not the active library. Candidate site edits preserve version-2 studies.

Validation: 16 host persistence tests pass, including the 30 original and 16 new shared fixtures,
failed migration/retry, backup conflicts and downgrade protection. The expanded host coverage suite
passes 124 tests, including actual editor-model restoration tests verifying input round trips,
frozen node locations and zero Link Profile elevation requests until Calculate. Live AWS tests
remain opt-in. Xcode build-for-testing passes. No simulator, device or visual checks were performed.

Remaining: Android adoption of the shared version-2 fixtures and Save/Open workflow, physical-device
qualification, and light/dark, larger-text, compact/wide, navigation and sheet review. Results caching,
compare/export and multi-transmitter studies remain deferred.

### Larger-study investigation — 2026-11-11

User reports app termination on some large studies and partial output on others. The screenshot
shows a partial estimate, not evidence of a crash cause. Current admission checks only minimum
sampling work; terrain feature retention and additional convergence passes may exhaust the
aggregate budget. Row-major evaluation can leave a band of later cells uncomputed. The UI now
distinguishes aggregate exhaustion from individual path limits; coarser grid spacing does not
reduce the cost of an individual path. Accuracy/convergence limits remain unchanged.

Each terrain refinement and RF invocation now has a local autorelease pool, bounding the lifetime
of temporary Foundation terrain-read and Kotlin/Native bridge objects to that call. The full
124-test host suite and Xcode build-for-testing pass. This addresses a plausible memory-growth
source; it does not establish the cause of reported device terminations. A failing setup and
crash/Jetsam report are needed to distinguish memory pressure from other failures and measure
physical-device peak memory. No device or visual tests were run.

Coverage comparisons must match coordinates, antenna heights AGL, gains/losses, frequency, power,
receiver threshold and reserve, terrain source/resolution, propagation model and statistical
assumptions. Our pinned kernel is P.1812-8, inland, zero clutter, median time/location (50%),
zero location variability. Meshtastic Site Planner documents ITM/Longley-Rice and a 90% time
fraction; those statistical conventions are not interchangeable. MeshPeaks' current internals
could not be verified from its accessible page. Do not tune a loss offset just to match a map.
References: https://github.com/meshtastic/meshtastic-site-planner/blob/main/parameters.md and
https://kotlinlang.org/docs/native-arc-integration.html#objective-c-objects-lifecycle.

### Margin display and resumable batches — 2026-11-12

iOS now shades adjusted margins below 0 dB red, 0 through 10 dB amber, and above 10 dB green.
Gray hatching remains unresolved; outside the radius is clear. These are planning bands, not
calibrated reliability estimates. Tap the map to inspect a nearby grid sample's predicted RX
and margin after reserve; exact-coordinate node predictions remain separate.

Calculation order is spatially distributed. Continue evaluates pending samples and unfinished
nodes without repeating completed predictions, using the same inputs and pinned terrain identities.
Continuation is in-session only: Saved Plans retain inputs, not calculation checkpoints. Cancelling
a continuation preserves the preceding completed batch. Individual path limits remain unresolved.
Preflight shows its minimum work estimate explicitly, and 5 km / 10 km grid spacing is available.

Per-call autorelease pools and an advisory pause below 128 MiB of available process memory help
bound work on physical iOS devices. Simulator and host processes skip this device-specific check.
This threshold has not been qualified on physical devices and cannot guarantee against
termination. A local latest-run JSON report records inputs, stage, work, memory observations and
node predictions. Sharing is explicit; reports contain coordinates. An interrupted report can
help investigation but is not a crash diagnosis.

Next: matched single-path comparisons and physical-device memory profiling using a failing setup
and crash/Jetsam report. RF model fidelity and statistical assumptions remain unchanged. No visual
checks of light/dark, compact/wide layouts or sheets were performed for these changes.

Simulator regression fixed on 2026-11-12: the saved report showed zero available memory,
804 pending samples and zero charged work. The device memory guard incorrectly ran on the
simulator, stopping every batch before its first path. Unsupported environments now return an
unavailable reading rather than exhausted headroom; physical-device zero readings still pause.
Regression tests cover unavailable, zero, threshold and falling headroom. All 129 host tests and
the Xcode build-for-testing pass; the corrected interactive simulator flow has not been rerun.

### Saved coverage results — 2026-11-12

Estimated Coverage now saves a versioned binary calculation snapshot alongside its inputs in the
version-2 library. It includes the grid cells, exact-node evaluations, model identity, work limits
and pinned terrain descriptors. Opening reconstructs the overlay locally without RF calculation
or a terrain download. Partial results retain pending cells; continuing still requires accepting
model assumptions and may need cached terrain to be downloaded again. Terrain files are not
embedded. Changes to inputs invalidate the restored result. Invalid snapshots fall back to inputs
with an explanatory error. Old input-only saves require calculation and saving again once.

Snapshots are limited to 8 MiB each; the bounded plan library now permits 32 MiB. Existing version-1
migration and atomic-write/conflict protections remain. Link Profile continues to save inputs only.
Validation: 130 host coverage/restoration tests, 16 persistence tests, and the Xcode app build pass.
Tests exercise disk round-trip, offline overlay rendering, pending cells, node margins, terrain
identity, invalid data and stale-input handling. Interactive device restoration has not been tested.
