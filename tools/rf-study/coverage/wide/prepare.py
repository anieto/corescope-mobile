#!/usr/bin/env python3
"""Bounded eight-tile full-resolution mosaic; no resampling, signed heights and voids preserved."""
import argparse,datetime,gzip,hashlib,io,json,mmap,struct,time,urllib.request
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('output',type=Path);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
north,west,south,east=51.90,-116.70,50.00,-113.60
ppd=3600;w=round((east-west)*ppd)+1;h=round((north-south)*ppd)+1
manifest_path=a.output/'manifest.json'
previous=json.loads(manifest_path.read_text()) if manifest_path.exists() else None
pins=json.loads((Path(__file__).parent/'terrain-pins.json').read_text())
records=[];start=time.perf_counter()
path=a.output/'terrain.bin'
with path.open('wb') as f:
 f.write(struct.pack('>8i',0x43535632,w,h,round(north*1e6),round(west*1e6),ppd,0,0))
 blank=b'\x80\x00'*w
 for _ in range(h):f.write(blank)
with path.open('r+b') as f:
 with mmap.mmap(f.fileno(),0) as dest:
  for lat in [50,51]:
   for lon in [-117,-116,-115,-114]:
    tile=f'N{lat:02d}W{abs(lon):03d}';cache=a.output/(tile+'.hgt.gz')
    url=f'https://elevation-tiles-prod.s3.amazonaws.com/v2/skadi/N{lat}/{tile}.hgt.gz'
    t=time.perf_counter();downloaded=False
    if not cache.exists():
     with urllib.request.urlopen(url,timeout=60) as response:
      data=response.read(40_000_001);assert len(data)<=40_000_000
     cache.write_bytes(data);downloaded=True
    packed=cache.read_bytes();digest=hashlib.sha256(packed).hexdigest()
    if tile in pins:assert digest==pins[tile],f'{tile}: changed source'
    with gzip.GzipFile(fileobj=io.BytesIO(packed)) as stream:raw=stream.read(3601*3601*2+1)
    assert len(raw)==3601*3601*2
    # Raster bounds align with HGT sample locations. Later north/east tiles own shared edges.
    y0=round((north-(lat+1))*ppd);x0=round((lon-west)*ppd)
    left=max(0,x0);right=min(w,x0+3601)
    for y in range(max(0,y0),min(h,y0+3601)):
     si=((y-y0)*3601+(left-x0))*2;di=32+(y*w+left)*2
     dest[di:di+(right-left)*2]=raw[si:si+(right-left)*2]
    record={'tile':tile,'url':url,'sha256':digest,'compressed_bytes':len(packed),'downloaded_this_run':downloaded,'fetch_decode_copy_seconds':time.perf_counter()-t}
    records.append(record);print(json.dumps(record),flush=True)
  dest.flush()
  digest=hashlib.sha256(dest).hexdigest()
  def elevation(lat,lon):
   y=round((north-lat)*ppd);x=round((lon-west)*ppd);return struct.unpack_from('>h',dest,32+2*(y*w+x))[0]
  # Pick the highest sample within a small, documented search box near Kananaskis.
  samples=[(elevation(lat/3600,lon/3600),lat/3600,lon/3600) for lat in range(round(50.94*3600),round(50.96*3600)+1) for lon in range(round(-115.16*3600),round(-115.14*3600)+1)]
  summit=max(samples)
manifest={'schema':2,'prepared_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'tiles':records,'north':north,'west':west,'south':south,'east':east,'width':w,'height':h,'ppd':ppd,'bytes':path.stat().st_size,'sha256':digest,'compressed_bytes':sum(x['compressed_bytes'] for x in records),'prepare_seconds':time.perf_counter()-start,'site':{'latitude':summit[1],'longitude':summit[2],'ground_m':summit[0],'antenna_agl_m':30},'datum':'EPSG:4326 horizontal; EGM96 heights per Skadi format documentation','sampling':'original one-arcsecond HGT sample locations, no decimation; later north/east tile owns duplicate border','nodata':-32768,'attribution':'Mapzen Terrain Tiles; Canada terrain data contains information licensed under the Open Government Licence – Canada; SRTM data courtesy of the U.S. Geological Survey'}
manifest_path.write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps({k:v for k,v in manifest.items() if k!='tiles'},indent=2))
