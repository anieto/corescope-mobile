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

internal class RefCall(private val argsRaw: String, private val outRaw: String) {
    val args: List<Any?> by lazy { parse(argsRaw) }
    val out: List<Any?> by lazy { parse(outRaw) }

    private fun parse(raw: String): List<Any?> = raw.split(';').map { field ->
        when {
            field == "~" -> null

            field.startsWith("*") ->
                field.substring(1).let { b ->
                    if (b.isEmpty()) DoubleArray(0) else b.split(',').map { it.toDouble() }.toDoubleArray()
                }

            else -> field.toDouble()
        }
    }
}
