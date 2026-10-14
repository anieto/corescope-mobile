# Beacon phase 3 implementation

This document records measured contracts, UI-parity decisions, unavoidable concessions, and test
results as [phase3-tasks.md](phase3-tasks.md) progresses.

## 2026-10-13: Milestone A started

### Contract baseline

The current official Beacon API contract was audited from `MeshCore-Beacon/beacon-docs` branch `dev`
at commit `541bc238a9d6b7c527d1362d47417446d91cb6e9`. This is newer than the Phase 0 pinned contract, so
live support on the three NodeScope test servers must be measured before any newer field becomes a
required capability.

Phase 0 already provides sanitized fixtures for:

- node neighbors;
- node observations;
- channel pages, channel detail, and channel messages; and
- relevant node errors.

Those fixtures remain useful for Beacon 2.0.3 compatibility. Missing Phase 3 coverage includes
version-specific large-history and database-reset samples. Phase 3 fixtures now cover observer
activity, observer telemetry, empty histories, multi-page/tied-cursor channel cases, and channel
404 behavior.

The current contract confirms:

- observer telemetry supports `1h`, `6h`, and `24h` points, with optional battery, transmit/receive
  airtime, noise floor, uptime, queue length, and receive errors;
- observer activity supports bounded ranges up to 720 hours and `5m`, `15m`, `1h`, `6h`, or `24h`
  buckets, with explicit rollup/raw coverage boundaries;
- a gap between `rolledUntil` and `rawFrom` is unknown coverage, not zero traffic;
- activity payload totals count observations, not necessarily unique packets;
- channels page with opaque `nextPageCursor`; integer channel ID is the only server-local unique
  identity; and
- channel messages provide message ID, packet hash, channel hash, sender, content, sender time,
  observation count, and scope status.

### Initial CoreScope-to-Beacon UI parity matrix

This is the starting classification for A3. “Pending” means A5 or live measurement must prove the
semantics before implementation.

| Existing CoreScope UI | Beacon source | Classification | Planned presentation |
|---|---|---|---|
| Reach/neighbors rows | `/nodes/{id}/neighbors` | Direct | Reuse the existing reach/neighbors card and navigation; full public keys are linkable |
| Neighbor signal | Neighbor `snr` | Direct | Use the existing signal formatting; do not invent RSSI when absent |
| Node Activity chart | Observation `heardAt` buckets | Derived | Reuse the existing activity card and time-axis behavior |
| Node Signal chart | Observation `snr`/`rssi` | Direct | Reuse the existing chart; omit missing series rather than plotting zero |
| Node Packet Types | Observation `payloadType` | Derived | Reuse the existing donut/legend and shared payload labels |
| Node Hop Count | Observation `hopCount` | Derived | Reuse the existing hop chart |
| Node uptime heatmap | Observation timestamps | Derived, pending | Use the existing heatmap only if it is labeled as observed activity and bounded history cannot imply uptime |
| Availability and longest silence | Observation timestamps | Derived, pending | Preserve the summary-card layout only if A5 can define honest bounded-window semantics |
| Observer coverage | Not present in node observations | Unavailable | Do not fan out to packet detail; explain the missing section if necessary |
| Peer interactions | No equivalent in recorded observation shape | Unavailable | Do not infer peers from routes or prefixes |
| Observer Packets Over Time | `/observers/{id}/activity` points | Direct | Reuse the existing bar chart, inserting zeros only in contract-defined covered gaps |
| Observer Packet Types | Activity `payloadTypes` | Direct | Reuse the existing packet-type chart |
| Observer SNR Distribution | Activity point averages/minima | Derived, pending | Match the existing chart only if a defensible bucket derivation is defined; otherwise use the same card with an explanatory state |
| Observer Unique Nodes Heard | No confirmed activity field | Pending/unavailable | Check 2.0.3/2.0.5 shapes; never substitute advert count without labeling it |
| Observer Recent Packets | Activity has no packet rows | Unavailable | Do not add per-packet requests; retain the card only with concise explanatory copy if layout parity benefits |
| Observer battery/noise/airtime | `/observers/{id}/telemetry` | Beacon-only | Add established NodeScope cards after the shared analytics sections |
| Channels list | `/channels?keyKnown=true` | Direct after identity migration | Reuse the existing server-monitored section, search, filters, sort, and selection behavior |
| Channel conversation | `/channels/{id}/messages` | Direct | Reuse message bubbles, timestamps, paging, refresh, and empty states |
| Channel favorite/recent/link | Source + Beacon integer ID | Semantic difference | Preserve UI; migrate persistence and deep-link payloads without hash-only matching |
| Monitored/on-phone channels | No affordable list payload | Unsupported | Keep the Phase 2 explained state; this is outside Phase 3 |
| Diagnostics layout | Bounded Phase 3 probes | Direct | Add rows to the existing sections; no bulk history probes |

