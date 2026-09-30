# Future Google Maps option

Discussion saved September 28, 2026. Implementation is deferred. The user plans to push current updates themselves; do not push on their behalf based on this discussion.

## Product direction

- Keep CARTO/MapLibre as the default.
- Add Google as an optional provider in the map's existing layers menu, grouped by provider. Remember the choice and preserve camera position when switching.
- The user is leaning toward Option B: the native Google Maps SDK for Android. This is a future preference, not authorization to implement now.
- Google Play devices are sufficient for this project.
- The user performs runtime testing. Provide focused manual checklists with expected results; do not run automated tests or operate/install on emulators unless asked. Build-only compilation has been acceptable.

## Option B: native Google Maps SDK

Current ordinary Android map loads without a map ID have unlimited free usage. Our existing features can be implemented without paid Google API calls: standard/satellite/terrain/hybrid maps, regular markers, local clustering, node labels, live packet paths, replay animation, camera framing, device GPS, and searching analyzer nodes.

Packet routes are radio hops supplied by our analyzer, not driving directions. They do not require the Google Routes API. Existing device-location code does not require Google's paid Geolocation web API.

Use ordinary markers and no map ID. Map IDs (including cloud styling and features requiring them) trigger the paid Dynamic Maps SKU. Street View, Places, geocoding, and route calculations can introduce separate charges. A billing-enabled Cloud project and correctly restricted Android API key are still required. Free usage is based on current pricing, not a permanent guarantee or exemption from account-specific setup/prepayment requirements. Recheck documentation before implementation.

Architecture: separate shared state and business logic from map rendering, then add a Google renderer alongside MapLibre. Share analyzer data, filters, node selection, route resolution, GPS validation, outlier/region calculations, replay controls/timing, and surrounding phone/tablet UI. Implement engine-specific map lifecycle, markers, labels, clustering, drawing/animation updates, hit-testing, camera fitting, and attribution. Preserve camera position on provider switches; retain CARTO fallback.

Assessment: moderate-to-large work within the map feature, not an app rewrite or necessarily double the map code. The difficult work is parity and performance: live animation, cluster expansion, temporary visibility of route nodes, co-located nodes, replay transitions, camera persistence, and lifecycle handling. No firm time estimate was made.

Suggested sequence:
1. Extract shared map state from MapLibre-specific rendering.
2. Add Google rendering and provider selection with camera handoff.
3. Complete feature parity and provide manual acceptance checks for both providers, phone/tablet/foldable layouts, and live traffic.

## Option A: Google Map Tiles in MapLibre

Likely less rendering work and largely reusable overlays, but not a zero-change swap. Needs raster source/style integration, session management, attribution, error handling, cache-policy review, and label/contrast checks.

As checked September 28: 100,000 free 2D tile requests per month, then $0.60 per 1,000 through the first million requests under standard global pricing. A tile request is not a map opening. Session tokens currently last about two weeks; use the returned expiry. High-DPI tiles are available. Raster labels cannot participate in MapLibre's vector-label collision placement.

Third-party renderers are supported subject to Google policies. Display Google Maps branding plus applicable viewport/data-provider attribution; observe caching and offline-use restrictions.

Do not assume Android package/SHA restrictions work identically to the native SDK. Google's FAQ lists IP restrictions for Map Tiles, while its general mobile REST guidance describes Android headers and requires verifying that incorrect identifiers are rejected. Resolve endpoint enforcement before committing to direct client access; a secured backend proxy may be necessary. Existing CARTO code sending Android headers does not prove server enforcement.

## Claude's recommendation (September 28, 2026)

Several testers have asked for Google Maps. Recommendation: **Option B, phased**, still deferred until the user says go.

Why B over A, for users: people asking for "Google Maps" want its feel: crisp vector labels at every zoom, familiar and tappable places, satellite/hybrid, and familiar gestures. A shows Google imagery inside MapLibre: labels baked into raster images (soft between zooms, no collision with our node labels), places not interactive. Why B for the project: free with no cap (no map ID), an Android API key that Google enforces for the SDK, and no quota outages. A needs a daily quota cap to stay free, falls back to CARTO once the cap is hit, and may need a backend proxy if its key restrictions aren't enforced (see above).

Phases:
1. Extract shared map state and logic from MapLibre rendering. CARTO must behave exactly as before, so it can be accepted as "nothing changed". This protects the 0.7.6 replay work: panel/replay sync, framing insets, wait-for-idle start, unclustered route-only nodes.
2. Google renderer as a **Beta** choice in the layers menu, with node dots, clustering, taps, framing, standard/satellite/terrain/hybrid, and replays. Early tester feedback.
3. Parity polish: smooth live route animation, node labels, co-located nodes, and camera handoff between providers.

Before starting, ask testers what they want most from Google Maps (satellite? places near nodes? just the familiar map?) to set phase 2 priorities. Work can be split with Codex (map work has been Codex's area).

## Status

- 2026-09-30: work started on branch `android-google-maps`. Phase 1 (engine split) is done
  and waiting for a "nothing changed" check on CARTO; see `progress.md`. Pricing rechecked
  the same day with no change. Phase 2 needs a billing-enabled Cloud project and an Android
  API key restricted to the package and the upload and Play app-signing SHA-1s.

## Sources to recheck

- [Google pricing](https://developers.google.com/maps/billing-and-pricing/pricing)
- [SKU triggers](https://developers.google.com/maps/billing-and-pricing/sku-details)
- [Android SDK billing](https://developers.google.com/maps/documentation/android-sdk/usage-and-billing)
- [Map ID billing caveat](https://developers.google.com/maps/documentation/android-sdk/map-ids/get-map-id)
- [SDK setup and Play services](https://developers.google.com/maps/documentation/android-sdk/config)
- [Tiles overview](https://developers.google.com/maps/documentation/tile/overview)
- [Tile policies](https://developers.google.com/maps/documentation/tile/policies)
- [Tile session tokens](https://developers.google.com/maps/documentation/tile/session_tokens)
- [Credential restrictions](https://developers.google.com/maps/faq)
- [Mobile REST security](https://developers.google.com/maps/api-security-best-practices)
