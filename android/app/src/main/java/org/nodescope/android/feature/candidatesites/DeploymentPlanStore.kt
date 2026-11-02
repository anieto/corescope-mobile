package org.nodescope.android.feature.candidatesites

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption

enum class DeploymentPlanStoreFailure { NOT_LOADED, CHANGED_ON_DISK }
class DeploymentPlanStoreException(val reason: DeploymentPlanStoreFailure) : Exception(reason.name)

/** One owner per file; invoke on an IO worker. Use filesDir/CandidateSites/plans.json, never cacheDir. */
class DeploymentPlanStore(
    private val file: File,
    private val write: (ByteArray, File) -> Unit = ::writePlanAtomically,
) {
    private var loaded = false
    private var baseline: ByteArray? = null

    @Synchronized fun load(): DeploymentPlanDocument {
        loaded = false
        val bytes = read()
        val document = bytes?.let(DeploymentPlanCodec::decode) ?: DeploymentPlanDocument(1, emptyList())
        baseline = bytes
        loaded = true
        return document
    }

    /** Complete-snapshot replacement; failed saves retain the caller's draft and old baseline. */
    @Synchronized fun save(document: DeploymentPlanDocument) {
        if (!loaded) throw DeploymentPlanStoreException(DeploymentPlanStoreFailure.NOT_LOADED)
        val bytes = DeploymentPlanCodec.encode(document)
        if (!read().contentEquals(baseline)) throw DeploymentPlanStoreException(DeploymentPlanStoreFailure.CHANGED_ON_DISK)
        Files.createDirectories(file.toPath().parent)
        write(bytes, file)
        baseline = bytes
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
}

/** Same-directory atomic rename, with no non-atomic fallback. Failure leaves the old file intact. */
private fun writePlanAtomically(bytes: ByteArray, file: File) {
    val staging = Files.createTempFile(file.toPath().parent, ".plans-", ".tmp")
    try {
        java.io.FileOutputStream(staging.toFile()).use { it.write(bytes); it.fd.sync() }
        Files.move(staging, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(staging)
    }
}
