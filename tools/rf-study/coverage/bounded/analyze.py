#!/usr/bin/env python3
"""Validate and archive bounded-job outcomes, cache invariance and historical parity."""
import argparse
import collections
import csv
import gzip
import hashlib
import io
import json
import math
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('results', type=Path)
p.add_argument('adaptive_evidence', type=Path)
p.add_argument('output', type=Path)
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=True)
names = ['r20_small', 'r20_large', 'refine_small', 'refine_large', 'r100', 'limited']
data = {}
summary = {'platforms': {}, 'max_platform_loss_delta_db': 0, 'max_historical_loss_delta_db': 0}

def compare(left, right, exact=False):
    assert len(left) == len(right)
    maximum = 0
    for x, y in zip(left, right):
        for key in ['east_m', 'north_m', 'state', 'samples']:
            assert x[key] == y[key], (key, x, y)
        assert bool(x['loss_db']) == bool(y['loss_db'])
        if x['loss_db']:
            delta = abs(float(x['loss_db'])-float(y['loss_db']))
            assert delta <= (0 if exact else .01), (x, y)
            maximum = max(maximum, delta)
    return maximum

for platform in ['ios', 'android']:
    folder = a.results/platform
    metrics = json.loads((folder/'metrics.json').read_text())
    data[platform] = {}
    counts = {}
    hashes = {}
    for run in metrics['runs']:
        name = run['name']; r = run['result']
        assert name in names
        raw = (folder/(name+'.csv')).read_bytes()
        rows = list(csv.DictReader(io.StringIO(raw.decode())))
        data[platform][name] = rows
        assert len(rows) == r['cells']
        assert sum(int(row['samples']) for row in rows) == r['samples'] <= r['budget']
        assert r['peak_payload_bytes'] <= r['capacity_bytes']
        assert r['failed_loads'] == 0
        assert r['minimum_work'] <= r['samples'] <= r['maximum_work'] if name != 'limited' else r['samples'] <= r['budget']
        counts[name] = dict(collections.Counter(row['state'] for row in rows))
        for row in rows:
            if row['state'] == 'QUALIFIED':
                assert math.isfinite(float(row['loss_db']))
            else:
                assert not row['loss_db']
        if name == 'limited':
            assert r['state'] == 'WORK_LIMIT'
            assert counts[name].get('NOT_COMPUTED', 0) > 0
            assert counts[name].get('WORK_LIMIT', 0) == 1
            assert r['completed'] < r['cells']
        else:
            assert r['state'] == 'COMPLETE' and r['completed'] == r['cells']
            assert 'NOT_COMPUTED' not in counts[name]
        if name.endswith('small'):
            assert r['evictions'] > 0
        (a.output/(platform+'-'+name+'.csv.gz')).write_bytes(gzip.compress(raw, mtime=0))
        hashes[name] = hashlib.sha256(raw).hexdigest()
    for prefix in ['r20', 'refine']:
        compare(data[platform][prefix+'_small'], data[platform][prefix+'_large'], exact=True)
    # Compare against the separately executed historical full-raster adaptive experiment.
    historical = list(csv.DictReader(io.StringIO(gzip.decompress((a.adaptive_evidence/(platform+'.csv.gz')).read_bytes()).decode())))
    lookup = {}
    for row in historical:
        if row['set'] != 'refinement': continue
        angle = math.radians(float(row['bearing_deg'])); distance = float(row['distance_m'])
        lookup[(round(distance*math.sin(angle)), round(distance*math.cos(angle)))] = row
    assert len(lookup) == 441
    for row in data[platform]['refine_small']:
        old = lookup[(int(row['east_m']), int(row['north_m']))]
        assert row['state'] == old['state'] and row['samples'] == old['samples_used']
        if row['loss_db']:
            delta = abs(float(row['loss_db'])-float(old['loss_db']))
            assert delta <= .01
            summary['max_historical_loss_delta_db'] = max(summary['max_historical_loss_delta_db'], delta)
    assert metrics['cancel_state'] == 'CANCELLED' and metrics['cache_released']
    assert 0 <= metrics['cancel_latency_ms'] < 1000
    cancelled_raw = (folder/'cancel.csv').read_bytes()
    cancelled = list(csv.DictReader(io.StringIO(cancelled_raw.decode())))
    assert any(row['state'] == 'NOT_COMPUTED' for row in cancelled)
    # Reserved work for a cancelled in-flight path may exceed published cell work.
    assert sum(int(row['samples']) for row in cancelled) <= metrics['cancel_samples'] <= 15000000
    (a.output/(platform+'-cancel.csv.gz')).write_bytes(gzip.compress(cancelled_raw, mtime=0))
    (a.output/(platform+'-metrics.json')).write_text(json.dumps(metrics, indent=2)+'\n')
    summary['platforms'][platform] = {'metrics': metrics, 'states': counts, 'csv_sha256': hashes, 'historical_cells_compared': 441}

for name in names:
    summary['max_platform_loss_delta_db'] = max(summary['max_platform_loss_delta_db'], compare(data['ios'][name], data['android'][name]))
    left = next(r['result'] for r in summary['platforms']['ios']['metrics']['runs'] if r['name'] == name)
    right = next(r['result'] for r in summary['platforms']['android']['metrics']['runs'] if r['name'] == name)
    assert left == right, (name, left, right)
summary['passed'] = True
(a.output/'summary.json').write_text(json.dumps(summary, indent=2)+'\n')
print(json.dumps({k: v for k, v in summary.items() if k != 'platforms'}, indent=2))
for platform, result in summary['platforms'].items():
    print(platform, 'RSS', result['metrics']['process_peak_rss_bytes'], 'cancellation ms', result['metrics']['cancel_latency_ms'])
    for run in result['metrics']['runs']:
        print(run['name'], round(run['elapsed_ms'], 2), 'ms', result['states'][run['name']])
