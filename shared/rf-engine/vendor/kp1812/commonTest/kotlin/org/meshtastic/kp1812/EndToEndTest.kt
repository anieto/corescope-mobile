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

/**
 * Whole-model conformance: every input the Recommendation takes, through [P1812.predict],
 * compared against the basic transmission loss the ITU reference implementation produces.
 *
 * [ReferenceConformanceTest] validates each equation block in isolation; this validates that
 * they are wired together correctly. A model assembled from individually-correct parts can
 * still be wrong, and only this test would notice.
 *
 * Coverage of the 63 cases: 0.03–6 GHz (the Recommendation's full range), time percentages
 * 1/10/20/50, both polarizations, path profiles from 6 to 2001 points, and predicted losses
 * from 87 to 226 dB.
 */
class EndToEndTest {

    @Test
    fun basicTransmissionLossMatchesReference() {
        var worst = 0.0
        var worstCase = ""
        for (c in E2E_CASES) {
            val prediction = P1812.predict(
                path = TerrainPath(c.d, c.h, c.r, c.zone),
                frequencyGhz = c.f,
                txHeightM = c.htg,
                rxHeightM = c.hrg,
                timePercent = c.p,
                pathCenterLatitudeDeg = c.phi,
                // The reference's `pol` is 1-based: Lbc_pol[pol - 1].
                polarization = if (c.pol == 1) Polarization.HORIZONTAL else Polarization.VERTICAL,
                atmosphere = Atmosphere(
                    deltaN = c.dn,
                    n0 = c.n0,
                    distanceToCoastTxKm = c.dct,
                    distanceToCoastRxKm = c.dcr,
                ),
                locationPercent = c.pl,
                locationVariabilityDb = c.sigmaL,
            )
            val delta = abs(prediction.basicTransmissionLossDb - c.lb)
            if (delta > worst) {
                worst = delta
                worstCase = c.name
            }
            assertTrue(
                delta <= TOLERANCE_DB,
                "${c.name}: Lb ${prediction.basicTransmissionLossDb} dB, reference ${c.lb} dB " +
                    "(delta $delta dB)",
            )
        }
        println("end-to-end: ${E2E_CASES.size} cases, worst |ΔLb| = $worst dB ($worstCase)")
    }

    @Test
    fun fieldStrengthMatchesReference() {
        for (c in E2E_CASES) {
            val prediction = P1812.predict(
                path = TerrainPath(c.d, c.h, c.r, c.zone),
                frequencyGhz = c.f,
                txHeightM = c.htg,
                rxHeightM = c.hrg,
                timePercent = c.p,
                pathCenterLatitudeDeg = c.phi,
                polarization = if (c.pol == 1) Polarization.HORIZONTAL else Polarization.VERTICAL,
                atmosphere = Atmosphere(c.dn, c.n0, c.dct, c.dcr),
                locationPercent = c.pl,
                locationVariabilityDb = c.sigmaL,
            )
            val delta = abs(prediction.fieldStrengthDbuVPerM - c.ep)
            assertTrue(
                delta <= TOLERANCE_DB,
                "${c.name}: Ep ${prediction.fieldStrengthDbuVPerM}, reference ${c.ep} (delta $delta)",
            )
        }
    }

    @Test
    fun receivedPowerSubtractsPathLoss() {
        val c = E2E_CASES.first()
        val prediction = P1812.predict(
            path = TerrainPath(c.d, c.h, c.r, c.zone),
            frequencyGhz = c.f,
            txHeightM = c.htg,
            rxHeightM = c.hrg,
            timePercent = c.p,
            pathCenterLatitudeDeg = c.phi,
        )
        val rx = P1812.receivedPower(prediction, txPowerDbm = 30.0, txGainDbi = 2.0, rxGainDbi = 1.0)
        assertTrue(abs(rx.value - (33.0 - prediction.basicTransmissionLossDb)) < 1e-9)
    }

    private companion object {
        /**
         * Absolute tolerance in dB. The port agrees with the reference to well within this;
         * the bound exists so a real regression fails loudly rather than a last-bit
         * difference in summation order.
         */
        const val TOLERANCE_DB = 1e-6
    }
}
