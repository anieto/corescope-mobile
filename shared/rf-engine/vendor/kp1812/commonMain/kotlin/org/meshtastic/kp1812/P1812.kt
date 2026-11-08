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

import kotlin.jvm.JvmInline
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tanh

/** Signal polarization. */
public enum class Polarization {
    HORIZONTAL,
    VERTICAL,
    ;

    internal val index: Int get() = ordinal
}

/**
 * A terrain path from transmitter to receiver.
 *
 * All four arrays are parallel and describe the same profile points, first point at the
 * transmitter. `distancesKm` must be ascending and start at 0.
 *
 * @property distancesKm distance of each profile point from the transmitter, km
 * @property heightsM ground height above mean sea level at each point, m
 * @property clutterHeightsM representative clutter height at each point, m
 * @property zones radiometeorological zone per point: [ZONE_SEA], [ZONE_COASTAL_LAND] or [ZONE_INLAND]
 */
public class TerrainPath(
    public val distancesKm: DoubleArray,
    public val heightsM: DoubleArray,
    public val clutterHeightsM: DoubleArray,
    public val zones: IntArray,
) {
    init {
        require(distancesKm.size >= MIN_PROFILE_POINTS) {
            "a path needs at least $MIN_PROFILE_POINTS points, got ${distancesKm.size}"
        }
        require(
            heightsM.size == distancesKm.size && clutterHeightsM.size == distancesKm.size &&
                zones.size == distancesKm.size,
        ) {
            "distancesKm, heightsM, clutterHeightsM and zones must be the same length"
        }
        require(distancesKm[0] == 0.0) { "distancesKm must start at 0, got ${distancesKm[0]}" }
        for (i in 1 until distancesKm.size) {
            require(distancesKm[i] > distancesKm[i - 1]) { "distancesKm must be strictly ascending at index $i" }
        }
    }

    /** Total path length, km. */
    public val totalKm: Double get() = distancesKm[distancesKm.size - 1] - distancesKm[0]
}

private const val MIN_PROFILE_POINTS = 3

/** Radiometeorological inputs. Defaults are the ITU global medians. */
public class Atmosphere(
    /** Average radio-refractivity lapse rate through the lowest 1 km, N-units/km. */
    public val deltaN: Double = 45.0,
    /** Sea-level surface refractivity, N-units. */
    public val n0: Double = 325.0,
    /** Distance of the transmitter from the coast, km. 500 means "far inland". */
    public val distanceToCoastTxKm: Double = INLAND_KM,
    /** Distance of the receiver from the coast, km. */
    public val distanceToCoastRxKm: Double = INLAND_KM,
)

/** "Far inland" for the purposes of the duct-coupling corrections, km. */
private const val INLAND_KM = 500.0

/** A P.1812 prediction. */
public class Prediction(
    /** Basic transmission loss not exceeded for p% time and pL% locations, dB. Eq (69). */
    public val basicTransmissionLossDb: Double,
    /** Field strength for 1 kW e.i.r.p., dB(uV/m). Eq (70). */
    public val fieldStrengthDbuVPerM: Double,
    /** Free-space basic transmission loss, dB. Eq (8). */
    public val freeSpaceLossDb: Double,
    /** Diffraction loss not exceeded for p% time, dB. Eq (41). */
    public val diffractionLossDb: Double,
    /** Troposcatter basic transmission loss, dB. Eq (44). */
    public val troposcatterLossDb: Double,
    /** Ducting / layer-reflection basic transmission loss, dB. Eq (46). */
    public val ductingLossDb: Double,
    /** Whether the path is line-of-sight or transhorizon. */
    public val lineOfSight: Boolean,
    /** Distance from the transmitter to its horizon, km. Eq (80). */
    public val txHorizonKm: Double,
    /** Distance from the receiver to its horizon, km. Eq (83). */
    public val rxHorizonKm: Double,
)

/** Received signal strength, dBm, for a given transmit power and antenna gains. */
@JvmInline
public value class Dbm(public val value: Double)

/**
 * Recommendation ITU-R P.1812-8: a path-specific propagation prediction method for point-to-area
 * terrestrial services in the frequency range 30 MHz to 6000 MHz.
 *
 * Pure Kotlin — the same implementation runs on JVM, Android, Apple, Linux, Windows, JS and wasm.
 */
