package org.nodescope.android.feature.candidatesites

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import org.nodescope.android.feature.coverage.PortableCoverageResult
import org.nodescope.android.feature.linkprofile.PortableLinkProfileResult

enum class DeploymentPlanStoreFailure { NOT_LOADED, CHANGED_ON_DISK, STORAGE_FULL }
class DeploymentPlanStoreException(val reason: DeploymentPlanStoreFailure) : Exception(reason.name)

/**
 * One owner per file; invoke on an IO worker. Use filesDir/CandidateSites/plans.json, never cacheDir.
 *
 * Reads versions 1–3 and always saves version 3 (docs/node-planner/saved-plans-v3-schema.md). In
 * memory a coverage result travels in `calculatedResult`, as before; on disk it's an immutable file in
 * `results/`, named by its SHA-256. The store assigns record revisions by comparing each record with
 * the last loaded copy. Loading never rewrites anything; the first save after loading v1 or v2 backs
 * up the exact original bytes first.
 */
class DeploymentPlanStore(
    private val file: File,
    private val write: (ByteArray, File) -> Unit = ::writePlanAtomically,
) {
    private val directory get() = file.absoluteFile.parentFile
    private val results get() = File(directory, "results")
    private var loaded = false
    private var baseline: ByteArray? = null
    /** The last loaded or saved document as stored: version, revisions and result references. */
    private var stored: DeploymentPlanDocument? = null

    @Synchronized fun load(): DeploymentPlanDocument {
        loaded = false
        val bytes = read()
        val document = bytes?.let(DeploymentPlanCodec::decode) ?: DeploymentPlanDocument(1, emptyList())
        baseline = bytes
        stored = document
        loaded = true
        return withResults(document)
    }

    /**
     * Complete-snapshot replacement, written as version 3. Failed saves retain the caller's draft and
     * the old files. Returns the document as saved: revisions, references and result states.
     */
    @Synchronized fun save(document: DeploymentPlanDocument): DeploymentPlanDocument {
        if (!loaded) throw DeploymentPlanStoreException(DeploymentPlanStoreFailure.NOT_LOADED)
        if (!read().contentEquals(baseline)) throw DeploymentPlanStoreException(DeploymentPlanStoreFailure.CHANGED_ON_DISK)
        val previous = requireNotNull(stored)
        val payloads = mutableMapOf<String, ByteArray>()
        val next = version3(document, previous, payloads)
        val bytes = DeploymentPlanCodec.encode(next)
        val total = next.savedStudies.mapNotNull { it.resultRef }.distinctBy { it.sha256 }.sumOf { it.byteLength.toLong() }
        if (total > MAXIMUM_RESULT_STORAGE) throw DeploymentPlanStoreException(DeploymentPlanStoreFailure.STORAGE_FULL)

        Files.createDirectories(directory.toPath())
        // 1. Result files first: an interrupted save leaves only unreferenced files behind.
        for ((sha, data) in payloads) install(sha, data)
        // 2. Before the first version 3 file replaces a version 1 or 2 original, keep the original.
        if (previous.schemaVersion < 3) baseline?.let(::backUp)
        // 3. The manifest, atomically.
        write(bytes, file)
        baseline = bytes
        stored = next
        // 4. Only then remove result files nothing references any more.
        collectGarbage(next)
        return withResults(next)
    }

    /** The document as version 3: result bytes moved to files, revisions advanced where records changed. */
    private fun version3(document: DeploymentPlanDocument, previous: DeploymentPlanDocument,
                         payloads: MutableMap<String, ByteArray>): DeploymentPlanDocument {
        val old = previous.savedStudies.associateBy { it.id }
        val known = previous.savedStudies.mapNotNull { it.resultRef }.associateBy { it.sha256 }
        val studies = document.savedStudies.map { study ->
            val before = old[study.id]
            val coverage = study.coverage
            val encoded = coverage?.calculatedResult ?: study.linkProfileResult
            val ref = when {
                encoded != null -> {
                    val data = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
                        throw DeploymentPlanException(DeploymentPlanFailure.INVALID_DATA)
                    }
                    val sha = sha256(data)
                    payloads[sha] = data
                    // Unchanged bytes keep the reference they were saved under (a migrated result keeps its format);
                    // a new record with bytes the library already holds, such as a duplicate, shares that reference.
                    val existing = if (before == null) known[sha] else before.resultRef?.takeIf { it.sha256 == sha }
                    fun invalid(): Nothing = throw DeploymentPlanException(DeploymentPlanFailure.INVALID_DATA)
                    existing ?: when {
                        study.kind == SavedStudy.Kind.LINK_PROFILE -> if (PortableLinkProfileResult.isPortable(data))
                            ResultRef(ResultRef.PORTABLE_LINK_PROFILE, 1, data.size, sha, PortableLinkProfileResult.fingerprint(data) ?: invalid())
                            else invalid()
                        PortableCoverageResult.isPortable(data) ->
                            ResultRef(ResultRef.PORTABLE_COVERAGE, 1, data.size, sha, PortableCoverageResult.fingerprint(data) ?: invalid())
                        else -> ResultRef(ResultRef.ANDROID_LEGACY, 1, data.size, sha)
                    }
                }
                // A result that couldn't be read here stays attached while the calculation inputs are unchanged.
                before?.resultRef != null && sameInputs(before, study) -> before.resultRef
                // A duplicate shares a reference the library already holds, even one this device can't read.
                before == null && study.resultRef != null && known[study.resultRef.sha256] == study.resultRef -> study.resultRef
                else -> null
            }
            study.copy(coverage = coverage?.copy(calculatedResult = null), resultRef = ref, revision = null,
                resultStatus = SavedResultStatus.NONE, linkProfileResult = null)
        }
        fun <T> revised(item: T, before: T?, revision: Long?, strip: (T) -> T): Long {
            val prior = revision ?: return 1
            if (before != null && strip(before) == strip(item)) return prior
            if (prior >= MAXIMUM_REVISION) throw DeploymentPlanException(DeploymentPlanFailure.INVALID_DATA)
            return prior + 1
        }
        val oldPlans = previous.plans.associateBy { it.id }
        val oldContainers = previous.studyPlans.orEmpty().associateBy { it.id }
        val oldStudies = previous.savedStudies.associateBy { it.id }
        val plans = document.plans.map { plan ->
            val before = oldPlans[plan.id]
            plan.copy(revision = revised(plan, before, before?.revision) { it.copy(revision = null) })
        }
        val containers = document.studyPlans.orEmpty().map { container ->
            val before = oldContainers[container.id]
            container.copy(revision = revised(container, before, before?.revision) { it.copy(revision = null) })
        }
        val revisedStudies = studies.map { study ->
            val before = oldStudies[study.id]
            study.copy(revision = revised(study, before, before?.revision) {
                it.copy(revision = null, resultStatus = SavedResultStatus.NONE, coverage = it.coverage?.copy(calculatedResult = null),
                    linkProfileResult = null)
            })
        }
        val revision = previous.documentRevision ?: 0
        if (revision >= MAXIMUM_REVISION) throw DeploymentPlanException(DeploymentPlanFailure.INVALID_DATA)
        return DeploymentPlanDocument(3, plans, revisedStudies, revision + 1, containers)
    }


    /** Attach result bytes and states for callers; the stored form keeps only references. */
    private fun withResults(document: DeploymentPlanDocument): DeploymentPlanDocument {
        if (document.schemaVersion < 3) return document.copy(studies = document.studies?.map { study ->
            study.copy(resultStatus = if (study.coverage?.calculatedResult != null) SavedResultStatus.AVAILABLE else SavedResultStatus.NONE)
        })
        return document.copy(studies = document.savedStudies.map { study ->
            val ref = study.resultRef ?: return@map study
            val data = readResult(ref.sha256, ref.byteLength)
            when {
                data == null -> study.copy(resultStatus = SavedResultStatus.MISSING)
                data.size != ref.byteLength || sha256(data) != ref.sha256 -> study.copy(resultStatus = SavedResultStatus.UNVERIFIED)
                // A portable result shows only for the inputs it was calculated from.
                ref.format == ResultRef.PORTABLE_COVERAGE && study.coverage != null && ref.inputFingerprint != coverageFingerprint(study.coverage) ->
                    study.copy(resultStatus = SavedResultStatus.UNVERIFIED)
                ref.format == ResultRef.PORTABLE_LINK_PROFILE && study.linkProfile != null ->
                    if (ref.inputFingerprint != runCatching { PortableInputFingerprint.linkProfile(study.linkProfile) }.getOrNull())
                        study.copy(resultStatus = SavedResultStatus.UNVERIFIED)
                    else study.copy(linkProfileResult = Base64.getEncoder().encodeToString(data), resultStatus = SavedResultStatus.AVAILABLE)
                (ref.format == ResultRef.ANDROID_LEGACY || ref.format == ResultRef.PORTABLE_COVERAGE) && study.coverage != null ->
                    study.copy(coverage = study.coverage.copy(calculatedResult = Base64.getEncoder().encodeToString(data)),
                        resultStatus = SavedResultStatus.AVAILABLE)
                else -> study.copy(resultStatus = SavedResultStatus.UNSUPPORTED)
            }
        })
    }

    private fun coverageFingerprint(coverage: SavedCoverage): String? = runCatching {
        PortableInputFingerprint.coverage(PortableCoverageResult.inputs(coverage), PortableCoverageResult.snapshots(coverage), coverage.catalogComplete)
    }.getOrNull()

    private fun readResult(sha: String, expected: Int): ByteArray? {
        val path = File(results, "$sha.result")
        if (!path.isFile) return null
        // A file larger than its reference can't be the right one; don't read more than that.
        if (path.length() > expected.toLong()) return ByteArray(0)
        return try { path.readBytes() } catch (_: java.io.IOException) { null }
    }

    /** Content-addressed install. An existing file is trusted only after its bytes are checked. */
    private fun install(sha: String, data: ByteArray) {
        Files.createDirectories(results.toPath())
        val target = File(results, "$sha.result")
        if (target.isFile && target.length() == data.size.toLong() && sha256(target.readBytes()) == sha) return
        writePlanAtomically(data, target)
    }

    /**
     * Keep the exact original: reuse the existing sibling v1 backup or an identical earlier one,
     * otherwise add a new content-addressed backup. A different existing backup is never overwritten.
     */
    private fun backUp(original: ByteArray) {
        val sibling = File(file.path + ".v1-backup")
        if (sibling.isFile && sibling.readBytes().contentEquals(original)) return
        val backups = File(directory, "migration-backups")
        Files.createDirectories(backups.toPath())
        val target = File(backups, "${sha256(original)}.json")
        if (target.isFile && target.readBytes().contentEquals(original)) return
        writePlanAtomically(original, target)
    }

    /**
     * Remove result files nothing references: not the saved manifest, not any recovery draft. Backups hold
     * inline results, not references. If a draft can't be read, nothing is deleted.
     */
    private fun collectGarbage(document: DeploymentPlanDocument) = synchronized(StudyDraftStore.LOCK) {
        val drafts = StudyDraftStore(directory).referencedResults() ?: return@synchronized
        val referenced = document.savedStudies.mapNotNull { it.resultRef?.sha256 }.toSet() + drafts
        try {
            results.listFiles()?.forEach { candidate ->
                val sha = candidate.name.removeSuffix(".result")
                if (candidate.name.endsWith(".result") && Regex("[0-9a-f]{64}").matches(sha) && sha !in referenced) candidate.delete()
            }
        } catch (_: SecurityException) {
            // Cleanup is best effort; an unreferenced file is harmless.
        }
    }

    private fun read(): ByteArray? {
        val input = try { Files.newInputStream(file.toPath()) } catch (_: NoSuchFileException) { return null }
        return input.use {
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (true) {
                val remaining = DeploymentPlanCodec.MAXIMUM_BYTES + 1 - result.size()
                if (remaining <= 0) throw DeploymentPlanException(DeploymentPlanFailure.TOO_LARGE)
                val count = it.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                result.write(buffer, 0, count)
                if (result.size() > DeploymentPlanCodec.MAXIMUM_BYTES) throw DeploymentPlanException(DeploymentPlanFailure.TOO_LARGE)
            }
            result.toByteArray()
        }
    }

    companion object {
        /** All referenced result files together (schema: 512 MiB for A1). */
        const val MAXIMUM_RESULT_STORAGE = 512L * 1024 * 1024

        /** Calculation inputs only: names, plan membership and result fields don't count. */
        internal fun sameInputs(a: SavedStudy, b: SavedStudy) =
            a.kind == b.kind && a.linkProfile == b.linkProfile && a.coverage?.copy(calculatedResult = null) == b.coverage?.copy(calculatedResult = null)
        internal fun sha256(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }
}

