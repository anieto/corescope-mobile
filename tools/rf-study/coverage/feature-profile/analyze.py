#!/usr/bin/env python3
"""Compare feature-preserving profiles with dense references and the saved ridge failures."""
import argparse
import collections
import csv
import gzip
import hashlib
import json
import math
from pathlib import Path

p=argparse.ArgumentParser();p.add_argument('results',type=Path);p.add_argument('previous',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
a.output.mkdir(parents=True,exist_ok=True)
summary={'accuracy_target_db':.25,'max_platform_loss_delta_db':0,'platforms':{}}
data={}
for platform in ['ios','android']:
    data[platform]={};sets={}
    old=list(csv.DictReader(gzip.decompress((a.previous/(platform+'-synthetic.csv.gz')).read_bytes()).decode().splitlines()))
    for name,expected in [('synthetic',56),('terrain',897)]:
        raw=(a.results/platform/(name+'.csv')).read_bytes()
        rows=list(csv.DictReader(raw.decode().splitlines()));assert len(rows)==expected
        data[platform][name]=rows;errors=[];failures=[]
        for r in rows:
            assert 0<=int(r['samples'])<=250000 and 0<=int(r['geometry'])<=200000
            if name=='terrain':assert r['reference_state']=='PREDICTED'
            if r['state']=='QUALIFIED':
                value=float(r['loss_db']);reference=float(r['reference_loss_db'])
                assert math.isfinite(value) and math.isfinite(reference)
                error=abs(value-reference);errors.append(error)
                flip=(value<=152)!=(reference<=152)
                if error>.25 or flip:failures.append({'error_db':error,'threshold_flip':flip,'fixture':r})
            else:assert r['loss_db']==''
        sets[name]={'rows':len(rows),'states':dict(collections.Counter(r['state'] for r in rows)),
                    'max_qualified_error_db':max(errors,default=None),'failures':failures,
                    'max_samples':max(int(r['samples']) for r in rows),'max_geometry':max(int(r['geometry']) for r in rows),
                    'csv_sha256':hashlib.sha256(raw).hexdigest()}
        if name=='synthetic':
            repaired=[]
            for before,after in zip(old,rows):
                for key in ['kind','distance_m','half_width_m','offset_m']:assert before[key]==after[key]
                if before['state']=='QUALIFIED' and abs(float(before['loss_db'])-float(before['reference_loss_db']))>.25:
                    repaired.append({'kind':after['kind'],'distance_m':after['distance_m'],'half_width_m':after['half_width_m'],
                                     'offset_m':after['offset_m'],'state':after['state'],
                                     'error_db':abs(float(after['loss_db'])-float(after['reference_loss_db'])) if after['loss_db'] else None})
            assert len(repaired)==20
            sets[name]['previously_false_accepted']=repaired
        else:
            sets[name]['states_by_site']={site:dict(collections.Counter(r['state'] for r in rows if r['site']==site)) for site in sorted({r['site'] for r in rows})}
        (a.output/(platform+'-'+name+'.csv.gz')).write_bytes(gzip.compress(raw,mtime=0))
    summary['platforms'][platform]=sets
for name in ['synthetic','terrain']:
    for left,right in zip(data['ios'][name],data['android'][name]):
        for key in left:
            if key.endswith('loss_db') and left[key]:
                delta=abs(float(left[key])-float(right[key]));assert delta<=.01,(key,left,right)
                summary['max_platform_loss_delta_db']=max(summary['max_platform_loss_delta_db'],delta)
            elif key in ['bearing_deg','distance_m']:
                # Serialized atan2/hypot inputs may differ by one floating-point ULP.
                assert abs(float(left[key])-float(right[key]))<=1e-8,(key,left,right)
            else:assert left[key]==right[key],(key,left,right)
summary['platform_parity_passed']=True
summary['accuracy_gate_passed']=all(not result['failures'] for sets in summary['platforms'].values() for result in sets.values())
(a.output/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps({k:v for k,v in summary.items() if k!='platforms'},indent=2))
for platform,sets in summary['platforms'].items():
    for name,result in sets.items():print(platform,name,json.dumps({k:v for k,v in result.items() if k not in ['failures','csv_sha256','previously_false_accepted']}),'failures',len(result['failures']))
raise SystemExit(0 if summary['accuracy_gate_passed'] else 1)
