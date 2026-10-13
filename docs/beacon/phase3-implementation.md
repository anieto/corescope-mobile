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
| Channel messages, initial view | 1 | 50 messages | 1 minute |
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

The project builds successfully and the complete iOS suite passes with 210 tests. C1 remains in
progress until the node-detail cache and source-switch UI loading path are integrated.
