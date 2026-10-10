# Beacon support plan

Status: phase 0 done (see [phase0-findings.md](phase0-findings.md), which wins where the two differ).
Phase 1 backend extraction implemented on iOS and Android; automated checks pass, manual CoreScope
regression pending. See [phase1-implementation.md](phase1-implementation.md). Last updated 2026-10-10.

NodeScope talks only to CoreScope today. Some communities are moving to
[Beacon](https://github.com/MeshCore-Beacon/beacon-docs) (Colorado Mesh already has, and dropped out of
our source list because of it). This plan adds Beacon as a second server type on iOS and Android
without changing anything for CoreScope users.

Beacon API reference: `docs/api-contract.md` in beacon-docs, plus Swagger at `/swagger/index.html` on
any server. Test servers: `beacon.meshtexas.org` (ours, MeshTexas data), `map.meshcore.coloradomesh.org`
(Colorado), `dev.meshcore.ca` (Beacon's own, Canadian data).

---

## 1. What we verified

- **Packet hashes are identical** across both servers. Five recent Beacon packet hashes all resolved on
  our CoreScope (`/api/packets/{hash}` → 200). Identifiers carry over; availability still depends on
  the selected server and its retention window.
- **Nodes and observers both expose `publicKey`** on Beacon (observer key only on the detail endpoint),
  and Beacon can look a node up by key (`/nodes?pubkey=`). So the public key stays the app-wide
  node identity. Observer identity remains a phase 0 validation gate: a cached UUID mapping alone
  does not solve public-key lookup on a fresh install.
- **Observer lookup by public key: confirmed missing** (beacon.meshtexas.org, server 2.0.3,
  2026-10-10). `/observers/{publicKey}` returns 400 (UUIDs only); `?publicKey=` and `?pubkey=` on
  `/observers` are ignored, not rejected; `/observers` and `/observers/directory` summaries omit
  `publicKey`; observers that never advert (e.g. MeshHub-bot) aren't in `/nodes?pubkey=` either. Our
  server has 71 observers, so a key lookup would mean up to 71 detail requests. Phase 0 must pick a
  design: source-scoped UUID references for Beacon observers, plus asking upstream to add `publicKey` to
  observer summaries or a key filter (both additive under `/api/v1/`).
- **Deep links** (`nodescope://node/<pubkey>`, `observer/<id>`, `packet/<hash>`) carry no host, so they
  preserve their syntax, but cannot guarantee that the selected server has the referenced item.
  New links should support optional source information; retain old links and ask before switching
  to an unfamiliar host. Channel links need a collision-safe mapping (section 5).
- **Favorites, recents and search history already store a `source`** (the host) per item, so they
  separate cleanly by server.
- **`/api/v1/info`** answers `{"minAppVersion": null, "serverVersion": "2.0.3"}` on ours; that's how we
  detect a Beacon server.
- **Raw packets are kept for 7 days** by default (`packets.retention: 168h`, ours included). Stats rollups
  go back 90 days.

## 2. Where each app stands

| | iOS | Android |
|---|---|---|
| Size | ~18.2k lines Swift | ~10.6k lines Kotlin |
| Server access | `APIClient.get(path)` called directly from 12 files, 17 CoreScope endpoints | Two interfaces, `AnalyzerClient` and `BrowseRepository`, with CoreScope implementations behind them |
| Models | Decoded straight from CoreScope JSON | Same, in `core/model` |
| Live feed | `LiveFeedService` (CoreScope WebSocket) | `LiveFeed.kt` |
| Tests | 7 test files | ~30 test files, including protocol and JSON tests |

**Android is already structured for a second backend; iOS isn't.** On iOS, the main cost is moving those
17 endpoint calls behind an interface first.

## 3. Architecture

One idea on both platforms: **screens talk to an `AnalyzerBackend`, not to URLs.**

- `AnalyzerBackend` (iOS protocol, Android interface) exposes what screens need, e.g. `nodes(region:)`,
  `nodeDetail(publicKey:)`, `packets(...)`, `packetDetail(hash:)`, `observers()`, `channels()`,
  `channelMessages(...)`, `regions()`, `mapDefaults()`, `liveFeed()`, `capabilities`.
- **Domain models stay the app's existing models** (`MeshNode`, `MeshObserver`, `PacketDetail`, ...).
  CoreScope keeps decoding into them. Beacon gets its own small DTOs plus mappers into the same models.
  No screen sees a Beacon type. Reuse models only where meanings match; extend neutral models where
  needed rather than inventing zero values or treating observer hearings as unique packets.
- **`capabilities`** is a set (e.g. `.nodeHealth`, `.nodeReach`, `.nodePaths`, `.nodeAnalytics`,
  `.observerAnalytics`, `.observerTelemetry`, `.channelDecryptOnPhone`, `.mapDefaults`). Screens hide
  or swap cards based on it, instead of checking server type.
- **`CoreScopeBackend`** wraps today's calls exactly. **`BeaconBackend`** handles UUID lookup (cached
  `publicKey → uuid`), epoch ms, camelCase, cursor paging (200-item pages), and 429 + `Retry-After`.
