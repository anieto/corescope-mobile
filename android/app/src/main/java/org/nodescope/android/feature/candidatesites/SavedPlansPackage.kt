package org.nodescope.android.feature.candidatesites

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.nodescope.android.feature.coverage.PortableCoverageResult
import org.nodescope.android.feature.linkprofile.PortableLinkProfileResult

/**
 * A `.nodescope` export package (docs/node-planner/saved-plans-v3-package.md), identical on iOS
 * (SavedPlansPackage.swift) and checked against shared/fixtures/saved-plans/packages.
 *
 * Reading follows the document's steps in order and is strict throughout. Records go through the
 * library's own v3 codec, results through B's readers. A result that can't be used here doesn't refuse
 * the package: its study is listed in [unusableResults] and travels without its reference.
 */
data class SavedPlansPackage(
    val scope: Scope,
    val exportedAtMilliseconds: Long,
    val exportedBy: ExportedBy,
    val includesResults: Boolean,
    val includesNotes: Boolean,
    val candidateSitePlans: List<DeploymentPlan> = emptyList(),
    val studyPlans: List<StudyPlan> = emptyList(),
    val studies: List<SavedStudy>,
    val omittedResults: List<Omission> = emptyList(),
    /** Result bytes by SHA-256: one per distinct result a study references. */
    val results: Map<String, ByteArray> = emptyMap(),
    /** After reading: studies whose result can't be imported here; their `resultRef` is already removed. */
    val unusableResults: Set<String> = emptySet(),
) {
    enum class Scope(val wire: String) { STUDY("study"), PLAN("plan"), LIBRARY("library") }
    enum class Platform(val wire: String) { IOS("ios"), ANDROID("android") }
    enum class OmissionReason(val wire: String) {
        NOT_ON_DEVICE("notOnDevice"), COULD_NOT_BE_VERIFIED("couldNotBeVerified"),
        NEEDS_NEWER_VERSION("needsNewerVersion"), NOT_READABLE_HERE("notReadableHere"),
    }
    data class ExportedBy(val platform: Platform, val appVersion: String)
    data class Omission(val studyID: String, val reason: OmissionReason)

    /** Refused. [newerVersion]: "This file needs a newer version of NodeScope"; otherwise the reason is for logs. */
    class Failure(val newerVersion: Boolean, reason: String) : Exception(reason)

    /** The package file. Every referenced result must be in [results]. */
    fun encoded(): ByteArray {
        // The records exactly as the library writes them, through its own codec (which also validates them).
        val records = Json.parseToJsonElement(DeploymentPlanCodec.encode(DeploymentPlanDocument(3, candidateSitePlans,
            studies.map { it.copy(coverage = it.coverage?.copy(calculatedResult = null), linkProfileResult = null) },
            1, studyPlans)).decodeToString()).jsonObject
        val manifest = JsonObject(linkedMapOf(
            "format" to JsonPrimitive(FORMAT), "version" to JsonPrimitive(1), "scope" to JsonPrimitive(scope.wire),
            "exportedAtMilliseconds" to JsonPrimitive(exportedAtMilliseconds),
            "exportedBy" to JsonObject(mapOf("platform" to JsonPrimitive(exportedBy.platform.wire), "appVersion" to JsonPrimitive(exportedBy.appVersion))),
            "includesResults" to JsonPrimitive(includesResults), "includesNotes" to JsonPrimitive(includesNotes),
            "candidateSitePlans" to records.getValue("plans"), "studyPlans" to records.getValue("studyPlans"),
            "studies" to records.getValue("studies"),
            "omittedResults" to JsonArray(omittedResults.map {
                JsonObject(mapOf("studyID" to JsonPrimitive(it.studyID), "reason" to JsonPrimitive(it.reason.wire)))
            }),
        ))
        val entries = mutableListOf("manifest.json" to Json.encodeToString(JsonObject.serializer(), manifest).toByteArray(Charsets.UTF_8))
        for (sha in studies.mapNotNull { it.resultRef?.sha256 }.toSortedSet()) {
            entries += "results/$sha.result" to (results[sha] ?: throw Failure(false, "result missing for export"))
        }
        return PackageArchive.archive(entries).also { if (it.size > MAXIMUM_PACKAGE_BYTES) throw Failure(false, "package size out of range") }
    }

    companion object {
        const val FORMAT = "nodescope.saved-plans"
        const val FILE_EXTENSION = "nodescope"
        const val MIME_TYPE = "application/vnd.nodescope.saved-plans+zip"
        const val MAXIMUM_PACKAGE_BYTES = 512 * 1024 * 1024
        const val MAXIMUM_MANIFEST_BYTES = 16 * 1024 * 1024
        const val MAXIMUM_EXPANDED_BYTES = 512L * 1024 * 1024
        val RESULT_LIMITS = mapOf(ResultRef.PORTABLE_COVERAGE to 8 * 1024 * 1024,
            ResultRef.PORTABLE_LINK_PROFILE to PortableLinkProfileResult.MAXIMUM_BYTES)
        private val appVersion = Regex("^[0-9A-Za-z.+-]{1,32}$")
        private val fields = setOf("format", "version", "scope", "exportedAtMilliseconds", "exportedBy", "includesResults",
            "includesNotes", "candidateSitePlans", "studyPlans", "studies", "omittedResults")

        private fun invalid(reason: String): Nothing = throw Failure(false, reason)

        fun read(data: ByteArray): SavedPlansPackage {
            val files = PackageArchive.entries(data)
            val manifest = files["manifest.json"] ?: invalid("manifest.json is missing")
            if (!StrictJsonText.isStrict(manifest)) invalid("manifest.json is not valid JSON")
            val root = try {
                Json.parseToJsonElement(manifest.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
            } catch (_: Exception) { null } ?: invalid("manifest.json is not valid JSON")
            if (root.text("format") != FORMAT) invalid("not a NodeScope saved-plans package")
            val version = root.integer("version")?.takeIf { it >= 1 } ?: invalid("package version")
            if (version != 1L) throw Failure(true, "package version $version needs a newer version of NodeScope")
            if (root.keys != fields) invalid("manifest fields")
            val scope = Scope.entries.firstOrNull { it.wire == root.text("scope") } ?: invalid("unknown scope")
            val exported = root.integer("exportedAtMilliseconds")?.takeIf { it in 0..253_402_300_799_999 } ?: invalid("exportedAtMilliseconds")
            val by = root["exportedBy"] as? JsonObject
            if (by == null || by.keys != setOf("platform", "appVersion")) invalid("exportedBy")
            val platform = Platform.entries.firstOrNull { it.wire == by.text("platform") } ?: invalid("exportedBy.platform")
            val app = by.text("appVersion")?.takeIf(appVersion::matches) ?: invalid("exportedBy.appVersion")
            val includesResults = root.flag("includesResults") ?: invalid("includes flags must be booleans")
            val includesNotes = root.flag("includesNotes") ?: invalid("includes flags must be booleans")
            val sites = root["candidateSitePlans"] as? JsonArray ?: invalid("record collections must be arrays")
            val plans = root["studyPlans"] as? JsonArray ?: invalid("record collections must be arrays")
            val studyObjects = (root["studies"] as? JsonArray ?: invalid("record collections must be arrays")).toMutableList()
            val omittedObjects = root["omittedResults"] as? JsonArray ?: invalid("record collections must be arrays")

            // A result in a newer result version makes that study's result unusable, not the package.
            val unusable = mutableSetOf<String>()
            for (index in studyObjects.indices) {
                val study = studyObjects[index] as? JsonObject ?: continue
                val ref = study["resultRef"] as? JsonObject ?: continue
                if (ref.text("format") !in RESULT_LIMITS) continue
                val refVersion = ref.integer("version") ?: continue
                if (refVersion == 1L) continue
                if (refVersion < 1) invalid("resultRef.version")
                study.text("id")?.let(unusable::add)
                studyObjects[index] = JsonObject(study + ("resultRef" to JsonObject(ref + ("version" to JsonPrimitive(1)))))
            }

            // Records: exactly the library's v3 rules, through its own codec.
            val document = try {
                DeploymentPlanCodec.decode(Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
                    "schemaVersion" to JsonPrimitive(3), "documentRevision" to JsonPrimitive(1), "plans" to sites,
                    "studyPlans" to plans, "studies" to JsonArray(studyObjects)))).toByteArray(Charsets.UTF_8))
            } catch (_: DeploymentPlanException) { invalid("records") }
            val containers = document.studyPlans.orEmpty()
            val stored = document.savedStudies

            when (scope) {
                Scope.STUDY -> if (stored.size != 1 || containers.isNotEmpty() || document.plans.isNotEmpty() || stored[0].planID != null)
                    invalid("a study package holds exactly one study and nothing else")
                Scope.PLAN -> if (containers.size != 1 || document.plans.isNotEmpty() || stored.any { it.planID != containers[0].id })
                    invalid("a plan package holds exactly one plan and its studies")
                Scope.LIBRARY -> Unit
            }
            if (!includesNotes && containers.any { it.notes != null }) invalid("notes present although the package says they were omitted")

            // Results: every reference has its entry, every entry is referenced, and the bytes match.
            val results = mutableMapOf<String, ByteArray>()
            val studies = stored.map { study ->
                val ref = study.resultRef ?: return@map study
                if (!includesResults) invalid("a result reference in a package without results")
                val limit = RESULT_LIMITS[ref.format] ?: invalid("results that aren't portable never travel")
                val content = files["results/${ref.sha256}.result"] ?: invalid("result entry missing")
                if (content.size != ref.byteLength || DeploymentPlanStore.sha256(content) != ref.sha256)
                    invalid("result bytes don't match their reference")
                results[ref.sha256] = content
                if (study.id in unusable || content.size > limit || !readable(content, ref, study)) {
                    unusable += study.id
                    study.copy(resultRef = null)
                } else study
            }
            val referenced = stored.mapNotNull { it.resultRef?.let { ref -> "results/${ref.sha256}.result" } }.toSet()
            if (files.keys - "manifest.json" != referenced) invalid("a result entry no study references")
            val kept = studies.mapNotNull { it.resultRef?.sha256 }.toSet()

            val ids = stored.map { it.id }.toSet()
            val omitted = mutableListOf<Omission>()
            for (element in omittedObjects) {
                val item = element as? JsonObject
                val studyID = item?.text("studyID")
                if (item == null || item.keys != setOf("studyID", "reason") || studyID == null || studyID !in ids || omitted.any { it.studyID == studyID })
                    invalid("omittedResults must name each package study at most once")
                val reason = OmissionReason.entries.firstOrNull { it.wire == item.text("reason") } ?: invalid("unknown omission reason")
                if (stored.first { it.id == studyID }.resultRef != null) invalid("an omitted result cannot also be included")
                omitted += Omission(studyID, reason)
            }

            return SavedPlansPackage(scope, exported, ExportedBy(platform, app), includesResults, includesNotes,
                document.plans, containers, studies, omitted, results.filterKeys { it in kept }, unusable)
        }

        /** B's strict reader accepts the payload for exactly this study's inputs. */
        private fun readable(data: ByteArray, ref: ResultRef, study: SavedStudy): Boolean = runCatching {
            study.linkProfile?.let { inputs ->
                require(ref.inputFingerprint == PortableInputFingerprint.linkProfile(inputs))
                PortableLinkProfileResult.decode(data, inputs)
                return@runCatching
            }
            val coverage = requireNotNull(study.coverage)
            val inputs = PortableCoverageResult.inputs(coverage)
            val snapshots = PortableCoverageResult.snapshots(coverage)
            require(ref.inputFingerprint == PortableInputFingerprint.coverage(inputs, snapshots, coverage.catalogComplete))
            PortableCoverageResult.decode(data, inputs, snapshots, coverage.catalogComplete)
        }.isSuccess

        private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        /** A JSON integer: not a string, boolean or fraction. */
        private fun JsonObject.integer(name: String): Long? =
            (this[name] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.longOrNull
        private fun JsonObject.flag(name: String): Boolean? =
            (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let { if (it == "true") true else if (it == "false") false else null }
    }

    // ByteArray values compare by content, so a package read back equals the one written.
    override fun equals(other: Any?): Boolean = other is SavedPlansPackage && scope == other.scope &&
        exportedAtMilliseconds == other.exportedAtMilliseconds && exportedBy == other.exportedBy &&
        includesResults == other.includesResults && includesNotes == other.includesNotes &&
        candidateSitePlans == other.candidateSitePlans && studyPlans == other.studyPlans && studies == other.studies &&
        omittedResults == other.omittedResults && unusableResults == other.unusableResults &&
        results.keys == other.results.keys && results.all { (sha, bytes) -> bytes.contentEquals(other.results.getValue(sha)) }

    override fun hashCode(): Int = listOf(scope, exportedAtMilliseconds, studies, results.keys).hashCode()
}

