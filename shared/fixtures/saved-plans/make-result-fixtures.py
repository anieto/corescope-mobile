#!/usr/bin/env python3
"""Portable result payload cases (docs/node-planner/saved-plans-v3-result-fields.md), deliverable B.

Writes results/result-cases-v1.json next to this script: valid and invalid
nodescope.coverage-result and nodescope.link-profile-result payloads, each with the study whose
inputs it must match and, when invalid, the reason a reader must refuse it.

Every case is checked by the reference validator below before anything is written. Payload values
are synthetic, not engine output; they follow the frozen field lists and carry correct input
fingerprints (contract-gate encoding, make-contract-vectors.py). The base studies and payloads are
the v3 reference library's (make-v3-fixtures.py).

Not covered here, because they depend on each platform's own code: CoverageTerrainDescriptor's full
validation (only its main rules are checked), the near-site and outside-radius masks (the base grid
has them right; cases only change cells the masks allow), and JSON number round-tripping (the
contract vectors' jsonNumbers cases).

Run from the repository root: python3 -I shared/fixtures/saved-plans/make-result-fixtures.py
"""
import copy
import importlib.util
import json
import math
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, file))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v3 = load('v3', 'make-v3-fixtures.py')
vectors = v3.vectors
Invalid, need, keys = v3.Invalid, v3.need, v3.keys
SHA = re.compile(r'^[0-9a-f]{64}$')
DATASET = re.compile(r'^[A-Za-z0-9_.-]{1,48}$')
MAX_MS = v3.MAX_MS
MAX_INT64 = 2 ** 63 - 1
REASONS = ['outsideRadius', 'invalidInput', 'missingTerrain', 'unsupportedPath', 'accuracyUnresolved',
           'thresholdUnresolved', 'processingLimit', 'pathWorkLimit']
STATUSES = ['clear', 'marginal', 'partialObstruction', 'blocked']
# First and last samples may differ from the endpoints by this much (degrees), since the samplers
# interpolate; longitudes compare modulo 360. About 11 cm.
ENDPOINT_TOLERANCE = 1e-6


# ---------------------------------------------------------------- reference validator

def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def integer(value, low, high):
    return isinstance(value, int) and not isinstance(value, bool) and low <= value <= high


def numbers(obj, names, where):
    need(all(number(obj[name]) for name in names), f'{where} values must be finite numbers')


def timestamp(value, nullable, where):
    need((nullable and value is None) or integer(value, 0, MAX_MS), f'{where} out of range')


def header(payload, fields, kind):
    keys(payload, fields, where='payload')
    need(payload['format'] == kind, f'format must be {kind}')
    need(integer(payload['version'], 1, 1), 'unsupported payload version')
    need(isinstance(payload['inputFingerprint'], str) and SHA.match(payload['inputFingerprint']) is not None,
         'inputFingerprint must be 64 lowercase hex digits')


def coordinate(latitude, longitude):
    return number(latitude) and number(longitude) and -90 <= latitude <= 90 and -180 <= longitude <= 180


