# Beacon phase 0 findings

Status: phase 0 complete 2026-10-10. Companion to [plan.md](plan.md); this file records what was
measured and the rules phases 1–2 must follow. Where this file and the plan disagree, this file wins.

## Pinned versions

| | Value |
|---|---|
| Minimum supported server | **2.0.3** (beacon.meshtexas.org and Colorado run 2.0.3; dev.meshcore.ca runs 2.0.5) |
| Contract revision | beacon-docs [`9d1dae3`](https://github.com/MeshCore-Beacon/beacon-docs/blob/9d1dae3bf989/docs/api-contract.md) (released with server 2.0.3) |
| Newer contract checked | [`182010d`](https://github.com/MeshCore-Beacon/beacon-docs/blob/182010dbc64d/docs/api-contract.md) (2.0.4/2.0.5): only `/routes/search` and `/routes/cross` parameters and `/info.minWebVersion` changed. Neither is used by the browse release. |
| Fixtures | `shared/fixtures/beacon/`, recorded from beacon.meshtexas.org 2.0.3, sanitized |
| Live re-check | `tools/beacon-contract/check_contract.py [host]`: 18 checks, passed on ours and Colorado |

## Identity rules

| Thing | Identity in NodeScope | Evidence |
|---|---|---|
| Node | Full public key, compared lowercase | Every node summary has `publicKey`; `/nodes?pubkey=` finds it in either case (ours, Colorado) |
| Packet | Packet hash | Same hash on Beacon and CoreScope for the same packet (5/5 checked) |
| Observer (Beacon) | **Source + Beacon UUID** | No efficient key lookup; see below |
| Channel (Beacon) | **Source + Beacon integer `id`** | Hash collisions on all three servers; see below |

**Observers.** On 2.0.3, summaries (`/observers`, `/observers/directory`) omit `publicKey`;
`/observers/{publicKey}` is a 400; `?publicKey=` and `?pubkey=` are ignored; observers that never
advert aren't nodes. The detail endpoint and live events with `includeObserverKey` do carry the key.
Rules:
- Store Beacon observer references as `(source, uuid)`. Learn `uuid ↔ publicKey` opportunistically
  from detail screens and live events, never by fetching every observer's detail.
- A key-based observer link (favorite, recent, deep link from CoreScope) opened on a Beacon source:
  use a learned mapping if one exists, otherwise show "not available on this server" with a way back.
  Never fan out.
- If Beacon adds `publicKey` to summaries or a key filter (`check_contract.py` reports a NOTE),
  switch Beacon observers to key identity and migrate stored `(source, uuid)` references.

**Channels.** `channelHash` is one byte. On every server, the decryptable `Public` channel (hash
`11`) shares its hash with an unrelated undecryptable channel; dev.meshcore.ca has 6 shared hashes in
50 channels. Only `id` is unique, and only within one server's database. Rules (for phase 3, defined
now):
- Store `(source, id)` plus the name and `isHashtag`.
- On a 404 for a stored id (server database reset), re-match by exact name only for hashtag channels
  and `Public`, where the name defines the key. Otherwise drop the reference and say so.
- Never match or merge by hash alone, and never across sources.

## Paging

- **Cursors are opaque.** The nodes cursor is an internal last-seen time that appears in no field;
  the packets cursor with a region filter is the time heard at those regions. Keep cursors inside the
  adapter and never derive or re-sort.
- **Consistency verified:** 878 nodes walked in pages of 100 and of 200 gave identical sets with no
  duplicates. Four 25-packet pages matched one 100-packet page exactly.
- **Server order is authoritative.** Region-filtered packet lists are not sorted by any visible field.
- `limit` is clamped to 200. Channels also return an opaque `nextPageCursor`; prefer it where offered.
- Node summaries have **no top-level `lastSeen`**: "last heard" is the newest `iatas[].lastHeard`
  (node detail has `lastSeen`).
- `/regions` lists `{id, slug, name}` only; members need `/regions/{id}` each (5 on ours). Cache them.

## Shapes worth remembering

- Three coordinate spellings: nodes `lat`/`lng`; `/iatas` `lat`/`lon`; route hops `latitude`/`longitude`.
- Times are epoch ms everywhere; durations are seconds (`clockDriftSeconds`) or ms (`propagationTimeMs`).
- Errors: `{"error": {"code", "message"}}`; malformed id → 400 `bad_request`, unknown → 404 `not_found`.
- Packet summaries carry `summary` only for adverts, ACK, TRACE and PING (36 of 100 sampled).
- Group-text packet detail includes `parsedPayload.cipherMac` and `ciphertext` (future on-phone decryption).
- `minAppVersion` is BEACON Mobile's floor: dev.meshcore.ca sets `"2.0.0"`. Comparing NodeScope's own
  version (0.8.0) against it would lock NodeScope out. **Never read it.**

## Live feed (`/ws`)

Measured on beacon.meshtexas.org:

| | SAT only | Statewide |
|---|---|---|
| `packetObservation` events/min | 14.7 | 62 |
| Avg bytes without `resolvePath` | ~700 | ~900 |
| Avg bytes with `resolvePath` | 1.5–3.3 KB (max 3.9 KB) | 0.6–1.2 KB |

- `hello` arrives immediately; `subscribe` → `subscribed`, `ping` → `pong`, `configure` → `configured`
  echoing all three flags. **`configure` replaces every flag** (omitting `resolvePath` turned it off):
  one owner per connection must merge all screens' needs and send the full set.
- Flag changes are not instantaneous: events produced before the change still arrive in the old
  shape. Accept either shape at any time.
- **`isFirstObservation` is unreliable for the app.** It means first heard anywhere, so with a region
  filter only 2 of 5 packets ever arrived with it. Group rows by `packetHash`; an observation's key is
  `(packetHash, observerId)`. No duplicate pairs were seen without `includeRepeats`.
- Live events carry no observation ID, so there's no REST resume point. **Recovery rule:** after a
  reconnect or any `lagged` notice, refetch the first page of `/packets` with the same filters and
  merge by `packetHash`: keep the server's order, take the larger `observationCount` and later
  `lastHeardAt`. Mark the feed as possibly incomplete if the gap outlasted that page.
- Reconnect with backoff 1, 2, 5, 10, 30 s plus jitter; at most 10 connects/min and 5 sockets per IP.
  Close on background; reopen, resubscribe, reconfigure and refresh on foreground.

## Budgets (initial, from measurements)

| Operation | Requests | Bytes | Server time |
|---|---|---|---|
| Full node list, 878 nodes | 5 | 300 KB | ~0.5 s total |
| Packet list page (50, region) | 1 | ~43 KB | ~90 ms |
| Packet detail (20 observations, `resolvePath`) | 1 | ~50 KB (~2.5 KB per observation) | ~110 ms |
| Node detail + neighbors + observations | 3 | ~10 KB | ~270 ms |
| App launch on a Beacon source (info, iatas, regions + members, first node page) | ≤ 9 | ≤ 100 KB | |

Screen-open ceiling stays at about 20 requests. Packet detail is fetched only on demand, never per row.

## `sources-v2.json`

Draft in [sources-v2.draft.json](sources-v2.draft.json); it goes to `CommunitySources/` at the 0.8.0
release, not before. Rules:
- Same fields as `us-sources.json`, with the existing top-level `"version"` (unread by both apps
  today) set to `2`, plus `"type"` per source.
- `type` is `"corescope"` or `"beacon"`. Missing `type` means `"corescope"`. **An unknown `type` skips
  that one entry**, never the whole list.
- New apps read `sources-v2.json` (live, then cached, then bundled); `us-sources.json` stays
  CoreScope-only for old apps, forever.
- Colorado's icon was deleted from `CommunitySources/icons/` in `6f2aca4`; restore it from history at
  release time.

## Tools

`tools/beacon-contract/`: `capture.py` (raw capture to `$BEACON_RAW_DIR`, outside the repo),
`ws_probe.py` (live feed probe), `make_fixtures.py` (sanitize), `check_leaks.py` (must report 0 leaks
before committing fixtures), `check_contract.py` (live assumption check).
