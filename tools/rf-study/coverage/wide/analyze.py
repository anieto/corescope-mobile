#!/usr/bin/env python3
import argparse,collections,csv,gzip,hashlib,json,math,statistics,xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('study',type=Path);p.add_argument('output',type=Path);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
names=['r10_g200','r20_g500','r50_g1000','r100_g2000','r50_g500','r100_g1000','refine50','reference50','sensitivity']
def read(platform,name):
 with (a.study/'results'/platform/(name+'.csv')).open() as f:return list(csv.DictReader(f))
report={'tolerance_db':.01,'runs':{},'metrics':{},'sampling_sensitivity':{}}
for platform in ['ios','android']:
 metrics=json.loads((a.study/'results'/platform/'metrics.json').read_text());report['metrics'][platform]=metrics
 assert metrics['oversized_rejected'] and metrics['cancel_completed']<40401 and 0<=metrics['cancel_latency_ms']<1000
 for name in names:
  data=(a.study/'results'/platform/(name+'.csv')).read_bytes()
  (a.output/(platform+'-'+name+'.csv.gz')).write_bytes(gzip.compress(data,mtime=0))
for name in names:
 left=read('ios',name);right=read('android',name);assert len(left)==len(right)
 diffs=[]
 for x,y in zip(left,right):
  assert x.keys()==y.keys()
  for key in x.keys()-{'loss_db'}:assert x[key]==y[key],(name,key,x,y)
  if x['state']=='PREDICTED':
   assert math.isfinite(float(x['loss_db'])) and math.isfinite(float(y['loss_db']))
   diffs.append(abs(float(x['loss_db'])-float(y['loss_db'])))
  else:assert not x['loss_db'] and not y['loss_db']
 assert max(diffs,default=0)<.01,(name,max(diffs))
 counts=dict(collections.Counter(x['state'] for x in left))
 assert counts.get('INVALID_INPUT',0)==counts.get('INVALID_MODEL',0)==counts.get('NOT_COMPUTED',0)==0
 report['runs'][name]={'counts':counts,'max_platform_difference_db':max(diffs,default=0),'cells':len(left),'above_threshold':sum(x['state']=='PREDICTED' and float(x['loss_db'])<=152 for x in left)}
 if name!='sensitivity':
  qualifying=[math.hypot(float(x['east_m']),float(x['north_m'])) for x in left if x['state']=='PREDICTED' and float(x['loss_db'])<=152]
  report['runs'][name]['farthest_qualifying_sample_m']=max(qualifying,default=0)
# Independently executed grids must agree at identical positions, including refinement corners.
for platform in ['ios','android']:
 reference={(x['east_m'],x['north_m']):x for x in read(platform,'reference50')}
 for x in read(platform,'refine50'):assert x==reference[x['east_m'],x['north_m']]
report['refinement']={'matching_cells':len(read('ios','refine50')),'exact_match_with_larger_fine_grid':True}
# Spatial detail: comparing threshold categories at finer points to nearest coarse-grid predictions
# quantifies a display approximation, not errors against measured RF truth.
for coarse_name,fine_name in [('r50_g1000','r50_g500'),('r100_g2000','r100_g1000')]:
 coarse=read('ios',coarse_name);fine=read('ios',fine_name);spacing=1000 if coarse_name=='r50_g1000' else 2000
 by_coord={(int(x['east_m']),int(x['north_m'])):x for x in coarse}
 comparable=changes=0
 for x in fine:
  key=(round(int(x['east_m'])/spacing)*spacing,round(int(x['north_m'])/spacing)*spacing)
  y=by_coord.get(key)
  if y and x['state']==y['state']=='PREDICTED':
   comparable+=1;changes+=(float(x['loss_db'])<=152)!=(float(y['loss_db'])<=152)
 report['runs'][fine_name]['nearest_coarse_classification']={'comparable':comparable,'threshold_changes':changes,'coarse_run':coarse_name}
rows=read('ios','sensitivity');reference={(r['bearing_deg'],r['distance_m']):r for r in rows if r['profile_m']=='10'}
for spacing in ['90','30','15']:
 differences=[];changes=0;worst=None
 for row in rows:
  if row['profile_m']!=spacing:continue
  ref=reference[row['bearing_deg'],row['distance_m']]
  assert row['state']==ref['state'],('sampling validity change',row,ref)
  if row['state']=='PREDICTED':
   difference=abs(float(row['loss_db'])-float(ref['loss_db']));differences.append(difference)
   if worst is None or difference>worst['difference_db']:worst={'bearing_deg':row['bearing_deg'],'distance_m':row['distance_m'],'difference_db':difference,'loss_db':float(row['loss_db']),'reference_loss_db':float(ref['loss_db'])}
   changes+=(float(row['loss_db'])<=152)!=(float(ref['loss_db'])<=152)
 differences.sort()
 report['sampling_sensitivity'][spacing]={'reference_profile_m':10,'compared_paths':len(differences),'max_difference_db':max(differences),'p95_difference_db':differences[math.ceil(.95*len(differences))-1],'median_difference_db':statistics.median(differences),'threshold_changes':changes,'worst':worst}
diagnostics=[read(platform,'diagnostic') for platform in ['ios','android']]
for platform in ['ios','android']:
 data=(a.study/'results'/platform/'diagnostic.csv').read_bytes()
 (a.output/(platform+'-diagnostic.csv.gz')).write_bytes(gzip.compress(data,mtime=0))
for x,y in zip(*diagnostics):
 assert all(x[k]==y[k] for k in x.keys()-{'loss_db'})
 assert abs(float(x['loss_db'])-float(y['loss_db']))<.01
report['targeted_convergence']={}
for bearing in ['240','285']:
 values={r['profile_m']:float(r['loss_db']) for r in diagnostics[0] if r['bearing_deg']==bearing}
 report['targeted_convergence'][bearing]={'distance_m':100000,'loss_by_profile_m':values,
  'difference_10_to_2_db':abs(values['10']-values['2']),'difference_5_to_2_db':abs(values['5']-values['2'])}
for platform in ['ios','android']:
 larger={(x['east_m'],x['north_m']):x for x in read(platform,'r100_g1000')}
 for x in read(platform,'r50_g1000'):
  if x['state']!='OUTSIDE_RADIUS':assert x==larger[x['east_m'],x['north_m']]
report['extent_invariance']='Increasing 50 to 100 km radius preserves all common in-radius 1 km grid results exactly'
manifest=json.loads((a.study/'data/manifest.json').read_text());(a.output/'terrain-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
report['artifacts_sha256']={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in a.output.glob('*.csv.gz')}
(a.output/'summary.json').write_text(json.dumps(report,indent=2)+'\n')
tests=[]
for f in (a.study/'jvm/build/test-results/test').glob('*.xml'):
 t=ET.parse(f).getroot();tests.append({'suite':t.attrib,'cases':[x.attrib for x in t.findall('testcase')]});assert t.attrib['failures']==t.attrib['errors']=='0'
(a.output/'tests.json').write_text(json.dumps(tests,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k!='artifacts_sha256'},indent=2))
