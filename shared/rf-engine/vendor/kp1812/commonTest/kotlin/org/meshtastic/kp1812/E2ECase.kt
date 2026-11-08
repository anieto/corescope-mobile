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

/**
 * One end-to-end case from E2EFixtures.kt: a full terrain profile and every model input, with
 * the basic transmission loss and field strength the reference produced for it. Arrays are
 * stored as strings and parsed on read, for the same JVM constant-size reason as [RefCall].
 */
internal class E2ECase(
    val name: String,
    private val dRaw: String,
    private val hRaw: String,
    private val rRaw: String,
    private val zoneRaw: String,
    val f: Double,
    val p: Double,
    val htg: Double,
    val hrg: Double,
    val pol: Int,
    val phi: Double,
    val dn: Double,
    val n0: Double,
    val dct: Double,
    val dcr: Double,
    val pl: Double,
    val sigmaL: Double,
    val lb: Double,
    val ep: Double,
) {
    val d: DoubleArray get() = parse(dRaw)
    val h: DoubleArray get() = parse(hRaw)
    val r: DoubleArray get() = parse(rRaw)
    val zone: IntArray get() = parse(zoneRaw).map { it.toInt() }.toIntArray()

    private fun parse(raw: String): DoubleArray = raw.substring(1).split(',').map { it.toDouble() }.toDoubleArray()
}