- **Live feed:** a `LiveFeed` interface emitting neutral packet/observation updates and connection or
  recovery state; two implementations. Define deduplication and reconnect recovery before implementation.
- **Caches** are already namespaced by host; also add the server type to cache keys so a host that
  switches from CoreScope to Beacon (Colorado's case) can't reuse stale decoded data.
- **Source lifecycle:** identify each active source session with a generation token. On a host or
  backend change, cancel old requests/subscriptions, discard late results, and clear live events,
  cursors and UUID mappings. Revalidate region selections and open detail screens. Scope persisted
  mappings by source/backend and invalidate stale UUIDs after a server rebuild or failed lookup.
- **Shared connection ownership:** coordinate subscriptions across all visible screens/windows using
  one connection per active source where possible. A hidden map must not disable path resolution
  while another visible map still needs it; backgrounding one window must not disconnect another.

## 4. Feature mapping

| Feature | CoreScope today | Beacon source | Effort | Notes |
|---|---|---|---|---|
| Node list, map pins | `/api/nodes?limit=5000` | `/nodes` paged (200/page, ~8 pages for 1,500 nodes) | Easy | Cache aggressively; load first page fast, then fill |
| Node detail (basic) | `/api/nodes/{pk}` | `/nodes?pubkey=` → `/nodes/{uuid}` | Easy | Beacon adds regions heard, clock drift, capability flags |
| Health / reach / paths cards | `/health`, `/reach`, `/paths` | `/neighbors`, `/observations`, `/routes/search` | Medium | Neighbors (with SNR) replaces most of reach; hide the rest at first |
| Node analytics screen | `/api/nodes/{pk}/analytics` | Computed on the phone from `/nodes/{uuid}/observations` | Medium | Activity, heatmap, packet types, hops, SNR all work; per-observer coverage needs a packet-detail call per packet, so cap it or skip it; 7-day max |
| Observers list | `/api/observers` | `/observers` | Medium | Public-key identity and fresh-install lookup must pass phase 0; no per-row detail fan-out |
| Observer analytics | `/api/observers/{id}/analytics` | `/observers/{id}/activity` + `/telemetry` | Medium | Beacon is richer (battery, noise floor, airtime); new cards possible later |
| Regions / picker | `/api/config/regions` + `/api/iata-coords` | `/iatas` (with coordinates) + `/regions` | Easy | Beacon regions are groups of IATAs, so they can appear as picker groups |
| Map defaults | `/api/config/map` | none | Easy | Frame on the selected region's IATA coordinates (`RegionFraming` already does this) |
| Packet feed | `/api/packets` | `/packets` (cursor = epoch ms) | Medium | |
| Packet detail | `/api/packets/{hash}` | `/packets/{hash}` | Medium | Beacon gives per-hop `resolvedPath` with confidence and coordinates |
| Route replay | CoreScope `resolved_path` | Beacon `resolvedPath` | Medium | Ambiguous hops need a rule (show all candidates, or skip) |
| Live map | CoreScope WebSocket | `/ws`: subscribe + `configure resolvePath` + 30s ping | Medium | Only turn on `resolvePath` while the map is visible (1–2 KB per event) |
| Server-decrypted channels | `/api/channels`, `/messages` | `/channels?keyKnown=true`, `/channels/{id}/messages` | Medium | Later release; source-scoped identity and migration must be defined, not hash + name |
| Your own monitored channels (decrypted on the phone) | Packet list includes encrypted payload | Only packet detail has `rawPayload`, one request per packet | Hard | Unsupported on Beacon initially; server-decrypted channels follow in phase 3; ask Beacon for a list-level payload or channel-hash filter |
| Add to MeshCore QR | node name, key, type | same fields | Easy | |
| Diagnostics screen | probes CoreScope endpoints | probes Beacon endpoints | Easy | Show server type and version |
| Favorites / recents / search | keyed by public key + source | nodes retain keys; observer/channel mapping needs validation | Medium | Preserve existing entries; migrate identity without merging unrelated items |

## 5. Things that are easy to miss

1. **Old app versions read the live source list.** If a Beacon server is added to `us-sources.json`,
   every installed copy of NodeScope will show it and fail against it. **Fix:** keep `us-sources.json`
   CoreScope-only forever, and add a new `sources-v2.json` that new app versions read, with a `type`
   field (`"corescope"` / `"beacon"`). Old apps never see Beacon entries.
2. **Ignore Beacon's `minAppVersion`.** It refers to BEACON Mobile's version numbers, not ours. Use
   `serverVersion` to set a minimum supported Beacon version instead (2.0.x today).
3. **Rate limits are per IP: 300 requests/min, 5 WebSockets, 10 WebSocket connects/min.** Phone carriers
   put many users behind one IPv4 address (CGNAT), so heavy request patterns could get several NodeScope
   users throttled together. Rules: page lazily, cache, never fan out per-row requests in lists, respect
   `Retry-After`, and close the WebSocket when backgrounded (Beacon's own guidance). Worth raising with
   the Beacon maintainers early.
4. **Custom hosts need auto-detection.** Probe `/api/v1/info` (Beacon) and fall back to CoreScope's
   `/api/stats` when appropriate. Require a valid identifying response before choosing a backend.
   A timeout, TLS failure, 429 or server error is not evidence of CoreScope: retain a known backend
   and report connectivity trouble. Re-detection may change the backend only on positive evidence.
5. **Identity mapping.** Full public keys remain node IDs; normalize hex case consistently without
   changing existing favorites silently. Validate an efficient observer-key lookup on a fresh install
   before committing to public keys for every observer. If none exists, explicitly design a neutral,
   source-scoped observer reference and legacy-link behavior. Missing keys must not trigger fetching
   every observer's details. Beacon UUIDs are implementation details wherever a stable key is available.
6. **Times and units.** Beacon uses epoch ms, CoreScope ISO strings; `radio` is `"freq,bw,sf"` text in
   some places and separate fields in others.
7. **History depth.** Anything beyond 7 days on Beacon comes from hourly rollups or not at all. Time-range
   pickers should cap or relabel on Beacon.
8. **Empty states.** Beacon learns nodes from adverts only (repeaters advert every ~12–47h), so a new
   server looks sparse. Word empty states so they don't read as errors.
9. **Licensing.** Beacon is AGPL, but we only call its public API, so nothing in NodeScope changes. Don't
   copy code from BEACON Mobile (its repo isn't public anyway).
10. **App Store.** No new data collection or permissions. Update the description and screenshots to say
    "CoreScope and Beacon", and mention it in review notes so a reviewer can test against a Beacon host.
11. **Courtesy.** Tell the Beacon maintainers a second client is coming (and about the rate-limit
    question), and ask server operators before listing them.
12. **Channel identity.** Beacon's channel hash is one byte, not a unique identifier. Hash + display
    name is not guaranteed unique or stable either. Define a source-scoped identity, persistence rules
    for server channel IDs, and migration of existing favorites/links before phase 3. Never merge
    channels based on a hash alone; handle renames, collisions and server database resets explicitly.
13. **Model semantics.** Preserve unknown versus zero, unsupported versus empty, incomplete history
    versus quiet periods, and observations versus unique packets. Distinguish omitted fields (keep
    existing data) from explicit nulls (clear data) in node updates. A resolved-hop key may be a prefix:
    do not use it as a full node identity or QR contact. Preserve uncertainty in route replay rather
    than drawing an inferred route as confirmed.
14. **Live recovery.** Handle dropped-event notices and reconnect gaps, not just socket reconnection.
    Live events do not provide observation IDs, so do not assume a live event supplies a REST backfill
    cursor. Validate how to obtain recovery checkpoints or use a bounded REST refresh with overlap and
    deduplication. Distinguish new packets, observation updates and transient repeat events. Use bounded
    exponential backoff with jitter, honor throttling, and restore subscriptions/configuration after
    reconnecting. Serialize connection-wide configuration so flags do not overwrite each other.
15. **Pagination and resource budgets.** Keep cursors opaque to screens and follow each endpoint's
    contract, including tie-breakers where offered. Test timestamp ties, repeated cursors, changing
    lists, cancellation and partial page failures. Deduplicate without hiding incomplete results;
    establish bounds for pages, memory, bytes and time-to-first-content, not just request counts.

These review additions draw on the published
[Beacon API contract](https://github.com/MeshCore-Beacon/beacon-docs/blob/main/docs/api-contract.md)
and current iOS code. They are not additional live-server verification. Phase 0 must pin the contract
revision and server versions used for fixtures; newer documentation may describe features absent on
the supported deployments.

## 6. Phases

| Phase | What | Visible change | Rough size |
|---|---|---|---|
| 0. Contract validation | Record versioned fixtures; prove observer lookup, channel identity rules, pagination and live recovery; draft `sources-v2.json` schema | None | Small, gates architecture |
| 1. Backend layer (iOS) | Introduce `AnalyzerBackend` + `CoreScopeBackend`; migrate all 17 calls feature-by-feature with regression tests; capabilities and neutral live-feed interface | None (pure refactor) | Large, riskiest |
| 1. Backend layer (Android) | Rename/extend existing interfaces to match; add capabilities and live-feed interface | None | Small |
| 2. Beacon browse | `BeaconBackend`: detection, nodes, basic node detail, observers, regions, map framing, packets/detail, replay, live map and basic diagnostics | Beacon servers work for browsing | Large |
| 2R. 0.8.0 release | Capability-gate unsupported features; validate both platforms; publish `sources-v2.json`, store text and review notes; TestFlight / Play beta first; prepare rollback | Public browse support | Small |
| 3. Beacon depth (later releases) | Computed node analytics, observer activity/telemetry, server-decrypted channels, neighbors card, expanded diagnostics | Most screens filled in | Medium |
| Later | Monitored (on-phone decrypted) channels on Beacon, if Beacon adds a way to list payloads cheaply | | Depends on upstream |

**Phase 0 exit gate:** record the chosen identity and recovery rules with fixtures and small contract
tests before the large refactor. Resolve unsupported assumptions rather than relying on a warm cache.
Channel rules must be understood now, but channel implementation remains outside 0.8.0.

**0.8.0 release gate:** both platforms pass CoreScope regression and Beacon browse tests; unsupported
analytics/channels are hidden or clearly explained, including entry through old favorites/deep links.
Ship a v2 bundled registry and separately version its persisted cache; unknown source types must not
break the entire list. Prepare removal/disablement of a problematic listing, retain last-known-good
registry data, and allow switching back to CoreScope. Removing a listing alone does not disable an
already selected custom host, so retain a recoverable error path for those users.

## 7. Platform order

Work each phase **iOS first, then Android right after, and release them together.**

- iOS needs the big refactor; Android doesn't. Doing iOS first settles the interface, capability names and
  mapping rules, and Android then follows a finished design. That's mostly translation, made easier by
  its existing interfaces.
- Don't finish all of iOS before starting Android. The two would drift apart, and `docs/android/parity-matrix.md`
  exists to prevent that. Phase by phase keeps them in step.
- Release both together, since `sources-v2.json` turns Beacon on for both at once.

## 8. Testing

- **Unit tests per platform** with recorded Beacon JSON (nodes page, node detail, observations, observers,
  observer detail, packet list/detail, channels, messages, info, WebSocket frames): mapping, paging,
  epoch ms, identity mapping, capability gating.
- **Regression:** CoreScope behaviour must not change in phase 1. The existing tests plus a manual pass on
  analyzer.meshtexas.org before and after. Add regression coverage as each feature moves behind the
  backend interface rather than waiting for the entire refactor to finish.
- **Live checks** against beacon.meshtexas.org (same mesh as our CoreScope, so results can be compared
  side by side), Colorado, and dev.meshcore.ca.
- **Rate-limit check:** count requests per screen on Beacon; no screen should need more than ~20 on open.
  Also record bytes, peak memory and time-to-first-content on a busy server; set measurable budgets
  from phase 0 results. Share throttling/backoff per source to avoid retries from every screen at once.
- **Identity and upgrades:** fresh-install observer links, missing keys, case normalization, channel
  hash collisions/renames, UUID changes, legacy favorites and source-bearing/legacy deep links.
- **Source transitions:** switch during REST loading, paging, reconnect and replay; simulate a backend
  change at the same host. No late response or cached event may populate the new source's UI.
- **Pagination and semantics:** timestamp ties, duplicate/repeated cursors, partial failures, unknown
  fields/types, missing versus null values, prefix-only hop keys, incomplete history and observation
  versus packet counts. Verify partial results are labeled, not presented as a complete dataset.
- **Live lifecycle:** dropped-event notices, overlap between REST and live data, reconnect gaps,
  throttling, subscription restoration, foreground/background transitions, and multiple visible
  screens/windows. Recover without duplicate rows, corrupted counts or reconnect storms.
- **Detection and registry:** offline first launch, TLS failures, malformed responses, 429/5xx,
  unknown source types, stale registry caches and rollback. Connectivity errors must not flip backend.
- The user does the hands-on app testing; builds must compile and unit tests must pass before handoff.

## 9. Decisions

Decided 2026-10-10:

1. **v1 scope:** browse first. 0.8.0 ships phases 1–2 through the phase 2R release checkpoint;
   phase 3 follows in later releases. Basic diagnostics are part of phase 2.
2. **Listed servers:** Colorado Mesh (`map.meshcore.coloradomesh.org`) in `sources-v2.json`. They've already
   agreed. beacon.meshtexas.org stays a test server, reachable as a custom host.
3. **Version:** phase 1 folds into 0.8.0 with the Beacon work, no separate point release.

Rate limits are per server, set in each operator's `config.yaml` (`ratelimit.*`, `websocket.*`), with
the defaults in section 5. Ours keys them by each visitor's real IP (Cloudflare → Caddy → `X-Real-IP`, with
`trusted_proxies` set). On a server whose proxy isn't configured that way, every visitor shares one
budget, which would hit NodeScope users first. Colorado runs behind nginx and its settings are unknown;
check how NodeScope behaves against it during testing.

Still open:

- ~~Phase 0 contracts~~ and ~~compatibility and budgets~~: resolved in
  [phase0-findings.md](phase0-findings.md) (server 2.0.3+, contract `9d1dae3`, identity, paging,
  live recovery and budget rules). How partial history and uncertain routes are *presented* is a UI
  decision for phases 2–3.
- **Contact the Beacon maintainers** about a second client, `publicKey` in observer summaries (or a
  key filter), and a cheap way to read group-text payloads.
