#!/usr/bin/env python3
"""Build sanitized Beacon fixtures from raw captures.

Usage: make_fixtures.py <raw-capture-dir> <out-dir>

<raw-capture-dir> holds `<host>/<name>.json` files written by `capture.py` (each is
{url, status, ms, bytes, data}) plus `<host>/ws/frames.json`. Raw captures contain real
node identities and messages, so they stay outside the repo. Only this script's output
is committed.

Every identity is replaced by a deterministic fake: the same real value always maps to
the same fake value across all files, so cross-references (a node in a list, its detail,
a hop in a route) still line up. Shapes, types, field presence, nulls and value
relationships (ordering, hash collisions, prefix lengths) are preserved.
"""
import hashlib
import json
import os
import re
import sys

UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
HEX_RE = re.compile(r"^[0-9a-fA-F]+$")

KEY_FIELDS = {"publicKey", "observerPublicKey", "originPubkey"}
UUID_FIELDS = {"id", "nodeId", "observerId", "connectionId", "subscriptionId", "origin_id"}
HASH_FIELDS = {"packetHash"}
HEX_FIELDS = {"rawPayload", "raw", "ciphertext", "cipherMac", "signature"}
PATH_FIELDS = {"pathBytes"}
NAME_FIELDS = {"name", "displayName", "observerName", "origin", "sender", "senderName"}
TEXT_FIELDS = {"content", "text", "message"}
SUMMARY_FIELDS = {"summary"}
LAT_FIELDS = {"lat", "latitude"}
LON_FIELDS = {"lng", "lon", "longitude"}
# Public channel names and region labels are not identities; keep them readable.
KEEP_NAMES = {"Public"}

# One fixed byte permutation for key prefixes and path bytes. Route hops and path bytes refer
# to nodes by the first 1-3 bytes of their public key, so those bytes must map consistently
# (and collisions must survive) for route fixtures to stay meaningful. Bytes past the third
# come from a digest instead, so a fake key reveals no more than a 3-byte hop hash already does.
_rng = list(range(256))
_seed = hashlib.sha256(b"nodescope-fixture:byte-permutation").digest()
for _i in range(255, 0, -1):
    _j = int.from_bytes(hashlib.sha256(_seed + bytes([_i])).digest()[:4], "big") % (_i + 1)
    _rng[_i], _rng[_j] = _rng[_j], _rng[_i]
BYTE_MAP = _rng
PREFIX_BYTES = 3


def map_bytes(hex_value):
    out = "".join(f"{BYTE_MAP[int(hex_value[i:i + 2], 16)]:02x}" for i in range(0, len(hex_value) - len(hex_value) % 2, 2))
    return out.upper() if hex_value.isupper() else out


# Shift real coordinates to a fixed synthetic area (keeps relative geometry, hides places).
LAT_SHIFT, LON_SHIFT = 10.0, 20.0


def digest(kind, value, n):
    return hashlib.sha256(f"nodescope-fixture:{kind}:{value}".encode()).hexdigest()[:n]


