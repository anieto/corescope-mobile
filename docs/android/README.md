# Android build plan

Planning baseline: 2026-09-21, iOS commit `cd98f8e` plus the inspected working tree.
Status: implementation authorized; first foundation increment in progress. See [progress](progress.md).

Build NodeScope for Android with the functionality of the existing iOS app and
native Android controls, navigation, accessibility, and adaptive layouts.

## Deliverables

1. [Feature parity matrix](parity-matrix.md): source-backed scope and verification criteria.
2. [Android experience](android-experience.md): navigation, screens, controls, and layouts.
3. [Implementation plan](implementation-plan.md): architecture, decisions, phases, and release gates.

## Scope agreement

- The reference is implemented iOS behavior, including Node Analytics. The iOS
  roadmap is context, not an automatic Android backlog.
- Keep Map, Explore, Channels, Observers, and Settings as the five destinations.
- Keep the read-only analyzer model, community source selection, local decryption,
  favorites, exports, diagnostics, and cached-data behavior.
- Use Kotlin, Jetpack Compose, and Material 3. Retain NodeScope identity and
  semantic role colors while using Android navigation and controls.
- Cover phones, tablets, foldables, rotation, and resizable windows from the start.
- Keep iOS in its existing repository paths; develop Android in `android/`.
- The initial prototype was replaced after user authorization. Retained build
  plumbing does not count as feature-parity acceptance; see the progress record.

## Findings that affect scope

- Node Analytics is already implemented, even though the roadmap still names it
  as the current priority. The actual ranges are **24h, 7d, 30d, and 1y**, not all-time.
- Current packet screens already expose raw packet data and observations. Do not
  defer these merely because richer investigation tools appear on the roadmap.
- The map already seeds routes from recent packets and supports individual packet
  replay. Continuous historical playback with a scrubber remains future work.
- Shared region selection, source-isolated saved items/cache data, and source
  switching are application-wide behaviors, not individual screen settings.
- Existing custom deep links contain an entity identifier but no analyzer host.
  Preserve compatibility; never silently guess or switch the analyzer on receipt.
- Local channel keys belong to the device; they are not analyzer credentials and
  must never be uploaded to obtain decrypted messages.

## Decisions to resolve

Google Play devices remain sufficient. On 2026-09-22 the user selected CARTO
basemaps and supplied a mobile-restricted key. Android now uses MapLibre Native
with CARTO vector tiles. Satellite/hybrid imagery is still an open parity item;
CARTO street maps do not provide it. Provider attribution and usage limits apply.

Proposed defaults: Android 8.0/API 26 minimum, branded Material theme with system /
light / dark choices, no background live-monitoring service, and no new account
system. Confirm the final application ID before publication. Exact dependency
versions and release target SDK will be selected and verified during foundation
work rather than inherited from the prototype.

## Next executable step

Phase 0: turn the matrix into fixture-backed acceptance cases, inspect the iOS
screens on representative devices, and build a disposable map feasibility test.
Then review the Android screen layouts and map decision before building the
production foundation. This planning pass inspected source; it did not run an
exhaustive visual or runtime audit of iOS.