def validate_coverage(study, p):
    header(p, ['format', 'version', 'calculatedAtMilliseconds', 'inputFingerprint', 'inputs', 'limits', 'model',
               'terrain', 'area', 'nodes'], 'nodescope.coverage-result')
    timestamp(p['calculatedAtMilliseconds'], True, 'calculatedAtMilliseconds')

    inputs = p['inputs']
    keys(inputs, vectors.COVERAGE_DOUBLES + ['grid', 'catalogComplete'], where='inputs')
    numbers(inputs, vectors.COVERAGE_DOUBLES, 'inputs')
    need(isinstance(inputs['catalogComplete'], bool), 'catalogComplete must be a boolean')
    grid = inputs['grid']
    keys(grid, vectors.COVERAGE_INTS, where='grid')
    need(all(integer(grid[name], -MAX_INT64, MAX_INT64) for name in vectors.COVERAGE_INTS), 'grid values must be integers')
    need(grid['radiusMeters'] > 0 and grid['spacingMeters'] > 0 and grid == v3.grid(grid['radiusMeters'], grid['spacingMeters']),
         'grid must follow its radius and spacing')
    cells = grid['columns'] * grid['rows']
    need(cells <= 60_000, 'grid has more than 60,000 cells')

    limits = p['limits']
    names = ['areaSamples', 'areaGeometry', 'nodeSamples', 'nodeGeometry', 'nodeModelCalls']
    keys(limits, names, where='limits')
    need(all(integer(limits[name], 0, 150_000_000) for name in names[:4]) and integer(limits['nodeModelCalls'], 0, 50_000)
         and limits['areaSamples'] + limits['nodeSamples'] <= 150_000_000
         and limits['areaGeometry'] + limits['nodeGeometry'] <= 150_000_000, 'limits out of range')

    if p['model'] is not None:
        keys(p['model'], ['sourceRevision', 'scenario'], where='model')
        need(all(isinstance(p['model'][k], str) and p['model'][k] for k in ('sourceRevision', 'scenario')),
             'model fields must be non-empty strings')
    if p['terrain'] is not None:
        need(isinstance(p['terrain'], list) and len(p['terrain']) <= 64, 'at most 64 terrain descriptors')
        for t in p['terrain']:
            keys(t, ['dataset', 'revision', 'tile', 'url', 'verticalDatum', 'attribution', 'sourceType', 'originURL',
                     'compressedSHA256', 'sourceETag', 'packedBytes', 'side'], where='terrain descriptor')
            need(all(isinstance(t[k], str) for k in ('dataset', 'revision', 'tile', 'url', 'verticalDatum', 'attribution',
                                                    'sourceType', 'originURL'))
                 and DATASET.match(t['dataset']) is not None and t['url'].startswith('https://')
                 # Exactly one identity: a content hash, or an S3 ETag whose origin is the download URL.
                 and ((isinstance(t['compressedSHA256'], str) and SHA.match(t['compressedSHA256']) is not None and t['sourceETag'] is None)
                      or (t['compressedSHA256'] is None and isinstance(t['sourceETag'], str)
                          and re.fullmatch(r'"[A-Za-z0-9-]{1,128}"', t['sourceETag']) is not None
                          and t['url'].startswith('https://elevation-tiles-prod.s3.amazonaws.com/') and t['originURL'] == t['url']))
                 and integer(t['packedBytes'], 1, 40_000_000) and integer(t['side'], 2, 3601), 'invalid terrain descriptor')

    area = p['area']
    keys(area, ['stateCodes', 'lossesDb', 'chargedSamples', 'chargedGeometry', 'workLimited'], where='area')
    need(isinstance(area['stateCodes'], list) and isinstance(area['lossesDb'], list)
         and len(area['stateCodes']) == len(area['lossesDb']) == cells, 'area must cover the grid')
    need(all(integer(state, 0, 7) for state in area['stateCodes']), 'unknown cell state code')
    need(all((state == 0) == (loss is not None) and (loss is None or number(loss))
             for state, loss in zip(area['stateCodes'], area['lossesDb'])), 'a loss exactly where the state is qualified')
    need(integer(area['chargedSamples'], 0, MAX_INT64) and integer(area['chargedGeometry'], 0, MAX_INT64),
         'charged work must be non-negative integers')
    need(isinstance(area['workLimited'], bool), 'workLimited must be a boolean')

    nodes = p['nodes']
    need(isinstance(nodes, list) and len(nodes) <= 200, 'at most 200 nodes')
    for node in nodes:
        keys(node, ['sourceID', 'nodeID', 'latitude', 'longitude', 'distanceMeters', 'prediction'], where='node')
        need(isinstance(node['sourceID'], str) and isinstance(node['nodeID'], str), 'node identities must be strings')
        need(coordinate(node['latitude'], node['longitude']), 'node coordinates must be valid')
        need(number(node['distanceMeters']) and node['distanceMeters'] >= 0, 'node distance must be non-negative')
        prediction = node['prediction']
        need(isinstance(prediction, dict) and prediction.get('kind') in ('qualifiedMargin', 'unavailable'), 'unknown prediction kind')
        if prediction['kind'] == 'qualifiedMargin':
            keys(prediction, ['kind', 'marginDb'], where='prediction')
            need(number(prediction['marginDb']), 'marginDb must be a finite number')
        else:
            keys(prediction, ['kind', 'reason'], where='prediction')
            need(prediction['reason'] in REASONS, 'unknown unavailable reason')
    identities = [(n['sourceID'], n['nodeID']) for n in nodes]
    need(len(set(identities)) == len(identities), 'duplicate node')

    catalog = study['coverage']['nodes']
    need([(n['sourceID'], n['nodeID'], n['latitude'], n['longitude']) for n in nodes]
         == [(n['sourceID'], n['nodeID'], n['latitude'], n['longitude']) for n in catalog],
         'nodes must follow the captured catalog')
    own = dict({k: inputs[k] for k in vectors.COVERAGE_DOUBLES}, **grid, catalogComplete=inputs['catalogComplete'],
               nodes=[{k: n[k] for k in ('sourceID', 'nodeID', 'latitude', 'longitude')} for n in nodes])
    need(vectors.coverage_digest(own) == p['inputFingerprint'], 'payload fingerprint must match its inputs')
    need(v3.study_fingerprint(study) == p['inputFingerprint'], 'payload fingerprint must match the study inputs')


