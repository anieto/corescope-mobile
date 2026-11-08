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

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Diffraction and path geometry from Recommendation ITU-R P.1812-8, §4.3 and Attachment 1.
 *
 * Every function carries the equation numbers it implements. That traceability is the reason this
 * is a port of P.1812 rather than of SPLAT!'s ITWOM: the ITU publishes a reference implementation
 * and validation data for each intermediate, so a defect names an equation instead of a pixel.
 */

/** Median effective Earth radius `ae`, and the radius `ab` exceeded for beta0% of time. Eq (6), (7). */
internal fun earthRadEff(dn: Double): Pair<Double, Double> = (6371.0 * (157.0 / (157.0 - dn))) to (6371.0 * 3.0)

/** Free-space and line-of-sight basic transmission loss, dB. Eq (8)–(11). */
internal fun plLos(
    d: Double,
    hts: Double,
    hrs: Double,
    f: Double,
    p: Double,
    b0: Double,
    dlt: Double,
    dlr: Double,
): Triple<Double, Double, Double> {
    val dfs2 = d * d + ((hts - hrs) / 1000.0).pow(2) // (8a)
    val lbfs = 92.4 + 20.0 * log10(f) + 10.0 * log10(dfs2) // (8)
    val esp = 2.6 * (1 - exp(-0.1 * (dlt + dlr))) * log10(p / 50.0) // (9a)
    val esb = 2.6 * (1 - exp(-0.1 * (dlt + dlr))) * log10(b0 / 50.0) // (9b)
    return Triple(lbfs, lbfs + esp, lbfs + esb) // (10), (11)
}

/** Time percentage for which refractivity exceeds 100 N-units/km. Eq (2)–(5). */
internal fun beta0(phi: Double, dtm: Double, dlm: Double): Double {
    val tau = 1 - exp(-(4.12e-4 * dlm.pow(2.41))) // (3)
    var mu1 = (10.0.pow(-dtm / (16 - 6.6 * tau)) + 10.0.pow(-5 * (0.496 + 0.354 * tau))).pow(0.2) // (2)
    if (mu1 > 1) mu1 = 1.0
    return if (abs(phi) <= 70) {
        10.0.pow(-0.015 * abs(phi) + 1.67) * mu1 * mu1.pow(-0.935 + 0.0176 * abs(phi)) // (4), (5)
    } else {
        4.17 * mu1 * mu1.pow(0.3) // (4), (5)
    }
}

/** Approximation to the inverse cumulative normal distribution. Eq (96), (97). */
internal fun invCumNorm(x: Double): Double {
    val c = x.coerceIn(0.000001, 0.999999)
    return if (c <= 0.5) tFn(c) - cFn(c) else -(tFn(1 - c) - cFn(1 - c)) // (96a), (96b)
}

private fun tFn(y: Double): Double = sqrt(-2.0 * ln(y)) // (97a)

private fun cFn(z: Double): Double { // (97b)
    val t = tFn(z)
    return (((0.010328 * t + 0.802853) * t) + 2.515516698) / (((0.001308 * t + 0.189269) * t + 1.432788) * t + 1)
}

/** Bullington part of the diffraction loss, dB. Eq (12)–(21). */
@Suppress("LongParameterList")
internal fun dlBull(d: DoubleArray, g: DoubleArray, hts: Double, hrs: Double, ap: Double, f: Double): Double {
    val ce = 1.0 / ap
    val lam = 0.2998 / f
    val dtot = d[d.size - 1] - d[0]

    var stim = Double.NEGATIVE_INFINITY
    for (i in 1 until d.size - 1) {
        stim = max(stim, (g[i] + 500 * ce * d[i] * (dtot - d[i]) - hts) / d[i]) // (13)
    }
    val str = (hrs - hts) / dtot // (14)

    val nu: Double
    if (stim < str) { // Case 1: line of sight
        var numax = Double.NEGATIVE_INFINITY
        for (i in 1 until d.size - 1) {
            val v = (g[i] + 500 * ce * d[i] * (dtot - d[i]) - (hts * (dtot - d[i]) + hrs * d[i]) / dtot) *
                sqrt(0.002 * dtot / (lam * d[i] * (dtot - d[i]))) // (15)
            numax = max(numax, v)
        }
        nu = numax
    } else { // Case 2: transhorizon
        var srim = Double.NEGATIVE_INFINITY
        for (i in 1 until d.size - 1) {
            srim = max(srim, (g[i] + 500 * ce * d[i] * (dtot - d[i]) - hrs) / (dtot - d[i])) // (17)
        }
        val dbp = (hrs - hts + srim * dtot) / (stim + srim) // (18)
        nu = (hts + stim * dbp - (hts * (dtot - dbp) + hrs * dbp) / dtot) *
            sqrt(0.002 * dtot / (lam * dbp * (dtot - dbp))) // (20)
    }
    val luc = if (nu > -0.78) 6.9 + 20 * log10(sqrt((nu - 0.1).pow(2) + 1) + nu - 0.1) else 0.0 // (12), (16), (20)
    return luc + (1 - exp(-luc / 6.0)) * (10 + 0.02 * dtot) // (21)
}

