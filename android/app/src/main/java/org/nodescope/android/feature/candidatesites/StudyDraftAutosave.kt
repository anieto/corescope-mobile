package org.nodescope.android.feature.candidatesites

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a tool has that isn't saved. */
class DraftContent(
    val title: String,
    /** The tool's raw editor values, including text that isn't a valid number yet. */
    val editor: Map<String, String>,
    val planID: String?,
    val savedStudyID: String?,
    val savedRevision: Long?,
    val catalog: List<SavedCoverageNode>? = null,
    val catalogComplete: Boolean = false,
    /** A completed result's portable payload; the same array for the same result, so it's hashed once. */
    val result: ByteArray? = null,
)

/** A tool whose unsaved work is kept as a recovery draft. */
interface StudyDraftSource {
    val draftKind: SavedStudy.Kind
    /** Null when there's nothing unsaved: nothing placed, or exactly what was opened or last saved. Main thread. */
    fun draftContent(): DraftContent?
}

/**
 * One editing session's draft. [flush] writes it when it changed, or removes it once nothing is
 * unsaved; a failed write is reported in [failure] and retried on the next flush. Thread-safe.
 */
class StudyDraftSession(private val store: StudyDraftStore, val id: String, private val kind: SavedStudy.Kind) {
    private var written: StudyDraft? = null
    private var writtenResult: ByteArray? = null
    @Volatile var failure: StudyDraftFailure? = null; private set

    @Synchronized fun flush(content: DraftContent?, host: String?, region: String?) {
        if (content == null) {
            if (written != null || store.draft(id) != null) store.delete(id)
            written = null
            writtenResult = null
            failure = null
            return
        }
        val now = System.currentTimeMillis()
        val previous = written ?: store.draft(id)
        val draft = StudyDraft(id, kind, previous?.createdAtMilliseconds ?: now, now, content.title, host, region,
            content.planID, content.savedStudyID, content.savedRevision, content.editor, content.catalog, content.catalogComplete,
            previous?.resultSha256)
        // Unchanged since the last write: nothing to do.
        if (written?.copy(updatedAtMilliseconds = now) == draft && writtenResult === content.result && failure == null) return
        try {
            written = store.save(draft, content.result)
            writtenResult = content.result
            failure = null
        } catch (error: StudyDraftException) {
            failure = error.reason
        }
    }

    fun discard() = flush(null, null, null)
}

/**
 * Keeps [source]'s unsaved work in [session]: shortly after changes, when the app goes to the background
 * and when the screen goes away. Restoring never starts work; this only writes.
 */
@Composable
fun StudyDraftAutosave(session: StudyDraftSession, source: StudyDraftSource, host: String?, region: String?) {
    val current = rememberUpdatedState(Triple(source, host, region))
    fun flushNow() {
        val (tool, analyzer, area) = current.value
        val content = tool.draftContent()
        CoroutineScope(Dispatchers.IO).launch { session.flush(content, analyzer, area) }
    }
    LaunchedEffect(session) {
        while (true) {
            delay(1_500)
            val (tool, analyzer, area) = current.value
            val content = tool.draftContent()
            withContext(Dispatchers.IO) { session.flush(content, analyzer, area) }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) flushNow() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            flushNow()
        }
    }
}
