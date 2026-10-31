#!/usr/bin/env python3
"""Standalone scientific preview; only qualifying margin is colored, with explicit study bounds."""
import argparse,csv
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('ios_results',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
svg=['<svg xmlns="http://www.w3.org/2000/svg" width="1250" height="550" viewBox="0 0 1250 550">','<title>Wide-area coverage and selected-area refinement</title>','<rect width="1250" height="550" fill="#fafafa"/>','<g font-family="sans-serif" fill="#142735"><text x="25" y="30" font-size="21">Wider scan → finer selected-area analysis</text><text x="25" y="55" font-size="13">Full-resolution terrain · 30 m profile spacing · 915 MHz · 30 m TX / 1.5 m RX AGL · North ↑</text></g>']
for panel,(name,spacing,loE,hiE,loN,hiN,title) in enumerate([
 ('r50_g1000',1000,-50000,50000,-50000,50000,'50 km radius · 1 km grid'),
 ('r50_g500',500,-50000,50000,-50000,50000,'50 km radius · 500 m grid'),
 ('refine50',50,10000,14000,0,4000,'Selected 4 × 4 km area · 50 m grid')]):
 ox=25+panel*410;oy=105;size=380
 def xy(e,n):return ox+(e-loE)/(hiE-loE)*size,oy+(hiN-n)/(hiN-loN)*size
 svg.append(f'<text x="{ox}" y="87" font-family="sans-serif" font-size="15">{title}</text>')
 svg.append(f'<rect x="{ox}" y="{oy}" width="{size}" height="{size}" fill="#eef1f4"/>')
 with (a.ios_results/(name+'.csv')).open() as f:
  for row in csv.DictReader(f):
   if row['state']!='PREDICTED':continue
   margin=152-float(row['loss_db'])
   if margin<0:continue
   t=min(1,margin/30);low=(190,150,219);high=(46,63,170)
   color='#'+''.join(f'{round(low[k]*(1-t)+high[k]*t):02x}' for k in range(3))
   x,y=xy(float(row['east_m']),float(row['north_m']));w=spacing/(hiE-loE)*size
   svg.append(f'<rect x="{x-w/2:.3f}" y="{y-w/2:.3f}" width="{w:.3f}" height="{w:.3f}" fill="{color}"/>')
 if panel<2:
  svg.append(f'<circle cx="{ox+size/2}" cy="{oy+size/2}" r="{size/2}" fill="none" stroke="#657080" stroke-dasharray="4 4"/>')
  x,y=xy(10000,4000);w=4000/100000*size
  svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{w}" fill="none" stroke="#d04a18" stroke-width="2"/>')
  x,y=xy(0,0);svg.append(f'<circle cx="{x}" cy="{y}" r="3" fill="#111"/>')
 svg.append(f'<text x="{ox}" y="507" font-family="sans-serif" font-size="12">'+('Orange box: refinement area · Dashed: study limit' if panel<2 else 'Computed independently; identical to matching fine-grid cells')+'</text>')
svg.append('<text x="25" y="532" font-family="sans-serif" font-size="12">Color: margin ≥ 0 dB (darker = stronger). Simulation, not measured coverage. Mapzen / Canada Open Government Licence / USGS SRTM.</text></svg>')
a.output.write_text('\n'.join(svg))