/** The ZIP subset a package uses: stored or deflate entries, no ZIP64, encryption or extra paths. */
internal object PackageArchive {
    const val MAXIMUM_ENTRIES = 101
    private const val MAXIMUM_CENTRAL_DIRECTORY = 1024 * 1024
    private const val ALLOWED_FLAGS = 0x0002 or 0x0004 or 0x0008 or 0x0800
    private val resultName = Regex("^results/[0-9a-f]{64}\\.result$")

    private class Entry(val name: String, val flags: Int, val method: Int, val crc: Long, val compressed: Long, val expanded: Long, val local: Long)

    private fun invalid(reason: String): Nothing = throw SavedPlansPackage.Failure(false, reason)
    private fun ByteArray.u16(offset: Int) = (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
    private fun ByteArray.u32(offset: Int) = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)

    /** Every entry by name, expanded and verified (steps 1–5). */
    fun entries(data: ByteArray): Map<String, ByteArray> {
        val size = data.size
        if (size !in 22..SavedPlansPackage.MAXIMUM_PACKAGE_BYTES) invalid("package size out of range")
        var eocd = -1
        var offset = size - 22
        while (offset >= maxOf(0, size - 22 - 65535)) {
            if (data.u32(offset) == 0x06054b50L && offset + 22 + data.u16(offset + 20) == size) { eocd = offset; break }
            offset--
        }
        if (eocd < 0) invalid("no end of central directory")
        if (eocd >= 20 && data.u32(eocd - 20) == 0x07064b50L) invalid("ZIP64 is not supported")
        val here = data.u16(eocd + 8)
        val total = data.u16(eocd + 10)
        val directorySize = data.u32(eocd + 12)
        val directoryOffset = data.u32(eocd + 16)
        if (data.u16(eocd + 4) != 0 || data.u16(eocd + 6) != 0 || here != total) invalid("multi-disk archives are not supported")
        if (total == 0xFFFF || directorySize == 0xFFFFFFFFL || directoryOffset == 0xFFFFFFFFL) invalid("ZIP64 is not supported")
        if (total !in 1..MAXIMUM_ENTRIES) invalid("entry count")
        if (directorySize > MAXIMUM_CENTRAL_DIRECTORY || directoryOffset + directorySize != eocd.toLong()) invalid("central directory out of place")

        val entries = mutableListOf<Entry>()
        var position = directoryOffset.toInt()
        repeat(total) {
            if (position + 46 > eocd || data.u32(position) != 0x02014b50L) invalid("damaged central directory")
            val flags = data.u16(position + 8)
            val method = data.u16(position + 10)
            val crc = data.u32(position + 16)
            val compressed = data.u32(position + 20)
            val expanded = data.u32(position + 24)
            val nameLength = data.u16(position + 28)
            val extraLength = data.u16(position + 30)
            val commentLength = data.u16(position + 32)
            val startDisk = data.u16(position + 34)
            val local = data.u32(position + 42)
            if (position + 46 + nameLength > eocd) invalid("damaged central directory")
            val nameBytes = data.copyOfRange(position + 46, position + 46 + nameLength)
            position += 46 + nameLength + extraLength + commentLength
            if (position > eocd) invalid("damaged central directory")
            if (compressed == 0xFFFFFFFFL || expanded == 0xFFFFFFFFL || local == 0xFFFFFFFFL) invalid("ZIP64 is not supported")
            if (flags and 0x2041 != 0) invalid("encrypted entries are not supported")
            if (flags and ALLOWED_FLAGS.inv() != 0) invalid("unsupported entry flags")
            if (method != 0 && method != 8) invalid("unsupported compression method")
            if (startDisk != 0) invalid("multi-disk archives are not supported")
            if (nameBytes.any { it < 0 }) invalid("unexpected entry name")
            val name = nameBytes.toString(Charsets.US_ASCII)
            if (name != "manifest.json" && !resultName.matches(name)) invalid("unexpected entry name")
            if (entries.any { it.name == name }) invalid("duplicate entry")
            val limit = if (name == "manifest.json") SavedPlansPackage.MAXIMUM_MANIFEST_BYTES else 8 * 1024 * 1024
            if (expanded > limit) invalid("$name is larger than its limit")
            if (method == 0 && compressed != expanded) invalid("stored entry sizes differ")
            if (compressed > expanded + 1024 + expanded / 1000) invalid("compressed size out of range")
            entries += Entry(name, flags, method, crc, compressed, expanded, local)
        }
        if (position != eocd) invalid("damaged central directory")
        if (entries.sumOf { it.expanded } > SavedPlansPackage.MAXIMUM_EXPANDED_BYTES) invalid("package expands beyond its limit")

        val boundaries = entries.map { it.local }.sorted() + directoryOffset
        return entries.associate { entry ->
            // Compared as Long first: an offset past 2 GiB is outside any package and must not wrap around.
            if (entry.local + 30 > directoryOffset) invalid("damaged local header")
            val local = entry.local.toInt()
            if (data.u32(local) != 0x04034b50L) invalid("damaged local header")
            val nameLength = data.u16(local + 26)
            val extraLength = data.u16(local + 28)
            if (data.u16(local + 6) != entry.flags || data.u16(local + 8) != entry.method || local + 30 + nameLength > directoryOffset ||
                !data.copyOfRange(local + 30, local + 30 + nameLength).contentEquals(entry.name.toByteArray(Charsets.US_ASCII)))
                invalid("local header disagrees with central directory")
            val start = local + 30 + nameLength + extraLength
            val following = boundaries[boundaries.indexOf(entry.local) + 1]
            if (start + entry.compressed > following) invalid("entries overlap")
            // Deflated entries inflate straight from the package's bytes; only stored ones are copied out.
            val content = if (entry.method == 0) data.copyOfRange(start, start + entry.compressed.toInt())
                else inflate(data, start, entry.compressed.toInt(), entry.expanded.toInt())
            if (content.size.toLong() != entry.expanded) invalid("compressed data does not match its sizes")
            if (CRC32().apply { update(content) }.value != entry.crc) invalid("checksum mismatch")
            entry.name to content
        }
    }

