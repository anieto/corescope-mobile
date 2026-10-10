# Shared protocol fixtures

Synthetic data derived from the iOS model/call-site shapes at `cd98f8e`. No private
keys, captured messages, or real node identities are included. These are contract
examples, not recordings of a live analyzer. Android JVM tests load this directory
as test resources. Add matching Swift tests as the cross-platform suite grows.

- `nodes-minimal.json`: nullable fields, unknown fields/role, fractional/whole
  timestamp strings and an invalid coordinate.

- `channels.json`, `channel-messages.json`: channel list/message shapes, including
  missing optional fields, an unknown field and a reserved-character identifier.
- `channel-packets.json`: `/api/packets?type=5` rows with an undecrypted `GRP_TXT`,
  an analyzer-decrypted `CHAN`, a bad MAC, a non-channel packet and malformed JSON.
- `channel-crypto.json`: synthetic GRP_TXT known-answer vectors (hashtag, Public and
  private key). The algorithm was checked against live analyzer-decrypted traffic on
  2026-09-23; no captured messages are stored. Swift tests should consume the same file.
- `observers.json`, `observer-analytics.json`: telemetry present, partial and absent;
  analytics with an unknown payload type.

- `packet-detail.json`: `/api/packets/{hash}` with an observation missing name/region/signal,
  a route contained in a longer one (case differs) and an unresolved hop.
- `node-analytics.json`: offset `timeRange` timestamps, a missing peers section, a
  signal sample without RSSI, an unknown payload type and duplicate/out-of-range heatmap cells.
- `node-health.json`, `node-paths.json`, `node-reach.json`: node detail sections with
  missing observer names/regions, a null average, an unresolved path hop and a link
  without a name or distance.

Socket and packet-detail fixtures remain part of the Phase 0 backlog.

## Beacon (`beacon/`)

Unlike the synthetic CoreScope files above, these are **sanitized recordings** of a real Beacon
2.0.3 server (beacon.meshtexas.org, contract `9d1dae3`), made by `tools/beacon-contract/`. Every
key, UUID, packet hash, name, message and coordinate is replaced by a deterministic fake, so cross
references still line up; `check_leaks.py` must report 0 leaks before committing a refresh. Key
prefixes and `pathBytes` share one byte permutation, so route hops still match their nodes' keys
and hash collisions survive. Each file is `{request, status, body}`; `ws-session.json` is a live
socket session (subscribe, configure on/off/on, ping). See `docs/beacon/phase0-findings.md` for what
each shape implies.
