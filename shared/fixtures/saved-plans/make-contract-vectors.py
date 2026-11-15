#!/usr/bin/env python3
"""Reference implementation of the saved-plans v3 input fingerprints and margin arithmetic.

Writes contract-vectors-v1.json (run: python3 -I shared/fixtures/saved-plans/make-contract-vectors.py > shared/fixtures/saved-plans/contract-vectors-v1.json). Swift (PortableContractTests) and Kotlin (PortableContractTest)
each reimplement the rules independently and must reproduce every value here exactly. Doubles
are written as IEEE-754 binary64 bit patterns ("0x...") so the vectors never depend on any
platform's decimal parsing; the jsonNumbers section tests that parsing separately.
"""
import hashlib
import json
import math
import struct


def bits(value):
    return '0x%016x' % struct.unpack('>Q', struct.pack('>d', value))[0]


class Writer:
    def __init__(self, domain):
        self.data = bytearray(domain.encode('utf-8') + b'\x00')

    def double(self, value):
        if not math.isfinite(value):
            raise ValueError('non-finite')
        self.data += struct.pack('>d', value)  # binary64, most significant byte first; keeps -0.0

    def int64(self, value):
        self.data += struct.pack('>q', value)

    def boolean(self, value):
        self.data += b'\x01' if value else b'\x00'

    def string(self, value):
        encoded = value.encode('utf-8')  # stored bytes as given; no Unicode normalization
        self.data += struct.pack('>I', len(encoded)) + encoded

    def count(self, value):
        self.data += struct.pack('>I', value)

    def optional_int64(self, value):
        if value is None:
            self.data += b'\x00'
        else:
            self.data += b'\x01'
            self.int64(value)

    def digest(self):
        return hashlib.sha256(bytes(self.data)).hexdigest()


COVERAGE_DOUBLES = ['transmitterLatitude', 'transmitterLongitude', 'frequencyMHz', 'transmitHeightMeters',
                    'receiveHeightMeters', 'transmitPowerDbm', 'transmitGainDbi', 'receiveGainDbi',
                    'transmitLossDb', 'receiveLossDb', 'sensitivityDbm', 'reserveDb']
COVERAGE_INTS = ['radiusMeters', 'spacingMeters', 'minEastMeters', 'maxNorthMeters', 'columns', 'rows']
ENDPOINT = ['latitude', 'longitude', 'heightMeters', 'gainDbi', 'lossDb']


def coverage_digest(i):
    w = Writer('nodescope.coverage-inputs.v1')
    for name in COVERAGE_DOUBLES:
        w.double(i[name])
    for name in COVERAGE_INTS:
        w.int64(i[name])
    w.boolean(i['catalogComplete'])
    w.count(len(i['nodes']))
    for node in i['nodes']:
        w.string(node['sourceID'])
        w.string(node['nodeID'])
        w.double(node['latitude'])
        w.double(node['longitude'])
    return w.digest()


def link_digest(i):
    w = Writer('nodescope.link-profile-inputs.v1')
    for point in ('pointA', 'pointB'):
        for name in ENDPOINT:
            w.double(i[point][name])
    for name in ('frequencyMHz', 'transmitPowerDbm', 'sensitivityDbm', 'refractionK'):
        w.double(i[name])
    w.optional_int64(i['sampleCount'])
    return w.digest()


def as_bits(value):
    """Doubles become bit strings; everything else is kept."""
    if isinstance(value, float):
        return bits(value)
    if isinstance(value, dict):
        return {key: as_bits(item) for key, item in value.items()}
    if isinstance(value, list):
        return [as_bits(item) for item in value]
    return value


def coverage(**changes):
    base = dict(transmitterLatitude=29.4241, transmitterLongitude=-98.4936, frequencyMHz=910.525,
                transmitHeightMeters=6.096, receiveHeightMeters=1.524, transmitPowerDbm=22.0,
                transmitGainDbi=2.15, receiveGainDbi=2.15, transmitLossDb=1.0, receiveLossDb=1.0,
                sensitivityDbm=-128.0, reserveDb=5.0, radiusMeters=16093, spacingMeters=1609,
                minEastMeters=-16090, maxNorthMeters=16090, columns=21, rows=21, catalogComplete=False,
                nodes=[dict(sourceID='analyzer.meshtexas.org', nodeID='a1b2c3d4', latitude=29.5, longitude=-98.6),
                       dict(sourceID='analyzer.meshtexas.org', nodeID='e5f6a7b8', latitude=29.38, longitude=-98.41)])
    base.update(changes)
    return base


def link(**changes):
    base = dict(pointA=dict(latitude=30.2672, longitude=-97.7431, heightMeters=6.096, gainDbi=2.15, lossDb=1.0),
                pointB=dict(latitude=30.5083, longitude=-97.6789, heightMeters=10.0, gainDbi=5.8, lossDb=0.5),
                frequencyMHz=910.525, transmitPowerDbm=22.0, sensitivityDbm=-128.0, refractionK=4.0 / 3.0,
                sampleCount=None)
    base.update(changes)
    return base