public object P1812 {

    /** Lowest frequency the Recommendation covers, GHz. */
    public const val MIN_FREQUENCY_GHZ: Double = 0.03

    /** Highest frequency the Recommendation covers, GHz. */
    public const val MAX_FREQUENCY_GHZ: Double = 6.0

    /**
     * Predict basic transmission loss along [path].
     *
     * @param path the terrain profile
     * @param frequencyGhz center frequency, GHz; must be within [MIN_FREQUENCY_GHZ]..[MAX_FREQUENCY_GHZ]
     * @param txHeightM transmitter antenna height above ground, m
     * @param rxHeightM receiver antenna height above ground, m
     * @param timePercent percentage of time the loss is not exceeded, 1..50
     * @param pathCenterLatitudeDeg latitude of the path center, degrees
     * @param polarization signal polarization
     * @param atmosphere radiometeorological inputs
     * @param locationPercent percentage of locations, 1..99
     * @param locationVariabilityDb standard deviation of location variability, dB. The
     *   Recommendation takes this as an input (§4.8, §4.10); compute it with
     *   [locationVariabilityStdDev] when you want the Eq (68) value, or leave it at 0 for a
     *   median-location prediction.
     */
    @Suppress("LongParameterList", "LongMethod")
    public fun predict(
        path: TerrainPath,
        frequencyGhz: Double,
        txHeightM: Double,
        rxHeightM: Double,
        timePercent: Double = 50.0,
        pathCenterLatitudeDeg: Double = 0.0,
        polarization: Polarization = Polarization.VERTICAL,
        atmosphere: Atmosphere = Atmosphere(),
        locationPercent: Double = 50.0,
        locationVariabilityDb: Double = 0.0,
    ): Prediction {
        require(frequencyGhz in MIN_FREQUENCY_GHZ..MAX_FREQUENCY_GHZ) {
            "frequencyGhz must be in $MIN_FREQUENCY_GHZ..$MAX_FREQUENCY_GHZ, got $frequencyGhz"
        }
        require(timePercent in MIN_TIME_PERCENT..MAX_TIME_PERCENT) {
            "timePercent must be in $MIN_TIME_PERCENT..$MAX_TIME_PERCENT, got $timePercent"
        }
        require(locationPercent in MIN_LOCATION_PERCENT..MAX_LOCATION_PERCENT) {
            "locationPercent must be in $MIN_LOCATION_PERCENT..$MAX_LOCATION_PERCENT, got $locationPercent"
        }
        require(locationVariabilityDb >= 0.0) {
            "locationVariabilityDb must be >= 0, got $locationVariabilityDb"
        }

        val d = path.distancesKm
        val h = path.heightsM
        val r = path.clutterHeightsM
        val zone = path.zones
        val f = frequencyGhz
        val p = timePercent
        val n = d.size
        val dtot = path.totalKm

        // §3.6 path zone statistics.
        val dtm = longestContDist(d, zone, ZONE_LAND_COMBINED)
        val dlm = longestContDist(d, zone, ZONE_INLAND)
        val b0 = beta0(pathCenterLatitudeDeg, dtm, dlm)
        val (ae, _) = earthRadEff(atmosphere.deltaN)
        val omega = pathFraction(d, zone, ZONE_SEA) // (1)

        val se = smoothEarthHeights(d, h, txHeightM, rxHeightM, ae, f)

        val hts = h[0] + txHeightM
        val hrs = h[n - 1] + rxHeightM

        // §3.2 representative clutter, excluding the terminals themselves.
        val g = DoubleArray(n) { h[it] + r[it] }
        g[0] = h[0]
        g[n - 1] = h[n - 1]

        // Interpolation factors for angular distance and path length. Eq (57), (58).
        val fj = 1.0 - 0.5 * (1.0 + tanh(3.0 * KSI * (se.thetaTot - THETA) / THETA)) // (57)
        val fk = 1.0 - 0.5 * (1.0 + tanh(3.0 * KAPPA * (dtot - DSW) / DSW)) // (58)

        val (lbfs, lb0p, lb0b) = plLos(dtot, hts, hrs, f, p, b0, se.dlt, se.dlr)
        val dl = dlP(d, g, hts, hrs, se.hstd, se.hsrd, f, omega, p, b0, atmosphere.deltaN)

        val lbd50 = DoubleArray(2) { lbfs + dl.ld50[it] } // (42)
        val lbd = DoubleArray(2) { lb0p + dl.ldp[it] } // (43)

        // Notional minimum loss associated with LoS and over-sea sub-path diffraction. Eq (59).
        val lminb0p = DoubleArray(2) { lb0p + (1 - omega) * dl.ldp[it] }
        if (p >= b0) {
            val fi = invCumNorm(p / 100.0) / invCumNorm(b0 / 100.0)
            for (i in 0..1) {
                lminb0p[i] = lbd50[i] + (lb0b + (1 - omega) * dl.ldp[i] - lbd50[i]) * fi // (59)
            }
        }

        val lba = tlAnomalous(
            dtot, se.dlt, se.dlr, atmosphere.distanceToCoastTxKm, atmosphere.distanceToCoastRxKm,
            dlm, hts, hrs, se.hte, se.hre, se.hm, se.thetaT, se.thetaR, f, p, omega, ae, b0,
        )
        val lminbap = logSumExp(lba, lb0p, ETA) // (60)

        val lbda = DoubleArray(2) { if (lminbap <= lbd[it]) lminbap + (lbd[it] - lminbap) * fk else lbd[it] } // (61)
        val lbam = DoubleArray(2) { lbda[it] + (lminb0p[it] - lbda[it]) * fj } // (62)

        val lbs = tlTropo(dtot, se.thetaTot, f, p, atmosphere.n0)
        val lbcPol = DoubleArray(2) { -5 * log10(10.0.pow(-0.2 * lbs) + 10.0.pow(-0.2 * lbam[it])) } // (63)
        val lbc = lbcPol[polarization.index]

        // §4.8/4.9 location variability — outdoors only, and not applied at sea. Eq (67a), (69).
        val lloc = if (zone[n - 1] == ZONE_SEA) {
            0.0
        } else {
            -invCumNorm(locationPercent / 100.0) * locationVariabilityDb // (67a)
        }
        val lb = max(lb0p, lbc + lloc) // (69)
        val ep = 199.36 + 20 * log10(f) - lb // (70)

        return Prediction(
            basicTransmissionLossDb = lb,
            fieldStrengthDbuVPerM = ep,
            freeSpaceLossDb = lbfs,
            diffractionLossDb = dl.ldp[polarization.index],
            troposcatterLossDb = lbs,
            ductingLossDb = lba,
            lineOfSight = se.pathtype == PATH_LOS,
            txHorizonKm = se.dlt,
            rxHorizonKm = se.dlr,
        )
    }