/** Same-directory atomic rename, with no non-atomic fallback. Failure leaves the old file intact. */
internal fun writePlanAtomically(bytes: ByteArray, file: File) {
    val staging = Files.createTempFile(file.toPath().parent, ".plans-", ".tmp")
    try {
        java.io.FileOutputStream(staging.toFile()).use { it.write(bytes); it.fd.sync() }
        Files.move(staging, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(staging)
    }
}

/** Disk use of a saved-plans folder, for Settings: result files and pre-upgrade backups. */
data class SavedPlansUsage(val resultBytes: Long = 0, val backupBytes: Long = 0) {
    companion object {
        /** Measures the folder holding `file` (CandidateSites/plans.json). Missing folders count as empty. Call off the main thread. */
        fun measure(file: File): SavedPlansUsage {
            val directory = file.absoluteFile.parentFile ?: return SavedPlansUsage()
            fun total(folder: String, suffix: String) =
                File(directory, folder).listFiles()?.filter { it.isFile && it.name.endsWith(suffix) }?.sumOf { it.length() } ?: 0L
            val sibling = File(file.path + ".v1-backup").takeIf { it.isFile }?.length() ?: 0L
            return SavedPlansUsage(total("results", ".result"), total("migration-backups", ".json") + sibling)
        }

        fun application(filesDir: File) = measure(File(filesDir, "CandidateSites/plans.json"))
    }
}