def validate_link(study, p):
    header(p, ['format', 'version', 'calculatedAtMilliseconds', 'inputFingerprint', 'engine', 'inputs',
               'resolvedSampleCount', 'terrain', 'result', 'bearingDegrees'], 'nodescope.link-profile-result')
    timestamp(p['calculatedAtMilliseconds'], False, 'calculatedAtMilliseconds')
    keys(p['engine'], ['id', 'version'], where='engine')
    need(p['engine']['id'] == 'meshcore-one-los-compatible' and integer(p['engine']['version'], 1, 1), 'unknown engine')

    inputs = p['inputs']
    keys(inputs, ['pointA', 'pointB', 'frequencyMHz', 'transmitPowerDbm', 'sensitivityDbm', 'refractionK', 'sampleCount'],
         where='inputs')
    for name in ('pointA', 'pointB'):
        keys(inputs[name], vectors.ENDPOINT, where=name)
        numbers(inputs[name], vectors.ENDPOINT, name)
    numbers(inputs, ['frequencyMHz', 'transmitPowerDbm', 'sensitivityDbm', 'refractionK'], 'inputs')
    requested = inputs['sampleCount']
    need(requested is None or integer(requested, 2, 100), 'sampleCount must be null or 2 to 100')
    resolved = p['resolvedSampleCount']
    need(integer(resolved, 2, 100), 'resolvedSampleCount must be 2 to 100')
    need(requested is None or resolved == requested, 'resolvedSampleCount must equal the requested sampleCount')

    terrain = p['terrain']
    keys(terrain, ['samples', 'provenance'], where='terrain')
    provenance = terrain['provenance']
    keys(provenance, ['provider', 'dataset', 'nominalResolutionMeters', 'fetchedAtMilliseconds', 'origin'], where='provenance')
    need(provenance['provider'] == 'open-meteo' and provenance['dataset'] == 'copernicus-dem-2021-glo-90'
         and integer(provenance['nominalResolutionMeters'], 90, 90) and provenance['origin'] in ('network', 'cache', 'mixed'),
         'unknown terrain provenance')
    timestamp(provenance['fetchedAtMilliseconds'], False, 'fetchedAtMilliseconds')
    samples = terrain['samples']
    need(isinstance(samples, list) and len(samples) == resolved, 'sample count must equal resolvedSampleCount')
    for sample in samples:
        keys(sample, ['latitude', 'longitude', 'distanceMetersFromA', 'elevationMeters'], where='sample')
        numbers(sample, ['latitude', 'longitude', 'distanceMetersFromA', 'elevationMeters'], 'sample')
        need(coordinate(sample['latitude'], sample['longitude']), 'sample coordinates must be valid')
    distances = [s['distanceMetersFromA'] for s in samples]
    need(distances[0] == 0, 'the first sample must be at distance 0')
    need(all(b > a for a, b in zip(distances, distances[1:])), 'sample distances must increase')

    result = p['result']
    keys(result, ['distanceMeters', 'freeSpacePathLossDb', 'peakDiffractionLossDb', 'totalPathLossDb',
                  'worstClearancePercent', 'clearanceStatus', 'obstructions', 'receivePowerDbm', 'linkMarginDb'], where='result')
    numbers(result, ['distanceMeters', 'freeSpacePathLossDb', 'peakDiffractionLossDb', 'totalPathLossDb',
                     'worstClearancePercent'], 'result')
    need(result['clearanceStatus'] in STATUSES, 'unknown clearance status')
    need(isinstance(result['obstructions'], list) and len(result['obstructions']) <= 98, 'at most 98 obstructions')
    for obstruction in result['obstructions']:
        keys(obstruction, ['distanceFromAMeters', 'obstructionHeightMeters', 'fresnelClearancePercent'], where='obstruction')
        numbers(obstruction, ['distanceFromAMeters', 'obstructionHeightMeters', 'fresnelClearancePercent'], 'obstruction')
    budget = (result['receivePowerDbm'], result['linkMarginDb'])
    need(budget == (None, None) or (number(budget[0]) and number(budget[1])),
         'receivePowerDbm and linkMarginDb are both present or both null')
    need(distances[-1] == result['distanceMeters'], 'the last sample distance must equal the path distance')

    def near(sample, point):
        east = abs(sample['longitude'] - point['longitude']) % 360
        return abs(sample['latitude'] - point['latitude']) <= ENDPOINT_TOLERANCE and min(east, 360 - east) <= ENDPOINT_TOLERANCE
    need(near(samples[0], inputs['pointA']) and near(samples[-1], inputs['pointB']),
         'the first and last samples must match the endpoints')
    need(number(p['bearingDegrees']) and 0 <= p['bearingDegrees'] < 360, 'bearingDegrees must be in [0, 360)')

    flat = dict({k: inputs[k] for k in ('frequencyMHz', 'transmitPowerDbm', 'sensitivityDbm', 'refractionK', 'sampleCount')},
                pointA=inputs['pointA'], pointB=inputs['pointB'])
    need(vectors.link_digest(flat) == p['inputFingerprint'], 'payload fingerprint must match its inputs')
    need(v3.study_fingerprint(study) == p['inputFingerprint'], 'payload fingerprint must match the study inputs')