class Sanitizer:
    def __init__(self):
        self.names = {}

    def fake_hex(self, kind, value):
        out = digest(kind, value.lower(), len(value))
        return out.upper() if value.isupper() else out

    def fake_uuid(self, value):
        h = digest("uuid", value, 32)
        return f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}"

    def fake_name(self, field, value):
        if re.match(r"^mqtt\d*$", value):  # Beacon's broker labels; must match sourceBroker
            return value
        if value in KEEP_NAMES or value.startswith("#"):
            return value if value in KEEP_NAMES else self.names.setdefault(("#", value), f"#channel-{len(self.names) + 1}")
        prefix = "Observer" if field in {"displayName", "observerName", "origin"} else "Node"
        if field in {"sender", "senderName"}:
            prefix = "Sender"
        return self.names.setdefault((prefix, value), f"{prefix} {len(self.names) + 1:03d}")

    def key(self, value):
        # Full keys and key prefixes both map through the byte permutation for their first
        # PREFIX_BYTES bytes, so a prefix's fake is always a prefix of its full key's fake.
        if len(value) % 2:
            return self.fake_hex("key", value)
        head = map_bytes(value[:PREFIX_BYTES * 2])
        tail = value[PREFIX_BYTES * 2:]
        if tail:
            tail = digest("key", value.lower(), len(tail))
            tail = tail.upper() if value.isupper() else tail
        return head + tail

    def scalar(self, field, value):
        if field == "request" and isinstance(value, str):
            value = re.sub(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", lambda m: self.fake_uuid(m.group()), value)
            value = re.sub(r"\b[0-9a-fA-F]{64}\b", lambda m: self.key(m.group()), value)
            return re.sub(r"\b[0-9a-fA-F]{16}\b", lambda m: self.fake_hex("packet", m.group()), value)
        if not isinstance(value, str):
            if isinstance(value, float) and field in LAT_FIELDS:
                return round(value + LAT_SHIFT, 5)
            if isinstance(value, float) and field in LON_FIELDS:
                return round(value + LON_SHIFT, 5)
            return value
        if field in KEY_FIELDS and HEX_RE.match(value):
            return self.key(value)
        if field in UUID_FIELDS and UUID_RE.match(value):
            return self.fake_uuid(value)
        if field in UUID_FIELDS and HEX_RE.match(value) and len(value) >= 16:
            return self.key(value)
        if field in HASH_FIELDS and HEX_RE.match(value):
            return self.fake_hex("packet", value)
        if field in PATH_FIELDS and HEX_RE.match(value):
            return map_bytes(value)
        if field in HEX_FIELDS and HEX_RE.match(value):
            return self.fake_hex(field, value)
        if field in NAME_FIELDS:
            return self.fake_name(field, value)
        if field in TEXT_FIELDS:
            return f"Synthetic message {digest('text', value, 6)}"
        if field in SUMMARY_FIELDS:
            parts = value.split(" ", 1)
            if parts[0] in {"ACK", "TRACE", "PING"} and len(parts) == 2:
                return f"{parts[0]} {self.fake_hex('ref', parts[1])}"
            return self.fake_name("name", value)
        return value

    def walk(self, value, field=None):
        if isinstance(value, dict):
            return {k: self.walk(v, k) for k, v in value.items()}
        if isinstance(value, list):
            return [self.walk(v, field) for v in value]
        return self.scalar(field, value)


def trim(data, n):
    if isinstance(data, dict) and isinstance(data.get("items"), list):
        return {**data, "items": data["items"][:n]}
    if isinstance(data, list):
        return data[:n]
    return data


def load(raw, host, name):
    with open(os.path.join(raw, host, name + ".json")) as f:
        return json.load(f)


def main(raw, out):
    s = Sanitizer()
    os.makedirs(out, exist_ok=True)
    ours = "beacon.meshtexas.org"
    written = []

    def emit(fname, payload):
        with open(os.path.join(out, fname), "w") as f:
            json.dump(s.walk(payload), f, indent=2, ensure_ascii=False)
            f.write("\n")
        written.append(fname)

    def rest(fname, name, n=None, host=ours, extra=None):
        r = load(raw, host, name)
        body = trim(r["data"], n) if n else r["data"]
        doc = {"request": r["url"].split("/api/v1/", 1)[-1], "status": r["status"], "body": body}
        if extra:
            doc.update(extra)
        emit(fname, doc)

    rest("info.json", "info")
    rest("iatas.json", "iatas")
    rest("regions.json", "regions")
    rest("nodes-page.json", "nodes-walk-100", n=6)
    rest("node-by-pubkey.json", "node-by-pubkey")
    rest("node-detail.json", "node-detail")
    rest("node-neighbors.json", "node-neighbors")
    rest("node-observations.json", "node-observations", n=8)
    rest("observers-page.json", "observers", n=5)
    rest("observer-detail.json", "observer-detail")
    rest("packets-page.json", "packets-sat", n=6)
    det = load(raw, ours, "packet-detail")
    det["data"] = {**det["data"], "observations": det["data"]["observations"][:3]}
    emit("packet-detail.json", {"request": "packets/{hash}", "status": 200, "body": det["data"],
                                "note": "observations trimmed to 3 of " + str(len(load(raw, ours, 'packet-detail')['data']['observations']))})
    rest("packet-detail-group-text.json", "packet-detail-grptxt")

    # Channels: keep every channel sharing a hash with a known one (collisions), plus a few others.
    ch = load(raw, ours, "channels")
    items = ch["data"]["items"]
    known_hashes = {c["channelHash"] for c in items if c["keyKnown"]}
    keep = [c for c in items if c["channelHash"] in known_hashes] + [c for c in items if c["channelHash"] not in known_hashes][:3]
    emit("channels-page.json", {"request": "channels?limit=200", "status": 200,
                                "body": {**ch["data"], "items": keep},
                                "note": "subset: every channel sharing a hash with a decryptable one, plus 3 others"})
    rest("channel-detail.json", "channel-detail")
    rest("channel-messages.json", "channel-messages", n=4)
    for name in ("err-node-bad-id", "err-node-unknown", "err-packet-unknown"):
        rest(name + ".json", name)

    # WebSocket: all control frames plus a few events of each kind, from the SAT-filtered session.
    with open(os.path.join(raw, ours, "ws", "frames.json")) as f:
        frames = json.load(f)
    seen, picked = {}, []
    for fr in frames:
        msg = fr["msg"]
        kind = msg.get("event") or msg["type"]
        if fr["dir"] == "out" or msg["type"] != "event" or seen.get(kind, 0) < 3:
            seen[kind] = seen.get(kind, 0) + 1
            picked.append({"t": fr["t"], "dir": fr["dir"], "msg": msg})
    emit("ws-session.json", {"note": "SAT-filtered session: subscribe, configure (resolvePath on/off/on), ping; "
                                     "control frames complete, events sampled", "frames": picked})

    print("\n".join(written))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
