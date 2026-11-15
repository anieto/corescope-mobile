#!/usr/bin/env python3
"""Synthetic layout comparison, not an engine result or codec conformance test."""
import gzip
import json
import math


def encoded(value):
    return json.dumps(value, separators=(',', ':'), allow_nan=False).encode()


rows = []
for count in (1681, 14641, 60000):
    for mode in ('full', 'partial', 'long-numbers'):
        cells = []
        states, losses, margins = [], [], []
        for i in range(count):
            qualified = mode != 'partial' or i % 4 == 0
            loss = (123.12345678901234 if mode == 'long-numbers' else 120 + math.sin(i) * 20) if qualified else None
            margin = (1.2345678901234567e-100 if mode == 'long-numbers' else 151.3 - loss) if qualified else None
            states.append(0 if qualified else 7)
            losses.append(loss)
            margins.append(margin)
            # Fair compact object comparison: omit null fields like Android's legacy writer.
            cell = {'state': 'qualified' if qualified else 'notComputed'}
            if qualified:
                cell.update(lossDb=loss, marginDb=margin)
            cells.append(cell)
        nodes = [{'sourceID': 'synthetic-source', 'nodeID': f'{i:064x}',
                  'latitude': 29.12345678901234, 'longitude': -98.12345678901234,
                  'distanceMeters': 12345.123456789012, 'marginDb': 17.12345678901234}
                 for i in range(200)]
        objects = encoded({'cells': cells, 'nodes': nodes})
        columns = encoded({'stateCodes': states, 'lossesDb': losses, 'marginsDb': margins, 'nodes': nodes})
        rows.append({'cells': count, 'case': mode, 'objectsBytes': len(objects),
                     'columnarBytes': len(columns), 'objectsGzipBytes': len(gzip.compress(objects, mtime=0)),
                     'columnarGzipBytes': len(gzip.compress(columns, mtime=0)),
                     'rawRatio': round(len(objects) / len(columns), 3)})
print(json.dumps({'scope': 'synthetic raster plus 200 nodes; excludes envelope, inputs, terrain and other metadata',
                  'measurements': rows}, indent=2))