def validate(case):
    study = {'kind': case['kind'], case['kind']: case['study']}
    (validate_coverage if case['kind'] == 'coverage' else validate_link)(study, case['payload'])


# ---------------------------------------------------------------- cases

def base_coverage():
    study = v3.coverage_study('10000000-0000-4000-8000-000000000001', 'Ridge coverage')
    return study, copy.deepcopy(v3.coverage_payload(study))


def base_link(sample_count=20):
    study = v3.link_study('10000000-0000-4000-8000-000000000002', 'Ridge to valley', sample_count=sample_count)
    return study, copy.deepcopy(v3.link_payload(study))


def refingerprint(study, payload):
    payload['inputFingerprint'] = v3.study_fingerprint(study)


def coverage_case(name, change=None, reason=None, change_study=None):
    study, payload = base_coverage()
    if change_study:
        change_study(study, payload)
    if change:
        change(payload)
    return dict(name=name, kind='coverage', valid=reason is None, reason=reason, study=study['coverage'], payload=payload)


def link_case(name, change=None, reason=None, change_study=None, sample_count=20):
    study, payload = base_link(sample_count)
    if change_study:
        change_study(study, payload)
    if change:
        change(payload)
    return dict(name=name, kind='linkProfile', valid=reason is None, reason=reason, study=study['linkProfile'], payload=payload)


