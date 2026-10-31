#!/usr/bin/env python3
"""Independent reference decoder; fixture preparation may hold a whole tile in memory."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import random
import struct

p = argparse.ArgumentParser()
p.add_argument('cached_gzip', type=Path)
p.add_argument('output', type=Path)
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=True)
pins = json.loads((Path(__file__).resolve().parent.parent / 'wide/terrain-pins.json').read_text())
tiles, cases, records = [], [], []
rng = random.Random(20261031)
for tile, expected in sorted(pins.items()):
    packed = (a.cached_gzip / (tile + '.hgt.gz')).read_bytes()
    assert hashlib.sha256(packed).hexdigest() == expected, tile
    raw = gzip.decompress(packed)
    assert len(raw) == 3601 * 3601 * 2, tile
    digest = hashlib.sha256(raw).hexdigest()
    name = tile + '.hgt'
    (a.output / name).write_bytes(raw)
    tiles.append(f'{name}\t{digest}')
    # Corners/row transitions, largest allowed read, final block, then random short runs.
    runs = [(0, 1), (3600, 3), (3601 * 3600, 1), (3601 * 3601 - 1, 1),
            (0, 32768), (3601 * 3601 - 32768, 32768)]
    runs += [(rng.randrange(3601 * 3601 - 128), rng.randrange(1, 129)) for _ in range(100)]
    for start, count in runs:
        values = struct.unpack_from(f'>{count}h', raw, start * 2)
        cases.append(f'{name}\t{start}\t' + ','.join(map(str, values)))
    records.append({'tile': tile, 'compressed_sha256': expected, 'decoded_sha256': digest,
                    'read_cases': len(runs), 'samples': sum(count for _, count in runs)})
(a.output / 'tiles.tsv').write_text('\n'.join(tiles) + '\n')
(a.output / 'samples.tsv').write_text('\n'.join(cases) + '\n')
(a.output / 'manifest.json').write_text(json.dumps({'tiles': records, 'read_cases': len(cases),
    'samples': sum(r['samples'] for r in records), 'seed': 20261031}, indent=2) + '\n')
print(f'Prepared {len(records)} pinned tiles and {len(cases)} read cases in {a.output}')
