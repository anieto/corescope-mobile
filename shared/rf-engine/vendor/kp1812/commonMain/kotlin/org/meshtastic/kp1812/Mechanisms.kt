/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.kp1812

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/*
 * The non-diffraction propagation mechanisms of ITU-R P.1812-8: troposcatter (§4.4) and
 * ducting / layer reflection (§4.5), plus the path-zone helpers of §3.
 */

/** Basic transmission loss due to troposcatter, dB. Eq (44), (45). */
internal fun tlTropo(dtot: Double, theta: Double, f: Double, p: Double, n0: Double): Double {
    val lf = 25 * log10(f) - 2.5 * (log10(f / 2.0)).pow(2) // (45)
    return 190.1 + lf + 20 * log10(dtot) + 0.573 * theta - 0.15 * n0 -
        10.125 * (log10(50.0 / p)).pow(0.7) // (44)
}

/** Basic transmission loss during ducting / layer reflection, dB. Eq (46)–(56). */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
internal fun tlAnomalous(
    dtot: Double,
    dlt: Double,
    dlr: Double,
    dct: Double,
    dcr: Double,
    dlm: Double,
    hts: Double,
    hrs: Double,
    hte: Double,
    hre: Double,
    hm: Double,
    thetaT: Double,
    thetaR: Double,
    f: Double,
    p: Double,
    omega: Double,
    ae: Double,
    b0: Double,
): Double {
    // Empirical correction for frequencies below 0.5 GHz. Eq (47a)
    val alf = if (f < 0.5) 45.375 - 137.0 * f + 92.5 * f * f else 0.0

    // Site-shielding diffraction losses for the terminals. Eq (48)
    val thetaT2 = thetaT - 0.1 * dlt // (48a)
    val thetaR2 = thetaR - 0.1 * dlr // (48a)
    val ast = if (thetaT2 > 0) {
        20 * log10(1 + 0.361 * thetaT2 * sqrt(f * dlt)) + 0.264 * thetaT2 * f.pow(1.0 / 3.0) // (48)
    } else {
        0.0
    }
    val asr = if (thetaR2 > 0) {
        20 * log10(1 + 0.361 * thetaR2 * sqrt(f * dlr)) + 0.264 * thetaR2 * f.pow(1.0 / 3.0) // (48)
    } else {
        0.0
    }

    // Over-sea surface duct coupling corrections. Eq (49)
    val act = if (dct <= 5 && dct <= dlt && omega >= OMEGA_SEA_DUCT) {
        -3 * exp(-0.25 * dct * dct) * (1 + tanh(0.07 * (50 - hts))) // (49)
    } else {
        0.0
    }
    val acr = if (dcr <= 5 && dcr <= dlr && omega >= OMEGA_SEA_DUCT) {
        -3 * exp(-0.25 * dcr * dcr) * (1 + tanh(0.07 * (50 - hrs))) // (49)
    } else {
        0.0
    }

    val gammaD = 5e-5 * ae * f.pow(1.0 / 3.0) // (51)
    val thetaT1 = min(thetaT, 0.1 * dlt) // (52a)
    val thetaR1 = min(thetaR, 0.1 * dlr) // (52a)
    val theta1 = 1e3 * dtot / ae + thetaT1 + thetaR1 // (52)

    val di = min(dtot - dlt - dlr, 40.0) // (56a)
    val mu3 = if (hm > 10) exp(-4.6e-5 * (hm - 10) * (43 + 6 * di)) else 1.0 // (56)
    val tau = 1 - exp(-(4.12e-4 * dlm.pow(2.41))) // (3)
    val alpha = max(-0.6 - EPSILON * 1e-9 * dtot.pow(3.1) * tau, -3.4) // (55a)
    val mu2 = min((500 / ae * dtot.pow(2) / (sqrt(hte) + sqrt(hre)).pow(2)).pow(alpha), 1.0) // (55)
    val beta = b0 * mu2 * mu3 // (54)

    val gamma = 1.076 / (2.0058 - log10(beta)).pow(1.012) *
        exp(-(9.51 - 4.8 * log10(beta) + 0.198 * (log10(beta)).pow(2)) * 1e-6 * dtot.pow(1.13)) // (53a)
    val ap = -12 + (1.2 + 3.7e-3 * dtot) * log10(p / beta) + 12 * (p / beta).pow(gamma) // (53)
    val adp = gammaD * theta1 + ap // (50)

    val af = 102.45 + 20 * log10(f) + 20 * log10(dlt + dlr) + alf + ast + asr + act + acr // (47)
    return af + adp // (46)
}

