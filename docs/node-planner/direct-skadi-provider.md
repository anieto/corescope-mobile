# Direct Skadi terrain provider

Implemented 2026-11-07 for 0.10.4 Estimated Coverage on iOS and Android.

Prepared studies without an explicit catalog discover required one-degree tiles directly from
`https://elevation-tiles-prod.s3.amazonaws.com/v2/skadi/{latitude}/{tile}.hgt.gz`.
Only a v2 HTTP 404 triggers the legacy `/skadi/` fallback. Missing tiles fail preparation;
they never silently become sea-level terrain. Discovery uses bounded, cancellable HTTPS HEAD
requests and rejects redirects, malformed metadata and unexpected content encoding.

The provider follows the direct-download approach documented by
[Meshtastic Site Planner](https://github.com/meshtastic/meshtastic-site-planner/blob/main/ARCHITECTURE.md).
The [AWS dataset](https://registry.opendata.aws/terrain-tiles/) and
[Skadi format](https://github.com/tilezen/joerd/blob/master/docs/formats.md) describe gzip HGT,
3601-square signed big-endian samples and EGM96 heights. Source data varies by region;
the descriptor does not promise bare-earth elevations. It retains the
[source attribution reference](https://github.com/tilezen/joerd/blob/master/docs/attribution.md).
Result-screen attribution presentation still needs integration.

## Identity and verification

AWS ETags are opaque representation identifiers, not independent checksums. Discovery records
the exact source URL, strong ETag and compressed length. Downloads send `If-Match`, require the
same response ETag, enforce byte limits and compute a local compressed SHA-256. Decoding checks
that hash, gzip integrity and exact dimensions. Cache metadata retains the local compressed hash
and decoded hash; cache identity includes URL and ETag. Changed representations cannot reuse an
older cache entry. Existing independently SHA-pinned catalog descriptors remain supported with
their existing cache identities.

This direct mode trusts AWS HTTPS for initial content authenticity; locally computed hashes do
not provide an independent publisher signature or checksum. Offline preparation requires retained
descriptors and complete cached tiles. Fresh discovery cannot run offline.

## Qualification

Both native host suites fetched `N50W116` from AWS v2: 16,123,465 compressed bytes,
25,934,402 decoded bytes, ETag `"eca406dc64a19280047b5e9faf9afe80"`. Each passed discovery,
conditional download, native decoding, cache publication and offline reopening with zero download
budget. Synthetic tests cover fallback, missing tiles, cancellation, malformed metadata, changed
ETags, hash failures and cache reuse. All 110 Swift and 119 Android coverage tests passed.

Live checks are opt-in with `NODESCOPE_SKADI_LIVE=1`; run the Swift host harness
`python3 tools/node-planner/run_coverage_terrain_tests.py`, or Android's
`./gradlew :app:testDebugUnitTest --tests '*Coverage*' --rerun-tasks` from `android/` with that
environment variable set. Live checks use temporary caches and remove them afterward.

This qualifies one real tile through the native terrain path, not global availability, device
performance or full RF execution. Upstream engine permission is assumed granted per the project
owner's instruction. Production kp1812 packaging/binding and Calculate/result integration remain.
