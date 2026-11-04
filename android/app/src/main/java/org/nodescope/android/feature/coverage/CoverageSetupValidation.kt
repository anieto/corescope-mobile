package org.nodescope.android.feature.coverage

enum class CoverageSetupIssue { required, number, latitude, longitude, frequency, height, nonnegative, unknownField }

/** Basic editor validation; passing does not establish terrain availability or RF model suitability. */
object CoverageSetupValidation {
    private val numeric = Regex("""[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?""")
    fun number(text: String): Double? = text.trim().takeIf { numeric.matches(it) }?.toDoubleOrNull()?.takeIf { it.isFinite() }
    fun issue(field: String, text: String): CoverageSetupIssue? {
        if (field !in listOf("latitude", "longitude", "frequency", "power", "txHeight", "txGain", "txLoss",
                "rxHeight", "rxGain", "rxLoss", "sensitivity", "reserve")) return CoverageSetupIssue.unknownField
        if (text.isBlank()) return CoverageSetupIssue.required
        val value = number(text) ?: return CoverageSetupIssue.number
        return when (field) {
            "latitude" -> if (value in -85.0..85.0) null else CoverageSetupIssue.latitude
            "longitude" -> if (value in -180.0..180.0) null else CoverageSetupIssue.longitude
            "frequency" -> if (value in 30.0..6000.0) null else CoverageSetupIssue.frequency
            "txHeight", "rxHeight" -> if (value in 1.0..3000.0) null else CoverageSetupIssue.height
            "txLoss", "rxLoss", "reserve" -> if (value >= 0) null else CoverageSetupIssue.nonnegative
            else -> null
        }
    }
}
