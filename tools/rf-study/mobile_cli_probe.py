#!/usr/bin/env python3
"""Run pinned upstream CLI fixtures on a booted simulator/emulator.
Usage: mobile_cli_probe.py CHECKOUT ios|android DEVICE OUTPUT_JSON
Requires study-ios-sim or study-android built in CHECKOUT/engine/build.
"""
import array, gzip, hashlib, json, subprocess, sys, time
from pathlib import Path
PIN='481e5a482f10fc6956a98aed39aacdb59c3fb6ec'
root=Path(sys.argv[1]).resolve(); platform,device,out=sys.argv[2:5]
assert subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip()==PIN
assert subprocess.check_output(['git', '-C', str(root/'splat'), 'rev-parse', 'HEAD'], text=True).strip() == '08f06f56470a8361a5609503a6b1cb02f07f48dc'
work=root/'engine/build/mobile-probe'; work.mkdir(exist_ok=True)
pages=work/'terrain';pages.mkdir(exist_ok=True)
for file in (root/'test/fixtures/terrain').glob('*.sdf.gz'):
 with gzip.open(file,'rt') as stream:
  header=[int(stream.readline()) for _ in range(4)]
  data=array.array('h',map(int,stream))
 assert len(data)==1440000
 if sys.byteorder!='little':data.byteswap()
 (pages/f'page_{header[1]}_{(header[0]-1)%360}.s16').write_bytes(data.tobytes())
climates=dict(zip(['equatorial','continental_subtropical','maritime_subtropical','desert','continental_temperate','maritime_temperate_land','maritime_temperate_sea'],range(1,8)))
adb=str(Path.home()/'Library/Android/sdk/platform-tools/adb')
remote='/data/local/tmp/nodescope-rf-study'
def run(args):
 result=subprocess.run(args,capture_output=True,text=True,timeout=180)
 if result.returncode:
  raise RuntimeError(f"Command failed ({result.returncode}): {args!r}\n{result.stderr}")
 return result
if platform=='android':
 run([adb,'-s',device,'shell','mkdir','-p',remote])
 run([adb,'-s',device,'push',str(pages),remote+'/terrain'])
 run([adb,'-s',device,'push',str(root/'engine/build/study-android'),remote+'/probe'])
 run([adb,'-s',device,'shell','chmod','755',remote+'/probe'])
results=[]
for file in sorted((root/'test/fixtures/cases').glob('*.json')):
 case=json.loads(file.read_text());name=file.stem
 erp=10**((case['tx_power']+case['tx_gain']-case['system_loss']-30)/10)
 params={'lat':case['lat'],'lon':case['lon'],'txft':case['tx_height'],'rxft':case['rx_height']/0.3048,
 'freq':case['frequency_mhz'],'erp':f'{erp:.2f}','dielect':case['ground_dielectric'],
 'cond':case['ground_conductivity'],'bend':case['atmosphere_bending'],'climate':climates[case['radio_climate']],
 'pol':int(case['polarization']=='vertical'),'conf':f"{case['situation_fraction']/100:.2f}",
 'rel':f"{case['time_fraction']/100:.2f}",'clutter-m':case['clutter_height'],'radius-km':min(case['radius'],100000)/1000,
 'terrain':str(pages) if platform=='ios' else remote+'/terrain',
 'out':str(work/(platform+'-'+name)) if platform=='ios' else remote+'/'+name}
 args=[v for k,value in params.items() for v in ['--'+k,str(value)]]
 command=(['xcrun','simctl','spawn',device,str(root/'engine/build/study-ios-sim')] if platform=='ios'
 else [adb,'-s',device,'shell',remote+'/probe'])+args
 t=time.perf_counter();log=run(command);elapsed=time.perf_counter()-t
 local=work/(platform+'-'+name)
 if platform=='android':
  for ext in ['signal.u8','mask.u8','meta.json']:
   run([adb,'-s',device,'pull',remote+'/'+name+'.'+ext,str(local)+'.'+ext])
 meta=json.loads(Path(str(local)+'.meta.json').read_text())
 assert meta['pages_loaded']==meta['pages']
 result={'case':name,'wall_seconds_including_launch_io':elapsed,'metadata':meta}
 expected=json.loads((root/'test/fixtures/golden-engine'/f'{name}.meta.json').read_text())
 result['region_matches_golden']=all(meta[k]==expected[k] for k in ['width','height','north','south','east','west'])
 for kind in ['signal','mask']:
  actual=Path(str(local)+'.'+kind+'.u8').read_bytes()
  golden=gzip.decompress((root/'test/fixtures/golden-engine'/f'{name}.{kind}.u8.gz').read_bytes())
  assert len(actual)==len(golden)
  result[kind+'_byte_mismatches']=sum(a!=b for a,b in zip(actual,golden))
  result[kind+'_sha256']=hashlib.sha256(actual).hexdigest()
 actual_signal=Path(str(local)+'.signal.u8').read_bytes()
 golden_signal=gzip.decompress((root/'test/fixtures/golden-engine'/f'{name}.signal.u8.gz').read_bytes())
 golden_mask=gzip.decompress((root/'test/fixtures/golden-engine'/f'{name}.mask.u8.gz').read_bytes())
 differences=[abs(a-b) for a,b,m in zip(actual_signal,golden_signal,golden_mask) if m & 248]
 result.update(max_computed_db_difference=max(differences,default=0),computed_cells=len(differences),
               computed_cells_over_1db=sum(d>1 for d in differences))
 results.append(result);print(json.dumps(result),flush=True)
 Path(out).write_text(json.dumps({'upstream':PIN,'platform':platform,'device':device,'results':results},indent=2)+'\n')
