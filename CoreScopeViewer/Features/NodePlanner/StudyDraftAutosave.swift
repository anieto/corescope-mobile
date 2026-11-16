import SwiftUI

/// What a tool has that isn't saved.
struct DraftContent {
    var title: String
    /// The tool's editor values, as text.
    var editor: [String: String]
    var planID: String?
    var savedStudyID: String?
    var savedRevision: Int64?
    var catalog: [SavedCoverageNode]? = nil
    var catalogComplete = false
    /// A completed result's portable payload.
    var result: Data? = nil
}

/// A tool whose unsaved work is kept as a recovery draft (deliverable C).
@MainActor
protocol StudyDraftSource: AnyObject {
    var draftKind: SavedStudy.Kind { get }
    /// Nil when there's nothing unsaved: nothing placed, or exactly what was opened or last saved.
    func draftContent() -> DraftContent?
}

/// One editing session's draft. `flush` writes it when it changed, or removes it once nothing is
/// unsaved; a failed write is kept in `failure` and retried on the next flush. Mirrors Android.
final class StudyDraftSession: @unchecked Sendable {
    let id = UUID().uuidString.lowercased()
    private let store: StudyDraftStore?
    private let kind: SavedStudy.Kind
    private let lock = NSLock()
    private var written: StudyDraft?
    private var writtenResult: Data?
    private(set) var failure: StudyDraftStoreError?

    init(kind: SavedStudy.Kind, store: StudyDraftStore? = try? .application()) {
        self.kind = kind
        self.store = store
    }

    func flush(_ content: DraftContent?, host: String?, region: String?) {
        lock.withLock {
            guard let store else { return }
            guard let content else {
                if written != nil || store.draft(id) != nil { store.delete(id) }
                written = nil
                writtenResult = nil
                failure = nil
                return
            }
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            let previous = written ?? store.draft(id)
            var draft = StudyDraft(id: id, kind: kind, createdAtMilliseconds: previous?.createdAtMilliseconds ?? now,
                                   updatedAtMilliseconds: now, title: content.title, host: host, region: region,
                                   planID: content.planID, savedStudyID: content.savedStudyID, savedRevision: content.savedRevision,
                                   editor: content.editor, catalog: content.catalog, catalogComplete: content.catalogComplete,
                                   resultSha256: previous?.resultSha256)
            // Unchanged since the last write: nothing to do.
            if var last = written, failure == nil, writtenResult == content.result {
                last.updatedAtMilliseconds = now
                draft.resultSha256 = last.resultSha256
                if last == draft { return }
            }
            do {
                written = try store.save(draft, result: content.result)
                writtenResult = content.result
                failure = nil
            } catch {
                failure = error as? StudyDraftStoreError ?? .writeFailed
            }
        }
    }

    func discard() { flush(nil, host: nil, region: nil) }
}

private struct StudyDraftAutosave: ViewModifier {
    let session: StudyDraftSession
    let source: any StudyDraftSource
    let host: String?
    let region: String?
    @Environment(\.scenePhase) private var scenePhase

    private func flush() {
        let content = source.draftContent()
        let session = session, host = host, region = region
        Task.detached(priority: .utility) { session.flush(content, host: host, region: region) }
    }

    func body(content: Content) -> some View {
        content
            .task(id: session.id) {
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(1.5))
                    flush()
                }
            }
            .onChange(of: scenePhase) { _, phase in if phase != .active { flush() } }
            .onDisappear { flush() }
    }
}

extension View {
    /// Keeps `source`'s unsaved work in `session`: shortly after changes, when the app leaves the
    /// foreground and when the screen goes away. This only writes; restoring never starts work.
    func studyDraftAutosave(_ session: StudyDraftSession, source: any StudyDraftSource, host: String?, region: String?) -> some View {
        modifier(StudyDraftAutosave(session: session, source: source, host: host, region: region))
    }
}
