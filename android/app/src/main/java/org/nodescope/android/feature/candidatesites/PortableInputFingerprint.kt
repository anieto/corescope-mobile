package org.nodescope.android.feature.candidatesites

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.nodescope.android.feature.coverage.CoverageNodeSnapshot
import org.nodescope.android.feature.coverage.CoverageStudyInputs

/**
 * Saved-plans v3 input fingerprints (docs/node-planner/saved-plans-v3-contract-gate.md). SHA-256
 * over a versioned binary stream, identical on iOS (PortableInputFingerprint.swift) and checked
 * against shared vectors in shared/fixtures/saved-plans/contract-vectors-v1.json. These tie a
 * saved result to the exact inputs it was calculated from; they are not the diagnostic
 * prediction fingerprint, which stays separate.
 */
object PortableInputFingerprint {
    /** Wire encoding: binary64 and int64 big-endian, 0/1 booleans, u32-length UTF-8, u32 counts. */
    class Writer(domain: String) {
        private val bytes = ByteArrayOutputStream()
        init { bytes.write(domain.toByteArray(Charsets.UTF_8)); bytes.write(0) }

        fun double(value: Double) = apply {
            require(value.isFinite()) { "Fingerprint inputs must be finite" }
            long(value.toRawBits()) // keeps -0.0 distinct from 0.0
        }
        fun int64(value: Long) = apply { long(value) }
        fun boolean(value: Boolean) = apply { bytes.write(if (value) 1 else 0) }
        fun string(value: String) = apply {
            val encoded = value.toByteArray(Charsets.UTF_8) // stored bytes as given; no normalization
            count(encoded.size); bytes.write(encoded)
        }
        fun count(value: Int) = apply {
            require(value >= 0)
            for (shift in intArrayOf(24, 16, 8, 0)) bytes.write((value ushr shift) and 0xff)
        }
        fun optionalInt64(value: Long?) = apply { if (value == null) bytes.write(0) else { bytes.write(1); long(value) } }

        fun sha256(): String = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun long(value: Long) { for (shift in 56 downTo 0 step 8) bytes.write(((value ushr shift) and 0xff).toInt()) }
    }

    /** A captured node, as the coverage fingerprint records it. */
    data class Node(val sourceID: String, val nodeID: String, val latitude: Double, val longitude: Double)

    /**
     * Every input a coverage result depends on: the twelve doubles in saved inputValues order
     * (transmitter latitude and longitude, frequency, transmit and receive heights, power, gains,
     * losses, sensitivity, reserve), the six grid integers (radius, spacing, minEast, maxNorth,
     * columns, rows), catalogComplete and the ordered captured nodes.
     */
    fun coverage(doubles: List<Double>, grid: List<Long>, catalogComplete: Boolean, nodes: List<Node>): String {
        require(doubles.size == 12 && grid.size == 6)
        val writer = Writer("nodescope.coverage-inputs.v1")
        doubles.forEach(writer::double)
        grid.forEach(writer::int64)
        writer.boolean(catalogComplete).count(nodes.size)
        nodes.forEach { writer.string(it.sourceID).string(it.nodeID).double(it.latitude).double(it.longitude) }
        return writer.sha256()
    }

    fun coverage(inputs: CoverageStudyInputs, nodes: List<CoverageNodeSnapshot>, catalogComplete: Boolean): String {
        val b = inputs.budget
        val g = inputs.grid
        return coverage(listOf(inputs.transmitter.latitude, inputs.transmitter.longitude, inputs.frequencyMHz,
            inputs.transmitHeightMeters, inputs.receiveHeightMeters, b.transmitPowerDbm, b.transmitGainDbi,
            b.receiveGainDbi, b.transmitLossDb, b.receiveLossDb, b.sensitivityDbm, b.reserveDb),
            listOf(g.radiusMeters, g.spacingMeters, g.minEastMeters, g.maxNorthMeters, g.columns, g.rows).map(Int::toLong),
            catalogComplete, nodes.map { Node(it.sourceID, it.nodeID, it.latitude, it.longitude) })
    }

    /** A Link Profile's calculation inputs. Endpoint names are labels, not inputs, and are left out. */
    fun linkProfile(inputs: SavedLinkProfile): String {
        val writer = Writer("nodescope.link-profile-inputs.v1")
        for (point in listOf(inputs.pointA, inputs.pointB)) {
            writer.double(point.latitude).double(point.longitude).double(point.antenna.heightMeters)
                .double(point.antenna.gainDbi).double(point.antenna.lossDb)
        }
        return writer.double(inputs.frequencyMHz).double(inputs.transmitPowerDbm).double(inputs.sensitivityDbm)
            .double(inputs.refractionK).optionalInt64(inputs.sampleCount?.toLong()).sha256()
    }
}
