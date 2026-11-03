import Foundation

enum CoverageLinkBudgetFailure: Error, Equatable { case invalidInput }

/// Explicit generic receiver assumptions; does not imply known-node equipment or reverse reception.
struct CoverageLinkBudget: Sendable {
    let transmitPowerDbm: Double
    let transmitGainDbi: Double
    let receiveGainDbi: Double
    let transmitLossDb: Double
    let receiveLossDb: Double
    let sensitivityDbm: Double
    let reserveDb: Double
    let maximumLossDb: Double
    private let receiveBeforePathLossDbm: Double

    init(transmitPowerDbm: Double, transmitGainDbi: Double, receiveGainDbi: Double,
         transmitLossDb: Double, receiveLossDb: Double, sensitivityDbm: Double, reserveDb: Double) throws {
        let values = [transmitPowerDbm, transmitGainDbi, receiveGainDbi, transmitLossDb,
                      receiveLossDb, sensitivityDbm, reserveDb]
        guard values.allSatisfy({ $0.isFinite }), transmitLossDb >= 0, receiveLossDb >= 0, reserveDb >= 0 else {
            throw CoverageLinkBudgetFailure.invalidInput
        }
        let receive = transmitPowerDbm + transmitGainDbi + receiveGainDbi - transmitLossDb - receiveLossDb
        let maximum = receive - sensitivityDbm - reserveDb
        guard receive.isFinite, maximum.isFinite else { throw CoverageLinkBudgetFailure.invalidInput }
        self.transmitPowerDbm = transmitPowerDbm
        self.transmitGainDbi = transmitGainDbi
        self.receiveGainDbi = receiveGainDbi
        self.transmitLossDb = transmitLossDb
        self.receiveLossDb = receiveLossDb
        self.sensitivityDbm = sensitivityDbm
        self.reserveDb = reserveDb
        self.receiveBeforePathLossDbm = receive
        self.maximumLossDb = maximum
    }

    /// Call only with a qualified model loss. No rounding or near-threshold classification here.
    func reception(pathLossDb: Double) throws -> (receiveLevelDbm: Double, adjustedMarginDb: Double) {
        let receive = receiveBeforePathLossDbm - pathLossDb
        // Match convergence's exact arithmetic so threshold decisions cannot diverge.
        let margin = maximumLossDb - pathLossDb
        guard pathLossDb.isFinite, receive.isFinite, margin.isFinite else {
            throw CoverageLinkBudgetFailure.invalidInput
        }
        return (receive, margin)
    }
}
