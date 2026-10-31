#!/usr/bin/env python3
"""Assert cross-runtime scientific agreement and archive reviewable evidence (stdlib only)."""
import argparse
import collections
import csv
import gzip
import hashlib
import json
import math
from pathlib import Path
import shutil
p=argparse.ArgumentParser();p.add_argument('study',type=Path);p.add_argument('output',type=Path)
a=p.parse_args();out=a.output;out.mkdir(parents=True,exist_ok=True)
results=a.study/'results'
def read(platform, name):
    with (results/platform/name).open() as f:return list(csv.DictReader(f))
summary={'tolerance_db':0.01,'scenarios':{},'platforms':{}}
for platform in ['ios','android']:
    metrics=json.loads((results/platform/'metrics.json').read_text())
    assert metrics['boundary_checks_passed']
    cancelled=next(x for x in metrics['runs'] if x['scenario']=='cancel')
    assert cancelled['state']=='CANCELLED' and 0 < cancelled['completed'] < 10201
    assert 0 <= cancelled['cancel_latency_ms'] < 250
    rows=read(platform,'cancel.csv')
    assert sum(r['state']!='NOT_COMPUTED' for r in rows)==cancelled['completed']
    assert all(r['loss_db']=='' for r in rows if r['state']!='PREDICTED')
    summary['platforms'][platform]=metrics
    for name in ['clean.csv','gap.csv','cancel.csv','links.csv']:
        (out/(platform+'-'+name+'.gz')).write_bytes(gzip.compress((results/platform/name).read_bytes(),mtime=0))
for scenario in ['clean','gap']:
    left,right=[read(platform,scenario+'.csv') for platform in ['ios','android']]
    assert len(left)==len(right)==10201
    deltas=[]
    for i,(x,y) in enumerate(zip(left,right)):
        assert x['index']==y['index']==str(i)
        assert x['state']==y['state'] and x['samples']==y['samples'], (scenario,i,x,y)
        if x['state']=='PREDICTED':
            for r in [x,y]:
                assert all(math.isfinite(float(r[k])) for k in ['loss_db','rx_dbm','margin_db'])
                assert abs(float(r['rx_dbm'])-(22-float(r['loss_db'])))<1e-9
                assert abs(float(r['margin_db'])-(float(r['rx_dbm'])+130))<1e-9
                assert 3 <= int(r['samples']) <= 2001
            deltas.append(abs(float(x['loss_db'])-float(y['loss_db'])))
        else:
            assert all(r[k]=='' for r in [x,y] for k in ['loss_db','rx_dbm','margin_db'])
    assert max(deltas)<summary['tolerance_db']
    counts=dict(collections.Counter(r['state'] for r in left))
    assert counts.get('NEAR_SITE')==5 and counts.get('INVALID_MODEL',0)==0
    if scenario=='clean': assert counts.get('MISSING_TERRAIN',0)==0
    else: assert counts.get('MISSING_TERRAIN',0)>0
    summary['scenarios'][scenario]={'counts':counts,'max_loss_difference_db':max(deltas),
        'loss_range_db':[min(float(x['loss_db']) for x in left if x['loss_db']),max(float(x['loss_db']) for x in left if x['loss_db'])],
        'max_profile_samples':max(int(x['samples']) for x in left)}
for platform in ['ios','android']:
    clean,gap=[read(platform,name+'.csv') for name in ['clean','gap']]
    for c,g in zip(clean,gap):
        if g['state']=='PREDICTED': assert c['state']=='PREDICTED' and c['loss_db']==g['loss_db']
        if c['state']!='PREDICTED':assert c['state']==g['state']
links=[read(platform,'links.csv') for platform in ['ios','android']]
link_deltas=[]
for x,y in zip(*links):
    assert x['fixture']==y['fixture'] and x['state']==y['state'] and x['samples']==y['samples']
    if x['loss_db']:link_deltas.append(abs(float(x['loss_db'])-float(y['loss_db'])))
assert max(link_deltas)<.01
assert [r['state'] for r in links[0]]==['PREDICTED','PREDICTED','PREDICTED','NEAR_SITE','MISSING_TERRAIN']
# An exact off-grid receiver must not be replaced with the closest display-cell result.
x=links[0][0];col=round(float(x['east_m'])/200)+50;row=50-round(float(x['north_m'])/200)
nearest=read('ios','clean.csv')[row*101+col]
summary['receiver_fixtures']={'max_loss_difference_db':max(link_deltas),
 'off_grid_loss_db':float(x['loss_db']),'nearest_display_cell_loss_db':float(nearest['loss_db']),
 'raised_receiver_loss_db':float(links[0][1]['loss_db']),
 'note':'Synthetic receiver fixtures at exact coordinates, not observed mesh nodes'}
assert abs(float(x['loss_db'])-float(nearest['loss_db']))>1e-6
shutil.copyfile(a.study/'data/terrain-manifest.json',out/'terrain-manifest.json')
for platform in ['ios','android']:
    info=results/platform/'device.txt'
    if info.exists(): shutil.copyfile(info,out/(platform+'-device.txt'))
summary['artifacts_sha256']={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(out.glob('*.csv.gz'))}
(out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
# A standalone scientific raster preview, not a proposed app screen or measured coverage map.
svg=['<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="610" viewBox="0 0 1000 610">',
 '<title>Candidate Sites prototype: modeled receive margin and deliberate terrain gap</title>',
 '<rect width="1000" height="610" fill="#fafafa"/>',
 '<g font-family="sans-serif" fill="#162331"><text x="30" y="30" font-size="20">10 km terrain-only coverage experiment · 915 MHz</text>',
 '<text x="30" y="54" font-size="13">51.15° N, 114.30° W · 200 m output cells · 30 m path sampling · North ↑</text></g>']
def color(margin):
    t=max(0,min(1,(margin+20)/50))
    low=(209,92,53);high=(36,118,160)
    return '#'+''.join(f'{round(low[k]*(1-t)+high[k]*t):02x}' for k in range(3))
for panel,scenario in enumerate(['clean','gap']):
    ox=40+panel*490;oy=100
    svg.append(f'<text x="{ox}" y="86" font-family="sans-serif" font-size="16">'+('Complete terrain' if panel==0 else 'Injected nodata strip → Unknown')+'</text>')
    for r in read('ios',scenario+'.csv'):
        if r['state']=='OUTSIDE_RADIUS':continue
        i=int(r['index']);fill=color(float(r['margin_db'])) if r['state']=='PREDICTED' else ('#17212b' if r['state']=='NEAR_SITE' else '#b8b8b8')
        svg.append(f'<rect x="{ox+(i%101)*4}" y="{oy+(i//101)*4}" width="4" height="4" fill="{fill}"/>')
    svg.append(f'<path d="M {ox} 518 h 101" stroke="#17212b" stroke-width="2"/><text x="{ox}" y="536" font-size="12" font-family="sans-serif">5 km</text>')
for i in range(100):svg.append(f'<rect x="{250+i*3}" y="550" width="3" height="12" fill="{color(-20+i*.5)}"/>')
svg.extend(['<g font-family="sans-serif" font-size="12" fill="#162331"><text x="30" y="560">Receive margin (threshold −130 dBm)</text><text x="250" y="579">−20 dB</text><text x="510" y="579">+30 dB</text><text x="600" y="560">Gray: Unknown · Black: unsupported &lt;250 m</text><text x="30" y="600">Simulation, not observed RF coverage. Mapzen Terrain Tiles; Canada Open Government Licence; USGS SRTM.</text></g></svg>'])
(out/'coverage-preview.svg').write_text('\n'.join(svg))
print(json.dumps(summary,indent=2))
