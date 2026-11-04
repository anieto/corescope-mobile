#!/usr/bin/env python3
"""Reproduce a regional catalog from checked-in study evidence; no network or publication.

This preserves reviewed pins, not a live discovery service. It must not silently bless new bytes.
"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / 'docs/node-planner/feasibility/wide-coverage/terrain-manifest.json'
PINS = ROOT / 'tools/rf-study/coverage/wide/terrain-pins.json'
OUTPUT = ROOT / 'docs/node-planner/feasibility/regional-terrain-catalog.json'


def build(manifest, pins):
    if manifest.get('schema') != 2 or manifest.get('ppd') != 3600 or manifest.get('nodata') != -32768:
        raise ValueError('Unsupported study terrain representation')
    rows = manifest['tiles']
    if not 1 <= len(rows) <= 4096:
        raise ValueError('Invalid tile count')
    entries = []
    seen = set()
    for row in rows:
        tile = row['tile']
        if tile in seen or tile not in pins:
            raise ValueError('Duplicate or unreviewed tile')
        seen.add(tile)
        digest = row['sha256']
        if digest != pins[tile] or len(digest) != 64 or any(c not in '0123456789abcdef' for c in digest):
            raise ValueError('Study digest disagrees with reviewed pin')
        url = f'https://elevation-tiles-prod.s3.amazonaws.com/v2/skadi/{tile[:3]}/{tile}.hgt.gz'
        if row['url'] != url:
            raise ValueError('Unexpected terrain origin')
        size = row['compressed_bytes']
        if type(size) is not int or not 1 <= size <= 40_000_000:
            raise ValueError('Invalid payload size')
        entries.append({'tile': tile, 'sha256': digest, 'bytes': size, 'url': url})
    if seen != set(pins) or sum(row['bytes'] for row in entries) != manifest['compressed_bytes']:
        raise ValueError('Incomplete or inconsistent study evidence')
    return {
        'version': 1, 'dataset': 'mapzen-skadi-study', 'revision': 'study-20261030',
        'side': 3601, 'vertical_datum': 'EGM96 per Skadi format documentation',
        'attribution': manifest['attribution'],
        'source_type': 'Multi-source terrain; per-pixel source and acquisition date not independently resolved',
        'tiles': sorted(entries, key=lambda row: row['tile']),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Verify the committed catalog is reproducible')
    args = parser.parse_args()
    catalog = build(json.loads(MANIFEST.read_text()), json.loads(PINS.read_text()))
    data = (json.dumps(catalog, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    if args.check:
        if OUTPUT.read_bytes() != data:
            raise SystemExit('Catalog differs from the reviewed study evidence')
    else:
        OUTPUT.write_bytes(data)
    print(f'{len(catalog["tiles"])} tiles, {sum(t["bytes"] for t in catalog["tiles"])} compressed bytes')
    print(f'Catalog SHA-256: {hashlib.sha256(data).hexdigest()}')
    print(OUTPUT)


if __name__ == '__main__':
    main()
