#!/usr/bin/env python3
"""Capture raw Beacon API responses for fixture refreshes and contract analysis.

Usage: BEACON_RAW_DIR=/path/outside/repo capture.py [extra-host ...]

Does a full capture of beacon.meshtexas.org (~55 requests) and a light one (7 requests) of
each extra host. Raw output holds real identities and messages: keep it outside the repo
and run make_fixtures.py + check_leaks.py to produce committable fixtures.
"""
import json, os, sys, time, urllib.request, urllib.error, urllib.parse

OUT = os.environ.get("BEACON_RAW_DIR") or sys.exit("set BEACON_RAW_DIR to a directory outside the repo")
UA = "NodeScope-phase0-probe/1.0 (contract validation)"
count = 0


def get(host, path):
    global count
    count += 1
    url = f"https://{host}/api/v1/{path}"
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            body, status = r.read(), r.status
    except urllib.error.HTTPError as e:
        body, status = e.read(), e.code
    ms = round((time.time() - t0) * 1000)
    try:
        data = json.loads(body)
    except Exception:
        data = {"_nonjson": body[:300].decode("utf-8", "replace")}
    return {"url": url, "status": status, "ms": ms, "bytes": len(body), "data": data}


def save(host, name, res):
    d = os.path.join(OUT, host)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, name + ".json"), "w") as f:
        json.dump(res, f, indent=1)
    items = res["data"].get("items") if isinstance(res["data"], dict) else res["data"]
    n = len(items) if isinstance(items, list) else "-"
    print(f"{host:32} {name:28} {res['status']} {res['ms']:5}ms {res['bytes']:8}B items={n}")
    return res


def walk(host, path, limit, name, max_pages=40):
    """Follow nextCursor; return all items plus per-page cursors."""
    items, cursors, cursor = [], [], None
    for _ in range(max_pages):
        sep = "&" if "?" in path else "?"
        p = f"{path}{sep}limit={limit}" + (f"&cursor={cursor}" if cursor is not None else "")
        res = get(host, p)
        d = res["data"]
        items += d.get("items", [])
        cursors.append({"cursor": cursor, "next": d.get("nextCursor"), "hasMore": d.get("hasMore"), "n": len(d.get("items", []))})
        if not d.get("hasMore") or d.get("nextCursor") is None:
            break
        cursor = d["nextCursor"]
        time.sleep(0.25)
    save(host, name, {"url": path, "status": 200, "ms": 0, "bytes": 0, "data": {"items": items, "pages": cursors}})
    return items


def full(host):
    s = lambda n, p: save(host, n, get(host, p))
    s("info", "info")
    s("iatas", "iatas")
    s("regions", "regions")
    nodes = walk(host, "nodes", 200, "nodes-walk-200")
    walk(host, "nodes", 100, "nodes-walk-100")
    obs = s("observers", "observers?limit=200")["data"]["items"]
    s("observers-directory", "observers/directory?limit=50")
    if obs:
        observer_id = obs[0]["id"]
        s("observer-detail", f"observers/{observer_id}")
        s("observer-activity-24h", f"observers/{observer_id}/activity?range=24h&interval=15m")
        s("observer-activity-7d", f"observers/{observer_id}/activity?range=168h&interval=1h")
        s("observer-telemetry-24h", f"observers/{observer_id}/telemetry?range=24h&interval=1h")
        s("observer-adverts", f"observers/{observer_id}/adverts?limit=50")
    rep = next((n for n in nodes if n.get("nodeType") == 2 and n.get("lat")), nodes[0])
    s("node-by-pubkey", f"nodes?pubkey={rep['publicKey']}")
    s("node-by-pubkey-upper", f"nodes?pubkey={rep['publicKey'].upper()}")
    s("node-detail", f"nodes/{rep['id']}")
    s("node-neighbors", f"nodes/{rep['id']}/neighbors")
    s("node-observations", f"nodes/{rep['id']}/observations?limit=200")
    walk(host, f"nodes/{rep['id']}/observations", 200, "node-observations-walk", max_pages=20)
    pk = s("packets", "packets?limit=50")["data"]["items"]
    s("packets-sat", "packets?limit=50&iatas=SAT")
    walk(host, "packets?iatas=SAT", 25, "packets-walk-25", max_pages=4)
    s("packets-100", "packets?limit=100&iatas=SAT")
    if pk:
        s("packet-detail", f"packets/{pk[0]['packetHash']}")
        grp = next((p for p in pk if p.get("payloadType") == 5), None)
        if grp:
            s("packet-detail-grptxt", f"packets/{grp['packetHash']}")
    ch = s("channels", "channels?limit=200")["data"]
    known_page = s("channels-keyknown", "channels?limit=200&keyKnown=true")["data"]
    tied_page = s("channels-keyknown-page-1", "channels?limit=2&keyKnown=true")["data"]
    if tied_page.get("hasMore") and tied_page.get("nextPageCursor"):
        cursor = urllib.parse.quote(str(tied_page["nextPageCursor"]), safe="")
        s("channels-keyknown-page-2", f"channels?limit=2&keyKnown=true&pageCursor={cursor}")
    items = ch.get("items", []) if isinstance(ch, dict) else ch
    known_items = known_page.get("items", []) if isinstance(known_page, dict) else known_page
    known = known_items or [c for c in items if c.get("keyKnown") or c.get("decryptable")]
    if known:
        s("channel-detail", f"channels/{known[0]['id']}")
        s("channel-messages", f"channels/{known[0]['id']}/messages?limit=20")
    s("messages", "messages?limit=20")
    s("err-node-bad-id", "nodes/not-a-uuid")
    s("err-node-unknown", "nodes/00000000-0000-0000-0000-000000000000")
    s("err-packet-unknown", "packets/0000000000000000")
    s("err-observer-activity-unknown", "observers/00000000-0000-0000-0000-000000000000/activity")
    s("err-channel-unknown", "channels/2147483647")


def light(host):
    s = lambda n, p: save(host, n, get(host, p))
    s("info", "info")
    s("nodes", "nodes?limit=50")
    observers = s("observers", "observers?limit=50")["data"].get("items", [])
    if observers:
        observer_id = observers[0]["id"]
        s("observer-activity", f"observers/{observer_id}/activity?range=24h&interval=1h")
        s("observer-telemetry", f"observers/{observer_id}/telemetry?range=24h&interval=1h")
    s("channels", "channels?limit=50")
    s("packets", "packets?limit=20")


if __name__ == "__main__":
    full("beacon.meshtexas.org")
    for h in sys.argv[1:]:
        light(h)
    print("requests:", count)