/** First-term spherical-earth diffraction for one set of ground constants. Eq (29)–(36). */
@Suppress("LongParameterList")
internal fun dlSeFtInner(
    epsr: Double,
    sigma: Double,
    d: Double,
    hte: Double,
    hre: Double,
    adft: Double,
    f: Double,
): DoubleArray {
    val k0 = 0.036 * (adft * f).pow(-1.0 / 3.0) *
        ((epsr - 1).pow(2) + (18 * sigma / f).pow(2.0)).pow(-1.0 / 4.0) // (29a)
    val k = doubleArrayOf(k0, k0 * (epsr.pow(2) + (18 * sigma / f).pow(2)).pow(0.5)) // (29b)

    return DoubleArray(2) { i ->
        val ki = k[i]
        val beta = (1 + 1.6 * ki.pow(2) + 0.67 * ki.pow(4)) / (1 + 4.5 * ki.pow(2) + 1.53 * ki.pow(4)) // (30)
        val x = 21.88 * beta * (f / adft.pow(2)).pow(1.0 / 3.0) * d // (31)
        val yt = 0.9575 * beta * (f * f / adft).pow(1.0 / 3.0) * hte // (32a)
        val yr = 0.9575 * beta * (f * f / adft).pow(1.0 / 3.0) * hre // (32b)

        val fx = if (x >= 1.6) 11 + 10 * log10(x) - 17.6 * x else -20 * log10(x) - 5.6488 * x.pow(1.425) // (33)
        val floor = 2 + 20 * log10(ki)
        val gyt = max(gFn(beta * yt), floor) // (34), (35)
        val gyr = max(gFn(beta * yr), floor) // (34), (35)
        -fx - gyt - gyr // (36)
    }
}

private fun gFn(b: Double): Double =
    if (b > 2) 17.6 * (b - 1.1).pow(0.5) - 5 * log10(b - 1.1) - 8 else 20 * log10(b + 0.1 * b.pow(3))

/** First-term spherical-earth diffraction over a mixed land/sea path. Eq (28). */
@Suppress("LongParameterList")
internal fun dlSeFt(d: Double, hte: Double, hre: Double, adft: Double, f: Double, omega: Double): DoubleArray {
    val land = dlSeFtInner(EPSR_LAND, SIGMA_LAND, d, hte, hre, adft, f)
    val sea = dlSeFtInner(EPSR_SEA, SIGMA_SEA, d, hte, hre, adft, f)
    return DoubleArray(2) { omega * sea[it] + (1 - omega) * land[it] } // (28)
}

private const val EPSR_LAND = 22.0
private const val SIGMA_LAND = 0.003
private const val EPSR_SEA = 80.0
private const val SIGMA_SEA = 5.0

/** Spherical-earth diffraction loss, dB, per polarization. Eq (22)–(27). */
@Suppress("LongParameterList", "ReturnCount")
internal fun dlSe(d: Double, hte: Double, hre: Double, ap: Double, f: Double, omega: Double): DoubleArray {
    val lam = 0.2998 / f
    val dlos = sqrt(2.0 * ap) * (sqrt(0.001 * hte) + sqrt(0.001 * hre)) // (22)
    if (d >= dlos) return dlSeFt(d, hte, hre, ap, f, omega)

    val c = (hte - hre) / (hte + hre) // (24d)
    val m = 250 * d * d / (ap * (hte + hre)) // (24e)
    val b = 2 * sqrt((m + 1.0) / (3.0 * m)) *
        cos(PI_3 + 1.0 / 3.0 * acos(3 * c / 2.0 * sqrt(3.0 * m / (m + 1.0).pow(3)))) // (24c)
    val dse1 = d / 2.0 * (1.0 + b) // (24a)
    val dse2 = d - dse1 // (24b)
    val hse = ((hte - 500 * dse1 * dse1 / ap) * dse2 + (hre - 500 * dse2 * dse2 / ap) * dse1) / d // (23)
    val hreq = 17.456 * sqrt(dse1 * dse2 * lam / d) // (25)
    if (hse > hreq) return DoubleArray(2)

    val aem = 500 * (d / (sqrt(hte) + sqrt(hre))).pow(2) // (26)
    val ldft = dlSeFt(d, hte, hre, aem, f, omega)
    return DoubleArray(2) { (1 - hse / hreq) * max(ldft[it], 0.0) } // (27)
}

