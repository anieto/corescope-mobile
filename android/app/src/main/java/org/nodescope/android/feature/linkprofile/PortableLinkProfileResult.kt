package org.nodescope.android.feature.linkprofile

import kotlin.math.abs
import kotlin.math.min
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.nodescope.android.feature.candidatesites.PortableInputFingerprint
import org.nodescope.android.feature.candidatesites.PortableJson
import org.nodescope.android.feature.candidatesites.SavedEndpoint
import org.nodescope.android.feature.candidatesites.SavedLinkProfile
import org.nodescope.android.feature.candidatesites.array
import org.nodescope.android.feature.candidatesites.double
import org.nodescope.android.feature.candidatesites.exactly
import org.nodescope.android.feature.candidatesites.long
import org.nodescope.android.feature.candidatesites.nullable
import org.nodescope.android.feature.candidatesites.obj
import org.nodescope.android.feature.candidatesites.objectValue
import org.nodescope.android.feature.candidatesites.string

/**
 * `nodescope.link-profile-result` version 1 (docs/node-planner/saved-plans-v3-result-fields.md), identical
 * on iOS (PortableLinkProfileResult.swift) and checked against shared/fixtures/saved-plans/results.
 * Reading is strict and needs the study's own inputs: the payload's fingerprint must match both.
 */
internal object PortableLinkProfileResult {
    const val FORMAT = "nodescope.link-profile-result"
    /** Provisional ceiling for one payload; B measures real encoder output before freezing it. */
    const val MAXIMUM_BYTES = 256 * 1024
    /** First and last samples may differ from the endpoints by this much (degrees); longitudes compare modulo 360. */
    const val ENDPOINT_TOLERANCE = 1e-6
    private val prefix = "{\"format\":\"$FORMAT\"".toByteArray(Charsets.UTF_8)
    private val statuses = mapOf(ClearanceStatus.CLEAR to "clear", ClearanceStatus.MARGINAL to "marginal",
        ClearanceStatus.PARTIAL_OBSTRUCTION to "partialObstruction", ClearanceStatus.BLOCKED to "blocked")
    private val endpointNames = arrayOf("latitude", "longitude", "heightMeters", "gainDbi", "lossDb")

    fun isPortable(bytes: ByteArray) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

