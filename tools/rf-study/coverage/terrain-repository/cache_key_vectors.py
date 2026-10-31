#!/usr/bin/env python3
"""Emit platform-neutral cache-key fixtures for future Swift/Kotlin adapters."""
import argparse,dataclasses,json
from pathlib import Path
from repository import TileSpec
p=argparse.ArgumentParser();p.add_argument('output',type=Path);a=p.parse_args()
base=TileSpec('fixture-dem','r1','N50W115','0'*64,123,5,'https://example.com/tile','EGM96','Terrain – Café')
specs=[('utf8',base),('transport_mirror',dataclasses.replace(base,url='https://mirror.example.com/tile')),
       ('revision',dataclasses.replace(base,revision='r2')),('resolution',dataclasses.replace(base,side=3601)),
       ('southern_western',dataclasses.replace(base,tile='S01W001')),('eastern_boundary',dataclasses.replace(base,tile='N00E179'))]
vectors=[{'name':name,'spec':dataclasses.asdict(spec),'identity':spec.identity,
          'canonical_utf8_json':json.dumps(spec.identity,sort_keys=True,separators=(',',':'),ensure_ascii=False),
          'sha256_key':spec.key} for name,spec in specs]
a.output.parent.mkdir(parents=True,exist_ok=True)
a.output.write_text(json.dumps({'encoding':'UTF-8 JSON; lexicographically sorted object keys; no whitespace; integer numbers; literal Unicode; no slash escaping; no Unicode normalization',
                               'vectors':vectors},indent=2,ensure_ascii=False)+'\n')