    /** Raw deflate that must end exactly at [expected] bytes and consume all of its [length] input bytes; never expands further. */
    private fun inflate(input: ByteArray, offset: Int, length: Int, expected: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(input, offset, length)
            val out = ByteArray(expected + 1)
            var produced = 0
            while (!inflater.finished() && produced < out.size) {
                val count = try { inflater.inflate(out, produced, out.size - produced) }
                    catch (_: java.util.zip.DataFormatException) { invalid("damaged compressed data") }
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                produced += count
            }
            if (!inflater.finished() || inflater.remaining != 0 || produced != expected) invalid("compressed data does not match its sizes")
            return out.copyOf(produced)
        } finally {
            inflater.end()
        }
    }

    /** Entries in order, each deflated when that's smaller, otherwise stored. */
    fun archive(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        fun ByteArrayOutputStream.u16(value: Int) { write(value and 0xFF); write((value shr 8) and 0xFF) }
        fun ByteArrayOutputStream.u32(value: Long) { u16((value and 0xFFFF).toInt()); u16(((value shr 16) and 0xFFFF).toInt()) }
        for ((name, content) in entries) {
            val crc = CRC32().apply { update(content) }.value
            val deflated = deflate(content)
            val method = if (deflated == null) 0 else 8
            val body = deflated ?: content
            val nameBytes = name.toByteArray(Charsets.US_ASCII)
            val local = out.size().toLong()
            // Version 2.0, no flags, 1980-01-01 00:00.
            out.u32(0x04034b50); out.u16(20); out.u16(0); out.u16(method); out.u16(0); out.u16(0x0021); out.u32(crc)
            out.u32(body.size.toLong()); out.u32(content.size.toLong()); out.u16(nameBytes.size); out.u16(0)
            out.write(nameBytes); out.write(body)
            central.u32(0x02014b50); central.u16(20); central.u16(20); central.u16(0); central.u16(method); central.u16(0)
            central.u16(0x0021); central.u32(crc); central.u32(body.size.toLong()); central.u32(content.size.toLong())
            central.u16(nameBytes.size); central.u16(0); central.u16(0); central.u16(0); central.u16(0); central.u32(0); central.u32(local)
            central.write(nameBytes)
        }
        val directoryOffset = out.size().toLong()
        val directory = central.toByteArray()
        out.write(directory)
        out.u32(0x06054b50); out.u16(0); out.u16(0); out.u16(entries.size); out.u16(entries.size)
        out.u32(directory.size.toLong()); out.u32(directoryOffset); out.u16(0)
        return out.toByteArray()
    }

    /** Raw deflate, or null when it wouldn't be smaller. */
    private fun deflate(content: ByteArray): ByteArray? {
        if (content.isEmpty()) return null
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(content)
            deflater.finish()
            val buffer = ByteArray(content.size)
            var written = 0
            while (!deflater.finished() && written < buffer.size) written += deflater.deflate(buffer, written, buffer.size - written)
            return if (deflater.finished() && written < content.size) buffer.copyOf(written) else null
        } finally {
            deflater.end()
        }
    }
}