def every_reason(study, payload):
    """Eight captured nodes, one for each unavailable reason (plus the base's qualified margin first)."""
    origin = study['coverage']['transmitter']
    nodes, predicted = [], []
    for k, reason in enumerate(['qualified'] + REASONS):
        latitude, longitude = round(origin['latitude'] + 0.002 * (k + 1), 6), origin['longitude']
        nodes.append(dict(sourceID='analyzer.example', nodeID=f'n{k:02d}', name=f'Node {k}', role='repeater',
                          latitude=latitude, longitude=longitude, lastSeenMilliseconds=1794770000000))
        prediction = dict(kind='qualifiedMargin', marginDb=12.5) if reason == 'qualified' else dict(kind='unavailable', reason=reason)
        predicted.append(dict(sourceID='analyzer.example', nodeID=f'n{k:02d}', latitude=latitude, longitude=longitude,
                              distanceMeters=round(v3.haversine((origin['latitude'], origin['longitude']), (latitude, longitude)), 1),
                              prediction=prediction))
    study['coverage']['nodes'] = nodes
    payload['nodes'] = predicted
    refingerprint(study, payload)


def adaptive(study, payload):
    """An adaptive Link Profile: no requested count; the engine resolved 20."""
    study['linkProfile']['sampleCount'] = None
    payload['inputs']['sampleCount'] = None
    refingerprint(study, payload)


def without_budget(payload):
    payload['result'].update(receivePowerDbm=None, linkMarginDb=None)


