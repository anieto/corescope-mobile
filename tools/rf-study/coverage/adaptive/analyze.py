#!/usr/bin/env python3
"""Archive and validate the adaptive study; exit nonzero on an accuracy failure."""
import argparse
import collections
import csv
import gzip
import hashlib
import json
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('results', type=Path)
p.add_argument('output', type=Path)
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=True)
summary = {'platforms': {}, 'failures': []}
platform_rows = {}
for platform in ['ios', 'android']:
    source = a.results / platform
    raw = (source / 'adaptive.csv').read_bytes()
    rows = list(csv.DictReader(raw.decode().splitlines()))
    platform_rows[platform] = rows
    (a.output / (platform + '.csv.gz')).write_bytes(gzip.compress(raw, mtime=0))
    errors = []
    groups = {}
    for r in rows:
        groups.setdefault(r['set'], collections.Counter())[r['state']] += 1
        assert int(r['samples_used']) <= int(r['budget'])
        assert r['reference_state'] == 'PREDICTED'
        if r['state'] == 'QUALIFIED':
            error = abs(float(r['loss_db']) - float(r['reference_loss_db']))
            errors.append(error)
            if error > .25 or ((float(r['loss_db']) <= float(r['threshold_loss_db'])) != (float(r['reference_loss_db']) <= float(r['threshold_loss_db']))):
                summary['failures'].append({'platform': platform, 'row': r, 'error_db': error})
        else:
            assert r['loss_db'] == ''
    assert len(rows) == 755
    assert groups['limited'] == {'WORK_LIMIT': 1}
    assert groups['threshold'] == {'THRESHOLD_UNRESOLVED': 1}
    cancel = (source / 'cancel.txt').read_text().split(',')
    assert cancel[0] == 'CANCELLED' and 0 <= float(cancel[1]) < 1000
    ordinary = [r for r in rows if r['set'] in ['original', 'holdout', 'refinement']]
    summary['platforms'][platform] = {
        'rows': len(rows), 'states_by_set': groups,
        'max_qualified_error_db': max(errors),
        'p95_qualified_error_db': sorted(errors)[int(.95 * (len(errors)-1))],
        'charged_sample_ratio_to_reference': sum(int(r['samples_used']) for r in ordinary) / sum(int(r['reference_samples']) for r in ordinary),
        'final_spacing_counts': collections.Counter(r['final_spacing_m'] for r in ordinary),
        'cancellation_ms': float(cancel[1]),
        'metrics_including_reference_computation': json.loads((source / 'adaptive-metrics.json').read_text()),
        'csv_sha256': hashlib.sha256(raw).hexdigest(),
        'previous_problem_paths': [r for r in rows if r['set'] == 'original' and r['distance_m'] == '100000.0' and r['bearing_deg'] in ['240.0', '285.0']]
    }
max_delta = 0
for left, right in zip(platform_rows['ios'], platform_rows['android']):
    for key in ['set', 'state', 'samples_used', 'final_spacing_m', 'passes', 'reference_state', 'reference_samples', 'budget']:
        assert left[key] == right[key], (key, left, right)
    for key in ['bearing_deg', 'distance_m', 'loss_db', 'diagnostic_last_loss_db', 'reference_loss_db', 'last_delta_db', 'threshold_loss_db']:
        assert bool(left[key]) == bool(right[key])
        if left[key]:
            delta = abs(float(left[key]) - float(right[key]))
            assert delta <= .01, (key, delta)
            if key.endswith('loss_db'): max_delta = max(max_delta, delta)
summary['max_platform_loss_delta_db'] = max_delta
summary['accuracy_target_db'] = .25
summary['passed'] = not summary['failures']
(a.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
raise SystemExit(0 if summary['passed'] else 1)
