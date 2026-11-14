import Foundation

enum CoverageStudyInputFailure: Error, Equatable {
    case field(String, CoverageSetupIssue)
    case invalidRadio
}

/// Immutable editor assumptions captured before terrain/model work. Model/environment and terrain
/// provenance still belong to the future production engine binding, not inferred defaults here.
struct CoverageStudyInputs: Sendable {
    let transmitter: CoverageTerrainCoordinate
    let grid: CoverageGrid
    let budget: CoverageLinkBudget
    let frequencyMHz: Double
    let transmitHeightMeters: Double
    let receiveHeightMeters: Double

    init(transmitter: CoverageTerrainCoordinate, grid: CoverageGrid, budget: CoverageLinkBudget,
         frequencyMHz: Double, transmitHeightMeters: Double, receiveHeightMeters: Double) throws {
        guard transmitter.latitude.isFinite, (-85...85).contains(transmitter.latitude),
              transmitter.longitude.isFinite, (-180...180).contains(transmitter.longitude),
              frequencyMHz.isFinite, (30...6000).contains(frequencyMHz),
              transmitHeightMeters.isFinite, (1...3000).contains(transmitHeightMeters),
              receiveHeightMeters.isFinite, (1...3000).contains(receiveHeightMeters) else {
            throw CoverageStudyInputFailure.invalidRadio
        }
        self.transmitter = transmitter
        self.grid = grid
        self.budget = budget
        self.frequencyMHz = frequencyMHz
        self.transmitHeightMeters = transmitHeightMeters
        self.receiveHeightMeters = receiveHeightMeters
    }

    /// Inputs that change terrain paths or propagation loss; excludes the radio budget.
    func matchesPropagation(_ other: CoverageStudyInputs) -> Bool {
        transmitter.latitude == other.transmitter.latitude &&
        transmitter.longitude == other.transmitter.longitude &&
        frequencyMHz == other.frequencyMHz &&
        transmitHeightMeters == other.transmitHeightMeters &&
        receiveHeightMeters == other.receiveHeightMeters &&
        grid.radiusMeters == other.grid.radiusMeters &&
        grid.spacingMeters == other.grid.spacingMeters &&
        grid.minEastMeters == other.grid.minEastMeters &&
        grid.maxNorthMeters == other.grid.maxNorthMeters &&
        grid.columns == other.grid.columns &&
        grid.rows == other.grid.rows
    }

    func matches(_ other: CoverageStudyInputs) -> Bool {
        matchesPropagation(other) &&
        budget.transmitPowerDbm == other.budget.transmitPowerDbm &&
        budget.transmitGainDbi == other.budget.transmitGainDbi &&
        budget.receiveGainDbi == other.budget.receiveGainDbi &&
        budget.transmitLossDb == other.budget.transmitLossDb &&
        budget.receiveLossDb == other.budget.receiveLossDb &&
        budget.sensitivityDbm == other.budget.sensitivityDbm &&
        budget.reserveDb == other.budget.reserveDb
    }

    /// Radius-only growth may reuse exact sample positions; every RF input must match.
    func canExpand(to other: CoverageStudyInputs) -> Bool {
        guard other.grid.radiusMeters > grid.radiusMeters,
              other.grid.spacingMeters == grid.spacingMeters,
              let sameGrid = try? CoverageStudyInputs(transmitter: other.transmitter, grid: grid,
                  budget: other.budget, frequencyMHz: other.frequencyMHz,
                  transmitHeightMeters: other.transmitHeightMeters,
                  receiveHeightMeters: other.receiveHeightMeters) else { return false }
        return matches(sameGrid)
    }

    static func fromEditor(fields: [String: String], radiusMeters: Int, spacingMeters: Int, heightInFeet: Bool = false) throws -> Self {
        func value(_ field: String) throws -> Double {
            let text = fields[field, default: ""]
            if let issue = CoverageSetupValidation.issue(field: field, text: text, heightInFeet: heightInFeet) {
                throw CoverageStudyInputFailure.field(field, issue)
            }
            guard let number = CoverageSetupValidation.number(text) else {
                throw CoverageStudyInputFailure.field(field, .number)
            }
            return heightInFeet && ["txHeight", "rxHeight"].contains(field) ? number * 0.3048 : number
        }
        let coordinate = try CoverageTerrainCoordinate(latitude: value("latitude"), longitude: value("longitude"))
        let frequency = try value("frequency"), txHeight = try value("txHeight"), rxHeight = try value("rxHeight")
        let budget = try CoverageLinkBudget(transmitPowerDbm: value("power"), transmitGainDbi: value("txGain"),
            receiveGainDbi: value("rxGain"), transmitLossDb: value("txLoss"), receiveLossDb: value("rxLoss"),
            sensitivityDbm: value("sensitivity"), reserveDb: value("reserve"))
        // Validate radius before negating it, including hostile Int.min values.
        guard (1000...100_000).contains(radiusMeters) else { throw CoverageGridFailure.invalidParameters }
        let grid = try CoverageGrid.studyArea(radiusMeters: radiusMeters, spacingMeters: spacingMeters)
        return try Self(transmitter: coordinate, grid: grid, budget: budget, frequencyMHz: frequency,
                        transmitHeightMeters: txHeight, receiveHeightMeters: rxHeight)
    }
}