/**
 * Checks what the JSON parser lets through but the package format refuses: duplicate object fields and
 * bare words other than true, false, null and numbers. Assumes otherwise well-formed JSON. Mirrors iOS.
 */
internal object StrictJsonText {
    private val number = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$")

    private class Level(val isObject: Boolean) { val keys = mutableSetOf<String>(); var expectingKey = isObject }

    fun isStrict(data: ByteArray): Boolean {
        val stack = ArrayDeque<Level>()
        var index = 0
        while (index < data.size) {
            when (data[index].toInt().toChar()) {
                '"' -> {
                    val level = stack.lastOrNull()
                    val isKey = level?.isObject == true && level.expectingKey
                    if (isKey) level!!.expectingKey = false
                    var cursor = index + 1
                    while (cursor < data.size && data[cursor] != '"'.code.toByte()) cursor += if (data[cursor] == '\\'.code.toByte()) 2 else 1
                    if (cursor >= data.size) return false
                    if (isKey) {
                        val key = runCatching {
                            (Json.parseToJsonElement(data.copyOfRange(index, cursor + 1).decodeToString(throwOnInvalidSequence = true)) as JsonPrimitive).content
                        }.getOrNull() ?: return false
                        if (!level!!.keys.add(key)) return false
                    }
                    index = cursor + 1
                    continue
                }
                '{' -> stack.addLast(Level(true))
                '[' -> stack.addLast(Level(false))
                '}', ']' -> stack.removeLastOrNull()
                ',' -> stack.lastOrNull()?.let { if (it.isObject) it.expectingKey = true }
                ' ', '\n', '\r', '\t', ':' -> Unit
                else -> {
                    var cursor = index
                    while (cursor < data.size && data[cursor].toInt().toChar() !in " \n\r\t,:]}") cursor++
                    val word = data.copyOfRange(index, cursor).decodeToString()
                    if (word != "true" && word != "false" && word != "null" && !number.matches(word)) return false
                    index = cursor
                    continue
                }
            }
            index++
        }
        return stack.isEmpty()
    }
}