### First binding concessions

No concession is final until A3/A5 complete, but two are already required by the recorded contract:

1. Node observer coverage cannot be reconstructed from `/nodes/{id}/observations` because it has no
   observer identity, and fetching every packet detail would violate the request budget.
2. Observer recent-packet rows cannot come from the activity or telemetry endpoints. NodeScope will
   not fan out requests merely to fill that CoreScope card.

These limitations affect data availability, not navigation or the surrounding visual design.

### Initial live validation

The extended capture completed 70 read-only requests across all three approved servers. Observer
activity and telemetry returned HTTP 200 everywhere:

| Server | Version | Activity (24h) | Telemetry (24h) |
|---|---:|---:|---:|
| `beacon.meshtexas.org` | 2.0.5 | 3,059 B / 128 ms | 1,259 B / 96 ms |
| `map.meshcore.coloradomesh.org` | 2.0.3 | 3,825 B / 55 ms | 3,763 B / 49 ms |
| `dev.meshcore.ca` | 2.0.5 | 3,612 B / 722 ms | 3,698 B / 270 ms |

MeshTexas channel pagination using `limit=2` and opaque `nextPageCursor` returned two stable pages.
Unknown observer activity and unknown integer channel IDs both returned structured 404 responses.
These results validate endpoint availability, not yet semantic parity or cache budgets.

All three servers returned the same observer activity and telemetry field sets. The 2.0.3 and 2.0.5
hourly activity responses include `rolledUntil` and `rawFrom`; the 15-minute raw response correctly
omits them. Telemetry points consistently exposed the same eight optional measurements. Sanitized
fixtures now cover raw and hourly activity, telemetry, observer adverts, tied channel pages, and the
new 404 cases. The sanitizer initially identified the previously unknown `nodePublicKey` and
`nodeName` identity fields in observer adverts; those fields are now scrubbed and the final leak scan
reports zero leaks across 2,794 identifiers, 1,289 names/texts, and 1,832 coordinates.

### Preliminary analytics derivations

The following rules are safe enough to guide the neutral models; A5 remains open until the node
observation paging budget is measured:

- activity counts group retained observations by the selected UTC bucket;
- packet-type counts group by `payloadType`, keeping unknown types distinct rather than discarding
  them;
- signal trend plots reported SNR/RSSI only, with missing readings omitted;
- hop distribution groups the recorded `hopCount`; zero is a direct observation, not unknown;
- relay percentage is the proportion of retained observations with `hopCount > 0`;
- packets per day is explicitly “observations per covered day” on Beacon because this endpoint counts
  observations; the UI must not call them unique packets;
- availability, uptime, and longest silence cannot be inferred from packet absence. Beacon retention
  and analytics coverage gaps make a quiet bucket different from proven downtime; and
- signal grade cannot reuse CoreScope's server-generated grade until its formula is defined. It must
  be omitted rather than independently reinterpreted on each platform.

The existing activity heatmap can be reused visually, but its Beacon title/copy must say “Observed
Activity” rather than “Uptime.” This is a necessary semantic concession while preserving the same
card, grid, colors, ordering, and interactions.

Cross-platform fixture guards now pin these coverage, empty-history, and paging semantics. The
complete regression suites pass with 201 iOS tests and 222 Android tests.

### Initial request and cache budgets

These ceilings are implementation requirements. A screen must report partial data instead of
silently exceeding them.

| Screen or operation | Maximum requests | Maximum pages or rows | Initial cache lifetime |
|---|---:|---:|---:|
| Neighbors | 1 | Server response | 5 minutes |
| Node analytics | 20 | 4,000 observations at 200/page | 5 minutes |
| Observer activity | 1 | Contract-bounded points | 5 minutes |
| Observer telemetry | 1 | Contract-bounded points | 5 minutes |
| Channels list | 10 | 2,000 channels at 200/page | 5 minutes |
| Channel messages, initial view | 1 | 20 messages | 1 minute |
| Channel messages, user paging | 1 per explicit page | 20 pages per visit | 1 minute |
| Diagnostics | 4 | One bounded probe per advertised Phase 3 family | No bulk cache |

The node-observation ceiling matches the existing hard 20-page paging guard and keeps the largest
automatic screen load at 20 requests. The channel list uses a lower ten-page ceiling because it is
navigation data rather than an explicit history request. Cache keys must include the source session,
resource identity, filters, and requested range; changing sources invalidates every result.

### Next work

1. Measure a representative multi-page node history against the 20-request ceiling.
2. Complete the detailed parity matrix and channel identity recovery cases.
3. Encode the neutral completeness and provenance models used by both platforms.

## 2026-10-13: Neutral analytics and neighbors foundation

