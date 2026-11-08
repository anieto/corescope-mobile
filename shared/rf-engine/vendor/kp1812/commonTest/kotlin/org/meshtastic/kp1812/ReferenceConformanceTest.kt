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
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Checks every ported function against calls captured from the ITU reference implementation
 * running over its 19 official validation profiles.
 *
 * This is the whole reason the engine is P.1812 and not SPLAT!'s ITWOM: a defect names an
 * *equation*, not a pixel. If [dlBull] passes and [dlSe] fails, the block of the
 * Recommendation to re-read is unambiguous.
 *
 * Runs on all 13 targets — the fixtures are generated Kotlin, not a file on disk, because
 * Kotlin/Native and wasm have no filesystem here.
 */
class ReferenceConformanceTest {

    private fun check(fn: String, i: Int, got: DoubleArray, want: DoubleArray) {
        if (got.size != want.size) {
            fail("$fn[$i]: expected ${want.size} outputs, got ${got.size}")
        }
        for (k in got.indices) {
            val g = got[k]
            val w = want[k]
            val agrees = g == w || (g.isNaN() && w.isNaN())
            if (!agrees) {
                val rel = if (w != 0.0) abs(g - w) / abs(w) else abs(g - w)
                assertTrue(rel <= TOLERANCE, "$fn[$i] output $k: got $g, reference $w (rel $rel)")
            }
        }
    }

    private fun Any?.d(): Double = this as Double
    private fun Any?.a(): DoubleArray = this as DoubleArray

    @Test
    fun earthRadiusMatchesReference() {
        REF_EARTH_RAD_EFF.forEachIndexed { i, c ->
            val (ae, ab) = earthRadEff(c.args[0].d())
            check("earth_rad_eff", i, doubleArrayOf(ae, ab), doubleArrayOf(c.out[0].d(), c.out[1].d()))
        }
    }

    @Test
    fun inverseCumulativeNormalMatchesReference() {
        REF_INV_CUM_NORM.forEachIndexed { i, c ->
            check("inv_cum_norm", i, doubleArrayOf(invCumNorm(c.args[0].d())), doubleArrayOf(c.out[0].d()))
        }
    }

    @Test
    fun beta0MatchesReference() {
        REF_BETA0.forEachIndexed { i, c ->
            val got = beta0(c.args[0].d(), c.args[1].d(), c.args[2].d())
            check("beta0", i, doubleArrayOf(got), doubleArrayOf(c.out[0].d()))
        }
    }

    @Test
    fun freeSpaceAndLosLossMatchReference() {
        REF_PL_LOS.forEachIndexed { i, c ->
            val (lbfs, lb0p, lb0b) = plLos(
                c.args[0].d(),
                c.args[1].d(),
                c.args[2].d(),
                c.args[3].d(),
                c.args[4].d(),
                c.args[5].d(),
                c.args[6].d(),
                c.args[7].d(),
            )
            check(
                "pl_los",
                i,
                doubleArrayOf(lbfs, lb0p, lb0b),
                doubleArrayOf(c.out[0].d(), c.out[1].d(), c.out[2].d()),
            )
        }
    }

    @Test
    fun pathFractionMatchesReference() {
        REF_PATH_FRACTION.forEachIndexed { i, c ->
            val d = c.args[0].a()
            val zone = c.args[1].a().map { it.toInt() }.toIntArray()
            val got = pathFraction(d, zone, c.args[2].d().toInt())
            check("path_fraction", i, doubleArrayOf(got), doubleArrayOf(c.out[0].d()))
        }
    }

    @Test
    fun bullingtonDiffractionMatchesReference() {
        REF_DL_BULL.forEachIndexed { i, c ->
            val got = dlBull(c.args[0].a(), c.args[1].a(), c.args[2].d(), c.args[3].d(), c.args[4].d(), c.args[5].d())
            check("dl_bull", i, doubleArrayOf(got), doubleArrayOf(c.out[0].d()))
        }
    }

    @Test
    fun sphericalEarthFirstTermMatchesReference() {
        REF_DL_SE_FT.forEachIndexed { i, c ->
            val got = dlSeFt(c.args[0].d(), c.args[1].d(), c.args[2].d(), c.args[3].d(), c.args[4].d(), c.args[5].d())
            check("dl_se_ft", i, got, c.out[0].a())
        }
    }

    @Test
    fun sphericalEarthDiffractionMatchesReference() {
        REF_DL_SE.forEachIndexed { i, c ->
            val got = dlSe(c.args[0].d(), c.args[1].d(), c.args[2].d(), c.args[3].d(), c.args[4].d(), c.args[5].d())
            check("dl_se", i, got, c.out[0].a())
        }
    }

    @Test
    fun deltaBullingtonMatchesReference() {
        REF_DL_DELTA_BULL.forEachIndexed { i, c ->
            val r = dlDeltaBull(
                c.args[0].a(), c.args[1].a(), c.args[2].d(), c.args[3].d(),
                c.args[4].d(), c.args[5].d(), c.args[6].d(), c.args[7].d(), c.args[8].d(),
            )
            check("dl_delta_bull.Ld", i, r.ld, c.out[0].a())
            check("dl_delta_bull.Lbulla", i, doubleArrayOf(r.lbulla), doubleArrayOf(c.out[1].d()))
            check("dl_delta_bull.Lbulls", i, doubleArrayOf(r.lbulls), doubleArrayOf(c.out[2].d()))
            check("dl_delta_bull.Ldsph", i, r.ldsph, c.out[3].a())
        }
    }

    @Test
    fun diffractionForTimePercentMatchesReference() {
        REF_DL_P.forEachIndexed { i, c ->
            val r = dlP(
                c.args[0].a(), c.args[1].a(), c.args[2].d(), c.args[3].d(), c.args[4].d(),
                c.args[5].d(), c.args[6].d(), c.args[7].d(), c.args[8].d(), c.args[9].d(), c.args[10].d(),
            )
            check("dl_p.Ldp", i, r.ldp, c.out[0].a())
            check("dl_p.Ldb", i, r.ldb, c.out[1].a())
            check("dl_p.Ld50", i, r.ld50, c.out[2].a())
        }
    }

    @Test
    fun smoothEarthHeightsMatchReference() {
        REF_SMOOTH_EARTH_HEIGHTS.forEachIndexed { i, c ->
            // reference args: d, h, R, htg, hrg, ae, f — R is applied by the caller, not here
            val r =
                smoothEarthHeights(
                    c.args[0].a(),
                    c.args[1].a(),
                    c.args[3].d(),
                    c.args[4].d(),
                    c.args[5].d(),
                    c.args[6].d(),
                )
            val got = doubleArrayOf(
                r.hstN, r.hsrN, r.hst, r.hsr, r.hstd, r.hsrd, r.hte, r.hre, r.hm,
                r.dlt, r.dlr, r.thetaT, r.thetaR, r.thetaTot, r.pathtype.toDouble(),
            )
            val want = DoubleArray(got.size) { c.out[it].d() }
            check("smooth_earth_heights", i, got, want)
        }
    }

    private companion object {
        /**
         * Relative tolerance. The port is bit-exact for most functions; the worst observed
         * deviation is ~70 ulps on `smooth_earth_heights`, whose 258-point summation orders
         * differently from numpy's. On a ~100 dB loss that is ~1e-12 dB.
         */
        const val TOLERANCE = 1e-12
    }
}
