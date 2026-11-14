package org.nodescope.android.feature.coverage

import kotlin.math.abs
import kotlin.math.log10

/**
 * A region or modem preset for Estimated Coverage, after the link-budget sheet on meshpeaks.com: it
 * fixes the frequency, spreading factor and bandwidth, and the receiver sensitivity follows from
 * those. Values are starting points for planning, not a radio's reported configuration. Matches iOS.
 */
data class CoverageRadioPreset(
    val id: String, val family: Family, val title: String, val frequencyMHz: Double,
    val spreadingFactor: Int, val bandwidthKHz: Double, val transmitPowerDbm: Double,
) {
    enum class Family(val title: String) { MESHCORE("MeshCore"), MESHTASTIC("Meshtastic") }

    val sensitivityDbm: Double get() = sensitivity(spreadingFactor, bandwidthKHz)

    /** "SF7 · 62.5 kHz", the modem settings behind the sensitivity. */
    val modemSummary: String get() {
        val bandwidth = if (bandwidthKHz % 1.0 == 0.0) bandwidthKHz.toLong().toString() else bandwidthKHz.toString()
        return "SF$spreadingFactor · $bandwidth kHz"
    }

    companion object {
        /**
         * LoRa receiver sensitivity: thermal noise over the bandwidth, a 6 dB receiver noise figure and
         * the demodulator's SNR limit of 10 − 2.5·SF dB, rounded to 0.5 dB. The same estimate
         * meshpeaks.com uses; a radio's datasheet figure may differ by a few dB.
         */
        fun sensitivity(spreadingFactor: Int, bandwidthKHz: Double, noiseFigureDb: Double = 6.0): Double {
            val thermalNoise = -174 + 10 * log10(bandwidthKHz * 1000)
            val snrLimit = 10 - 2.5 * spreadingFactor
            // Swift's .rounded() rounds half away from zero; match it rather than Kotlin's half-even.
            val doubled = (thermalNoise + noiseFigureDb + snrLimit) * 2
            return (if (doubled < 0) -(-doubled).roundHalfUp() else doubled.roundHalfUp()) / 2
        }

        private fun Double.roundHalfUp(): Double = (this + 0.5).let { kotlin.math.floor(it) }

        val all = listOf(
            CoverageRadioPreset("meshcore-us-ca", Family.MESHCORE, "USA/Canada", 910.525, 7, 62.5, 22.0),
            CoverageRadioPreset("meshcore-eu-uk-narrow", Family.MESHCORE, "EU/UK (Narrow)", 869.618, 8, 62.5, 14.0),
            CoverageRadioPreset("meshcore-eu-433-narrow", Family.MESHCORE, "EU 433 MHz (Narrow)", 433.65, 8, 62.5, 10.0),
            CoverageRadioPreset("meshcore-au-narrow", Family.MESHCORE, "Australia (Narrow)", 916.575, 7, 62.5, 22.0),
            CoverageRadioPreset("meshcore-nz-narrow", Family.MESHCORE, "New Zealand (Narrow)", 917.375, 7, 62.5, 22.0),
            CoverageRadioPreset("meshtastic-us-long-fast", Family.MESHTASTIC, "US Long Fast", 906.875, 11, 250.0, 22.0),
            CoverageRadioPreset("meshtastic-eu-868-long-fast", Family.MESHTASTIC, "EU 868 Long Fast", 869.525, 11, 250.0, 27.0),
        )

        /** The opening preset, matching Link Profile's. */
        val standard: CoverageRadioPreset get() = all[0]

        fun preset(id: String?): CoverageRadioPreset? = id?.let { wanted -> all.firstOrNull { it.id == wanted } }

        fun presets(family: Family): List<CoverageRadioPreset> = all.filter { it.family == family }

        /** The preset these radio values came from, or null once any of them has been edited (a custom radio). */
        fun matching(frequencyMHz: Double?, transmitPowerDbm: Double?, sensitivityDbm: Double?): CoverageRadioPreset? =
            all.firstOrNull { it.frequencyMHz == frequencyMHz && it.transmitPowerDbm == transmitPowerDbm && it.sensitivityDbm == sensitivityDbm }
    }
}

/** Common antenna types and their nominal gains, as offered on meshpeaks.com. */
data class CoverageAntennaPreset(val id: String, val title: String, val gainDbi: Double) {
    companion object {
        val all = listOf(
            CoverageAntennaPreset("quarter-wave", "1/4-wave ground plane", 1.5),
            CoverageAntennaPreset("half-wave-dipole", "1/2-wave dipole", 2.15),
            CoverageAntennaPreset("omni-3", "3 dBi omni", 3.0),
            CoverageAntennaPreset("omni-5", "5 dBi omni", 5.0),
            CoverageAntennaPreset("omni-6", "6 dBi omni", 6.0),
            CoverageAntennaPreset("yagi-small", "Small Yagi", 4.5),
            CoverageAntennaPreset("yagi-high", "High-gain Yagi", 9.0),
        )

        /** The preset with this gain, if one matches to within a hundredth of a dB. */
        fun matching(gainDbi: Double): CoverageAntennaPreset? = all.firstOrNull { abs(it.gainDbi - gainDbi) < 0.01 }
    }
}

/** Typical antenna heights above ground. Stored in meters; shown in the preferred unit. */
data class CoverageHeightPreset(val id: String, val title: String, val meters: Double) {
    companion object {
        val HANDHELD = CoverageHeightPreset("handheld", "Handheld", 1.5)
        val VEHICLE = CoverageHeightPreset("vehicle", "Vehicle", 2.0)
        val ROOFTOP = CoverageHeightPreset("rooftop", "Rooftop", 6.0)
        val MAST = CoverageHeightPreset("mast", "Mast", 10.0)
        val TOWER = CoverageHeightPreset("tower", "Tower", 30.0)

        val transmitter = listOf(HANDHELD, ROOFTOP, MAST, TOWER)
        val receiver = listOf(HANDHELD, VEHICLE, ROOFTOP)

        /** The preset at this height, if one matches to within 15 cm. */
        fun matching(meters: Double, presets: List<CoverageHeightPreset>): CoverageHeightPreset? =
            presets.firstOrNull { abs(it.meters - meters) < 0.15 }
    }
}

/** Planning reserve (fade margin) choices. None is a valid, explicit choice. */
object CoverageReservePreset {
    val choicesDb = listOf(0.0, 6.0, 10.0)
}