The iOS neutral analytics contract now carries explicit count units, provenance, requested versus
covered bounds, and complete/partial/unsupported status. CoreScope payloads continue decoding with
no added wire requirement. Beacon history requests longer than seven days remain represented as the
original request plus partial seven-day coverage rather than appearing to be complete.

The neutral neighbor contract keeps full public keys, display prefixes, and source-local IDs
separate. Only validated 64-character public keys are linkable. Missing signal, time, direction, or
position stays unknown. CoreScope adapts its existing reach response without changing its request;
Beacon resolves the node's source-local UUID and then reads `/nodes/{id}/neighbors` in two bounded
requests. Beacon currently reports SNR but not RSSI or reliable directionality, so those values are
left unknown.

The node-detail cache and source-switch loading path now include Beacon neighbors. The screen reuses
the existing Mesh Reach and Links card positions, row density, expansion behavior, catalog-backed
navigation, loading state, refresh gesture, and error status. Beacon leaves the Two-way Links and
Observers metrics visibly unknown and explains why, while each neighbor row shows reported SNR.
The project builds successfully and the complete pre-UI iOS suite passes with 210 tests. C2 remains
in progress until side-by-side runtime review.

## 2026-10-13: iOS node analytics started

Beacon node analytics now use one full-key identity lookup plus at most 19 pages of 200 observations,
keeping the entire screen at or below 20 requests with no packet-detail fan-out. Cursors may not
repeat, duplicate observation IDs are ignored, and obsolete source sessions cannot publish results.
Beacon cursors are ordered by observation ID rather than strictly by `heardAt`; delayed ingestion can
therefore produce valid timestamp inversions. The client accepts those inversions and sorts retained
observations by time for presentation. Results are cached for five minutes by source, node, and range.

The existing CoreScope analytics screen now accepts honest unknown summary values. Beacon fills the
shared activity, observed-activity heatmap, signal, packet-type, and hop sections; counts and exports
say “observations,” not “packets.” Requests beyond seven days show the retained seven-day window as
partial. Availability, longest silence, signal grade, observer coverage, and peer interactions remain
unknown with an explicit explanation because the node-history endpoint cannot support them. The
CoreScope wire contract and labels remain unchanged.

## 2026-10-14: iOS observer activity and telemetry started

Beacon observer analytics now fetch the bounded 24-hour activity and telemetry endpoints concurrently
for a total of two requests, then cache the combined source-scoped result for five minutes. Observer
UUIDs are validated before any request, optional telemetry stays unknown when omitted, and empty
activity or telemetry remains a successful empty result rather than an error.

The existing observer-detail screen now reuses the CoreScope Packets Over Time and Packet Types card
positions for Beacon observation counts. It explicitly explains that unique-node history, recent
packet rows, and a true SNR distribution are unavailable from the bounded activity response instead
of synthesizing those sections. Beacon-only telemetry follows the shared sections with the established
card treatment: latest battery, noise floor, transmit/receive airtime, uptime, and receive errors,
plus battery and noise-floor history charts. CoreScope decoding and presentation remain unchanged.

The previously reported node-analytics pagination failure was traced against both live servers to
valid delayed-ingestion timestamps. The map also now derives a whole-source viewport from Beacon IATA
coordinates and reframes when those asynchronous coordinates arrive, so custom Beacon hosts do not
depend on published community-source viewport metadata.

The Xcode project builds successfully and all 216 iOS tests pass. E1–E3 remain in progress pending
device review, additional cancellation/source-switch assertions, and the Android port.
## 2026-10-14: iOS server-decrypted channel foundation

- Added Beacon keyed-channel paging through `/channels?keyKnown=true`, using the authoritative opaque
  `nextPageCursor` and a bounded ten-page walk.
- Added Beacon channel-message mapping through `/channels/{id}/messages`. The compatibility adapter
  walks Beacon cursors when the existing CoreScope UI requests an older offset; it does not invent
  observer, hop, SNR, last-message, or message-count values that Beacon does not return.
- Beacon channel identity is now the server integer ID within the selected source. Hash collisions no
  longer merge list rows, cache entries, favorites, recents, or navigation state. Existing CoreScope
  channels retain their hash identity and persisted decoding behavior.
- Reused the CoreScope channel list and conversation UI. Beacon still displays a separate notice that
  on-device monitored-channel decryption is unavailable; server-decrypted channels no longer show an
  unsupported notice.
- Added fixture tests for opaque list paging, source-scoped identity, and message projection. The full
  iOS suite passes: 218 of 218 tests.
- Remaining channel work: deeper message-cursor/error fixtures, live-server budgets, dropped-reference
  UI review, and device review.

### Channel reset recovery