private const val OMEGA_SEA_DUCT = 0.75
private const val EPSILON = 3.5

/**
 * Standard deviation of location variability, dB. Eq (68).
 *
 * @param f frequency, GHz
 * @param h receiver height above ground, m
 * @param r representative clutter height at the receiver, m
 * @param wa prediction resolution, m
 */
internal fun stdDev(f: Double, h: Double, r: Double, wa: Double): Double {
    val uh = when {
        h < r -> 1.0
        h >= r + 10 -> 0.0
        else -> 1 - (h - r) / 10.0
    }
    return (0.52 + 0.024 * f) * wa.pow(0.28) * uh
}

// ── Path zone helpers (§3.1, §3.6) ───────────────────────────────────────────

/** Zone codes as used by P.1812: sea, coastal land, inland. */
internal const val ZONE_SEA = 1
internal const val ZONE_COASTAL_LAND = 3
internal const val ZONE_INLAND = 4

/** Start/stop index pairs of each maximal run where [mask] is true. */
internal fun findIntervals(mask: BooleanArray): Pair<IntArray, IntArray> {
    val starts = mutableListOf<Int>()
    val stops = mutableListOf<Int>()
    var i = 0
    while (i < mask.size) {
        if (mask[i]) {
            val s = i
            while (i + 1 < mask.size && mask[i + 1]) i++
            starts.add(s)
            stops.add(i)
        }
        i++
    }
    return starts.toIntArray() to stops.toIntArray()
}

/** Longest continuous run of [zoneRef] along the path, km. `34` means inland + coastal land. */
internal fun longestContDist(d: DoubleArray, zone: IntArray, zoneRef: Int): Double {
    val mask = BooleanArray(zone.size) {
        if (zoneRef == ZONE_LAND_COMBINED) {
            zone[it] == ZONE_COASTAL_LAND || zone[it] == ZONE_INLAND
        } else {
            zone[it] == zoneRef
        }
    }
    val (starts, stops) = findIntervals(mask)
    var dm = 0.0
    for (i in starts.indices) {
        var delta = 0.0
        if (d[stops[i]] < d[d.size - 1]) delta += (d[stops[i] + 1] - d[stops[i]]) / 2.0
        if (d[starts[i]] > 0) delta += (d[starts[i]] - d[starts[i] - 1]) / 2.0
        dm = max(d[stops[i]] - d[starts[i]] + delta, dm)
    }
    return dm
}

internal const val ZONE_LAND_COMBINED = 34

/** Fraction of the path over [zoneRef]. Eq (1). */
internal fun pathFraction(d: DoubleArray, zone: IntArray, zoneRef: Int): Double {
    val (starts, stops) = findIntervals(BooleanArray(zone.size) { zone[it] == zoneRef })
    var dm = 0.0
    for (i in starts.indices) {
        var delta = 0.0
        if (d[stops[i]] < d[d.size - 1]) delta += (d[stops[i] + 1] - d[stops[i]]) / 2.0
        if (d[starts[i]] > 0) delta += (d[starts[i]] - d[starts[i] - 1]) / 2.0
        dm += d[stops[i]] - d[starts[i]] + delta
    }
    return dm / (d[d.size - 1] - d[0]) // (1)
}

/** Natural log helper kept next to its only use, the ducting/LoS combination. Eq (60). */
internal fun logSumExp(a: Double, b: Double, eta: Double): Double = eta * ln(exp(a / eta) + exp(b / eta))
