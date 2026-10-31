#!/usr/bin/env python3
import argparse,collections,csv,gzip,hashlib,json,math
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('results',type=Path);p.add_argument('previous',type=Path);p.add_argument('output',type=Path);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
summary={'platforms':{},'max_platform_loss_delta_db':0,'max_previous_loss_delta_db':0};data={}
def compare(left,right,exact=False):
    assert len(left)==len(right);maximum=0
    for x,y in zip(left,right):
        for key in ['east_m','north_m','state','samples','geometry']:assert x[key]==y[key],(key,x,y)
        assert bool(x['loss_db'])==bool(y['loss_db'])
        if x['loss_db']:
            delta=abs(float(x['loss_db'])-float(y['loss_db']));assert delta<=(0 if exact else .01),(x,y);maximum=max(maximum,delta)
    return maximum
for platform in ['ios','android']:
    folder=a.results/platform;metrics=json.loads((folder/'metrics.json').read_text());data[platform]={};states={};hashes={}
    assert len(metrics['runs'])==8
    for run in metrics['runs']:
        name=run['name'];r=run['result'];raw=(folder/(name+'.csv')).read_bytes();rows=list(csv.DictReader(raw.decode().splitlines()));data[platform][name]=rows
        assert len(rows)==r['cells']
        assert sum(int(x['samples']) for x in rows)==r['samples']<=r['sample_budget']
        assert sum(int(x['geometry']) for x in rows)==r['geometry']<=r['geometry_budget']
        assert r['peak_payload_bytes']<=r['capacity_bytes'] and r['failed_loads']==0 and r['stale_publication_rejected']
        states[name]=dict(collections.Counter(x['state'] for x in rows))
        for row in rows:
            if row['state']=='QUALIFIED':assert math.isfinite(float(row['loss_db']))
            else:assert not row['loss_db']
        if name in ['sample_limited','geometry_limited']:
            assert r['state']=='WORK_LIMIT' and r['completed']<r['cells']
            assert states[name].get('NOT_COMPUTED',0)>0 and states[name].get('WORK_LIMIT',0)==1
        else:
            assert r['state']=='COMPLETE' and r['completed']==r['cells']
            assert 'NOT_COMPUTED' not in states[name]
        if name=='refine200_small':assert r['evictions']>0
        (a.output/(platform+'-'+name+'.csv.gz')).write_bytes(gzip.compress(raw,mtime=0));hashes[name]=hashlib.sha256(raw).hexdigest()
    compare(data[platform]['refine200_small'],data[platform]['refine200_large'],exact=True)
    reference={(r['east_m'],r['north_m']):r for r in data[platform]['reference100']}
    for name in ['refine100','refine200_large']:
        compare(data[platform][name],[reference[(r['east_m'],r['north_m'])] for r in data[platform][name]],exact=True)
    # Independent single-path feature study, before this scheduler existed.
    previous=list(csv.DictReader(gzip.decompress((a.previous/(platform+'-terrain.csv.gz')).read_bytes()).decode().splitlines()))
    lookup={}
    for r in previous:
        if r['site']!='3':continue
        angle=math.radians(float(r['bearing_deg']));d=float(r['distance_m'])
        lookup[(round(d*math.sin(angle)),round(d*math.cos(angle)))]=r
    for row in data[platform]['refine200_large']:
        old=lookup[(int(row['east_m']),int(row['north_m']))]
        for key in ['state','samples','geometry']:assert row[key]==old[key],(key,row,old)
        if row['loss_db']:
            delta=abs(float(row['loss_db'])-float(old['loss_db']));assert delta<=.01
            summary['max_previous_loss_delta_db']=max(summary['max_previous_loss_delta_db'],delta)
    assert metrics['cancel_state']=='CANCELLED' and metrics['cache_released'] and 0<=metrics['cancel_latency_ms']<1000
    cancelled_raw=(folder/'cancel.csv').read_bytes();cancelled=list(csv.DictReader(cancelled_raw.decode().splitlines()))
    assert any(r['state']=='NOT_COMPUTED' for r in cancelled)
    for key,metric in [('samples','cancel_samples'),('geometry','cancel_geometry')]:
        assert sum(int(r[key]) for r in cancelled)<=metrics[metric]<=150000000
    (a.output/(platform+'-cancel.csv.gz')).write_bytes(gzip.compress(cancelled_raw,mtime=0))
    (a.output/(platform+'-metrics.json')).write_text(json.dumps(metrics,indent=2)+'\n')
    summary['platforms'][platform]={'metrics':metrics,'states':states,'csv_sha256':hashes}
for name in data['ios']:
    summary['max_platform_loss_delta_db']=max(summary['max_platform_loss_delta_db'],compare(data['ios'][name],data['android'][name]))
    left=next(r['result'] for r in summary['platforms']['ios']['metrics']['runs'] if r['name']==name)
    right=next(r['result'] for r in summary['platforms']['android']['metrics']['runs'] if r['name']==name)
    assert left==right,(name,left,right)
summary['passed']=True
(a.output/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps({k:v for k,v in summary.items() if k!='platforms'},indent=2))
for platform,content in summary['platforms'].items():
    print(platform,'RSS',content['metrics']['process_peak_rss_bytes'],'cancel ms',content['metrics']['cancel_latency_ms'])
    for run in content['metrics']['runs']:print(run['name'],round(run['elapsed_ms'],2),'ms',run['result']['samples'],'samples',run['result']['geometry'],'geometry',content['states'][run['name']])
