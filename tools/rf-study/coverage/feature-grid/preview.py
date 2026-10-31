#!/usr/bin/env python3
"""Self-contained coverage/terrain SVG and local HTML viewer; no network basemap."""
import argparse,base64,csv,html,json,math,mmap,struct,zlib
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('results',type=Path);p.add_argument('terrain',type=Path);p.add_argument('output',type=Path);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
metrics=json.loads((a.results/'metrics.json').read_text())
for name in ['r20_1000','refine100']:
    run=next(r['result'] for r in metrics['runs'] if r['name']==name)
    assert run['state']=='COMPLETE' and run['completed']==run['cells'], 'Do not label an incomplete study complete'
def png(width,height,pixels):
    def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
    rows=b''.join(b'\0'+pixels[y*width*3:(y+1)*width*3] for y in range(height))
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>2I5B',width,height,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(rows))+chunk(b'IEND',b'')
handle=a.terrain.open('rb');terrain=mmap.mmap(handle.fileno(),0,access=mmap.ACCESS_READ)
magic,w,h,north,west,ppd,_,_=struct.unpack_from('>8i',terrain)
assert magic==0x43535632 and len(terrain)==32+w*h*2
north/=1e6;west/=1e6
lat0=math.radians(50.96);lon0=math.radians(-115.16)
def height(e,n):
    arc=math.hypot(e,n)/6371008.8;bearing=math.atan2(e,n)
    lat=math.asin(math.sin(lat0)*math.cos(arc)+math.cos(lat0)*math.sin(arc)*math.cos(bearing))
    lon=lon0+math.atan2(math.sin(bearing)*math.sin(arc)*math.cos(lat0),math.cos(arc)-math.sin(lat0)*math.sin(lat))
    x=(math.degrees(lon)-west)*ppd;y=(north-math.degrees(lat))*ppd
    assert 0<=x<=w-1 and 0<=y<=h-1
    ix=min(int(x),w-2);iy=min(int(y),h-2);fx=x-ix;fy=y-iy
    values=[struct.unpack_from('>h',terrain,32+2*((iy+dy)*w+ix+dx))[0] for dy,dx in [(0,0),(0,1),(1,0),(1,1)]]
    assert all(-12000<=v<=10000 for v in values)
    return (values[0]*(1-fx)+values[1]*fx)*(1-fy)+(values[2]*(1-fx)+values[3]*fx)*fy
def relief(loE,hiE,loN,hiN,size=400):
    step=(hiE-loE)/(size-1)
    heights=[[height(loE+x*step,hiN-y*step) for x in range(size)] for y in range(size)]
    pixels=bytearray()
    for y in range(size):
        for x in range(size):
            dx=(heights[y][min(x+1,size-1)]-heights[y][max(x-1,0)])/(2*step)
            dn=(heights[max(y-1,0)][x]-heights[min(y+1,size-1)][x])/(2*step)
            light=(.5*dx+.5*dn+.7071)/math.sqrt(dx*dx+dn*dn+1)
            shade=round(max(110,min(242,164+76*light)))
            pixels.extend((max(0,shade-5),shade,max(0,shade-8)))
    return base64.b64encode(png(size,size,pixels)).decode()
svg=['<svg xmlns="http://www.w3.org/2000/svg" width="1120" height="720" viewBox="0 0 1120 720" role="img" aria-labelledby="title desc">',
     '<title id="title">Feature-preserving candidate-site coverage preview</title>',
     '<desc id="desc">Two computed coverage panels over terrain relief: a twenty-kilometre scan and a refined selected area. Color denotes above-threshold estimates, not measured coverage.</desc>',
     '<rect width="1120" height="720" fill="#fafaf7"/>',
     '<defs><pattern id="unknown" width="7" height="7" patternUnits="userSpaceOnUse"><path d="M-1,1 L1,-1 M0,7 L7,0 M6,8 L8,6" stroke="#ac5500" stroke-width="2"/></pattern></defs>',
     '<g font-family="-apple-system,BlinkMacSystemFont,Arial,sans-serif" fill="#192d38">',
     '<text x="30" y="34" font-size="23" font-weight="600">Updated coverage: wide scan + selected-area refinement</text>',
     '<text x="30" y="60" font-size="13">Feature-preserving profiles · 915 MHz · TX 30 m / RX 1.5 m AGL · 50.9600° N, 115.1600° W</text>',
     '<text x="30" y="81" font-size="12">Terrain relief from the study DEM; no street basemap. Local geodesic-distance view, north up. Not an app screen.</text>']
