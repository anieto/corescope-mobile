# NodeScope for Android

Native Kotlin / Jetpack Compose implementation alongside the iOS client.
The earlier node-browser prototype has been replaced with the planned application
structure. This is the first foundation increment, not a feature-parity release.

## Run

1. Open this `android/` directory in Android Studio and use its bundled JDK.
2. Install Android SDK 36; sync Gradle.
3. Run `app` on an Android 8.0+ emulator/device.
4. Choose a bundled community analyzer or enter an HTTPS hostname.

The build preserves the Android Studio-upgraded AGP 9.4.1 / Gradle 9.6.0 setup.
Kotlin 2.2.20 remains explicitly configured with the existing AGP migration flags.
Do not remove those flags without migrating to built-in Kotlin. Dependencies are
pinned for reproducibility; latest-version lint recommendations are not suppressed.
The initial app ID is `org.nodescope.android`, pending publishing review.

## CARTO maps setup

Maps use MapLibre Native 13.6.1 (OpenGL for broad device compatibility) with CARTO vector basemaps. No Google Cloud project or
Google Maps key is required. Add your CARTO Basemaps key to ignored
`android/local.properties`, preserving the existing `sdk.dir`:

```properties
CARTO_API_KEY=your_carto_basemaps_key
```

Alternatively set `CARTO_API_KEY` in the build environment. Rebuild after changes.
Without a key, onboarding/settings/Explore still work and Map shows an unavailable state.
The key is embedded in the APK: do not commit it, and configure CARTO mobile-app
restrictions for package `org.nodescope.android` and your signing SHA-1. Obtain
fingerprints with `./gradlew :app:signingReport`; add the Play app-signing certificate
before distribution. CARTO requests include `X-Android-Package` and `X-Android-Cert`,
computed from the installed app. Authentication is limited to HTTPS CARTO basemap
hosts, including nested vector tiles, glyphs and sprites; analyzer requests never
use this map client. Network URL logging is disabled for the map client.

Region framing uses `app/src/main/assets/iata-airports.csv` (IATA code, latitude,
longitude) for regions whose analyzer omits a center. It is generated from
[OurAirports](https://ourairports.com/data/) data, which is in the public domain.

Styles: Voyager (Standard), Positron (Light), Dark Matter (Dark). Visible links
credit CARTO and OpenStreetMap. Satellite/hybrid imagery needs a separate provider
and remains an outstanding parity item. No offline map download feature is enabled.

[CARTO key setup](https://carto.com/basemaps/apikey/),
[CARTO basemaps](https://docs.carto.com/faqs/carto-basemaps).

Use **Settings → Map validation (debug)** for clearly labeled synthetic nodes and
route animation. It exercises styles, clustering, node taps and replay without
mixing sample traffic into the analyzer data.

## Current implementation

- First-launch source selection and persistent onboarding via DataStore.
- Bundled registry from `CommunitySources/`, remote refresh and cached fallback.
- Material five-destination shell, bottom navigation on phones / rail on wider windows.
- Typed navigation, per-destination back stacks, source picker and node detail routes.
- iOS-inspired blue/slate palette, grouped cards, and system/light/dark appearance.
- Cancellable HTTPS loading of nodes, map defaults and regions; one session flow.
- Source/region switching invalidates old content; refresh errors retain same-source data.
- Node browsing/search/basic details; shared region control.
- Shared foreground WebSocket feed with retry/backoff, ping checks and REST history reconciliation.
- Bounded packet observations, observer-based regional filtering, live/recent labels and packet type filters.
- Explore dashboard with live-feed entry, analyzer-scoped saved nodes and recent history; node search/add in a native sheet.
- Map toolbar for refresh/search/style, region and role filters, compact node count and zoom; no packet feed panel.
- Live route trails/dots for known hops; server-unresolved hops and ambiguous prefixes remain gaps.
- MapLibre/CARTO vector maps with standard/light/dark styles, node clustering and node selection.
- Debug map validation route with synthetic data and motion-aware sample playback.
- Shared synthetic contract fixture, JVM tests and Compose instrumented UI tests.

- Channels: server channel list with search, source/activity/sort filters and shared region;
  read-only conversations with live refresh; hashtag, private-key and generated-key monitoring
  with on-device decryption. Keys are sealed with an Android Keystore AES-GCM key and excluded
  from backup/transfer.
- Observers: searchable list with activity/hardware/sort filters and region, telemetry cards,
  and observer details with packet, packet-type, unique-node, SNR and recent-packet analytics.

Node favorites and the last 20 unique viewed nodes persist per analyzer.
Channel/observer favorites, packet detail/replay from messages, exports, dashboard
customization, persistent response caching, complete node details/analytics, external deep links,
location controls and exports are still planned. The source registry is cached;
node responses currently remain in memory only.

## Verify

Set `JAVA_HOME` and `ANDROID_HOME` for command-line builds, then run from `android/`:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Foundation UI tests use synthetic data and require an emulator/device, but no map
key or live analyzer. The live CARTO map smoke test runs only when a key is configured;
it verifies all three styles and overlays against real tiles. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
JUnit results: `app/build/test-results/testDebugUnitTest/`.
UI report: `app/build/reports/androidTests/connected/debug/`.
Lint report: `app/build/reports/lint-results-debug.html`.

To opt into the real analyzer end-to-end UI test, pass
`-Pandroid.testInstrumentationRunnerArguments.liveNetwork=true` to the connected
check. It waits for an actual packet from the default public analyzer and captures
map/Explore/feed screenshots in the test app's external files directory. This test
is skipped by default so CI does not depend on live traffic.

The CI build also compiles the instrumentation APK. Connected-device test execution
is currently local. See [implementation progress](../docs/android/progress.md) and
[the parity plan](../docs/android/README.md) for outstanding acceptance gates.
