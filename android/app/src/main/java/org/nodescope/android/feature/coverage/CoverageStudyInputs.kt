package org.nodescope.android.feature.coverage

class CoverageStudyInputFailure(val field: String? = null, val issue: CoverageSetupIssue? = null) : Exception("Invalid coverage study inputs")

/** Frozen editor assumptions, not a substitute for future model/environment and terrain provenance. */
class CoverageStudyInputs(val transmitter: CoverageTerrainCoordinate, val grid: CoverageGrid,
    val budget: CoverageLinkBudget, val frequencyMHz: Double, val transmitHeightMeters: Double, val receiveHeightMeters: Double) {
    init {
        if (!transmitter.latitude.isFinite() || transmitter.latitude !in -85.0..85.0 ||
            !transmitter.longitude.isFinite() || transmitter.longitude !in -180.0..180.0 ||
            !frequencyMHz.isFinite() || frequencyMHz !in 30.0..6000.0 ||
            !transmitHeightMeters.isFinite() || transmitHeightMeters !in 1.0..3000.0 ||
            !receiveHeightMeters.isFinite() || receiveHeightMeters !in 1.0..3000.0) throw CoverageStudyInputFailure()
    }
    /** Compare captured values, including assumptions with identical derived loss. */
    fun matches(other: CoverageStudyInputs): Boolean =
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
        grid.rows == other.grid.rows &&
        budget.transmitPowerDbm == other.budget.transmitPowerDbm &&
        budget.transmitGainDbi == other.budget.transmitGainDbi &&
        budget.receiveGainDbi == other.budget.receiveGainDbi &&
        budget.transmitLossDb == other.budget.transmitLossDb &&
        budget.receiveLossDb == other.budget.receiveLossDb &&
        budget.sensitivityDbm == other.budget.sensitivityDbm &&
        budget.reserveDb == other.budget.reserveDb

    companion object {
        fun fromEditor(fields: Map<String, String>, radiusMeters: Int, spacingMeters: Int, heightInFeet: Boolean = false): CoverageStudyInputs {
            fun value(field: String): Double {
                val text = fields[field] ?: ""
                CoverageSetupValidation.issue(field, text, heightInFeet)?.let { throw CoverageStudyInputFailure(field, it) }
                val number = CoverageSetupValidation.number(text) ?: throw CoverageStudyInputFailure(field, CoverageSetupIssue.number)
                return if (heightInFeet && field in listOf("txHeight", "rxHeight")) number * 0.3048 else number
            }
            val coordinate = CoverageTerrainCoordinate(value("latitude"), value("longitude"))
            val frequency = value("frequency")
            val txHeight = value("txHeight")
            val rxHeight = value("rxHeight")
            val budget = CoverageLinkBudget(value("power"), value("txGain"), value("rxGain"),
                value("txLoss"), value("rxLoss"), value("sensitivity"), value("reserve"))
            if (radiusMeters !in 1000..100_000) throw CoverageGridFailure(CoverageGridFailureState.invalidParameters)
            val grid = CoverageGrid(radiusMeters, spacingMeters, -radiusMeters, radiusMeters, -radiusMeters, radiusMeters)
            return CoverageStudyInputs(coordinate, grid, budget, frequency, txHeight, rxHeight)
        }
    }
}
