# Phase 1 backend extraction

Implemented 2026-10-10 on `beacon/phase-1-backend`. Manual CoreScope regression remains pending.
No Beacon implementation, server detection, registry publication or version bump is included.

## iOS

- `AnalyzerBackend` exposes typed operations; `CoreScopeBackend` owns the REST paths, path encoding
  and query parameters. All existing feature/cache `APIClient.get` call sites now use that boundary.
- `AnalyzerBackendFactory` currently always selects CoreScope. `APIClient` remains its HTTP transport.
- `BackendCapability` describes client-supported operations, not proof that an individual server
  implements every endpoint. Existing optional-endpoint failures and analytics 404 behavior remain.
- Existing response models, cache identities/lifetimes, page sizes and channel offsets are preserved.
- `AnalyzerLiveFeed` emits packet and connection events through `AsyncStream`; `CoreScopeLiveFeed`
  owns socket lifecycle and CoreScope decoding. The observable `LiveFeedService` remains the shared
  UI-facing store, retains 200 events and rejects updates from disconnected feed generations.
- Existing `LiveEnvelope`/`LivePacketData` models are reused at the boundary. Their CoreScope-shaped
  fields will need explicit Beacon mapping; this extraction does not assert semantic equivalence.
- CoreScope's three-second reconnect behavior is preserved. Switching source clears live events;
  reconnecting the same source retains history. Diagnostics probes remain CoreScope-specific for now.

## Android

- `AnalyzerBackend` composes the existing `AnalyzerRepository` and `BrowseRepository` interfaces and
  exposes the existing neutral `PacketSource`/`LiveSignal` live-feed boundary.
- `CoreScopeBackend` delegates to the existing implementations, preserving saved-response behavior,
  host/region parameters, socket cancellation and reconnect rules.
- `AppContainer` selects the backend through one factory and exposes its existing interfaces to
  consumers. Capability names match the iOS set semantically; no UI behavior changes are intended.

## Validation

- iOS device-target app build passed. Simulator build-for-testing passed.
- iOS tests on iPhone Duo: **68 passed**, zero failures or skipped tests. New tests cover 19 REST
  request variants, reserved path characters, HTTP/decoding failures, date decoding, cache identity,
  capabilities, live decoding, event-buffer bounds and disconnected-feed isolation.
- Android `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain` passed:
  **160 tests**, zero failures/errors/skips, and a debug APK built. New coverage exercises backend
  delegation, cached restores, live-history reads, query parameters and channel path encoding.
- Device-target iOS test builds require a development team for the test target; signing settings
  were not changed. Tests ran on the simulator, and the original Xcode destination was restored.
- `git diff --check` passed. No commit was made by the implementation agent.

## Before phase 2 / manual handoff

- Manually check CoreScope map, live feed, node/packet details, analytics, observers, channels,
  older messages, monitored channels, search, region/source changes and background/foreground behavior.
- Before enabling Beacon, implement capability-driven UI gating, backend-aware cache namespaces,
  source-session invalidation for REST results, Beacon diagnostics, and phase 0 identity rules.
- The channel message API still exposes CoreScope offsets; replace that with opaque continuation
  state before adding Beacon channels in phase 3. Do not translate offsets into guessed cursors.
- Beacon live recovery, subscriptions/configuration, history completeness and uncertainty rules are
  still phase 2 work, not provided by the new CoreScope transport.
