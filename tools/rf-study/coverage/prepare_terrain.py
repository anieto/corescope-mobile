#!/usr/bin/env python3
"""Fetch one bounded study tile; preserve nodata and pin downloaded bytes by SHA-256."""
import argparse
import datetime
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct
import time
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('output', type=Path)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
url = 'https://elevation-tiles-prod.s3.amazonaws.com/v2/skadi/N51/N51W115.hgt.gz'
expected_archive_sha256 = 'b494f6412411f2816ec8f44d3ab161300003c54419578d04338070b2ad75ff82'
archive = args.output / 'N51W115.hgt.gz'
manifest_path = args.output / 'terrain-manifest.json'
if archive.exists() and manifest_path.exists():
    manifest = json.loads(manifest_path.read_text())
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == manifest['archive_sha256']
else:
    start = time.perf_counter()
    with urllib.request.urlopen(url, timeout=60) as response:
        packed = response.read(40_000_001)
        assert len(packed) <= 40_000_000, 'download budget exceeded'
        headers = dict(response.headers)
    archive.write_bytes(packed)
    manifest = {'url': url, 'fetched_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'download_seconds': time.perf_counter() - start, 'download_bytes': len(packed),
                'archive_sha256': hashlib.sha256(packed).hexdigest(),
                'etag': headers.get('ETag'), 'last_modified': headers.get('Last-Modified')}
assert hashlib.sha256(archive.read_bytes()).hexdigest() == expected_archive_sha256, 'terrain revision changed; review before updating the study pin'
with gzip.GzipFile(fileobj=io.BytesIO(archive.read_bytes())) as stream:
    raw = stream.read(3601 * 3601 * 2 + 1)
assert len(raw) == 3601 * 3601 * 2, 'unexpected HGT shape'
# North=51.25, south=51.05, west=-114.46, east=-114.14; no decimation.
row0, col0, rows, cols = 2700, 1944, 721, 1153
crop = bytearray(struct.pack('>8i', 0x43535631, cols, rows, row0, col0, 51, -115, 3600))
for row in range(row0, row0 + rows):
    offset = (row * 3601 + col0) * 2
    crop.extend(raw[offset:offset + cols * 2])
(args.output / 'terrain.bin').write_bytes(crop)
values = [v[0] for v in struct.iter_unpack('>h', crop[32:])]
manifest.update({'raw_sha256': hashlib.sha256(raw).hexdigest(), 'crop_sha256': hashlib.sha256(crop).hexdigest(),
                 'crop_bytes': len(crop), 'rows': rows, 'cols': cols, 'north': 51.25, 'south': 51.05,
                 'west': -114.46, 'east': -114.14, 'sample_spacing_arcseconds': 1,
                 'horizontal_crs': 'EPSG:4326', 'vertical_reference': 'WGS84/EGM96 geoid, per provider format docs',
                 'nodata': -32768, 'nodata_count': values.count(-32768),
                 'valid_min_m': min(v for v in values if v != -32768), 'valid_max_m': max(values),
                 'decoder': 'coverage-study-v1; signed big-endian; north-first; exact crop, no resampling',
                 'interpolation': 'bilinear; any supporting nodata or outside-crop sample => Unknown',
                 'attribution': 'Mapzen Terrain Tiles; Canada terrain data contains information licensed under the Open Government Licence – Canada; SRTM data courtesy of the U.S. Geological Survey',
                 'source_caveat': 'Multi-source product; per-pixel contributing source and acquisition date not resolved in this prototype',
                 'format_docs': 'https://github.com/tilezen/joerd/blob/master/docs/formats.md',
                 'attribution_docs': 'https://github.com/tilezen/joerd/blob/master/docs/attribution.md'})
manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps(manifest, indent=2))
