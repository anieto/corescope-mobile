import Foundation

/// `nodescope.coverage-result` version 1 (docs/node-planner/saved-plans-v3-result-fields.md), identical
/// on Android (PortableCoverageResult.kt) and checked against shared/fixtures/saved-plans/results.
///
/// Reading is strict: unknown or missing fields and unknown values are refused at every level, and the
/// payload's fingerprint must match both its own inputs and the study's. It then goes through the same
/// checks as a restored legacy result (grid masks, margins, node run), so nothing is half-trusted.
enum PortableCoverageResult {
    static let format = "nodescope.coverage-result"
    static let maximumBytes = 8 * 1024 * 1024
    private static let maximumMilliseconds: Int64 = 253_402_300_799_999
    /// Encoding always starts with this, so stored bytes are recognized without parsing them.
    private static let prefix = Data(#"{"format":"nodescope.coverage-result""#.utf8)
    private static let doubles = ["transmitterLatitude", "transmitterLongitude", "frequencyMHz", "transmitHeightMeters",
        "receiveHeightMeters", "transmitPowerDbm", "transmitGainDbi", "receiveGainDbi", "transmitLossDb", "receiveLossDb",
        "sensitivityDbm", "reserveDb"]
    private static let gridNames = ["radiusMeters", "spacingMeters", "minEastMeters", "maxNorthMeters", "columns", "rows"]
    private static let limitNames = ["areaSamples", "areaGeometry", "nodeSamples", "nodeGeometry", "nodeModelCalls"]
    /// Cell state codes, in order.
    private static let states: [CoverageAreaState] = [.qualified, .nearSite, .outsideRadius, .missingTerrain,
        .accuracyUnresolved, .thresholdUnresolved, .workLimit, .notComputed]
    /// The reasons evaluators write; status-only reasons are refused.
    private static let reasons: [CoverageNodeReason] = [.outsideRadius, .invalidInput, .missingTerrain, .unsupportedPath,
        .accuracyUnresolved, .thresholdUnresolved, .processingLimit, .pathWorkLimit]
    private static let terrainStrings = ["dataset", "revision", "tile", "url", "verticalDatum", "attribution", "sourceType", "originURL"]

    enum Failure: Error { case invalid }

    static func isPortable(_ data: Data) -> Bool { data.starts(with: prefix) }

    /// The payload's input fingerprint, for its result reference; nil if it isn't a readable payload.
    static func fingerprint(of data: Data) -> String? {
        guard let root = try? PortableJSON.parse(data, limit: maximumBytes).object(),
              case .string(let value)? = root.values["inputFingerprint"], isSHA(value) else { return nil }
        return value
    }

    /// Captures `result`; nil if a node it evaluated has no recorded evaluation.
    static func encode(_ result: CoverageStudyResult) throws -> Data? {
        let run = result.nodes.run
        var evaluations: [CoverageNodeEvaluation] = []
        for node in run.nodesToEvaluate {
            guard let evaluation = run.evaluation(for: node) else { return nil }
            evaluations.append(evaluation)
        }
        let inputs = result.inputs, grid = inputs.grid, limits = result.limits
        let gridValues = [grid.radiusMeters, grid.spacingMeters, grid.minEastMeters, grid.maxNorthMeters, grid.columns, grid.rows]
        let fingerprint = try PortableInputFingerprint.coverage(doubles: values(inputs), grid: gridValues.map(Int64.init),
            catalogComplete: run.catalogComplete,
            nodes: run.nodesToEvaluate.map { .init(sourceID: $0.sourceID, nodeID: $0.nodeID, latitude: $0.latitude, longitude: $0.longitude) })
        typealias Out = PortableJSON.Output
        let inputFields: [(String, Out)] = zip(doubles, values(inputs)).map { ($0, Out.double($1)) } + [
            ("grid", .object(zip(gridNames, gridValues).map { ($0, Out.int(Int64($1))) })),
            ("catalogComplete", .bool(run.catalogComplete)),
        ]
        let limitValues = [limits.areaSamples, limits.areaGeometry, limits.nodeSamples, limits.nodeGeometry, limits.nodeModelCalls]
        let limitFields: [(String, Out)] = zip(limitNames, limitValues).map { ($0, Out.int(Int64($1))) }
        var model = Out.null
        if let value = result.model {
            model = .object([("sourceRevision", .string(value.sourceRevision)), ("scenario", .string(value.scenario))])
        }
        var terrain = Out.null
        if let sources = result.terrainSources {
            terrain = .array(sources.descriptors.map(terrainOutput))
        }
        let cells = result.area.cells
        let codes: [Out] = cells.map { .int(Int64(states.firstIndex(of: $0.state) ?? 7)) }
        let losses: [Out] = cells.map { cell in
            guard cell.state == .qualified, let loss = cell.lossDb else { return .null }
            return .double(loss)
        }
        let area: [(String, Out)] = [
            ("stateCodes", .array(codes)), ("lossesDb", .array(losses)),
            ("chargedSamples", .int(result.area.chargedSamples)), ("chargedGeometry", .int(result.area.chargedGeometry)),
            ("workLimited", .bool(result.area.workLimited)),
        ]
        let nodes: [Out] = evaluations.map(nodeOutput)
        var calculatedAt = Out.null
        if let value = result.calculatedAtMilliseconds { calculatedAt = .int(value) }
        let fields: [(String, Out)] = [
            ("format", .string(format)), ("version", .int(1)), ("calculatedAtMilliseconds", calculatedAt),
            ("inputFingerprint", .string(fingerprint)), ("inputs", .object(inputFields)), ("limits", .object(limitFields)),
            ("model", model), ("terrain", terrain), ("area", .object(area)), ("nodes", .array(nodes)),
        ]
        let payload = Out.object(fields)
        let data = payload.encoded()
        return data.count <= maximumBytes ? data : nil
    }

    private static func terrainOutput(_ t: CoverageTerrainDescriptor) -> PortableJSON.Output {
        var hash = PortableJSON.Output.null, etag = PortableJSON.Output.null
        if let value = t.compressedSHA256 { hash = .string(value) }
        if let value = t.sourceETag { etag = .string(value) }
        return .object([
            ("dataset", .string(t.dataset)), ("revision", .string(t.revision)), ("tile", .string(t.tile)), ("url", .string(t.url)),
            ("verticalDatum", .string(t.verticalDatum)), ("attribution", .string(t.attribution)),
            ("sourceType", .string(t.sourceType)), ("originURL", .string(t.originURL)),
            ("compressedSHA256", hash), ("sourceETag", etag), ("packedBytes", .int(t.packedBytes)), ("side", .int(Int64(t.side))),
        ])
    }

    private static func nodeOutput(_ evaluation: CoverageNodeEvaluation) -> PortableJSON.Output {
        let prediction: PortableJSON.Output
        switch evaluation.prediction {
        case .qualifiedMargin(let margin): prediction = .object([("kind", .string("qualifiedMargin")), ("marginDb", .double(margin))])
        case .unavailable(let reason): prediction = .object([("kind", .string("unavailable")), ("reason", .string(reason.rawValue))])
        }
        return .object([
            ("sourceID", .string(evaluation.node.sourceID)), ("nodeID", .string(evaluation.node.nodeID)),
            ("latitude", .double(evaluation.node.latitude)), ("longitude", .double(evaluation.node.longitude)),
            ("distanceMeters", .double(evaluation.distanceMeters)), ("prediction", prediction),
        ])
    }

    /// Rebuilds the result for a study's `inputs`, captured `snapshots` and `catalogComplete`; throws if the
    /// payload is malformed or belongs to other inputs.
    static func decode(_ data: Data, inputs: CoverageStudyInputs, snapshots: [CoverageNodeSnapshot],
                       catalogComplete: Bool) throws -> CoverageStudyResult {
        let root = try PortableJSON.parse(data, limit: maximumBytes).object(exactly: ["format", "version", "calculatedAtMilliseconds",
            "inputFingerprint", "inputs", "limits", "model", "terrain", "area", "nodes"])
        guard try root.string("format") == format, try root.int("version") == 1 else { throw Failure.invalid }
        let calculatedAt = try root.nullable("calculatedAtMilliseconds")?.int(in: 0...maximumMilliseconds)
        let fingerprint = try root.string("inputFingerprint")
        guard isSHA(fingerprint) else { throw Failure.invalid }

        let payloadInputs = try root.object("inputs", exactly: doubles + ["grid", "catalogComplete"])
        let values = try doubles.map { try payloadInputs.double($0) }
        let grid = try payloadInputs.object("grid", exactly: gridNames)
        let gridValues = try gridNames.map { try grid.int($0) }
        let payloadComplete = try payloadInputs.bool("catalogComplete")

        let limitsObject = try root.object("limits", exactly: limitNames)
        let limitValues = try limitNames.map { Int(try limitsObject.int($0, in: 0...Int64(Int32.max))) }
        let limits = CoverageStudyLimits(areaSamples: limitValues[0], areaGeometry: limitValues[1], nodeSamples: limitValues[2],
                                         nodeGeometry: limitValues[3], nodeModelCalls: limitValues[4])
        let model = try root.nullable("model").map { value -> CoverageStudyModel in
            let model = try value.object(exactly: ["sourceRevision", "scenario"])
            let revision = try model.string("sourceRevision"), scenario = try model.string("scenario")
            guard !revision.isEmpty, !scenario.isEmpty else { throw Failure.invalid }
            return CoverageStudyModel(sourceRevision: revision, scenario: scenario)
        }
        let terrain = try root.nullable("terrain").map { value -> [SavedCoverageCalculation.Terrain] in
            let list = try value.array()
            guard list.count <= 64 else { throw Failure.invalid }
            return try list.map { item in
                let t = try item.object(exactly: terrainStrings + ["compressedSHA256", "sourceETag", "packedBytes", "side"])
                let hash = try t.nullable("compressedSHA256")?.string()
                guard hash.map(isSHA) ?? true else { throw Failure.invalid }
                return SavedCoverageCalculation.Terrain(dataset: try t.string("dataset"), revision: try t.string("revision"),
                    tile: try t.string("tile"), url: try t.string("url"), verticalDatum: try t.string("verticalDatum"),
                    attribution: try t.string("attribution"), sourceType: try t.string("sourceType"),
                    originURL: try t.string("originURL"), compressedSHA256: hash,
                    sourceETag: try t.nullable("sourceETag")?.string(), packedBytes: try t.int("packedBytes"),
                    side: Int(try t.int("side", in: 2...3601)))
            }
        }

        let area = try root.object("area", exactly: ["stateCodes", "lossesDb", "chargedSamples", "chargedGeometry", "workLimited"])
        let codes = try area.array("stateCodes").map { Int(try $0.int(in: 0...7)) }
        let losses = try area.array("lossesDb").map { try $0.isNull ? nil : $0.double() }
        guard codes.count == losses.count else { throw Failure.invalid }
        let cells = try zip(codes, losses).map { code, loss -> CoverageAreaCell in
            guard (code == 0) == (loss != nil) else { throw Failure.invalid }
            return CoverageAreaCell(state: states[code], lossDb: loss,
                                    marginDb: try loss.map { try inputs.budget.reception(pathLossDb: $0).adjustedMarginDb })
        }
        let charged = (try area.int("chargedSamples", in: 0...Int64.max), try area.int("chargedGeometry", in: 0...Int64.max))

        let nodeList = try root.array("nodes")
        guard nodeList.count <= 200 else { throw Failure.invalid }
        let nodes = try nodeList.map { item -> SavedCoverageCalculation.Node in
            let node = try item.object(exactly: ["sourceID", "nodeID", "latitude", "longitude", "distanceMeters", "prediction"])
            let prediction = try node.object("prediction")
            let value: CoverageNodePrediction
            switch try prediction.string("kind") {
            case "qualifiedMargin":
                try prediction.require(exactly: ["kind", "marginDb"])
                value = .qualifiedMargin(try prediction.double("marginDb"))
            case "unavailable":
                try prediction.require(exactly: ["kind", "reason"])
                guard let reason = reasons.first(where: { $0.rawValue == (try? prediction.string("reason")) }) else { throw Failure.invalid }
                value = .unavailable(reason)
            default: throw Failure.invalid
            }
            return SavedCoverageCalculation.Node(
                snapshot: CoverageNodeSnapshot(sourceID: try node.string("sourceID"), nodeID: try node.string("nodeID"),
                                               latitude: try node.double("latitude"), longitude: try node.double("longitude")),
                distance: try node.double("distanceMeters"), prediction: value)
        }

        let own = try PortableInputFingerprint.coverage(doubles: values, grid: gridValues, catalogComplete: payloadComplete,
            nodes: nodes.map { .init(sourceID: $0.snapshot.sourceID, nodeID: $0.snapshot.nodeID,
                                     latitude: $0.snapshot.latitude, longitude: $0.snapshot.longitude) })
        let study = try PortableInputFingerprint.coverage(doubles: Self.values(inputs),
            grid: [inputs.grid.radiusMeters, inputs.grid.spacingMeters, inputs.grid.minEastMeters, inputs.grid.maxNorthMeters,
                   inputs.grid.columns, inputs.grid.rows].map(Int64.init),
            catalogComplete: catalogComplete,
            nodes: snapshots.map { .init(sourceID: $0.sourceID, nodeID: $0.nodeID, latitude: $0.latitude, longitude: $0.longitude) })
        guard own == fingerprint, study == fingerprint else { throw Failure.invalid }

        // The legacy form records the grid after the twelve doubles.
        return try SavedCoverageCalculation(inputValues: values + gridValues.map(Double.init),
            area: CoverageAreaResult(cells: cells, chargedSamples: charged.0, chargedGeometry: charged.1,
                                     workLimited: try area.bool("workLimited")),
            limits: limits, model: model, terrain: terrain, nodes: nodes)
            .restore(inputs: inputs, nodes: snapshots, catalogComplete: catalogComplete, calculatedAtMilliseconds: calculatedAt)
    }

    /// A study's saved inputs as the engine takes them; throws if they aren't valid coverage inputs.
    static func inputs(_ saved: SavedCoverage) throws -> CoverageStudyInputs {
        try CoverageStudyInputs(
            transmitter: CoverageTerrainCoordinate(latitude: saved.transmitter.latitude, longitude: saved.transmitter.longitude),
            grid: CoverageGrid.studyArea(radiusMeters: saved.radiusMeters, spacingMeters: saved.spacingMeters),
            budget: CoverageLinkBudget(transmitPowerDbm: saved.transmitPowerDbm, transmitGainDbi: saved.transmitter.antenna.gainDbi,
                receiveGainDbi: saved.receiver.gainDbi, transmitLossDb: saved.transmitter.antenna.lossDb,
                receiveLossDb: saved.receiver.lossDb, sensitivityDbm: saved.sensitivityDbm, reserveDb: saved.reserveDb),
            frequencyMHz: saved.frequencyMHz, transmitHeightMeters: saved.transmitter.antenna.heightMeters,
            receiveHeightMeters: saved.receiver.heightMeters)
    }

    static func snapshots(_ saved: SavedCoverage) -> [CoverageNodeSnapshot] {
        saved.nodes.map { CoverageNodeSnapshot(sourceID: $0.sourceID, nodeID: $0.nodeID, latitude: $0.latitude, longitude: $0.longitude) }
    }

    /// The study's input fingerprint, computed from its saved inputs; nil if they aren't valid coverage inputs.
    static func studyFingerprint(_ saved: SavedCoverage) -> String? {
        guard let inputs = try? inputs(saved) else { return nil }
        let grid = inputs.grid
        return try? PortableInputFingerprint.coverage(doubles: values(inputs),
            grid: [grid.radiusMeters, grid.spacingMeters, grid.minEastMeters, grid.maxNorthMeters, grid.columns, grid.rows].map(Int64.init),
            catalogComplete: saved.catalogComplete,
            nodes: saved.nodes.map { .init(sourceID: $0.sourceID, nodeID: $0.nodeID, latitude: $0.latitude, longitude: $0.longitude) })
    }

    private static func values(_ inputs: CoverageStudyInputs) -> [Double] {
        let b = inputs.budget
        return [inputs.transmitter.latitude, inputs.transmitter.longitude, inputs.frequencyMHz, inputs.transmitHeightMeters,
                inputs.receiveHeightMeters, b.transmitPowerDbm, b.transmitGainDbi, b.receiveGainDbi, b.transmitLossDb,
                b.receiveLossDb, b.sensitivityDbm, b.reserveDb]
    }

    private static func isSHA(_ value: String) -> Bool {
        value.utf8.count == 64 && value.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
    }
}

extension SavedCoverageCalculation {
    init(inputValues: [Double], area: CoverageAreaResult, limits: CoverageStudyLimits, model: CoverageStudyModel?,
         terrain: [Terrain]?, nodes: [Node]) {
        self.inputValues = inputValues
        self.area = area
        self.limits = limits
        self.model = model
        self.terrain = terrain
        self.nodes = nodes
    }
}

extension SavedCoverageCalculation.Terrain {
    init(dataset: String, revision: String, tile: String, url: String, verticalDatum: String, attribution: String,
         sourceType: String, originURL: String, compressedSHA256: String?, sourceETag: String?, packedBytes: Int64, side: Int) {
        self.dataset = dataset; self.revision = revision; self.tile = tile; self.url = url
        self.verticalDatum = verticalDatum; self.attribution = attribution; self.sourceType = sourceType
        self.originURL = originURL; self.compressedSHA256 = compressedSHA256; self.sourceETag = sourceETag
        self.packedBytes = packedBytes; self.side = side
    }
}

/// JSON for strict portable readers and writers. Foundation's decoders ignore unknown keys and can't
/// tell a missing field from a null one, so payloads are read as a tree and checked field by field.
indirect enum PortableJSON {
    case null, bool(Bool), number(Double, Int64?), string(String), array([PortableJSON]), object([String: PortableJSON])

    struct Fields {
        let values: [String: PortableJSON]

        func require(exactly names: [String]) throws {
            guard Set(values.keys) == Set(names) else { throw PortableCoverageResult.Failure.invalid }
        }
        func field(_ name: String) throws -> PortableJSON {
            guard let value = values[name] else { throw PortableCoverageResult.Failure.invalid }
            return value
        }
        func nullable(_ name: String) throws -> PortableJSON? { let value = try field(name); return value.isNull ? nil : value }
        func object(_ name: String, exactly names: [String]? = nil) throws -> Fields {
            let fields = try field(name).object()
            if let names { try fields.require(exactly: names) }
            return fields
        }
        func array(_ name: String) throws -> [PortableJSON] { try field(name).array() }
        func string(_ name: String) throws -> String { try field(name).string() }
        func bool(_ name: String) throws -> Bool { try field(name).bool() }
        func int(_ name: String, in range: ClosedRange<Int64> = Int64.min...Int64.max) throws -> Int64 { try field(name).int(in: range) }
        func double(_ name: String) throws -> Double { try field(name).double() }
    }

    var isNull: Bool { if case .null = self { true } else { false } }

    static func parse(_ data: Data, limit: Int) throws -> PortableJSON {
        guard data.count <= limit else { throw PortableCoverageResult.Failure.invalid }
        return try JSONDecoder().decode(PortableJSON.self, from: data)
    }

    func object(exactly names: [String]? = nil) throws -> Fields {
        guard case .object(let values) = self else { throw PortableCoverageResult.Failure.invalid }
        let fields = Fields(values: values)
        if let names { try fields.require(exactly: names) }
        return fields
    }
    func array() throws -> [PortableJSON] {
        guard case .array(let values) = self else { throw PortableCoverageResult.Failure.invalid }
        return values
    }
    func string() throws -> String {
        guard case .string(let value) = self else { throw PortableCoverageResult.Failure.invalid }
        return value
    }
    func bool() throws -> Bool {
        guard case .bool(let value) = self else { throw PortableCoverageResult.Failure.invalid }
        return value
    }
    /// Integers only: a number written with a fraction or exponent isn't one.
    func int(in range: ClosedRange<Int64> = Int64.min...Int64.max) throws -> Int64 {
        guard case .number(_, let value?) = self, range.contains(value) else { throw PortableCoverageResult.Failure.invalid }
        return value
    }
    func double() throws -> Double {
        guard case .number(let value, _) = self, value.isFinite else { throw PortableCoverageResult.Failure.invalid }
        return value
    }

    /// A value to write, with object fields in a fixed order.
    indirect enum Output {
        case null, bool(Bool), int(Int64), double(Double), string(String), array([Output]), object([(String, Output)])

        func encoded() -> Data {
            var text = ""
            write(into: &text)
            return Data(text.utf8)
        }

        private func write(into text: inout String) {
            switch self {
            case .null: text += "null"
            case .bool(let value): text += value ? "true" : "false"
            case .int(let value): text += String(value)
            // Swift's description is the shortest text that reads back to the same bits, -0.0 included.
            case .double(let value): text += value.description
            case .string(let value): Self.quote(value, into: &text)
            case .array(let values):
                text += "["
                for (index, value) in values.enumerated() {
                    if index > 0 { text += "," }
                    value.write(into: &text)
                }
                text += "]"
            case .object(let fields):
                text += "{"
                for (index, (name, value)) in fields.enumerated() {
                    if index > 0 { text += "," }
                    Self.quote(name, into: &text)
                    text += ":"
                    value.write(into: &text)
                }
                text += "}"
            }
        }

        private static func quote(_ value: String, into text: inout String) {
            text += "\""
            for scalar in value.unicodeScalars {
                switch scalar {
                case "\"": text += "\\\""
                case "\\": text += "\\\\"
                case "\n": text += "\\n"
                case "\r": text += "\\r"
                case "\t": text += "\\t"
                case _ where scalar.value < 0x20: text += String(format: "\\u%04x", scalar.value)
                default: text.unicodeScalars.append(scalar)
                }
            }
            text += "\""
        }
    }
}

extension PortableJSON: Decodable {
    init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() { self = .null }
        else if let value = try? container.decode(Bool.self) { self = .bool(value) }
        else if let value = try? container.decode(Double.self) { self = .number(value, try? container.decode(Int64.self)) }
        else if let value = try? container.decode(String.self) { self = .string(value) }
        else if let value = try? container.decode([PortableJSON].self) { self = .array(value) }
        else { self = .object(try container.decode([String: PortableJSON].self)) }
    }
}
