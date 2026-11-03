package org.nodescope.android.feature.coverage

/** Explicit generic receiver assumptions, not known-node equipment or reverse reception. */
class CoverageLinkBudget(
    val transmitPowerDbm: Double, val transmitGainDbi: Double, val receiveGainDbi: Double,
    val transmitLossDb: Double, val receiveLossDb: Double, val sensitivityDbm: Double, val reserveDb: Double,
) {
    private val receiveBeforePathLossDbm: Double
    val maximumLossDb: Double
    init {
        require(listOf(transmitPowerDbm, transmitGainDbi, receiveGainDbi, transmitLossDb,
            receiveLossDb, sensitivityDbm, reserveDb).all { it.isFinite() })
        require(transmitLossDb >= 0 && receiveLossDb >= 0 && reserveDb >= 0)
        receiveBeforePathLossDbm = transmitPowerDbm + transmitGainDbi + receiveGainDbi - transmitLossDb - receiveLossDb
        maximumLossDb = receiveBeforePathLossDbm - sensitivityDbm - reserveDb
        require(receiveBeforePathLossDbm.isFinite() && maximumLossDb.isFinite())
    }
    data class Reception(val receiveLevelDbm: Double, val adjustedMarginDb: Double)
    /** Call only with qualified loss. Match convergence arithmetic without rounding. */
    fun reception(pathLossDb: Double): Reception {
        val receive = receiveBeforePathLossDbm - pathLossDb
        val margin = maximumLossDb - pathLossDb
        require(pathLossDb.isFinite() && receive.isFinite() && margin.isFinite())
        return Reception(receive, margin)
    }
}
