#!/usr/bin/env python3
"""Re-check the Beacon API assumptions NodeScope's design depends on, against a live server.

Usage: check_contract.py [host]   (default beacon.meshtexas.org; ~12 requests)

FAIL  an assumption the adapter relies on no longer holds: fix the adapter or the plan.
NOTE  a known Beacon limitation changed (often good news, e.g. observer summaries gained
      `publicKey`): revisit the matching decision in docs/beacon/phase0-findings.md.
OK    as expected.
Exit status 1 when anything FAILs.
"""
import json
import sys
import urllib.error
import urllib.request

HOST = sys.argv[1] if len(sys.argv) > 1 else "beacon.meshtexas.org"
MIN_SERVER = (2, 0, 3)
results = []


def get(path):
    req = urllib.request.Request(f"https://{HOST}/api/v1/{path}",
                                 headers={"User-Agent": "NodeScope-contract-check/1.0", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read())
        except Exception:
            return e.code, None


def check(kind_if_false, ok, label):
    results.append(("OK" if ok else kind_if_false, label))


def main():
    status, info = get("info")
    version = tuple(int(x) for x in str((info or {}).get("serverVersion", "0.0.0")).split(".")[:3])
    check("FAIL", status == 200 and version >= MIN_SERVER, f"/info serverVersion {info and info.get('serverVersion')} >= 2.0.3")

    _, nodes = get("nodes?limit=50")
    items = (nodes or {}).get("items") or []
    check("FAIL", bool(items), "/nodes returns a page with items")
    node = next((n for n in items if n.get("lat") is not None), items[0] if items else {})
    check("FAIL", "publicKey" in node and "id" in node, "node summary has publicKey and id")
    check("FAIL", all("lastHeard" in i for i in node.get("iatas", [])), "node summary iatas[] carry lastHeard")
    check("NOTE", "lastSeen" not in node, "node summary has no top-level lastSeen (derive from iatas[])")
    check("FAIL", "lat" in node and "lng" in node, "node coordinates are lat/lng")
    check("FAIL", nodes.get("hasMore") is False or nodes.get("nextCursor") is not None, "hasMore pages carry nextCursor")

    _, by_key = get(f"nodes?pubkey={node.get('publicKey', '').upper()}")
    found = [n["id"] for n in (by_key or {}).get("items", [])]
    check("FAIL", found == [node.get("id")], "/nodes?pubkey= finds the node, case-insensitively")

    _, iatas = get("iatas")
    check("FAIL", isinstance(iatas, list) and (not iatas or ("lat" in iatas[0] and "lon" in iatas[0])), "/iatas coordinates are lat/lon")

    _, observers = get("observers?limit=50")
    obs = (observers or {}).get("items") or []
    check("FAIL", bool(obs) and "id" in obs[0], "/observers returns items with id")
    check("NOTE", not any("publicKey" in o for o in obs), "observer summaries omit publicKey (UUID references needed)")
    if obs:
        _, detail = get(f"observers/{obs[0]['id']}")
        key = (detail or {}).get("publicKey")
        check("FAIL", bool(key), "observer detail has publicKey")
        if key:
            s, _ = get(f"observers/{key}")
            check("NOTE", s == 400, f"/observers/{{publicKey}} is rejected (got {s})")
            _, filtered = get(f"observers?publicKey={key}&limit=50")
            ids = [o["id"] for o in (filtered or {}).get("items", [])]
            check("NOTE", ids != [obs[0]["id"]], "/observers ignores a publicKey filter")

    _, channels = get("channels?limit=200")
    chans = (channels or {}).get("items") or []
    check("FAIL", bool(chans) and isinstance(chans[0].get("id"), int) and "channelHash" in chans[0],
          "channels have integer id and channelHash")
    hashes = [c["channelHash"] for c in chans]
    check("NOTE", len(hashes) != len(set(hashes)), "channel hashes collide (never key channels by hash)")

    s, err = get("nodes/00000000-0000-0000-0000-000000000000")
    check("FAIL", s == 404 and (err or {}).get("error", {}).get("code") == "not_found", "unknown node is 404 not_found")
    s, err = get("nodes/not-a-uuid")
    check("FAIL", s == 400 and (err or {}).get("error", {}).get("code") == "bad_request", "malformed node id is 400 bad_request")

    for kind, label in results:
        print(f"{kind:4}  {label}")
    return 1 if any(k == "FAIL" for k, _ in results) else 0


if __name__ == "__main__":
    sys.exit(main())