- A message 404 now performs one forced keyed-channel refresh on the same active source. Recovery is
  allowed only when exactly one channel has the same case-sensitive name and the saved channel is
  `Public` or a hashtag channel. Private names, renamed channels, ambiguous names, and hash matches
  are rejected.
- A successful recovery switches subsequent message paging to the replacement integer ID and moves
  matching favorites and recent items to that identity. Favorite ordering and its original saved date
  are preserved.
- Legacy CoreScope channel snapshots without Beacon identity fields still decode and retain hash-based
  identity. Reset, ambiguity, rename, private-channel, and legacy decoding tests are included.
- The full iOS suite passes: 220 of 220 tests.

## 2026-10-14: iOS diagnostics correction

- Beacon diagnostics no longer report server-decrypted channels as unavailable. Nodes, channels,
  observers, and regions reflect the capabilities of the verified Beacon adapter; map defaults remain
  a warning with the documented region-coordinate fallback.
- Diagnostics still make only the existing bounded `/info` request. Explicit rows now describe
  neighbors, node analytics, observer activity, observer telemetry, and channel messages; live
  per-capability endpoint probes remain in progress.
- Missing/repeated channel-message cursors now fail as incomplete pagination, while a complete empty
  channel remains a successful empty result. The full iOS suite passes: 222 of 222 tests.

### Live channel acceptance and paging budget

Nine bounded read-only requests checked `/info`, one keyed channel, and its first message page across
the approved servers on October 14, 2026:

| Server | Version | Keyed channel list (`limit=1`) | Messages (`limit=20`) |
|---|---:|---:|---:|
| `beacon.meshtexas.org` | 2.0.5 | 200 · 200 B · 145 ms | 200 · 4,251 B · 92 ms |
| `map.meshcore.coloradomesh.org` | 2.0.3 | 200 · 202 B · 46 ms | 200 · 4,672 B · 44 ms |
| `dev.meshcore.ca` | 2.0.5 | 200 · 200 B · 1,060 ms | 200 · 4,481 B · 276 ms |

All three returned one keyed channel and a full 20-message page with `hasMore=true`. The reader now
retains Beacon’s cursor for each loaded offset, so the normal explicit “Load older messages” action
uses one request rather than replaying every earlier page. A bounded fallback walk remains for a
missing in-memory cursor, such as restoration from an older cache entry.

### iOS channel source-session hardening

- Channel-list and channel-message cache keys now include the active source-session UUID in addition
  to the analyzer URL and channel identity. Renewing a Beacon session at the same URL therefore
  cannot reuse a completed response created by the previous session.
- Cache loads reject a key whose session does not match the backend, while existing invalidation and
  late-result checks continue to protect in-flight requests.
- Milestone F1–F4 are complete on iOS. Android remains separately tracked as F5.
- The Xcode project builds for testing and the complete iOS suite passes: 223 of 223 tests.

### iOS bounded Phase 3 diagnostics

- A supported Beacon diagnostic run now performs exactly four bounded, read-only requests: `/info`,
  `/nodes?limit=1`, `/observers?limit=1`, and `/channels?limit=1&keyKnown=true`.
- The three one-row family checks cover the Phase 3 node, observer, and server-channel capability
  rows without fetching histories, details, messages, or additional pages. A failed family check is
  shown as a warning rather than a false compatibility failure.
- Unsupported versions and failed `/info` checks do not start the three browse-family requests.
  CoreScope's existing diagnostics request contract is unchanged.
- Milestone G1–G2 are complete on iOS; Android remains separately tracked as G3. The Xcode project
  builds for testing and all 223 iOS tests pass.

### iOS analytics source-session hardening

- Node analytics, observer analytics, and observer-list cache namespaces now include the active
  source-session UUID. Reconnecting or renewing the same server URL cannot briefly display cached
  values from the previous session.
- Late-result checks and request cancellation remain in place; a duplicate node-analytics current-
  request guard was removed while auditing the path.
- D1, E1, and the Phase 3 capability split B5 are complete on iOS. The complete suite remains green
  at 223 of 223 tests.

### iOS analytics UI parity audit

- Beacon neighbor results now keep the same Links card position when the server returns an empty
  collection, with an explicit "No Neighbors Reported" state instead of silently omitting the section.
- A missing neighbor SNR is labeled "Signal unavailable" rather than incorrectly describing link
  direction, which Beacon does not report.
- Node and observer analytics now show explicit no-activity cards for successful empty histories.
  Existing CoreScope/Beacon charts, ordering, card styling, range controls, and exports remain shared.
- The Beacon telemetry summary now renders the latest sample time as an actual localized relative
  date rather than literal placeholder text.
- The iPhone Simulator build succeeds and the complete iOS suite passes: 223 of 223 tests. C2, D2,
  E2, and E3 remain in review until the side-by-side visual acceptance pass is complete.