private const val PI_3 = kotlin.math.PI / 3.0

/** Result of the delta-Bullington construction. */
internal class DeltaBull(val ld: DoubleArray, val lbulla: Double, val lbulls: Double, val ldsph: DoubleArray)

/** Delta-Bullington diffraction loss. Eq (37)–(39). */
@Suppress("LongParameterList")
internal fun dlDeltaBull(
    d: DoubleArray,
    g: DoubleArray,
    hts: Double,
    hrs: Double,
    hstd: Double,
    hsrd: Double,
    ap: Double,
    f: Double,
    omega: Double,
): DeltaBull {
    val lbulla = dlBull(d, g, hts, hrs, ap, f)
    val hts1 = hts - hstd // (37a)
    val hrs1 = hrs - hsrd // (37b)
    val dtot = d[d.size - 1] - d[0]
    val lbulls = dlBull(d, DoubleArray(g.size), hts1, hrs1, ap, f)
    val ldsph = dlSe(dtot, hts1, hrs1, ap, f, omega) // (38a), (38b)
    return DeltaBull(DoubleArray(2) { lbulla + max(ldsph[it] - lbulls, 0.0) }, lbulla, lbulls, ldsph) // (39)
}

/** Diffraction loss not exceeded for p% time, with its 50% and beta0% components. */
internal class DlP(
    val ldp: DoubleArray,
    val ldb: DoubleArray,
    val ld50: DoubleArray,
    val lbulla50: Double,
    val lbulls50: Double,
    val ldsph50: DoubleArray,
)

/** Diffraction loss not exceeded for p% time. Eq (40a), (41). */
@Suppress("LongParameterList")
internal fun dlP(
    d: DoubleArray,
    g: DoubleArray,
    hts: Double,
    hrs: Double,
    hstd: Double,
    hsrd: Double,
    f: Double,
    omega: Double,
    p: Double,
    b0: Double,
    dn: Double,
): DlP {
    val (ae, ab) = earthRadEff(dn)
    val at50 = dlDeltaBull(d, g, hts, hrs, hstd, hsrd, ae, f, omega)
    val atb = dlDeltaBull(d, g, hts, hrs, hstd, hsrd, ab, f, omega)
    val ldp = if (p == 50.0) {
        at50.ld
    } else {
        val fi = if (p > b0) invCumNorm(p / 100) / invCumNorm(b0 / 100) else 1.0 // (40a)
        DoubleArray(2) { at50.ld[it] + fi * (atb.ld[it] - at50.ld[it]) } // (41)
    }
    // The reference reports the sub-results of the LAST delta-Bullington call, which is the ab one.
    return DlP(ldp, atb.ld, at50.ld, atb.lbulla, atb.lbulls, atb.ldsph)
}

/** Smooth-earth heights, horizon distances and path angles. */
@Suppress("LongParameterList")
internal class SmoothEarth(
    val hstN: Double,
    val hsrN: Double,
    val hst: Double,
    val hsr: Double,
    val hstd: Double,
    val hsrd: Double,
    val hte: Double,
    val hre: Double,
    val hm: Double,
    val dlt: Double,
    val dlr: Double,
    val thetaT: Double,
    val thetaR: Double,
    val thetaTot: Double,
    val pathtype: Int,
)