for panel,(name,spacing,loE,hiE,loN,hiN,title,subtitle) in enumerate([
    ('r20_1000',1000,-20000,20000,-20000,20000,'20 km scan · 1 km output grid','Dashed circle = study boundary; orange box = refined area'),
    ('refine100',100,10000,14000,0,4000,'Selected 4 × 4 km area · 100 m output grid','10–14 km east, 0–4 km north of the candidate')]):
    ox=30+panel*550;oy=135;size=510
    def xy(e,n):return ox+(e-loE)/(hiE-loE)*size,oy+(hiN-n)/(hiN-loN)*size
    svg.extend([f'<text x="{ox}" y="111" font-size="17" font-weight="600">{title}</text>',
        f'<clipPath id="clip{panel}"><rect x="{ox}" y="{oy}" width="{size}" height="{size}"/></clipPath>',
        f'<image x="{ox}" y="{oy}" width="{size}" height="{size}" href="data:image/png;base64,{relief(loE,hiE,loN,hiN)}"/>'])
    rows=list(csv.DictReader((a.results/(name+'.csv')).open()));positive=0;unknown=0
    svg.append(f'<g class="coverage" opacity="0.75" clip-path="url(#clip{panel})">')
    for row in rows:
        if row['state']!='QUALIFIED':continue
        margin=152-float(row['loss_db'])
        if margin<0:continue
        positive+=1;t=min(1,margin/30);low=(116,65,185);high=(26,170,124)
        color='#'+''.join(f'{round(low[k]*(1-t)+high[k]*t):02x}' for k in range(3))
        x,y=xy(float(row['east_m']),float(row['north_m']));pixel=spacing/(hiE-loE)*size
        label=f"East {row['east_m']} m, north {row['north_m']} m; estimated margin {margin:.1f} dB"
        svg.append(f'<rect x="{x-pixel/2:.3f}" y="{y-pixel/2:.3f}" width="{pixel:.3f}" height="{pixel:.3f}" fill="{color}"><title>{html.escape(label)}</title></rect>')
    svg.append('</g>')
    svg.append(f'<g clip-path="url(#clip{panel})">')
    for row in rows:
        if row['state'] in ['QUALIFIED','OUTSIDE_RADIUS','NEAR_SITE']:continue
        unknown+=1;x,y=xy(float(row['east_m']),float(row['north_m']));pixel=spacing/(hiE-loE)*size
        svg.append(f'<rect x="{x-pixel/2:.3f}" y="{y-pixel/2:.3f}" width="{pixel:.3f}" height="{pixel:.3f}" fill="url(#unknown)"><title>{row["state"]}</title></rect>')
    svg.append('</g>')
    if panel==0:
        svg.append(f'<circle cx="{ox+size/2}" cy="{oy+size/2}" r="{size/2}" fill="none" stroke="#3e4d52" stroke-dasharray="5 5"/>')
        x,y=xy(10000,4000);width=4000/40000*size
        svg.append(f'<rect x="{x}" y="{y}" width="{width}" height="{width}" fill="none" stroke="#e86c00" stroke-width="3"/>')
        x,y=xy(0,0);svg.append(f'<circle cx="{x}" cy="{y}" r="5" fill="#152b33" stroke="white" stroke-width="2"><title>Proposed candidate site</title></circle>')
    scale=5000 if panel==0 else 500;length=scale/(hiE-loE)*size
    svg.append(f'<rect x="{ox+10}" y="{oy+size-38}" width="{length+20}" height="30" rx="3" fill="white" opacity="0.9"/>')
    svg.append(f'<path d="M{ox+20},{oy+size-20} h{length}" stroke="#182b34" stroke-width="3"/><text x="{ox+20}" y="{oy+size-25}" font-size="11">{scale/1000:g} km</text>')
    svg.append(f'<text x="{ox}" y="665" font-size="12">{subtitle}</text><text x="{ox}" y="685" font-size="12">{positive} above-threshold cells · {unknown} unresolved cells · complete study</text>')
svg.append('<text x="30" y="710" font-size="11">Purple → green: 0 → 30+ dB estimated margin. Uncolored: below threshold or excluded; orange hatching: unresolved. Estimates, not measured RF coverage.</text></g></svg>')
svg_text='\n'.join(svg)
(a.output/'coverage-preview.svg').write_text(svg_text)
page='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Candidate Sites coverage preview</title>
<style>body{margin:24px;background:#fafaf7;color:#192d38;font:16px system-ui,sans-serif}main{max-width:1200px;margin:auto}svg{width:100%;height:auto}label{display:flex;gap:12px;align-items:center;flex-wrap:wrap}p{line-height:1.5}input{width:240px}a{color:#125bc4}</style><main>
<label>Coverage opacity <input id="opacity" type="range" min="0" max="100" value="75"><output id="value">75%</output></label>
'''+svg_text+'''
<p><strong>How to read this:</strong> colored cells are model estimates above the illustrative −130 dBm receiver threshold (152 dB path loss). The dashed circle is only the calculation boundary. Terrain remains visible beneath the overlay. Hover over colored cells for their estimated margin.</p>
<p>The selected area was computed independently and checked against matching cells from a larger 100 m grid. This preview uses the new feature-preserving algorithm; it is not the earlier fixed-profile preview.</p>
<p>Simulation assumptions: 915 MHz, 30 m transmitter height, 1.5 m receiver height, median inland conditions, zero clutter, 22 dBm combined link-budget numerator. Buildings, trees and actual radio performance are not verified. DEM interpolation cannot recover features missing from the terrain source.</p>
<p>Terrain attribution: Mapzen Terrain Tiles; contains information licensed under the Open Government Licence – Canada; SRTM data courtesy of the U.S. Geological Survey.</p>
<p><a href="coverage-preview.svg">Open standalone SVG</a> · <a href="summary.json">Validation results</a></p></main>
<script>const control=document.getElementById('opacity');control.addEventListener('input',()=>{document.querySelectorAll('.coverage').forEach(g=>g.setAttribute('opacity',control.value/100));document.getElementById('value').textContent=control.value+'%';});</script></html>'''
(a.output/'coverage-preview.html').write_text(page)
terrain.close();handle.close()
print(a.output/'coverage-preview.html')
