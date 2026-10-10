#!/usr/bin/env python3
"""Probe Beacon /ws: handshake, subscribe, configure, ping, event rate/size/duplicates.

Usage: BEACON_RAW_DIR=/path/outside/repo ws_probe.py [host] [seconds] [--all]
Needs the `websockets` package (pip install websockets). --all subscribes to every region
instead of SAT. Frames are saved under $BEACON_RAW_DIR/<host>/ws (or ws-all).
"""
import asyncio, json, os, sys, time, collections
import websockets

ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
ALL = "--all" in sys.argv
HOST = ARGS[0] if ARGS else "beacon.meshtexas.org"
SECS = int(ARGS[1]) if len(ARGS) > 1 else 90
RAW = os.environ.get("BEACON_RAW_DIR") or sys.exit("set BEACON_RAW_DIR to a directory outside the repo")
OUT = os.path.join(RAW, HOST, "ws-all" if ALL else "ws")
SCOPE = {} if ALL else {"iatas": ["SAT"]}
os.makedirs(OUT, exist_ok=True)


async def main():
    frames, t0 = [], time.time()
    async with websockets.connect(f"wss://{HOST}/ws", user_agent_header="NodeScope-phase0-probe/1.0",
                                  ping_interval=None, max_size=2**22) as ws:
        async def send(m):
            await ws.send(json.dumps(m)); frames.append({"t": round(time.time() - t0, 3), "dir": "out", "msg": m})

        async def reader():
            async for raw in ws:
                frames.append({"t": round(time.time() - t0, 3), "dir": "in", "bytes": len(raw), "msg": json.loads(raw)})

        rt = asyncio.create_task(reader())
        await asyncio.sleep(1)
        await send({"v": 1, "type": "subscribe", "id": "sub-sat", "scope": {**SCOPE,
                   "events": ["packetObservation", "nodeUpdate", "observerStatus", "channelMessage"]}})
        await send({"v": 1, "type": "configure", "id": "cfg-1", "resolvePath": True, "includeObserverKey": True, "includeRepeats": False})
        # Second configure with resolvePath off, then on again: proves configure replaces every flag.
        await asyncio.sleep(SECS / 3)
        await send({"v": 1, "type": "configure", "id": "cfg-2", "includeObserverKey": True})
        await send({"v": 1, "type": "ping", "id": "p-1"})
        await asyncio.sleep(SECS / 3)
        await send({"v": 1, "type": "configure", "id": "cfg-3", "resolvePath": True, "includeObserverKey": True})
        await send({"v": 1, "type": "ping", "id": "p-2"})
        await asyncio.sleep(SECS / 3)
        rt.cancel()
    with open(os.path.join(OUT, "frames.json"), "w") as f:
        json.dump(frames, f, indent=1)

    ins = [f for f in frames if f["dir"] == "in"]
    kinds = collections.Counter(f["msg"].get("event") or f["msg"]["type"] for f in ins)
    print("frames in:", len(ins), dict(kinds))
    print("control replies:", [(f["t"], f["msg"]) for f in ins if f["msg"]["type"] != "event"][:8])
    po = [f for f in ins if f["msg"].get("event") == "packetObservation"]
    if po:
        size = [f["bytes"] for f in po]
        print("packetObservation/min: %.1f  bytes avg %d max %d" % (len(po) / (SECS / 60), sum(size) / len(size), max(size)))
        firsts = sum(1 for f in po if f["msg"]["data"]["packet"].get("isFirstObservation"))
        hashes = collections.Counter(f["msg"]["data"]["packetHash"] for f in po)
        print("isFirstObservation:", firsts, "of", len(po), "| unique packets:", len(hashes), "| max obs per packet:", max(hashes.values()))
        keyed = collections.Counter((f["msg"]["data"]["packetHash"], f["msg"]["data"]["observation"].get("observerId")) for f in po)
        print("duplicate (packet, observer) pairs:", sum(v - 1 for v in keyed.values() if v > 1))
        for label, lo, hi in (("rp on #1", 0, SECS / 3), ("rp off", SECS / 3, 2 * SECS / 3), ("rp on #2", 2 * SECS / 3, SECS + 5)):
            seg = [f for f in po if lo <= f["t"] < hi]
            withrp = sum(1 for f in seg if f["msg"]["data"]["observation"].get("resolvedPath") is not None)
            avg = sum(f["bytes"] for f in seg) / len(seg) if seg else 0
            print(f"  {label}: {len(seg)} events, {withrp} with resolvedPath, avg {avg:.0f} B")
        print("observation keys:", sorted(po[0]["msg"]["data"]["observation"].keys()))
        print("packet keys:", sorted(po[0]["msg"]["data"]["packet"].keys()))
        iatas = collections.Counter(f["msg"]["data"]["observation"].get("iata") for f in po)
        print("iatas in events:", dict(iatas))


asyncio.run(main())
