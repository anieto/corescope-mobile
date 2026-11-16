import Foundation

/// `nodescope.link-profile-result` version 1 (docs/node-planner/saved-plans-v3-result-fields.md), identical
/// on Android (PortableLinkProfileResult.kt) and checked against shared/fixtures/saved-plans/results.
/// Reading is strict and needs the study's own inputs: the payload's fingerprint must match both.
enum PortableLinkProfileResult {
    static let format = "nodescope.link-profile-result"
    /// One payload's ceiling: about twice the measured worst case (Android PortableResultSizeTest).
    static let maximumBytes = 64 * 1024
    /// First and last samples may differ from the endpoints by this much (degrees); longitudes compare modulo 360.
    static let endpointTolerance = 1e-6
    private static let maximumMilliseconds: Int64 = 253_402_300_799_999
    private static let prefix = Data(#"{"format":"nodescope.link-profile-result""#.utf8)
    private static let endpointNames = ["latitude", "longitude", "heightMeters", "gainDbi", "lossDb"]
    typealias Failure = PortableCoverageResult.Failure
    typealias Out = PortableJSON.Output

    static func isPortable(_ data: Data) -> Bool { data.starts(with: prefix) }

    static func fingerprint(of data: Data) -> String? {
        guard let root = try? PortableJSON.parse(data, limit: maximumBytes).object(),
              case .string(let value)? = root.values["inputFingerprint"], isSHA(value) else { return nil }
        return value
    }

    static func studyFingerprint(_ inputs: SavedLinkProfile) -> String? {
        try? PortableInputFingerprint.linkProfile(pointA: values(inputs.pointA), pointB: values(inputs.pointB),
            frequencyMHz: inputs.frequencyMHz, transmitPowerDbm: inputs.transmitPowerDbm, sensitivityDbm: inputs.sensitivityDbm,
            refractionK: inputs.refractionK, sampleCount: inputs.sampleCount.map(Int64.init))
    }

    /// Captures `analysis` for the study `inputs`; nil if the analysis wasn't calculated from exactly these inputs.
    static func encode(_ analysis: NodePlannerLinkAnalysis, inputs: SavedLinkProfile) -> Data? {
        let request = analysis.request, samples = analysis.terrain.samples
        guard let fingerprint = studyFingerprint(inputs), let budget = request.linkBudget,
              request.heightAMeters == inputs.pointA.antenna.heightMeters, request.heightBMeters == inputs.pointB.antenna.heightMeters,
              request.frequencyMHz == inputs.frequencyMHz, request.refractionK == inputs.refractionK, request.sampleCount == inputs.sampleCount,
              budget == linkBudget(inputs), (2...100).contains(samples.count), samples.count == analysis.sampleDistancesMeters.count,
              let first = samples.first, let last = samples.last,
              near(first.coordinate, inputs.pointA), near(last.coordinate, inputs.pointB) else { return nil }
        let result = analysis.result
        let sampleOutput: [Out] = zip(samples, analysis.sampleDistancesMeters).map { sample, distance in
            .object([("latitude", .double(sample.coordinate.latitude)), ("longitude", .double(sample.coordinate.longitude)),
                     ("distanceMetersFromA", .double(distance)), ("elevationMeters", .double(sample.elevationMeters))])
        }
        let provenance: [(String, Out)] = [
            ("provider", .string(NodePlannerElevationProvenance.provider)), ("dataset", .string(NodePlannerElevationProvenance.dataset)),
            ("nominalResolutionMeters", .int(Int64(NodePlannerElevationProvenance.nominalResolutionMeters))),
            ("fetchedAtMilliseconds", .int(milliseconds(analysis.terrain.provenance.fetchedAt))),
            ("origin", .string(analysis.terrain.provenance.origin.rawValue)),
        ]
        let obstructions: [Out] = result.obstructions.map {
            .object([("distanceFromAMeters", .double($0.distanceFromAMeters)), ("obstructionHeightMeters", .double($0.obstructionHeightMeters)),
                     ("fresnelClearancePercent", .double($0.fresnelClearancePercent))])
        }
        var receive = Out.null, margin = Out.null
        if let value = result.receivePowerDBm { receive = .double(value) }
        if let value = result.linkMarginDB { margin = .double(value) }
        let resultFields: [(String, Out)] = [
            ("distanceMeters", .double(result.distanceMeters)), ("freeSpacePathLossDb", .double(result.freeSpacePathLossDB)),
            ("peakDiffractionLossDb", .double(result.peakDiffractionLossDB)), ("totalPathLossDb", .double(result.totalPathLossDB)),
            ("worstClearancePercent", .double(result.worstClearancePercent)), ("clearanceStatus", .string(result.clearanceStatus.rawValue)),
            ("obstructions", .array(obstructions)), ("receivePowerDbm", receive), ("linkMarginDb", margin),
        ]
        var sampleCount = Out.null
        if let value = inputs.sampleCount { sampleCount = .int(Int64(value)) }
        let inputFields: [(String, Out)] = [
            ("pointA", endpoint(inputs.pointA)), ("pointB", endpoint(inputs.pointB)),
            ("frequencyMHz", .double(inputs.frequencyMHz)), ("transmitPowerDbm", .double(inputs.transmitPowerDbm)),
            ("sensitivityDbm", .double(inputs.sensitivityDbm)), ("refractionK", .double(inputs.refractionK)),
            ("sampleCount", sampleCount),
        ]
        let fields: [(String, Out)] = [
            ("format", .string(format)), ("version", .int(1)),
            ("calculatedAtMilliseconds", .int(milliseconds(analysis.calculatedAt))), ("inputFingerprint", .string(fingerprint)),
            ("engine", .object([("id", .string(analysis.engineID)), ("version", .int(Int64(analysis.engineVersion)))])),
            ("inputs", .object(inputFields)), ("resolvedSampleCount", .int(Int64(samples.count))),
            ("terrain", .object([("samples", .array(sampleOutput)), ("provenance", .object(provenance))])),
            ("result", .object(resultFields)), ("bearingDegrees", .double(analysis.bearingDegrees)),
        ]
        let data = Out.object(fields).encoded()
        return data.count <= maximumBytes ? data : nil
    }

    /// Rebuilds the saved analysis for the study `inputs` without fetching terrain; throws if it isn't theirs or is malformed.
    static func decode(_ data: Data, inputs: SavedLinkProfile) throws -> NodePlannerLinkAnalysis {
        let root = try PortableJSON.parse(data, limit: maximumBytes).object(exactly: ["format", "version", "calculatedAtMilliseconds",
            "inputFingerprint", "engine", "inputs", "resolvedSampleCount", "terrain", "result", "bearingDegrees"])
        guard try root.string("format") == format, try root.int("version") == 1 else { throw Failure.invalid }
        let calculatedAt = try root.int("calculatedAtMilliseconds", in: 0...maximumMilliseconds)
        let fingerprint = try root.string("inputFingerprint")
        let engine = try root.object("engine", exactly: ["id", "version"])
        guard isSHA(fingerprint), try engine.string("id") == NodePlannerPathResult.engineID,
              try engine.int("version") == Int64(NodePlannerPathResult.engineVersion) else { throw Failure.invalid }

        let payloadInputs = try root.object("inputs", exactly: ["pointA", "pointB", "frequencyMHz", "transmitPowerDbm",
                                                                "sensitivityDbm", "refractionK", "sampleCount"])
        let endpoints = try ["pointA", "pointB"].map { name in
            let point = try payloadInputs.object(name, exactly: endpointNames)
            return try endpointNames.map { try point.double($0) }
        }
        let radio = try ["frequencyMHz", "transmitPowerDbm", "sensitivityDbm", "refractionK"].map { try payloadInputs.double($0) }
        let requested = try payloadInputs.nullable("sampleCount")?.int(in: 2...100)
        let resolved = try root.int("resolvedSampleCount", in: 2...100)
        guard requested == nil || requested == resolved else { throw Failure.invalid }

        let terrain = try root.object("terrain", exactly: ["samples", "provenance"])
        let provenance = try terrain.object("provenance", exactly: ["provider", "dataset", "nominalResolutionMeters",
                                                                    "fetchedAtMilliseconds", "origin"])
        guard try provenance.string("provider") == NodePlannerElevationProvenance.provider,
              try provenance.string("dataset") == NodePlannerElevationProvenance.dataset,
              try provenance.int("nominalResolutionMeters") == Int64(NodePlannerElevationProvenance.nominalResolutionMeters),
              let origin = NodePlannerElevationOrigin(rawValue: try provenance.string("origin")) else { throw Failure.invalid }
        let fetchedAt = try provenance.int("fetchedAtMilliseconds", in: 0...maximumMilliseconds)
        let samples = try terrain.array("samples").map { item -> (NodePlannerTerrainSample, Double) in
            let sample = try item.object(exactly: ["latitude", "longitude", "distanceMetersFromA", "elevationMeters"])
            let coordinate = try NodePlannerCoordinate(latitude: try sample.double("latitude"), longitude: try sample.double("longitude"))
            return (NodePlannerTerrainSample(coordinate: coordinate, elevationMeters: try sample.double("elevationMeters")),
                    try sample.double("distanceMetersFromA"))
        }
        let distances = samples.map(\.1)
        guard Int64(samples.count) == resolved, distances.first == 0,
              zip(distances, distances.dropFirst()).allSatisfy({ $1 > $0 }) else { throw Failure.invalid }

        let result = try root.object("result", exactly: ["distanceMeters", "freeSpacePathLossDb", "peakDiffractionLossDb",
            "totalPathLossDb", "worstClearancePercent", "clearanceStatus", "obstructions", "receivePowerDbm", "linkMarginDb"])
        guard let status = NodePlannerClearanceStatus(rawValue: try result.string("clearanceStatus")) else { throw Failure.invalid }
        let obstructionList = try result.array("obstructions")
        guard obstructionList.count <= 98 else { throw Failure.invalid }
        let obstructions = try obstructionList.map { item -> NodePlannerObstruction in
            let o = try item.object(exactly: ["distanceFromAMeters", "obstructionHeightMeters", "fresnelClearancePercent"])
            return NodePlannerObstruction(distanceFromAMeters: try o.double("distanceFromAMeters"),
                obstructionHeightMeters: try o.double("obstructionHeightMeters"), fresnelClearancePercent: try o.double("fresnelClearancePercent"))
        }
        let receive = try result.nullable("receivePowerDbm")?.double(), margin = try result.nullable("linkMarginDb")?.double()
        let distance = try result.double("distanceMeters")
        let bearing = try root.double("bearingDegrees")
        guard (receive == nil) == (margin == nil), distances.last == distance, (0..<360).contains(bearing),
              let first = samples.first?.0, let last = samples.last?.0,
              near(first.coordinate, latitude: endpoints[0][0], longitude: endpoints[0][1]),
              near(last.coordinate, latitude: endpoints[1][0], longitude: endpoints[1][1]) else { throw Failure.invalid }

        let own = try PortableInputFingerprint.linkProfile(pointA: endpoints[0], pointB: endpoints[1], frequencyMHz: radio[0],
            transmitPowerDbm: radio[1], sensitivityDbm: radio[2], refractionK: radio[3], sampleCount: requested)
        guard own == fingerprint, studyFingerprint(inputs) == fingerprint else { throw Failure.invalid }

        let request = NodePlannerLinkAnalysisRequest(
            pointA: try NodePlannerCoordinate(latitude: inputs.pointA.latitude, longitude: inputs.pointA.longitude),
            pointB: try NodePlannerCoordinate(latitude: inputs.pointB.latitude, longitude: inputs.pointB.longitude),
            heightAMeters: inputs.pointA.antenna.heightMeters, heightBMeters: inputs.pointB.antenna.heightMeters,
            frequencyMHz: inputs.frequencyMHz, refractionK: inputs.refractionK, linkBudget: linkBudget(inputs),
            sampleCount: inputs.sampleCount)
        let path = NodePlannerPathResult(distanceMeters: distance, freeSpacePathLossDB: try result.double("freeSpacePathLossDb"),
            peakDiffractionLossDB: try result.double("peakDiffractionLossDb"), totalPathLossDB: try result.double("totalPathLossDb"),
            worstClearancePercent: try result.double("worstClearancePercent"), clearanceStatus: status, obstructions: obstructions,
            frequencyMHz: inputs.frequencyMHz, refractionK: inputs.refractionK, receivePowerDBm: receive, linkMarginDB: margin)
        return NodePlannerLinkAnalysis(request: request,
            terrain: NodePlannerTerrainProfile(samples: samples.map(\.0),
                provenance: NodePlannerElevationProvenance(fetchedAt: date(fetchedAt), origin: origin)),
            result: path, calculatedAt: date(calculatedAt), engineID: NodePlannerPathResult.engineID,
            engineVersion: NodePlannerPathResult.engineVersion, sampleDistancesMeters: distances, bearingDegrees: bearing)
    }

    private static func linkBudget(_ inputs: SavedLinkProfile) -> NodePlannerLinkBudget {
        NodePlannerLinkBudget(transmitPowerDBm: inputs.transmitPowerDbm, gainADB: inputs.pointA.antenna.gainDbi,
            gainBDB: inputs.pointB.antenna.gainDbi, lossADB: inputs.pointA.antenna.lossDb, lossBDB: inputs.pointB.antenna.lossDb,
            receiverSensitivityDBm: inputs.sensitivityDbm)
    }

    private static func values(_ point: SavedEndpoint) -> [Double] {
        [point.latitude, point.longitude, point.antenna.heightMeters, point.antenna.gainDbi, point.antenna.lossDb]
    }

    private static func endpoint(_ point: SavedEndpoint) -> Out {
        .object(zip(endpointNames, values(point)).map { ($0, Out.double($1)) })
    }

    private static func near(_ coordinate: NodePlannerCoordinate, _ point: SavedEndpoint) -> Bool {
        near(coordinate, latitude: point.latitude, longitude: point.longitude)
    }

    private static func near(_ coordinate: NodePlannerCoordinate, latitude: Double, longitude: Double) -> Bool {
        let east = abs(coordinate.longitude - longitude).truncatingRemainder(dividingBy: 360)
        return abs(coordinate.latitude - latitude) <= endpointTolerance && min(east, 360 - east) <= endpointTolerance
    }

    private static func milliseconds(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }
    private static func date(_ milliseconds: Int64) -> Date { Date(timeIntervalSince1970: Double(milliseconds) / 1000) }

    private static func isSHA(_ value: String) -> Bool {
        value.utf8.count == 64 && value.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
    }
}