    /**
     * Received power at the receiver, dBm.
     *
     * @param prediction the result of [predict]
     * @param txPowerDbm transmitter output power, dBm
     * @param txGainDbi transmitter antenna gain, dBi
     * @param rxGainDbi receiver antenna gain, dBi
     */
    public fun receivedPower(
        prediction: Prediction,
        txPowerDbm: Double,
        txGainDbi: Double = 0.0,
        rxGainDbi: Double = 0.0,
    ): Dbm = Dbm(txPowerDbm + txGainDbi + rxGainDbi - prediction.basicTransmissionLossDb)

    /**
     * Standard deviation of location variability, dB. Eq (68).
     *
     * Pass the result to [predict] as `locationVariabilityDb` when modeling location
     * variability; §4.8 and §4.10 of the Recommendation define when that applies.
     *
     * @param frequencyGhz center frequency, GHz
     * @param rxHeightM receiver height above ground, m
     * @param clutterHeightM representative clutter height at the receiver, m
     * @param predictionResolutionM the resolution the prediction is made at, m
     */
    public fun locationVariabilityStdDev(
        frequencyGhz: Double,
        rxHeightM: Double,
        clutterHeightM: Double,
        predictionResolutionM: Double,
    ): Double = stdDev(frequencyGhz, rxHeightM, clutterHeightM, predictionResolutionM)

    private const val THETA = 0.3
    private const val KSI = 0.8
    private const val DSW = 20.0
    private const val KAPPA = 0.5
    private const val ETA = 2.5
    private const val MIN_TIME_PERCENT = 1.0
    private const val MAX_TIME_PERCENT = 50.0
    private const val MIN_LOCATION_PERCENT = 1.0
    private const val MAX_LOCATION_PERCENT = 99.0
}
