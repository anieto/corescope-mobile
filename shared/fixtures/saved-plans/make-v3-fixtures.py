#!/usr/bin/env python3
"""Saved-plans v3 reference library and cases (docs/node-planner/saved-plans-v3-schema.md).

Writes, next to this script:
  v3/reference/plans.json           a valid v3 manifest exercising every feature
  v3/reference/results/<sha>.result the immutable result files it references (one deliberately absent)
  v3/v3-cases.json                  valid and invalid manifests with the expected outcome

Every case is checked by the reference validator below before anything is written, so the
fixtures can't disagree with the rules. Result payload values are synthetic (not engine
output) but structurally valid against saved-plans-v3-result-fields.md, and their input
fingerprints use the contract-gate encoding from make-contract-vectors.py.

Run from the repository root: python3 -I shared/fixtures/saved-plans/make-v3-fixtures.py
"""
import copy
import hashlib
import importlib.util
import json
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('vectors', os.path.join(HERE, 'make-contract-vectors.py'))
vectors = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vectors)

UUID = re.compile(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
SHA = re.compile(r'^[0-9a-f]{64}$')
MAX_MS = 253_402_300_799_999
RESULT_FORMATS = {
    'coverage': {('nodescope.coverage-result', 1), ('legacy.ios-plist-coverage', 1), ('legacy.android-json-coverage', 1)},
    'linkProfile': {('nodescope.link-profile-result', 1)},
}
MODEL = {'sourceRevision': 'a911b6ecd58f8c16ac8ae4becf6bdbba1784e5a9',
         'scenario': 'P.1812-8; inland; zero clutter; vertical polarization; median atmosphere (45/325); '
                     'coast distances 500 km; time/location 50%; location variability 0 dB'}


# ---------------------------------------------------------------- reference validator

class Invalid(Exception):
    pass


def need(condition, reason):
    if not condition:
        raise Invalid(reason)


def keys(obj, required, optional=(), where=''):
    need(isinstance(obj, dict), f'{where} must be an object')
    unknown = set(obj) - set(required) - set(optional)
    need(not unknown, f'{where} has unknown fields {sorted(unknown)}')
    missing = set(required) - set(obj)
    need(not missing, f'{where} is missing {sorted(missing)}')


def utf8(text, limit, where):
    need(isinstance(text, str) and 0 < len(text.encode('utf-8')) <= limit, f'{where} must be 1-{limit} UTF-8 bytes')


def times(obj, where):
    created, updated = obj['createdAtMilliseconds'], obj['updatedAtMilliseconds']
    need(all(isinstance(v, int) and not isinstance(v, bool) and 0 <= v <= MAX_MS for v in (created, updated)),
         f'{where} timestamps must be epoch milliseconds')
    need(updated >= created, f'{where} updated before created')


def revision(value, where):
    need(isinstance(value, int) and not isinstance(value, bool) and value >= 1, f'{where} revision must be >= 1')


def validate(document):
    """Manifest rules. Returns nothing; raises Invalid. Payload contents are checked separately."""
    need(isinstance(document, dict), 'document must be an object')
    version = document.get('schemaVersion')
    need(version == 3, f'unsupported schemaVersion {version!r}: this reader handles 3 (and 1-2 via migration)')
    keys(document, ['schemaVersion', 'documentRevision', 'plans', 'studyPlans', 'studies'], where='document')
    revision(document['documentRevision'], 'documentRevision')
    plans, study_plans, studies = document['plans'], document['studyPlans'], document['studies']
    need(isinstance(plans, list) and len(plans) <= 100, 'plans: at most 100 candidate-site plans')
    need(isinstance(study_plans, list) and len(study_plans) <= 100, 'studyPlans: at most 100')
    need(isinstance(studies, list) and len(studies) <= 100, 'studies: at most 100')

    for plan in plans:  # Candidate Sites keep their v2 shape, plus a revision.
        keys(plan, ['id', 'name', 'createdAtMilliseconds', 'updatedAtMilliseconds', 'sites', 'revision'],
             ['selectedSiteID', 'notes'], 'candidate-site plan')
        revision(plan['revision'], 'candidate-site plan')
        need(UUID.match(plan['id']), 'candidate-site plan id must be a lowercase UUID')
        times(plan, 'candidate-site plan')
    need(len({p['id'] for p in plans}) == len(plans), 'duplicate candidate-site plan id')

    ids = set()
    for plan in study_plans:
        keys(plan, ['id', 'name', 'createdAtMilliseconds', 'updatedAtMilliseconds', 'revision'], ['notes'], 'study plan')
        need(UUID.match(plan['id']), 'study plan id must be a lowercase UUID')
        need(plan['id'] not in ids, 'duplicate study plan id')
        ids.add(plan['id'])
        utf8(plan['name'], 160, 'study plan name')
        if 'notes' in plan:
            need(isinstance(plan['notes'], str) and len(plan['notes'].encode('utf-8')) <= 4096,
                 'study plan notes: at most 4096 UTF-8 bytes')
        times(plan, 'study plan')
        revision(plan['revision'], 'study plan')

    study_ids = set()
    for study in studies:
        keys(study, ['id', 'name', 'createdAtMilliseconds', 'updatedAtMilliseconds', 'kind', 'revision'],
             ['planID', 'origin', 'importOrigin', 'resultRef', 'linkProfile', 'coverage'], 'study')
        need(UUID.match(study['id']), 'study id must be a lowercase UUID')
        need(study['id'] not in study_ids, 'duplicate study id')
        study_ids.add(study['id'])
        utf8(study['name'], 160, 'study name')
        times(study, 'study')
        revision(study['revision'], 'study')
        kind = study['kind']
        need(kind in ('linkProfile', 'coverage'), f'unknown study kind {kind!r}')
        need((kind == 'linkProfile') == ('linkProfile' in study) and (kind == 'coverage') == ('coverage' in study),
             'a study has exactly the payload its kind names')
        if kind == 'coverage':
            need('calculatedResult' not in study['coverage'], 'v3 coverage results live in resultRef files, not inline')
        if study.get('planID') is not None:
            need(study['planID'] in ids, 'planID must name an existing study plan')
        origin = study.get('origin')
        if origin is not None:
            keys(origin, ['host', 'region', 'capturedAtMilliseconds'], where='origin')
        imported = study.get('importOrigin')
        if imported is not None:
            keys(imported, ['studyID', 'revision'], where='importOrigin')
            need(UUID.match(imported['studyID']), 'importOrigin.studyID must be a lowercase UUID')
            revision(imported['revision'], 'importOrigin')
        ref = study.get('resultRef')
        if ref is not None:
            keys(ref, ['format', 'version', 'byteLength', 'sha256', 'inputFingerprint'], where='resultRef')
            need((ref['format'], ref['version']) in RESULT_FORMATS[kind], f'{ref["format"]} v{ref["version"]} is not a {kind} result')
            need(isinstance(ref['byteLength'], int) and 0 < ref['byteLength'] <= 8 * 1024 * 1024, 'resultRef.byteLength out of range')
            need(isinstance(ref['sha256'], str) and SHA.match(ref['sha256']), 'resultRef.sha256 must be 64 lowercase hex digits')
            portable = ref['format'].startswith('nodescope.')
            need(portable == (ref['inputFingerprint'] is not None), 'portable results carry inputFingerprint; legacy results carry null')
            if portable:
                need(SHA.match(ref['inputFingerprint']), 'resultRef.inputFingerprint must be 64 lowercase hex digits')


def study_fingerprint(study):
    """The input fingerprint of a study's saved inputs (contract-gate encoding)."""
    if study['kind'] == 'linkProfile':
        i = study['linkProfile']
        flat = {'pointA': endpoint_values(i['pointA']), 'pointB': endpoint_values(i['pointB']),
                'frequencyMHz': i['frequencyMHz'], 'transmitPowerDbm': i['transmitPowerDbm'],
                'sensitivityDbm': i['sensitivityDbm'], 'refractionK': i['refractionK'],
                'sampleCount': i.get('sampleCount')}
        return vectors.link_digest(flat)
    return vectors.coverage_digest(coverage_inputs(study['coverage']))


def endpoint_values(point):
    a = point['antenna']
    return dict(latitude=point['latitude'], longitude=point['longitude'], heightMeters=a['heightMeters'],
                gainDbi=a['gainDbi'], lossDb=a['lossDb'])


def grid(radius, spacing):
    extent = (radius // spacing) * spacing  # CoverageGrid.studyArea on both platforms
    count = 2 * extent // spacing + 1
    return dict(radiusMeters=radius, spacingMeters=spacing, minEastMeters=-extent, maxNorthMeters=extent,
                columns=count, rows=count)


def coverage_inputs(c):
    t, r = c['transmitter'], c['receiver']
    return dict(transmitterLatitude=t['latitude'], transmitterLongitude=t['longitude'], frequencyMHz=c['frequencyMHz'],
                transmitHeightMeters=t['antenna']['heightMeters'], receiveHeightMeters=r['heightMeters'],
                transmitPowerDbm=c['transmitPowerDbm'], transmitGainDbi=t['antenna']['gainDbi'],
                receiveGainDbi=r['gainDbi'], transmitLossDb=t['antenna']['lossDb'], receiveLossDb=r['lossDb'],
                sensitivityDbm=c['sensitivityDbm'], reserveDb=c['reserveDb'],
                **grid(c['radiusMeters'], c['spacingMeters']), catalogComplete=c['catalogComplete'],
                nodes=[dict(sourceID=n['sourceID'], nodeID=n['nodeID'], latitude=n['latitude'], longitude=n['longitude'])
                       for n in c['nodes']])


def check_payload(study, data):
    """Structural checks a reader makes before trusting a portable payload for this study."""
    payload = json.loads(data.decode('utf-8'))
    ref = study['resultRef']
    need(payload['format'] == ref['format'] and payload['version'] == ref['version'], 'payload format/version must match its reference')
    need(payload['inputFingerprint'] == ref['inputFingerprint'] == study_fingerprint(study), 'payload fingerprint must match the study inputs')
    if study['kind'] == 'coverage':
        inputs = coverage_inputs(study['coverage'])
        g = payload['inputs']['grid']
        cells = g['columns'] * g['rows']
        need(len(payload['area']['stateCodes']) == len(payload['area']['lossesDb']) == cells, 'area columns must match the grid')
        need(all((s == 0) == (l is not None) for s, l in zip(payload['area']['stateCodes'], payload['area']['lossesDb'])),
             'a loss exactly where the state is qualified')
        need([(n['sourceID'], n['nodeID']) for n in payload['nodes']] ==
             [(n['sourceID'], n['nodeID']) for n in inputs['nodes']], 'nodes must follow the captured catalog')
    else:
        samples = payload['terrain']['samples']
        need(len(samples) == payload['resolvedSampleCount'], 'sample count must equal resolvedSampleCount')
        need(samples[-1]['distanceMetersFromA'] == payload['result']['distanceMeters'], 'last distance must equal the path distance')


# ---------------------------------------------------------------- reference library

def haversine(a, b):
    lat_a, lat_b = math.radians(a[0]), math.radians(b[0])
    h = math.sin((lat_b - lat_a) / 2) ** 2 + math.cos(lat_a) * math.cos(lat_b) * math.sin(math.radians(b[1] - a[1]) / 2) ** 2
    return 2 * 6_371_000.0 * math.atan2(math.sqrt(min(max(h, 0), 1)), math.sqrt(max(1 - h, 0)))


def coverage_study(study_id, name, plan_id=None, origin=None, transmitter_name='Ridge relay'):
    study = dict(id=study_id, name=name, createdAtMilliseconds=1794700000000, updatedAtMilliseconds=1794780000000,
                 kind='coverage', revision=2, coverage=dict(
                     transmitter=dict(name=transmitter_name, latitude=29.4241, longitude=-98.4936,
                                      antenna=dict(heightMeters=6.096, gainDbi=2.15, lossDb=1.0)),
                     receiver=dict(heightMeters=1.524, gainDbi=2.15, lossDb=1.0),
                     frequencyMHz=910.525, transmitPowerDbm=22.0, sensitivityDbm=-128.0, reserveDb=5.0,
                     radiusMeters=1000, spacingMeters=1000, catalogComplete=False,
                     nodes=[dict(sourceID='analyzer.example', nodeID='a1b2c3d4', name='Hilltop', role='repeater',
                                 latitude=29.4282, longitude=-98.4936, lastSeenMilliseconds=1794770000000),
                            dict(sourceID='analyzer.example', nodeID='e5f6a7b8', name='Far repeater', role='repeater',
                                 latitude=29.5, longitude=-98.4, lastSeenMilliseconds=1794760000000)]))
    if plan_id:
        study['planID'] = plan_id
    study['origin'] = origin
    return study


def coverage_payload(study):
    c = study['coverage']
    inputs = coverage_inputs(c)
    g = grid(c['radiusMeters'], c['spacingMeters'])
    # 3x3, north-west first: corners outside the radius (2), centre near the site (1), edges evaluated.
    states = [2, 0, 2, 0, 1, 5, 2, 0, 2]
    losses = [None, 121.25, None, 118.0, None, None, None, 126.75, None]
    doubles = {k: inputs[k] for k in vectors.COVERAGE_DOUBLES}
    payload = {
        'format': 'nodescope.coverage-result', 'version': 1, 'calculatedAtMilliseconds': 1794779000000,
        'inputFingerprint': study_fingerprint(study),
        'inputs': dict(doubles, grid=g, catalogComplete=c['catalogComplete']),
        'limits': dict(areaSamples=120_000_000, areaGeometry=120_000_000, nodeSamples=30_000_000,
                       nodeGeometry=30_000_000, nodeModelCalls=1000),
        'model': MODEL,
        'terrain': [dict(dataset='mapzen-skadi', revision='direct-v1', tile='N29W099', compressedSHA256=None,
                         packedBytes=1_234_567, side=3601,
                         url='https://elevation-tiles-prod.s3.amazonaws.com/v2/skadi/N29/N29W099.hgt.gz',
                         verticalDatum='EGM96',
                         attribution='Mapzen Terrain Tiles; source attribution: https://github.com/tilezen/joerd/blob/master/docs/attribution.md',
                         sourceType='mixed-source elevation; surface/bare-earth varies',
                         originURL='https://elevation-tiles-prod.s3.amazonaws.com/v2/skadi/N29/N29W099.hgt.gz',
                         sourceETag='"0123456789abcdef0123456789abcdef"')],
        'area': dict(stateCodes=states, lossesDb=losses, chargedSamples=48_211, chargedGeometry=96_422, workLimited=False),
        'nodes': [dict(sourceID='analyzer.example', nodeID='a1b2c3d4', latitude=29.4282, longitude=-98.4936,
                       distanceMeters=456.0, prediction=dict(kind='qualifiedMargin', marginDb=18.5)),
                  dict(sourceID='analyzer.example', nodeID='e5f6a7b8', latitude=29.5, longitude=-98.4,
                       distanceMeters=12_480.0, prediction=dict(kind='unavailable', reason='outsideRadius'))],
    }
    return payload


def legacy_android_payload(study):
    """The bytes Android 0.10.6 stores (base64-decoded): migration keeps them as they are."""
    c = study['coverage']
    i = coverage_inputs(c)
    g = grid(c['radiusMeters'], c['spacingMeters'])
    values = [i[k] for k in vectors.COVERAGE_DOUBLES] + [float(g[k]) for k in vectors.COVERAGE_INTS]
    receive = c['transmitPowerDbm'] + i['transmitGainDbi'] + i['receiveGainDbi'] - i['transmitLossDb'] - i['receiveLossDb']
    maximum = receive - c['sensitivityDbm'] - c['reserveDb']
    cells = []
    for state, loss in zip(['outsideRadius', 'qualified', 'outsideRadius', 'qualified', 'nearSite', 'notComputed',
                            'outsideRadius', 'qualified', 'outsideRadius'],
                           [None, 121.25, None, 118.0, None, None, None, 126.75, None]):
        cells.append({'state': state} if loss is None else {'state': state, 'lossDb': loss, 'marginDb': maximum - loss})
    return {'version': 1, 'format': 'nodescope-android-json', 'inputValues': values, 'cells': cells,
            'chargedSamples': 30_100, 'chargedGeometry': 60_200, 'workLimited': True,
            'limits': [120_000_000, 120_000_000, 30_000_000, 30_000_000, 1000],
            'modelRevision': MODEL['sourceRevision'], 'modelScenario': MODEL['scenario'],
            'nodes': [{'sourceID': n['sourceID'], 'nodeID': n['nodeID'], 'latitude': n['latitude'],
                       'longitude': n['longitude'], 'distanceMeters': d, **extra}
                      for n, d, extra in zip(c['nodes'], [456.0, 12_480.0],
                                             [{'reason': 'processingLimit'}, {'reason': 'outsideRadius'}])]}


def link_study(study_id, name, plan_id=None, origin=None, sample_count=20):
    study = dict(id=study_id, name=name, createdAtMilliseconds=1794600000000, updatedAtMilliseconds=1794610000000,
                 kind='linkProfile', revision=1, linkProfile=dict(
                     pointA=dict(name='Ridge relay', latitude=29.4241, longitude=-98.4936,
                                 antenna=dict(heightMeters=6.096, gainDbi=2.15, lossDb=1.0)),
                     pointB=dict(name='Valley home', latitude=29.4384, longitude=-98.4721,
                                 antenna=dict(heightMeters=3.048, gainDbi=5.8, lossDb=0.5)),
                     frequencyMHz=910.525, transmitPowerDbm=22.0, sensitivityDbm=-128.0,
                     refractionK=4.0 / 3.0, sampleCount=sample_count))
    if plan_id:
        study['planID'] = plan_id
    study['origin'] = origin
    return study


def link_payload(study):
    lp = study['linkProfile']
    a = (lp['pointA']['latitude'], lp['pointA']['longitude'])
    b = (lp['pointB']['latitude'], lp['pointB']['longitude'])
    count = lp['sampleCount']
    points = [(a[0] + f * (b[0] - a[0]), a[1] + f * (b[1] - a[1])) for f in (k / (count - 1) for k in range(count))]
    samples, distance = [], 0.0
    for k, point in enumerate(points):
        if k:
            distance += haversine(points[k - 1], point)
        samples.append(dict(latitude=point[0], longitude=point[1], distanceMetersFromA=distance,
                            elevationMeters=round(210 + 18 * math.sin(k / 3), 1)))
    return {
        'format': 'nodescope.link-profile-result', 'version': 1, 'calculatedAtMilliseconds': 1794609000000,
        'inputFingerprint': study_fingerprint(study),
        'engine': dict(id='meshcore-one-los-compatible', version=1),
        'inputs': dict(pointA=endpoint_values(lp['pointA']), pointB=endpoint_values(lp['pointB']),
                       frequencyMHz=lp['frequencyMHz'], transmitPowerDbm=lp['transmitPowerDbm'],
                       sensitivityDbm=lp['sensitivityDbm'], refractionK=lp['refractionK'], sampleCount=lp['sampleCount']),
        'resolvedSampleCount': count,
        'terrain': dict(samples=samples, provenance=dict(provider='open-meteo', dataset='copernicus-dem-2021-glo-90',
                                                         nominalResolutionMeters=90, fetchedAtMilliseconds=1794608000000,
                                                         origin='mixed')),
        'result': dict(distanceMeters=distance, freeSpacePathLossDb=98.7, peakDiffractionLossDb=4.2,
                       totalPathLossDb=102.9, worstClearancePercent=72.5, clearanceStatus='marginal',
                       obstructions=[dict(distanceFromAMeters=1180.0, obstructionHeightMeters=231.4,
                                          fresnelClearancePercent=72.5)],
                       receivePowerDbm=-71.8, linkMarginDb=56.2),
        'bearingDegrees': 51.3,
    }


def encoded(payload):
    return json.dumps(payload, separators=(',', ':'), ensure_ascii=False, allow_nan=False).encode('utf-8')


def reference_ref(fmt, data, fingerprint):
    return dict(format=fmt, version=1, byteLength=len(data), sha256=hashlib.sha256(data).hexdigest(),
                inputFingerprint=fingerprint)


def build_reference():
    plan = '30000000-0000-4000-8000-000000000001'
    empty_plan = '30000000-0000-4000-8000-000000000002'
    meshtexas = dict(host='analyzer.example', region='SAT', capturedAtMilliseconds=1794700000000)
    files = {}

    covered = coverage_study('40000000-0000-4000-8000-000000000001', 'Ridge relay coverage', plan, meshtexas)
    data = encoded(coverage_payload(covered))
    covered['resultRef'] = reference_ref('nodescope.coverage-result', data, study_fingerprint(covered))
    files[covered['resultRef']['sha256']] = data

    link = link_study('40000000-0000-4000-8000-000000000002', 'Ridge to valley', plan, meshtexas)
    data = encoded(link_payload(link))
    link['resultRef'] = reference_ref('nodescope.link-profile-result', data, study_fingerprint(link))
    files[link['resultRef']['sha256']] = data

    # Migrated from v2: Android's legacy bytes, kept as they were. Unknown source: never guessed.
    legacy = coverage_study('40000000-0000-4000-8000-000000000003', 'Coverage saved on 0.10.6', None, None)
    legacy['revision'] = 1
    data = encoded(legacy_android_payload(legacy))
    legacy['resultRef'] = reference_ref('legacy.android-json-coverage', data, None)
    files[legacy['resultRef']['sha256']] = data

    inputs_only = link_study('40000000-0000-4000-8000-000000000004', 'Inputs only', None, None, sample_count=None)

    # Restored from Android cloud backup: the manifest names a result that isn't on this device.
    restored = coverage_study('40000000-0000-4000-8000-000000000005', 'Restored without results', None, meshtexas)
    restored['coverage']['reserveDb'] = 10.0
    missing = encoded(coverage_payload(restored))
    restored['resultRef'] = reference_ref('nodescope.coverage-result', missing, study_fingerprint(restored))

    # An independent imported copy: same inputs, so it shares the immutable result file by content.
    copy_of = copy.deepcopy(covered)
    copy_of.update(id='40000000-0000-4000-8000-000000000006', name='Ridge relay coverage (copy)', revision=1,
                   createdAtMilliseconds=1794785000000, updatedAtMilliseconds=1794785000000,
                   importOrigin=dict(studyID=covered['id'], revision=covered['revision']))
    copy_of.pop('planID')

    document = {
        'schemaVersion': 3, 'documentRevision': 12,
        'plans': [dict(id='11111111-1111-4111-8111-111111111111', name='West-side options',
                       createdAtMilliseconds=1793448000000, updatedAtMilliseconds=1793448000000, revision=1,
                       selectedSiteID='22222222-2222-4222-8222-222222222222',
                       sites=[dict(id='22222222-2222-4222-8222-222222222222', name='Ridge – Café',
                                   latitude=29.43212, longitude=-98.61774, assumptions={})])],
        'studyPlans': [dict(id=plan, name='Ridge relay site', notes='Compare the roof mount with a 10 m mast.',
                            createdAtMilliseconds=1794600000000, updatedAtMilliseconds=1794780000000, revision=4),
                       dict(id=empty_plan, name='Downtown (empty)', createdAtMilliseconds=1794500000000,
                            updatedAtMilliseconds=1794500000000, revision=1)],
        'studies': [covered, link, legacy, inputs_only, restored, copy_of],
    }
    return document, files, [restored['id']]


# ---------------------------------------------------------------- cases

def cases(reference):
    def variant(name, change, reason=None):
        document = copy.deepcopy(reference)
        change(document)
        return dict(name=name, valid=reason is None, reason=reason, document=document)

    def set_study(index, **fields):
        return lambda d: d['studies'][index].update(fields)

    out = [dict(name='reference_library', valid=True, reason=None, document=copy.deepcopy(reference)),
           variant('empty_library', lambda d: d.update(plans=[], studyPlans=[], studies=[])),
           variant('study_plan_without_notes', lambda d: d['studyPlans'][0].pop('notes')),
           variant('future_schema_version', lambda d: d.update(schemaVersion=4), 'unsupported schemaVersion'),
           variant('v2_document_is_not_v3', lambda d: d.update(schemaVersion=2), 'unsupported schemaVersion'),
           variant('missing_document_revision', lambda d: d.pop('documentRevision'), 'missing'),
           variant('zero_document_revision', lambda d: d.update(documentRevision=0), 'revision must be >= 1'),
           variant('dangling_plan_membership', set_study(0, planID='30000000-0000-4000-8000-0000000000ff'), 'existing study plan'),
           variant('duplicate_study_plan', lambda d: d['studyPlans'].append(copy.deepcopy(d['studyPlans'][0])), 'duplicate study plan id'),
           variant('duplicate_study', lambda d: d['studies'].append(copy.deepcopy(d['studies'][0])), 'duplicate study id'),
           variant('uppercase_study_id', set_study(0, id='40000000-0000-4000-8000-00000000000A'), 'lowercase UUID'),
           variant('base_revision_is_not_v3', set_study(0, baseRevision=1), 'unknown fields'),
           variant('inline_result_is_not_v3', lambda d: d['studies'][2]['coverage'].update(calculatedResult='e30='),
                   'live in resultRef files'),
           variant('unknown_study_plan_field', lambda d: d['studyPlans'][0].update(color='blue'), 'unknown fields'),
           variant('notes_too_long', lambda d: d['studyPlans'][0].update(notes='x' * 4097), 'at most 4096'),
           variant('notes_at_limit', lambda d: d['studyPlans'][0].update(notes='é' * 2048)),
           variant('name_too_long', lambda d: d['studyPlans'][0].update(name='x' * 161), '1-160 UTF-8 bytes'),
           variant('too_many_study_plans', lambda d: d['studyPlans'].extend(
               dict(d['studyPlans'][1], id='31000000-0000-4000-8000-%012d' % k) for k in range(99)), 'at most 100'),
           variant('plan_updated_before_created', lambda d: d['studyPlans'][0].update(updatedAtMilliseconds=1), 'updated before created'),
           variant('candidate_site_plan_needs_revision', lambda d: d['plans'][0].pop('revision'), 'missing'),
           variant('link_study_with_coverage_result', lambda d: d['studies'][1]['resultRef'].update(format='nodescope.coverage-result'),
                   'is not a linkProfile result'),
           variant('unknown_result_format', lambda d: d['studies'][0]['resultRef'].update(format='nodescope.coverage-result', version=2),
                   'is not a coverage result'),
           variant('uppercase_result_hash', lambda d: d['studies'][0]['resultRef'].update(
               sha256=d['studies'][0]['resultRef']['sha256'].upper()), '64 lowercase hex'),
           variant('legacy_result_with_fingerprint', lambda d: d['studies'][2]['resultRef'].update(
               inputFingerprint=d['studies'][0]['resultRef']['inputFingerprint']), 'legacy results carry null'),
           variant('portable_result_without_fingerprint', lambda d: d['studies'][0]['resultRef'].update(inputFingerprint=None),
                   'portable results carry inputFingerprint'),
           variant('incomplete_origin', set_study(0, origin={'host': 'analyzer.example'}), 'missing'),
           variant('unknown_origin_is_valid', set_study(0, origin=None)),
           variant('import_origin_needs_revision', set_study(5, importOrigin={'studyID': '40000000-0000-4000-8000-000000000001'}), 'missing'),
           variant('kind_and_payload_disagree', lambda d: d['studies'][3].update(kind='coverage'), 'exactly the payload'),
           ]
    return out


def main():
    reference, files, missing = build_reference()
    validate(reference)
    by_id = {s['id']: s for s in reference['studies']}
    for study in reference['studies']:
        ref = study.get('resultRef')
        if ref and ref['sha256'] in files:
            need(hashlib.sha256(files[ref['sha256']]).hexdigest() == ref['sha256'] and len(files[ref['sha256']]) == ref['byteLength'],
                 'reference file bytes must match their reference')
            if ref['format'].startswith('nodescope.'):
                check_payload(study, files[ref['sha256']])
    need(all(by_id[m]['resultRef']['sha256'] not in files for m in missing), 'the missing result must really be absent')

    results = []
    for case in cases(reference):
        try:
            validate(case['document'])
            outcome = None
        except Invalid as error:
            outcome = str(error)
        if case['valid']:
            need(outcome is None, f"{case['name']} should be valid: {outcome}")
        else:
            need(outcome is not None and case['reason'] in outcome, f"{case['name']} should fail with {case['reason']!r}, got {outcome!r}")
        results.append(case)

    out = os.path.join(HERE, 'v3')
    results_dir = os.path.join(out, 'reference', 'results')
    os.makedirs(results_dir, exist_ok=True)
    for name in os.listdir(results_dir):
        os.remove(os.path.join(results_dir, name))
    for sha, data in files.items():
        with open(os.path.join(results_dir, sha + '.result'), 'wb') as handle:
            handle.write(data)
    with open(os.path.join(out, 'reference', 'plans.json'), 'w', encoding='utf-8') as handle:
        json.dump(reference, handle, indent=2, ensure_ascii=False)
        handle.write('\n')
    expectations = {
        'missingResults': missing,
        'sharedResult': [reference['studies'][0]['id'], reference['studies'][5]['id']],
        'legacyResults': [reference['studies'][2]['id']],
        'inputsOnly': [reference['studies'][3]['id']],
        'unknownOrigin': [s['id'] for s in reference['studies'] if s.get('origin') is None],
    }
    with open(os.path.join(out, 'v3-cases.json'), 'w', encoding='utf-8') as handle:
        json.dump({'format': 'nodescope.saved-plans-v3-cases', 'version': 1, 'reference': expectations,
                   'cases': results}, handle, indent=2, ensure_ascii=False)
        handle.write('\n')
    valid = sum(c['valid'] for c in results)
    print(f'{len(results)} cases ({valid} valid, {len(results) - valid} invalid); '
          f'{len(files)} result files; {len(missing)} deliberately missing')


if __name__ == '__main__':
    main()