    fun fingerprint(bytes: ByteArray): String? = runCatching {
        (PortableJson.parse(bytes, MAXIMUM_BYTES)["inputFingerprint"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf(PortableJson.sha::matches)
    }.getOrNull()

    /** Captures [analysis] for the study [inputs]; null if the analysis wasn't calculated from exactly these inputs. */
    fun encode(analysis: LinkAnalysis, inputs: SavedLinkProfile): ByteArray? {
        val request = analysis.request
        val budget = request.linkBudget
        val points = analysis.terrain.points
        val coordinates = analysis.terrain.coordinates
        if (request.heightAMeters != inputs.pointA.antenna.heightMeters || request.heightBMeters != inputs.pointB.antenna.heightMeters ||
            request.frequencyMHz != inputs.frequencyMHz || request.refractionK != inputs.refractionK || budget == null ||
            budget != LinkBudget(inputs.transmitPowerDbm, inputs.pointA.antenna.gainDbi, inputs.pointB.antenna.gainDbi,
                inputs.pointA.antenna.lossDb, inputs.pointB.antenna.lossDb, inputs.sensitivityDbm) ||
            points.size !in 2..100 || inputs.sampleCount?.let { it != points.size } == true ||
            !near(coordinates.first(), inputs.pointA) || !near(coordinates.last(), inputs.pointB)) return null
        val result = analysis.result
        val payload = buildJsonObject {
            put("format", FORMAT)
            put("version", 1)
            put("calculatedAtMilliseconds", analysis.calculatedAt)
            put("inputFingerprint", PortableInputFingerprint.linkProfile(inputs))
            put("engine", buildJsonObject { put("id", LinkProfileEngine.ENGINE_ID); put("version", LinkProfileEngine.ENGINE_VERSION) })
            put("inputs", buildJsonObject {
                put("pointA", endpoint(inputs.pointA)); put("pointB", endpoint(inputs.pointB))
                put("frequencyMHz", inputs.frequencyMHz); put("transmitPowerDbm", inputs.transmitPowerDbm)
                put("sensitivityDbm", inputs.sensitivityDbm); put("refractionK", inputs.refractionK)
                put("sampleCount", inputs.sampleCount)
            })
            put("resolvedSampleCount", points.size)
            put("terrain", buildJsonObject {
                put("samples", buildJsonArray {
                    points.zip(coordinates).forEach { (point, coordinate) ->
                        add(buildJsonObject {
                            put("latitude", coordinate.latitude); put("longitude", coordinate.longitude)
                            put("distanceMetersFromA", point.distanceMeters); put("elevationMeters", point.elevationMeters)
                        })
                    }
                })
                put("provenance", buildJsonObject {
                    put("provider", "open-meteo"); put("dataset", "copernicus-dem-2021-glo-90"); put("nominalResolutionMeters", 90)
                    put("fetchedAtMilliseconds", analysis.terrain.fetchedAt); put("origin", analysis.terrain.origin)
                })
            })
            put("result", buildJsonObject {
                put("distanceMeters", result.distanceMeters); put("freeSpacePathLossDb", result.freeSpacePathLossDB)
                put("peakDiffractionLossDb", result.peakDiffractionLossDB); put("totalPathLossDb", result.totalPathLossDB)
                put("worstClearancePercent", result.worstClearancePercent); put("clearanceStatus", statuses.getValue(result.clearanceStatus))
                put("obstructions", buildJsonArray {
                    result.obstructions.forEach {
                        add(buildJsonObject {
                            put("distanceFromAMeters", it.distanceFromAMeters); put("obstructionHeightMeters", it.obstructionHeightMeters)
                            put("fresnelClearancePercent", it.fresnelClearancePercent)
                        })
                    }
                })
                put("receivePowerDbm", result.receivePowerDBm); put("linkMarginDb", result.linkMarginDB)
            })
            put("bearingDegrees", analysis.bearing)
        }
        return Json.encodeToString(JsonElement.serializer(), payload).toByteArray(Charsets.UTF_8).takeIf { it.size <= MAXIMUM_BYTES }
    }

    /** Rebuilds the saved analysis for the study [inputs] without fetching terrain; throws if it isn't theirs or is malformed. */
    fun decode(bytes: ByteArray, inputs: SavedLinkProfile): LinkAnalysis {
        val root = PortableJson.parse(bytes, MAXIMUM_BYTES)
        root.exactly("format", "version", "calculatedAtMilliseconds", "inputFingerprint", "engine", "inputs",
            "resolvedSampleCount", "terrain", "result", "bearingDegrees")
        require(root.string("format") == FORMAT && root.long("version") == 1L)
        val calculatedAt = root.long("calculatedAtMilliseconds").also { require(it in 0..PortableJson.MAX_MS) }
        val fingerprint = root.string("inputFingerprint").also { require(PortableJson.sha.matches(it)) }
        root.obj("engine").also {
            it.exactly("id", "version")
            require(it.string("id") == LinkProfileEngine.ENGINE_ID && it.long("version") == LinkProfileEngine.ENGINE_VERSION.toLong())
        }

        val payloadInputs = root.obj("inputs")
        payloadInputs.exactly("pointA", "pointB", "frequencyMHz", "transmitPowerDbm", "sensitivityDbm", "refractionK", "sampleCount")
        val endpoints = listOf("pointA", "pointB").map { name ->
            payloadInputs.obj(name).also { it.exactly(*endpointNames) }.let { point -> endpointNames.map { point.double(it) } }
        }
        val radio = listOf("frequencyMHz", "transmitPowerDbm", "sensitivityDbm", "refractionK").map { payloadInputs.double(it) }
        val requested = payloadInputs.nullable("sampleCount")?.let { payloadInputs.long("sampleCount") }
        require(requested == null || requested in 2..100)
        val resolved = root.long("resolvedSampleCount")
        require(resolved in 2..100 && (requested == null || requested == resolved))

        val terrain = root.obj("terrain").also { it.exactly("samples", "provenance") }
        val provenance = terrain.obj("provenance")
        provenance.exactly("provider", "dataset", "nominalResolutionMeters", "fetchedAtMilliseconds", "origin")
        val origin = provenance.string("origin")
        require(provenance.string("provider") == "open-meteo" && provenance.string("dataset") == "copernicus-dem-2021-glo-90" &&
            provenance.long("nominalResolutionMeters") == 90L && origin in setOf("network", "cache", "mixed"))
        val fetchedAt = provenance.long("fetchedAtMilliseconds").also { require(it in 0..PortableJson.MAX_MS) }
        val samples = terrain.array("samples").map { item ->
            item.objectValue().also { it.exactly("latitude", "longitude", "distanceMetersFromA", "elevationMeters") }.let {
                val coordinate = ProfileCoordinate(it.double("latitude"), it.double("longitude"))
                require(coordinate.latitude in -90.0..90.0 && coordinate.longitude in -180.0..180.0)
                coordinate to ElevationPoint(it.double("distanceMetersFromA"), it.double("elevationMeters"))
            }
        }
        require(samples.size.toLong() == resolved && samples.first().second.distanceMeters == 0.0)
        require(samples.zipWithNext().all { (a, b) -> b.second.distanceMeters > a.second.distanceMeters })

        val result = root.obj("result")
        result.exactly("distanceMeters", "freeSpacePathLossDb", "peakDiffractionLossDb", "totalPathLossDb", "worstClearancePercent",
            "clearanceStatus", "obstructions", "receivePowerDbm", "linkMarginDb")
        val status = statuses.entries.first { it.value == result.string("clearanceStatus") }.key
        val obstructions = result.array("obstructions").also { require(it.size <= 98) }.map { item ->
            item.objectValue().also { it.exactly("distanceFromAMeters", "obstructionHeightMeters", "fresnelClearancePercent") }.let {
                Obstruction(it.double("distanceFromAMeters"), it.double("obstructionHeightMeters"), it.double("fresnelClearancePercent"))
            }
        }
        val receive = result.nullable("receivePowerDbm")?.let { result.double("receivePowerDbm") }
        val margin = result.nullable("linkMarginDb")?.let { result.double("linkMarginDb") }
        require((receive == null) == (margin == null))
        val distance = result.double("distanceMeters")
        require(samples.last().second.distanceMeters == distance)
        val pointA = SavedEndpoint(null, endpoints[0][0], endpoints[0][1], org.nodescope.android.feature.candidatesites.SavedAntenna(endpoints[0][2], endpoints[0][3], endpoints[0][4]))
        val pointB = SavedEndpoint(null, endpoints[1][0], endpoints[1][1], org.nodescope.android.feature.candidatesites.SavedAntenna(endpoints[1][2], endpoints[1][3], endpoints[1][4]))
        require(near(samples.first().first, pointA) && near(samples.last().first, pointB))
        val bearing = root.double("bearingDegrees").also { require(it >= 0 && it < 360) }

        val own = PortableInputFingerprint.linkProfile(SavedLinkProfile(pointA, pointB, radio[0], radio[1], radio[2], radio[3], requested?.toInt()))
        require(own == fingerprint && PortableInputFingerprint.linkProfile(inputs) == fingerprint)

        val request = PathRequest(samples.map { it.second }, inputs.pointA.antenna.heightMeters, inputs.pointB.antenna.heightMeters,
            inputs.frequencyMHz, inputs.refractionK, LinkBudget(inputs.transmitPowerDbm, inputs.pointA.antenna.gainDbi,
                inputs.pointB.antenna.gainDbi, inputs.pointA.antenna.lossDb, inputs.pointB.antenna.lossDb, inputs.sensitivityDbm))
        val path = PathResult(distance, result.double("freeSpacePathLossDb"), result.double("peakDiffractionLossDb"),
            result.double("totalPathLossDb"), result.double("worstClearancePercent"), status, obstructions, inputs.frequencyMHz,
            inputs.refractionK, receive, margin)
        return LinkAnalysis(request, TerrainProfile(samples.map { it.second }, fetchedAt, origin, samples.map { it.first }), path,
            bearing, calculatedAt)
    }

    private fun endpoint(point: SavedEndpoint): JsonObject = buildJsonObject {
        put("latitude", point.latitude); put("longitude", point.longitude); put("heightMeters", point.antenna.heightMeters)
        put("gainDbi", point.antenna.gainDbi); put("lossDb", point.antenna.lossDb)
    }

    private fun near(sample: ProfileCoordinate, point: SavedEndpoint): Boolean {
        val east = abs(sample.longitude - point.longitude) % 360
        return abs(sample.latitude - point.latitude) <= ENDPOINT_TOLERANCE && min(east, 360 - east) <= ENDPOINT_TOLERANCE
    }
}
