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
    companion object {
        fun fromEditor(fields: Map<String, String>, radiusMeters: Int, spacingMeters: Int): CoverageStudyInputs {
            fun value(field: String): Double {
                val text = fields[field] ?: ""
                CoverageSetupValidation.issue(field, text)?.let { throw CoverageStudyInputFailure(field, it) }
                return CoverageSetupValidation.number(text) ?: throw CoverageStudyInputFailure(field, CoverageSetupIssue.number)
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