def cases():
    def at(path, value):
        def change(payload):
            target = payload
            for part in path[:-1]:
                target = target[part]
            target[path[-1]] = value
        return change

    def add(path, field):
        def change(payload):
            target = payload
            for part in path:
                target = target[part]
            target[field] = 1
        return change

    def drop(path, field):
        def change(payload):
            target = payload
            for part in path:
                target = target[part]
            del target[field]
        return change

    def edges(states):
        def change(payload):
            for index, state in zip((1, 3, 5, 7), states):
                payload['area']['stateCodes'][index] = state
                payload['area']['lossesDb'][index] = 120.5 if state == 0 else None
        return change

    out = [
        # Coverage, valid.
        coverage_case('coverage_reference'),
        coverage_case('coverage_converted_legacy_shape', lambda p: p.update(calculatedAtMilliseconds=None, model=None, terrain=None)),
        coverage_case('coverage_remaining_cell_states', edges([3, 4, 6, 7])),
        coverage_case('coverage_every_unavailable_reason', change_study=every_reason),
        coverage_case('coverage_terrain_identified_by_hash', lambda p: p['terrain'][0].update(sourceETag=None, compressedSHA256='ab' * 32)),
        coverage_case('coverage_work_limited', at(['area', 'workLimited'], True)),
        # Coverage, invalid.
        coverage_case('coverage_unknown_field', add([], 'extra'), 'payload has unknown fields'),
        coverage_case('coverage_unknown_inputs_field', add(['inputs'], 'extra'), 'inputs has unknown fields'),
        coverage_case('coverage_unknown_area_field', add(['area'], 'margins'), 'area has unknown fields'),
        coverage_case('coverage_unknown_node_field', lambda p: p['nodes'][0].update(name='Hilltop'), 'node has unknown fields'),
        coverage_case('coverage_unknown_terrain_field', lambda p: p['terrain'][0].update(extra=1), 'terrain descriptor has unknown fields'),
        coverage_case('coverage_missing_calculated_at', drop([], 'calculatedAtMilliseconds'), 'payload is missing'),
        coverage_case('coverage_wrong_format', at(['format'], 'nodescope.link-profile-result'), 'format must be nodescope.coverage-result'),
        coverage_case('coverage_future_version', at(['version'], 2), 'unsupported payload version'),
        coverage_case('coverage_uppercase_fingerprint', lambda p: p.update(inputFingerprint=p['inputFingerprint'].upper()),
                      'inputFingerprint must be 64 lowercase hex digits'),
        coverage_case('coverage_negative_calculated_at', at(['calculatedAtMilliseconds'], -1), 'calculatedAtMilliseconds out of range'),
        coverage_case('coverage_string_number', at(['inputs', 'frequencyMHz'], '910.525'), 'inputs values must be finite numbers'),
        coverage_case('coverage_grid_disagrees', at(['inputs', 'grid', 'columns'], 5), 'grid must follow its radius and spacing'),
        coverage_case('coverage_limits_too_high', at(['limits', 'nodeModelCalls'], 50_001), 'limits out of range'),
        coverage_case('coverage_limits_sum_too_high', lambda p: p['limits'].update(areaSamples=100_000_000, nodeSamples=60_000_000),
                      'limits out of range'),
        coverage_case('coverage_empty_model_revision', at(['model', 'sourceRevision'], ''), 'model fields must be non-empty strings'),
        coverage_case('coverage_too_many_terrain', lambda p: p.update(terrain=p['terrain'] * 65), 'at most 64 terrain descriptors'),
        coverage_case('coverage_terrain_bad_side', lambda p: p['terrain'][0].update(side=1), 'invalid terrain descriptor'),
        coverage_case('coverage_terrain_without_identity', lambda p: p['terrain'][0].update(sourceETag=None), 'invalid terrain descriptor'),
        coverage_case('coverage_area_short', lambda p: p['area'].update(stateCodes=p['area']['stateCodes'][:8],
                                                                        lossesDb=p['area']['lossesDb'][:8]), 'area must cover the grid'),
        coverage_case('coverage_unknown_state', at(['area', 'stateCodes', 4], 8), 'unknown cell state code'),
        coverage_case('coverage_loss_on_unqualified_cell', at(['area', 'lossesDb', 4], 100.0), 'a loss exactly where the state is qualified'),
        coverage_case('coverage_missing_loss_on_qualified_cell', at(['area', 'lossesDb', 1], None), 'a loss exactly where the state is qualified'),
        coverage_case('coverage_negative_charged_work', at(['area', 'chargedSamples'], -1), 'charged work must be non-negative integers'),
        coverage_case('coverage_unknown_prediction_kind', at(['nodes', 0, 'prediction'], dict(kind='predicted', marginDb=1.0)),
                      'unknown prediction kind'),
        coverage_case('coverage_status_only_reason', at(['nodes', 1, 'prediction', 'reason'], 'changedInputs'), 'unknown unavailable reason'),
        coverage_case('coverage_margin_on_unavailable', at(['nodes', 1, 'prediction', 'marginDb'], 1.0), 'prediction has unknown fields'),
        coverage_case('coverage_negative_node_distance', at(['nodes', 0, 'distanceMeters'], -1.0), 'node distance must be non-negative'),
        coverage_case('coverage_duplicate_node', lambda p: p['nodes'].append(copy.deepcopy(p['nodes'][0])), 'duplicate node'),
        coverage_case('coverage_nodes_reordered', lambda p: p['nodes'].reverse(), 'nodes must follow the captured catalog'),
        coverage_case('coverage_node_moved', at(['nodes', 0, 'latitude'], 29.43), 'nodes must follow the captured catalog'),
        coverage_case('coverage_inputs_edited', at(['inputs', 'reserveDb'], 6.0), 'payload fingerprint must match its inputs'),
        coverage_case('coverage_for_another_study', change_study=lambda s, p: s['coverage'].update(reserveDb=6.0),
                      reason='payload fingerprint must match the study inputs'),
        # Link Profile, valid.
        link_case('link_reference'),
        link_case('link_adaptive_sampling', change_study=adaptive),
        link_case('link_without_link_budget', without_budget),
        link_case('link_two_samples', sample_count=2),
        link_case('link_hundred_samples', sample_count=100),
        *[link_case(f'link_status_{status}', at(['result', 'clearanceStatus'], status)) for status in STATUSES if status != 'marginal'],
        link_case('link_bearing_zero', at(['bearingDegrees'], 0.0)),
        # Link Profile, invalid.
        link_case('link_unknown_field', add([], 'clearanceStretches'), 'payload has unknown fields'),
        link_case('link_unknown_result_field', add(['result'], 'frequencyMHz'), 'result has unknown fields'),
        link_case('link_unknown_sample_field', lambda p: p['terrain']['samples'][0].update(extra=1), 'sample has unknown fields'),
        link_case('link_null_calculated_at', at(['calculatedAtMilliseconds'], None), 'calculatedAtMilliseconds out of range'),
        link_case('link_wrong_engine', at(['engine', 'id'], 'other-engine'), 'unknown engine'),
        link_case('link_future_engine_version', at(['engine', 'version'], 2), 'unknown engine'),
        link_case('link_sample_count_out_of_range', at(['resolvedSampleCount'], 101), 'resolvedSampleCount must be 2 to 100'),
        link_case('link_resolved_differs_from_requested', lambda p: p.update(resolvedSampleCount=19), 'resolvedSampleCount must equal the requested sampleCount'),
        link_case('link_sample_count_mismatch', change_study=adaptive,
                  change=lambda p: p.update(resolvedSampleCount=21), reason='sample count must equal resolvedSampleCount'),
        link_case('link_unknown_provider', at(['terrain', 'provenance', 'provider'], 'other'), 'unknown terrain provenance'),
        link_case('link_unknown_terrain_origin', at(['terrain', 'provenance', 'origin'], 'disk'), 'unknown terrain provenance'),
        link_case('link_first_distance_not_zero', at(['terrain', 'samples', 0, 'distanceMetersFromA'], 0.5),
                  'the first sample must be at distance 0'),
        link_case('link_distances_not_increasing', lambda p: p['terrain']['samples'][2].update(
            distanceMetersFromA=p['terrain']['samples'][1]['distanceMetersFromA']), 'sample distances must increase'),
        link_case('link_last_distance_differs', lambda p: p['result'].update(distanceMeters=p['result']['distanceMeters'] + 1),
                  'the last sample distance must equal the path distance'),
        link_case('link_endpoint_moved', lambda p: p['terrain']['samples'][0].update(latitude=p['terrain']['samples'][0]['latitude'] + 1e-4),
                  'the first and last samples must match the endpoints'),
        link_case('link_unknown_status', at(['result', 'clearanceStatus'], 'unknown'), 'unknown clearance status'),
        link_case('link_too_many_obstructions', lambda p: p['result'].update(obstructions=p['result']['obstructions'] * 99),
                  'at most 98 obstructions'),
        link_case('link_half_budget', at(['result', 'linkMarginDb'], None), 'receivePowerDbm and linkMarginDb are both present or both null'),
        link_case('link_bearing_full_circle', at(['bearingDegrees'], 360.0), 'bearingDegrees must be in [0, 360)'),
        link_case('link_inputs_edited', at(['inputs', 'refractionK'], 1.0), 'payload fingerprint must match its inputs'),
        link_case('link_for_another_study', change_study=lambda s, p: s['linkProfile'].update(frequencyMHz=915.0),
                  reason='payload fingerprint must match the study inputs'),
    ]
    return out


