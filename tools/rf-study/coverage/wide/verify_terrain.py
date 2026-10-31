#!/usr/bin/env python3
"""Independent geographic lookup checks across HGT tile seams and the mosaic interior."""
import argparse,gzip,hashlib,json,math,mmap,random,struct
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('data',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
m=json.loads((a.data/'manifest.json').read_text());random.seed(1812)
points=[(random.randrange(m['height']),random.randrange(m['width'])) for _ in range(1000)]
# Check exact tile seams plus the immediately adjacent source samples.
for lat in [50.1,50.5,51,51.5,51.8]:
 for lon in [-116,-115,-114]:
  y=round((m['north']-lat)*3600);x=round((lon-m['west'])*3600)
  points.extend((y+dy,x+dx) for dy in [-1,0,1] for dx in [-1,0,1])
for lon in [-116.5,-116,-115.5,-115,-114.5,-114]:
 y=round((m['north']-51)*3600);x=round((lon-m['west'])*3600)
 points.extend((y+dy,x+dx) for dy in [-1,0,1] for dx in [-1,0,1])
groups={}
for y,x in points:
 lat=round((m['north']-y/3600)*3600)/3600;lon=round((m['west']+x/3600)*3600)/3600
 tile_lat=math.floor(lat);tile_lon=math.floor(lon)
 groups.setdefault((tile_lat,tile_lon),[]).append((y,x,lat,lon))
with (a.data/'terrain.bin').open('rb') as f:
 with mmap.mmap(f.fileno(),0,access=mmap.ACCESS_READ) as raster:
  assert hashlib.sha256(raster).hexdigest()==m['sha256']
  for (lat,lon),items in groups.items():
   tile=f'N{lat:02d}W{abs(lon):03d}';packed=(a.data/(tile+'.hgt.gz')).read_bytes()
   assert hashlib.sha256(packed).hexdigest()==next(t['sha256'] for t in m['tiles'] if t['tile']==tile)
   data=gzip.decompress(packed)
   for y,x,latitude,longitude in items:
    sy=round((lat+1-latitude)*3600);sx=round((longitude-lon)*3600)
    expected=struct.unpack_from('>h',data,2*(sy*3601+sx))[0]
    actual=struct.unpack_from('>h',raster,32+2*(y*m['width']+x))[0]
    assert actual==expected,(latitude,longitude,expected,actual)
record={'checked_locations':len(points),'exact_and_adjacent_seams':True,'all_match_original_hgt':True,'mosaic_sha256':m['sha256']}
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(record,indent=2)+'\n');print(record)