def main():
    base_nodes = coverage()['nodes']
    coverage_cases = [
        ('basic', coverage()),
        ('catalog-complete', coverage(catalogComplete=True)),
        ('positive-zero-longitude', coverage(transmitterLongitude=0.0)),
        ('negative-zero-longitude', coverage(transmitterLongitude=-0.0)),
        ('fractional-heights', coverage(transmitHeightMeters=3.048, receiveHeightMeters=1.8288)),
        ('southern-eastern', coverage(transmitterLatitude=-33.8688, transmitterLongitude=151.2093,
                                      minEastMeters=-8000, maxNorthMeters=8000, radiusMeters=8000,
                                      spacingMeters=1000, columns=17, rows=17)),
        ('nodes-reordered', coverage(nodes=list(reversed(base_nodes)))),
        ('no-nodes', coverage(nodes=[])),
        ('non-ascii-identifiers', coverage(nodes=[dict(sourceID='mesh.example', nodeID='ノード-é', latitude=29.5,
                                                       longitude=-98.6)])),
    ]
    link_cases = [
        ('basic', link()),
        ('sample-count-50', link(sampleCount=50)),
        ('sample-count-absent-vs-zero-tag', link(sampleCount=0)),
        ('positive-zero-latitude', link(pointA=dict(link()['pointA'], latitude=0.0))),
        ('negative-zero-latitude', link(pointA=dict(link()['pointA'], latitude=-0.0))),
        ('southern-western', link(pointA=dict(latitude=-34.6037, longitude=-58.3816, heightMeters=1.5, gainDbi=0.0,
                                              lossDb=0.0),
                                  pointB=dict(latitude=-34.9011, longitude=-56.1645, heightMeters=30.48, gainDbi=8.5,
                                              lossDb=2.25))),
        ('endpoints-swapped', link(pointA=link()['pointB'], pointB=link()['pointA'])),
        ('refraction-one', link(refractionK=1.0)),
    ]


    def margin(budget, loss):
        receive = budget['transmitPowerDbm'] + budget['transmitGainDbi'] + budget['receiveGainDbi'] \
            - budget['transmitLossDb'] - budget['receiveLossDb']
        maximum = receive - budget['sensitivityDbm'] - budget['reserveDb']
        return receive - loss, maximum, maximum - loss


    budget_default = dict(transmitPowerDbm=22.0, transmitGainDbi=2.15, receiveGainDbi=2.15, transmitLossDb=1.0,
                          receiveLossDb=1.0, sensitivityDbm=-128.0, reserveDb=5.0)
    margin_cases = [
        ('default-budget', budget_default, 130.123456789),
        ('just-below-threshold', budget_default, 147.30000000000001),
        ('fractional-everything', dict(transmitPowerDbm=20.3, transmitGainDbi=0.1, receiveGainDbi=0.2,
                                       transmitLossDb=0.7, receiveLossDb=1.3, sensitivityDbm=-132.25,
                                       reserveDb=3.3), 141.98765432109876),
        ('eu-narrow', dict(transmitPowerDbm=14.0, transmitGainDbi=3.0, receiveGainDbi=2.0, transmitLossDb=1.0,
                           receiveLossDb=1.0, sensitivityDbm=-130.0, reserveDb=5.0), 99.99999999999999),
        ('large-loss', budget_default, 180.5),
    ]

    json_numbers = [0.1, 2.15, -98.4936, 32.808398950131235, 123.12345678901234, 1e-300, 5e-324,
                    1.7976931348623157e308, -0.0, 0.0, 1.0, 910.525, 4.0 / 3.0]

    document = {
        'format': 'nodescope.contract-vectors',
        'version': 1,
        'rules': 'saved-plans-v3-contract-gate.md: fingerprint wire contract and margin recomputation',
        'coverageInputs': [{'name': n, 'input': as_bits(i), 'sha256': coverage_digest(i)} for n, i in coverage_cases],
        'linkProfileInputs': [{'name': n, 'input': as_bits(i), 'sha256': link_digest(i)} for n, i in link_cases],
        'margins': [{'name': n, 'budget': as_bits(b), 'lossDb': bits(l),
                     'receiveLevelDbm': bits(margin(b, l)[0]), 'maximumLossDb': bits(margin(b, l)[1]),
                     'adjustedMarginDb': bits(margin(b, l)[2])} for n, b, l in margin_cases],
        # Each platform must parse `text` to `bits`, and its own JSON encode/decode must keep `bits`.
        'jsonNumbers': [{'text': repr(v), 'bits': bits(v)} for v in json_numbers],
    }
    digests = [c['sha256'] for c in document['coverageInputs']] + [c['sha256'] for c in document['linkProfileInputs']]
    assert len(set(digests)) == len(digests), 'every vector must have a distinct digest'
    print(json.dumps(document, indent=2, ensure_ascii=False))


if __name__ == '__main__':
    main()