def main():
    results = []
    for case in cases():
        try:
            validate(case)
            outcome = None
        except Invalid as error:
            outcome = str(error)
        if case['valid']:
            need(outcome is None, f"{case['name']} should be valid: {outcome}")
        else:
            need(outcome is not None and case['reason'] in outcome, f"{case['name']} should fail with {case['reason']!r}, got {outcome!r}")
        results.append(case)
    names = [c['name'] for c in results]
    need(len(set(names)) == len(names), 'case names must be unique')
    out = os.path.join(HERE, 'results')
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, 'result-cases-v1.json'), 'w', encoding='utf-8') as handle:
        json.dump({'format': 'nodescope.result-cases', 'version': 1,
                   'rules': 'saved-plans-v3-result-fields.md; each case pairs a payload with the study inputs it must match',
                   'endpointToleranceDegrees': ENDPOINT_TOLERANCE, 'cases': results},
                  handle, indent=2, ensure_ascii=False, allow_nan=False)
        handle.write('\n')
    valid = sum(c['valid'] for c in results)
    coverage = sum(c['kind'] == 'coverage' for c in results)
    print(f'{len(results)} cases ({valid} valid, {len(results) - valid} invalid; '
          f'{coverage} coverage, {len(results) - coverage} link profile)')


if __name__ == '__main__':
    main()
