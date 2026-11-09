# Estimated Coverage beta testing

The 0.10.4 development build now supports an in-app calculation from setup through coverage overlay
and known-node predictions. Open **Explore → Node Planner → Estimated Coverage**. This is a
terrain-only inland estimate, not measured reception; coast/sea paths, buildings and tree clutter
are not modeled. Accept the displayed assumptions before Calculate becomes available.

Start with a 5 km radius and 2 km sample spacing. Enter all radio fields explicitly. One reproducible
inland smoke-test location is latitude 50.5, longitude -115.5, using these *test assumptions*:

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
edge cases. Saved coverage setups/results are not implemented; leaving the tool can discard the draft.
Clean iOS builds require the [shared framework build step](../../shared/rf-engine/README.md).