/** Smooth-earth heights and horizon geometry. Eq (76)–(95), Attachment 1. */
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
internal fun smoothEarthHeights(
    d: DoubleArray,
    h: DoubleArray,
    htg: Double,
    hrg: Double,
    ae: Double,
    f: Double,
): SmoothEarth {
    val n = d.size
    val dtot = d[n - 1]
    val hts = h[0] + htg
    val hrs = h[n - 1] + hrg

    var v1 = 0.0
    var v2 = 0.0
    for (i in 1 until n) {
        v1 += (d[i] - d[i - 1]) * (h[i] + h[i - 1]) // (85)
        v2 += (d[i] - d[i - 1]) * (h[i] * (2 * d[i] + d[i - 1]) + h[i - 1] * (d[i] + 2 * d[i - 1])) // (86)
    }
    var hst = (2 * v1 * dtot - v2) / (dtot * dtot) // (87)
    var hsr = (v2 - v1 * dtot) / (dtot * dtot) // (88)
    val hstN = hst
    val hsrN = hsr

    var hobs = Double.NEGATIVE_INFINITY
    var alphaObt = Double.NEGATIVE_INFINITY
    var alphaObr = Double.NEGATIVE_INFINITY
    for (i in 1 until n - 1) {
        val hh = h[i] - (hts * (dtot - d[i]) + hrs * d[i]) / dtot // (89d)
        hobs = max(hobs, hh) // (89a)
        alphaObt = max(alphaObt, hh / d[i]) // (89b)
        alphaObr = max(alphaObr, hh / (dtot - d[i])) // (89c)
    }
    val gt = alphaObt / (alphaObt + alphaObr) // (90e)
    val gr = alphaObr / (alphaObt + alphaObr) // (90f)
    val hstp = if (hobs <= 0) hst else hst - hobs * gt // (90a), (90c)
    val hsrp = if (hobs <= 0) hsr else hsr - hobs * gr // (90b), (90d)
    val hstd = if (hstp >= h[0]) h[0] else hstp // (91a), (91b)
    val hsrd = if (hsrp > h[n - 1]) h[n - 1] else hsrp // (91c), (91d)

    val theta = DoubleArray(n - 2) { i ->
        val j = i + 1
        1000 * atan((h[j] - hts) / (1000 * d[j]) - d[j] / (2 * ae)) // (77)
    }
    val thetaTd = 1000 * atan((hrs - hts) / (1000 * dtot) - dtot / (2 * ae)) // (78)
    val thetaRd = 1000 * atan((hts - hrs) / (1000 * dtot) - dtot / (2 * ae)) // (81)
    val thetaMax = theta.max() // (76)
    val pathtype = if (thetaMax > thetaTd) PATH_TRANSHORIZON else PATH_LOS // (150)
    val thetaT = max(thetaMax, thetaTd) // (79)

    val dlt: Double
    val dlr: Double
    val thetaR: Double
    val lt: Int
    val lr: Int
    if (pathtype == PATH_TRANSHORIZON) {
        lt = theta.indexOfFirst { it == thetaMax } + 1
        dlt = d[lt] // (80)
        val theta2 = DoubleArray(n - 2) { i ->
            val j = i + 1
            1000 * atan((h[j] - hrs) / (1000 * (dtot - d[j])) - (dtot - d[j]) / (2 * ae)) // (82a)
        }
        thetaR = theta2.max()
        lr = theta2.indexOfLast { it == thetaR } + 1
        dlr = dtot - d[lr] // (83)
    } else {
        thetaR = thetaRd // (81)
        val lam = 0.2998 / f
        val ce = 1.0 / ae
        val nu = DoubleArray(n - 2) { i ->
            val j = i + 1
            (h[j] + 500 * ce * d[j] * (dtot - d[j]) - (hts * (dtot - d[j]) + hrs * d[j]) / dtot) *
                sqrt(0.002 * dtot / (lam * d[j] * (dtot - d[j]))) // (81)
        }
        val numax = nu.max()
        lt = nu.indexOfLast { it == numax } + 1
        dlt = d[lt] // (80)
        dlr = dtot - dlt // (83a)
        lr = lt
    }
    val thetaTot = 1e3 * dtot / ae + thetaT + thetaR // (84)

    hst = min(hst, h[0]) // (92a)
    hsr = min(hsr, h[n - 1]) // (92b)
    val m = (hsr - hst) / dtot // (93)
    var hm = Double.NEGATIVE_INFINITY
    for (i in lt..lr) {
        hm = max(hm, h[i] - (hst + m * d[i])) // (95)
    }
    return SmoothEarth(
        hstN, hsrN, hst, hsr, hstd, hsrd,
        htg + h[0] - hst, // (94a)
        hrg + h[n - 1] - hsr, // (94b)
        hm, dlt, dlr, thetaT, thetaR, thetaTot, pathtype,
    )
}

internal const val PATH_LOS = 1
internal const val PATH_TRANSHORIZON = 2
