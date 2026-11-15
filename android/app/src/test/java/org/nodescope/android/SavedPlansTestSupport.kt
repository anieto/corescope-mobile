package org.nodescope.android

import java.io.File
import org.nodescope.android.feature.candidatesites.*

/** A library's content without version 3 bookkeeping (versions, revisions, result references and states). */
internal fun DeploymentPlanDocument.content(): Pair<List<DeploymentPlan>, List<SavedStudy>> =
    plans.map { it.copy(revision = null) } to savedStudies.map {
        it.copy(revision = null, resultRef = null, resultStatus = SavedResultStatus.NONE)
    }

/** Where the store keeps a pre-upgrade original with these exact bytes. */
internal fun migrationBackup(file: File, original: ByteArray) =
    File(file.parentFile, "migration-backups/${DeploymentPlanStore.sha256(original)}.json")
