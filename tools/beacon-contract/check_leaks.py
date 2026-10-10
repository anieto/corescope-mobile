#!/usr/bin/env python3
"""Fail if any real identity from the raw captures appears in the sanitized fixtures.

Usage: check_leaks.py <raw-capture-dir>/<host> <fixture-dir>

Checks full keys, UUIDs, packet hashes and payload hex (plus inner 12-character fragments
past the 3-byte prefix that make_fixtures.py intentionally maps through a byte permutation),
node/observer/sender names, message text and coordinates. Exit status 1 on any leak.
"""
import glob
import json
import re
import sys

ID_FIELDS = {"publicKey", "observerPublicKey", "originPubkey", "id", "nodeId", "observerId",
             "connectionId", "subscriptionId", "origin_id", "packetHash", "rawPayload", "raw", "ciphertext"}
NAME_FIELDS = {"name", "displayName", "observerName", "origin", "sender", "senderName", "summary", "content", "text"}
COORD_FIELDS = {"lat", "lng", "lon", "latitude", "longitude"}
NOT_IDENTITIES = {"Public"}


def collect(raw_dir):
    ids, names, coords = set(), set(), set()

    def walk(v, f=None):
        if isinstance(v, dict):
            for k, x in v.items():
                walk(x, k)
        elif isinstance(v, list):
            for x in v:
                walk(x, f)
        elif isinstance(v, str):
            if f in ID_FIELDS and len(v) >= 16:
                ids.add(v.lower())
            elif f in NAME_FIELDS and len(v) >= 4 and v not in NOT_IDENTITIES and not re.match(r"^mqtt\d*$", v):
                names.add(v)
        elif isinstance(v, float) and f in COORD_FIELDS:
            coords.add(round(v, 4))

    for path in glob.glob(f"{raw_dir}/*.json") + glob.glob(f"{raw_dir}/ws/*.json"):
        with open(path) as fh:
            walk(json.load(fh))
    return ids, names, coords


def main(raw_dir, fixture_dir):
    ids, names, coords = collect(raw_dir)
    text, numbers = "", set()
    for path in glob.glob(f"{fixture_dir}/*.json"):
        with open(path) as fh:
            t = fh.read()
        text += t + "\n"
        numbers |= {round(float(m), 4) for m in re.findall(r"-?\d+\.\d+", t)}
    lower = text.lower()
    leaks = []
    for value in ids:
        fragments = [value[i:i + 12] for i in range(6, len(value) - 12, 12)]
        if value in lower or any(f in lower for f in fragments):
            leaks.append(("id", value[:16]))
    leaks += [("name", n[:30]) for n in names if re.search(r'"' + re.escape(json.dumps(n)[1:-1]) + r'"', text)]
    leaks += [("coord", c) for c in coords if c in numbers]
    print(f"checked {len(ids)} ids, {len(names)} names/texts, {len(coords)} coordinates: {len(leaks)} leaks")
    for leak in leaks[:20]:
        print("  LEAK", *leak)
    return 1 if leaks else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
