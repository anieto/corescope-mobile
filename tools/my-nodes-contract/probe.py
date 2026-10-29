#!/usr/bin/env python3
"""Bounded, anonymous GET-only 0.9.0 contract probe; emits aggregate metadata only."""

import argparse
import datetime
import json
import time
import urllib.error
import urllib.parse
import urllib.request


SOURCES = {
    "analyzer.meshtexas.org": "corescope",
    "beacon.meshtexas.org": "beacon",
    "map.meshcore.coloradomesh.org": "beacon",
    "dev.meshcore.ca": "beacon",
}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Probe:
    def __init__(self, host):
        self.host = host
        self.records = []
        self.stopped = False
        self.opener = urllib.request.build_opener(NoRedirect)

    def get(self, path, label):
        if self.stopped or len(self.records) >= 12:
            return None
        started = time.monotonic()
        record = {"operation": label}
        self.records.append(record)
        request = urllib.request.Request(
            "https://" + self.host + path,
            headers={"Accept": "application/json", "User-Agent": "NodeScope-0.9-contract-probe"},
            method="GET",
        )
        try:
            with self.opener.open(request, timeout=25) as response:
                record["status"] = response.status
                raw = response.read(4 * 1024 * 1024 + 1)
                record["bytes"] = len(raw)
                if len(raw) > 4 * 1024 * 1024:
                    record["error"] = "response-size-limit"
                    return None
                if "json" not in response.headers.get("Content-Type", "").lower():
                    record["error"] = "non-json-response"
                    return None
                value = json.loads(raw)
                record["shape"] = sorted(value) if isinstance(value, dict) else "array"
                return value
        except urllib.error.HTTPError as error:
            record["status"] = error.code
            if error.code == 429:
                self.stopped = True
                record["cooldown"] = True
        except (urllib.error.URLError, TimeoutError, ValueError):
            record["error"] = "network-timeout-or-invalid-json"
        finally:
            record["elapsedMs"] = round((time.monotonic() - started) * 1000)
        return None

    def run(self):
        kind = SOURCES[self.host]
        facts = {}
        if kind == "corescope":
            config = self.get("/api/config/client", "client-capabilities")
            if isinstance(config, dict):
                facts["accountsEnabled"] = config.get("userManagement", {}).get("enabled") is True
            stats = self.get("/api/stats", "version")
            if isinstance(stats, dict):
                facts["version"] = stats.get("version")
            nodes = self.get("/api/nodes?limit=200", "nodes-first-page")
            self.get("/api/observers", "observers")
            bulk = self.get("/api/nodes/bulk-health?limit=200", "bulk-health")
            facts["bulkCount"] = len(bulk) if isinstance(bulk, list) else None
            if isinstance(nodes, dict):
                rows = nodes.get("nodes", [])
                facts.update(nodePageCount=len(rows), nodeTotal=nodes.get("total"))
                if rows:
                    key = urllib.parse.quote(rows[0]["public_key"], safe="")
                    for label in ("analytics-first", "analytics-repeat"):
                        analytics = self.get("/api/nodes/" + key + "/analytics?days=1", label)
                        if isinstance(analytics, dict):
                            facts["analytics"] = {
                                "signalSamples": len(analytics.get("snrTrend", [])),
                                "coverageRows": len(analytics.get("observerCoverage", [])),
                                "contextPresent": analytics.get("context") is not None,
                                "statsFields": sorted(analytics.get("computedStats", {})),
                            }
        else:
            info = self.get("/api/v1/info", "version")
            if isinstance(info, dict):
                facts["versionFields"] = sorted(info)
            nodes = self.get("/api/v1/nodes?limit=200", "nodes-first-page")
            self.get("/api/v1/observers?limit=200", "observers-first-page")
            if isinstance(nodes, dict):
                rows = nodes.get("items", [])
                facts.update(nodePageCount=len(rows), nodeHasMore=nodes.get("hasMore"))
                if rows:
                    identifier = urllib.parse.quote(rows[0]["id"], safe="")
                    cursor = None
                    seen = set()
                    total = 0
                    for index in range(3):
                        query = "?limit=200"
                        if cursor is not None:
                            query += "&cursor=" + urllib.parse.quote(str(cursor), safe="")
                        page = self.get("/api/v1/nodes/" + identifier + "/observations" + query,
                                        "observation-page-" + str(index + 1))
                        if not isinstance(page, dict):
                            break
                        total += len(page.get("items", []))
                        facts["observationsSampled"] = total
                        facts["observationHasMore"] = page.get("hasMore")
                        if not page.get("hasMore"):
                            break
                        cursor = page.get("nextCursor")
                        if cursor is None or str(cursor) in seen:
                            facts["observationPagingUnavailable"] = True
                            break
                        seen.add(str(cursor))
        return {"host": self.host, "backend": kind, "facts": facts, "requests": self.records}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", choices=SOURCES, action="append", required=True)
    args = parser.parse_args()
    report = {
        "capturedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "scope": "anonymous GET sample; first-page node, not a complete dashboard or cache benchmark",
        "sources": [Probe(host).run() for host in dict.fromkeys(args.host)],
    }
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
