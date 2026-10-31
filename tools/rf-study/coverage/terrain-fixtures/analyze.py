#!/usr/bin/env python3
"""Record scientific failures as evidence and return nonzero when accuracy fails."""
import argparse
import collections
import csv
import gzip
import hashlib
import json
import math
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('results',type=Path)
p.add_argument('output',type=Path)
a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
summary={'accuracy_target_db':.25,'platform_tolerance_db':.01,'platforms':{},'max_platform_loss_delta_db':0}
all_rows={}
for platform in ['ios','android']:
    sets={};all_rows[platform]={}
    for name,expected in [('synthetic',56),('terrain',144)]:
        raw=(a.results/platform/(name+'.csv')).read_bytes()
        rows=list(csv.DictReader(raw.decode().splitlines()));assert len(rows)==expected
        all_rows[platform][name]=rows
        errors=[];failures=[];threshold_flips=0;reference_delta=0
        for r in rows:
            if name=='terrain':assert r['reference_state']=='PREDICTED'
            reference=float(r['reference_loss_db']);assert math.isfinite(reference)
            if name=='synthetic':reference_delta=max(reference_delta,abs(reference-float(r['finer_reference_loss_db'])))
            if r['state']=='QUALIFIED':
                value=float(r['loss_db']);assert math.isfinite(value)
                delta=abs(value-reference);errors.append(delta)
                flip=(value<=152)!=(reference<=152)
                threshold_flips+=int(flip)
                if delta>.25 or flip:failures.append({'error_db':delta,'threshold_flip':flip,'fixture':r})
            else:assert not r['loss_db']
        result={'rows':len(rows),'states':dict(collections.Counter(r['state'] for r in rows)),
                'maximum_qualified_error_db':max(errors),'accuracy_failures':len(failures),
                'threshold_flips':threshold_flips,'failures':failures,
                'reference_refinement_max_delta_db':reference_delta if name=='synthetic' else None,
                'csv_sha256':hashlib.sha256(raw).hexdigest()}
        if name=='terrain':
            result['states_by_site']={site:dict(collections.Counter(r['state'] for r in rows if r['site']==site)) for site in sorted({r['site'] for r in rows})}
        sets[name]=result
        (a.output/(platform+'-'+name+'.csv.gz')).write_bytes(gzip.compress(raw,mtime=0))
    summary['platforms'][platform]=sets
for name in ['synthetic','terrain']:
    for left,right in zip(all_rows['ios'][name],all_rows['android'][name]):
        for key in left:
            if key.endswith('loss_db') and left[key]:
                delta=abs(float(left[key])-float(right[key]));assert delta<=.01,(key,left,right)
                summary['max_platform_loss_delta_db']=max(summary['max_platform_loss_delta_db'],delta)
            else:assert left[key]==right[key],(key,left,right)
summary['platform_parity_passed']=True
summary['accuracy_gate_passed']=all(result['accuracy_failures']==0 for sets in summary['platforms'].values() for result in sets.values())
summary['synthetic_reference_check_passed']=all(sets['synthetic']['reference_refinement_max_delta_db']<=.25 for sets in summary['platforms'].values())
(a.output/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps({k:v for k,v in summary.items() if k!='platforms'},indent=2))
for platform,sets in summary['platforms'].items():
    for name,result in sets.items():print(platform,name,json.dumps({k:v for k,v in result.items() if k not in ['failures','csv_sha256']}))
raise SystemExit(0 if summary['accuracy_gate_passed'] and summary['synthetic_reference_check_passed'] else 1)
