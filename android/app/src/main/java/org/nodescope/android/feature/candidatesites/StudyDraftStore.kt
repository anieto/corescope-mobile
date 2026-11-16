package org.nodescope.android.feature.candidatesites

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Unsaved work in a Link Profile or Estimated Coverage session (deliverable C), kept so a source
 * switch, process death or crash doesn't lose it. One per editing session; never a named study.
 */
@Serializable
data class StudyDraft(
    val id: String,
    val kind: SavedStudy.Kind,
    val createdAtMilliseconds: Long,
    val updatedAtMilliseconds: Long,
    /** For the Unfinished studies list: the study's name, or where it is. */
    val title: String,
    /** The analyzer and region it was being edited against. */
    val host: String? = null,
    val region: String? = null,
    /** The plan the tool was opened from, and the saved study it edits at the revision it opened. */
    val planID: String? = null,
    val savedStudyID: String? = null,
    val savedRevision: Long? = null,
    /** The tool's raw editor values, including text that isn't a valid number yet. */
    val editor: Map<String, String> = emptyMap(),
    /** Coverage: the captured node catalog, kept until an explicit refresh. */
    val catalog: List<SavedCoverageNode>? = null,
    val catalogComplete: Boolean = false,
    /** A completed result for exactly these inputs: a portable payload in results/, by SHA-256. */
    val resultSha256: String? = null,
    val format: String = FORMAT,
    val version: Int = 1,
) {
    companion object { const val FORMAT = "nodescope.study-draft" }
}

enum class StudyDraftFailure { FULL, WRITE_FAILED }
class StudyDraftException(val reason: StudyDraftFailure) : Exception(reason.name)

/**
 * Drafts in `CandidateSites/drafts/<id>.json` beside the library (never the cache), each written
 * atomically on its own, so sessions can't overwrite one another. A draft's result is an ordinary
 * content-addressed result file; the library's cleanup keeps every file a draft refers to and deletes
 * nothing while any draft can't be read. Drafts are never evicted: at [MAXIMUM_DRAFTS] a new one is
 * refused instead. Invoke on an IO worker.
 */
class StudyDraftStore(private val directory: File) {
    private val drafts get() = File(directory, "drafts")
    private val results get() = File(directory, "results")

    /** Saves [draft] and, if given, its [result] payload; returns the draft as stored. */
    fun save(draft: StudyDraft, result: ByteArray?): StudyDraft = synchronized(LOCK) {
        val target = File(drafts, "${draft.id}.json")
        if (!target.isFile && (drafts.listFiles { file -> file.name.endsWith(".json") }?.size ?: 0) >= MAXIMUM_DRAFTS)
            throw StudyDraftException(StudyDraftFailure.FULL)
        val sha = result?.let(DeploymentPlanStore::sha256)
        val stored = draft.copy(resultSha256 = sha)
        try {
            Files.createDirectories(drafts.toPath())
            // The reference first, then the file, so cleanup never sees the file without its owner.
            writePlanAtomically(json.encodeToString(StudyDraft.serializer(), stored).toByteArray(Charsets.UTF_8), target)
            if (result != null && sha != null) {
                Files.createDirectories(results.toPath())
                val file = File(results, "$sha.result")
                if (!(file.isFile && file.length() == result.size.toLong() && DeploymentPlanStore.sha256(file.readBytes()) == sha))
                    writePlanAtomically(result, file)
            }
        } catch (_: java.io.IOException) {
            throw StudyDraftException(StudyDraftFailure.WRITE_FAILED)
        }
        stored
    }

    /** Every readable draft, most recently edited first. */
    fun list(): List<StudyDraft> = synchronized(LOCK) {
        drafts.listFiles { file -> file.name.endsWith(".json") }.orEmpty().mapNotNull(::read)
            .sortedWith(compareByDescending<StudyDraft> { it.updatedAtMilliseconds }.thenBy { it.id })
    }

    fun draft(id: String): StudyDraft? = synchronized(LOCK) { read(File(drafts, "$id.json")) }

    /** The draft's result payload, if its file is here and intact. */
    fun result(draft: StudyDraft): ByteArray? {
        val sha = draft.resultSha256 ?: return null
        val file = File(results, "$sha.result")
        if (!file.isFile || file.length() > MAXIMUM_RESULT_BYTES) return null
        return file.readBytes().takeIf { DeploymentPlanStore.sha256(it) == sha }
    }

    fun delete(id: String) = synchronized(LOCK) { File(drafts, "$id.json").delete(); Unit }

    /** Result files drafts refer to; null if any draft can't be read, so cleanup deletes nothing. */
    internal fun referencedResults(): Set<String>? {
        val files = drafts.listFiles { file -> file.name.endsWith(".json") } ?: return emptySet()
        return files.map { read(it) ?: return null }.mapNotNull { it.resultSha256 }.toSet()
    }

    private fun read(file: File): StudyDraft? = try {
        if (!file.isFile || file.length() > MAXIMUM_DRAFT_BYTES) null
        else json.decodeFromString(StudyDraft.serializer(), file.readText()).takeIf {
            it.format == StudyDraft.FORMAT && it.version == 1 && "${it.id}.json" == file.name
        }
    } catch (_: Exception) { null }

    companion object {
        const val MAXIMUM_DRAFTS = 50
        /** A draft holds inputs and at most 200 captured nodes; far below this. */
        private const val MAXIMUM_DRAFT_BYTES = 2L * 1024 * 1024
        private const val MAXIMUM_RESULT_BYTES = 8L * 1024 * 1024
        /** Shared with the library's cleanup, so a draft's file can't be collected while it's being written. */
        internal val LOCK = Any()
        private val json = Json { encodeDefaults = true }

        fun application(filesDir: File) = StudyDraftStore(File(filesDir, "CandidateSites"))
    }
}

/** A draft ready to resume: the saved study it edits (at the draft's revision) and its result, if intact. */
class ResumedDraft(val draft: StudyDraft, val study: SavedStudy?, val result: ByteArray?)

/**
 * Loads draft [id] for resuming. The saved study is the library's current record with the draft's
 * revision, so Update still refuses a study changed since; a deleted one becomes a stand-in that the
 * save sheet offers to save as new.
 */
fun StudyDraftStore.resume(id: String, library: SavedPlansLibrary): ResumedDraft? {
    val draft = draft(id) ?: return null
    val study = draft.savedStudyID?.let { savedID ->
        library.studies.firstOrNull { it.id == savedID }?.copy(revision = draft.savedRevision)
            ?: SavedStudy(savedID, draft.title, draft.createdAtMilliseconds, draft.updatedAtMilliseconds, draft.kind, revision = draft.savedRevision)
    }
    return ResumedDraft(draft, study, result(draft))
}
