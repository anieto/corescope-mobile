# Beacon phase 2 implementation

## NodeScope 0.8.0

NodeScope 0.8.0 adds Beacon as a first-class browse source on iOS and Android while preserving the
existing CoreScope behavior. Both apps now share the same source-type, capability, paging, identity,
route-confidence, live-demand, and recovery rules.

Beacon sources support the map, nodes, node details, observers, packet history, packet details,
confirmed route replay, live packet animation, source-aware diagnostics, and sparse-server empty
states. Features that Beacon does not expose are hidden or replaced with an explanatory state.

The v2 source registry identifies each server as CoreScope or Beacon. Missing types retain legacy
CoreScope behavior; unknown or malformed entries are skipped independently. Colorado Mesh is the
first listed Beacon source.

Release verification on 2026-10-13:

- iOS: 199 tests passed.
- Android: 220 tests passed.
- Manual CoreScope and Beacon passes completed on both platforms.
- Live contract checks passed against `beacon.meshtexas.org`,
  `map.meshcore.coloradomesh.org`, and `dev.meshcore.ca`.
- No contract drift required a fixture refresh.

Remaining release operations are publishing the registry commit, supplying store screenshots,
uploading TestFlight and Play beta builds, and completing the beta smoke test.
