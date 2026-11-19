import SwiftUI

@MainActor @Observable
final class CoreScopeAccountModel {
    enum NotificationStatus { case checking, unavailable, failed, ready }
    private(set) var notificationStatus: NotificationStatus = .checking
    private(set) var notifications: CoreScopeAccountNotifications?
    private var sourceNotificationsEnabled = false

    private(set) var user: CoreScopeAccountUser?
    private(set) var supported = false
    private(set) var busy = false
    private(set) var message: String?
    private(set) var selection: CoreScopeAccountSelectionState?
    private(set) var resetDocument: CoreScopeAccountSettings?
    private var client: CoreScopeAccountClient?
    private var origin = ""
    private var source = ""
    private let store: CoreScopeAccountSelectionStore
    // Serialize account operations across windows, including logout and local edits.
    private static var activeOrigins: Set<String> = []
    private static var epochs: [String: UUID] = [:]
    private var epoch: UUID?

    init(store: CoreScopeAccountSelectionStore = .shared,
         client: CoreScopeAccountClient? = nil, origin: String = "", source: String = "",
         user: CoreScopeAccountUser? = nil) {
        self.store = store
        self.client = client
        self.origin = origin
        self.source = source
        self.user = user
        self.epoch = Self.epochs[origin]
    }

    var favoriteKeys: [String] { selection?.favorites.sorted() ?? [] }
    var claimed: [String: String] {
        guard let selection else { return [:] }
        return (try? CoreScopeFavoriteMerge.selection(selection.baseline).claimed) ?? [:]
    }

    func open(settings: AnalyzerSettings, favorites: FavoritesStore) async {
        guard !busy else { return }
        origin = settings.baseURL.absoluteString
        source = AnalyzerSettings.normalizedHost(settings.host)
        epoch = Self.epochs[origin]
        await action {
            guard settings.beaconBackend == nil else { throw CoreScopeAccountError.unsupported }
            let origin = self.origin
            let cookie = try AccountSessionKeychain.load(origin: origin)
            let client = try CoreScopeAccountClient(origin: settings.baseURL, cookie: cookie, lifetime: settings.sourceSession,
                save: { try AccountSessionKeychain.save($0, origin: origin) })
            self.client = client
            let capabilities = try await client.accountCapabilities()
            supported = capabilities?.enabled == true
            sourceNotificationsEnabled = capabilities?.notifications == true
            guard supported else { throw CoreScopeAccountError.unsupported }
            if cookie != nil {
                user = try await client.me()
                try await reconcile(upload: false)
            } else {
                store.deactivate(origin: origin)
            }
        }
    }

    func login(email: String, password: String, favorites: FavoritesStore) async {
        await action {
            guard let client, supported else { throw CoreScopeAccountError.unsupported }
            user = try await client.login(email: email.trimmingCharacters(in: .whitespacesAndNewlines), password: password)
            epoch = UUID()
            Self.epochs[origin] = epoch
            selection = nil
            resetDocument = nil
            try await reconcile(upload: false)
        }
    }

    func sync(upload: Bool) async {
        await action {
            guard let client, let previousUser = user else { throw CoreScopeAccountError.signedOut }
            let authenticated = try await client.me()
            guard authenticated.id == previousUser.id else {
                user = nil
                selection = nil
                throw CoreScopeAccountError.signedOut
            }
            user = authenticated
            try await reconcile(upload: upload)
        }
    }

    private func reconcile(upload: Bool) async throws {
        guard let client, let user else { throw CoreScopeAccountError.signedOut }
        store.activate(origin: origin, userID: user.id)
        selection = try store.load(origin: origin, userID: user.id)
        let remote = try await client.settings()
        try Task.checkCancellation()
        // Favorites can stage an edit from another view while this request is suspended.
        let saved = try store.load(origin: origin, userID: user.id)
        if let saved, saved.baseline.generation != remote.generation {
            resetDocument = remote
            throw CoreScopeAccountError.reset
        }
        resetDocument = nil
        var next = try saved ?? CoreScopeAccountSelectionState(remote: remote)
        try next.refresh(remote)
        // Persist rebased edits before IO: failures and conflicts leave them pending for retry.
        try persist(next)
        if upload && next.hasPendingEdits {
            let (document, _) = try CoreScopeFavoriteMerge.document(base: next.baseline, local: next.favorites, remote: remote)
            if document.keys != (remote.doc?.keys ?? [:]) {
                // A conflict remains pending. No immediate write retry can bypass the minute limit.
                let accepted = try await client.put(document, base: remote, csrf: user.csrfToken)
                try Task.checkCancellation()
                var latest = try store.load(origin: origin, userID: user.id) ?? next
                try latest.acknowledge(accepted, sentFavorites: next.favorites)
                try persist(latest)
            }
        }
        if upload && selection?.hasPendingEdits == true {
            message = String(localized: "Earlier changes synced. Newer edits are saved on this device; sync again after one minute.")
        } else {
            message = upload ? String(localized: "Account favorites synced.") : String(localized: "Account favorites refreshed. Device favorites are unchanged.")
        }
    }

    private func persist(_ state: CoreScopeAccountSelectionState) throws {
        guard let user else { throw CoreScopeAccountError.signedOut }
        try store.save(state, origin: origin, userID: user.id)
        selection = state
    }

    func importDeviceFavorites(_ favorites: FavoritesStore) async {
        await edit { state in
            let keys = favorites.nodeKeys(source: source)
            guard keys.allSatisfy(CoreScopeFavoriteMerge.validKey) else { throw CoreScopeAccountError.response }
            state.favorites.formUnion(keys)
        }
    }

    func removeFavorite(_ key: String) async {
        await edit { $0.favorites.remove(key) }
    }

    private func edit(_ update: (inout CoreScopeAccountSelectionState) throws -> Void) async {
        await action {
            guard let user, resetDocument == nil,
                  var state = try store.load(origin: origin, userID: user.id) else { throw CoreScopeAccountError.reset }
            try update(&state)
            try persist(state)
            message = String(localized: "Account edits saved on this device. Choose Sync now to upload them.")
        }
    }

    func resolveReset(restore: Bool) async {
        await action {
            guard let user, let remote = resetDocument,
                  var state = try store.load(origin: origin, userID: user.id) else { return }
            if restore { try state.restore(onto: remote) }
            else { state = try CoreScopeAccountSelectionState(remote: remote) }
            try persist(state)
            resetDocument = nil
            message = restore ? String(localized: "Selections restored locally. Choose Sync now to upload them.") : String(localized: "Using the server’s selections.")
        }
    }

    func copyToDevice(_ favorites: FavoritesStore) async {
        await action { try copy(favorites) }
    }

    private func copy(_ favorites: FavoritesStore) throws {
        guard let user, let state = try store.load(origin: origin, userID: user.id) else { return }
        let claimed = try CoreScopeFavoriteMerge.selection(state.baseline).claimed
        favorites.applyAccountNodes(favorites.nodeKeys(source: source).union(state.favorites).union(claimed.keys),
            names: claimed, source: source)
        message = String(localized: "Account selections copied to device favorites.")
    }

    func loadNotifications() async {
        guard let user, let client else { return }
        if !sourceNotificationsEnabled {
            notifications = nil
            notificationStatus = .unavailable
            return
        }
        notificationStatus = .checking
        do {
            let current = try await client.notifications()
            try Task.checkCancellation()
            guard self.user?.id == user.id else { return }
            notifications = current
            notificationStatus = .ready
            #if NODESCOPE_PUSH_STAGING || NODESCOPE_PUSH_PRODUCTION
            if let capability = current.nodeScopePush {
                await StagingPush.shared.renew(client: client, user: user, capability: capability)
            }
            #endif
        } catch CoreScopeAccountError.signedOut {
            guard !Task.isCancelled, self.user?.id == user.id else { return }
            store.deactivate(origin: origin)
            self.user = nil
            selection = nil
            resetDocument = nil
            notifications = nil
            message = String(localized: "Sign in again to access this account.")
        } catch {
            if !Task.isCancelled { notificationStatus = .failed }
        }
    }

    func setWatch(_ key: String, watched: Bool) async {
        await action {
            guard let client, let user, notificationStatus == .ready else { return }
            notifications = try await client.setWatch(key, watched: watched, csrf: user.csrfToken)
        }
    }

    #if NODESCOPE_PUSH_STAGING || NODESCOPE_PUSH_PRODUCTION
    func setPush(_ enable: Bool) async {
        await action {
            guard let client, let user else { return }
            if enable, let pushCapability = notifications?.nodeScopePush {
                try await StagingPush.shared.enable(client: client, user: user, capability: pushCapability)
                message = String(localized: "Watched-node alerts enabled on this device.")
            } else {
                try await StagingPush.shared.disable(origin: client.origin, account: user.id, client: client, csrf: user.csrfToken)
                message = String(localized: "Watched-node alerts disabled on this device.")
            }
        }
    }
    #endif

    func logout(copyTo favorites: FavoritesStore? = nil) async {
        await action {
            guard let client, let user else { return }
            if let favorites { try copy(favorites) }
            #if NODESCOPE_PUSH_STAGING || NODESCOPE_PUSH_PRODUCTION
            try? await StagingPush.shared.disable(origin: client.origin, account: user.id, client: client, csrf: user.csrfToken)
            #endif
            // Clear account data only after the credential store confirms local deletion.
            var revocationFailed = false
            do { try await client.logout() }
            catch CoreScopeAccountError.storage { throw CoreScopeAccountError.storage }
            catch { revocationFailed = true }
            epoch = UUID()
            Self.epochs[origin] = epoch
            store.remove(origin: origin, userID: user.id)
            selection = nil
            resetDocument = nil
            self.user = nil
            notifications = nil
            notificationStatus = .checking
            message = revocationFailed
                ? String(localized: "Signed out on this device. Server revocation was not confirmed; the session may remain active until revoked on CoreScope or expired.")
                : String(localized: "Signed out. Device favorites are unchanged.")
        }
    }

    private func action(_ work: () async throws -> Void) async {
        guard !busy else { return }
        guard epoch == Self.epochs[origin] else {
            user = nil; selection = nil; resetDocument = nil; supported = false
            message = String(localized: "The account session changed in another window. Reopen this page to continue.")
            return
        }
        guard Self.activeOrigins.insert(origin).inserted else {
            message = String(localized: "An account operation is already running. Try again shortly.")
            return
        }
        let activeOrigin = origin
        busy = true
        message = nil
        defer { busy = false; Self.activeOrigins.remove(activeOrigin) }
        do { try await work() }
        catch {
            if case CoreScopeAccountError.signedOut = error {
                store.deactivate(origin: origin)
                user = nil; selection = nil; resetDocument = nil
            }
            if !Task.isCancelled { message = error.localizedDescription }
        }
    }
}

struct CoreScopeAccountScreen: View {
    var watchNode: MeshNode? = nil
    var onWatchAdded: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var attemptedRequestedWatch = false
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(FavoritesStore.self) private var favorites
    @Environment(\.scenePhase) private var scenePhase
    @State private var model = CoreScopeAccountModel()
    @State private var email = ""
    @State private var password = ""
    @State private var operation: Task<Void, Never>?
    @State private var showsSignOut = false
    @State private var showsPushGuide = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let watchNode {
                    VStack(alignment: .leading, spacing: 8) {
                        Label("Add a watch", systemImage: "bell")
                            .font(.monaspace(.headline))
                        Text(watchNode.name ?? watchNode.publicKey)
                            .font(.monaspace(.subheadline))
                        if model.user == nil {
                            Text("Sign in to CoreScope to add this node to your watch list.")
                                .font(.subheadline).foregroundStyle(.secondary)
                        } else if model.notificationStatus == .ready {
                            if model.busy || !attemptedRequestedWatch {
                                ProgressView("Adding watch…")
                            } else if model.notifications?.watches.contains(where: {
                                $0.pubkey == watchNode.publicKey.lowercased()
                            }) != true {
                                Button("Retry adding watch") { run { await finishRequestedWatch() } }
                                    .buttonStyle(.borderedProminent)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .instrumentCard()
                }
                AccountSourceCard(host: settings.host)
                if model.busy || model.message != nil {
                    AccountStatusCard(busy: model.busy, message: model.message)
                }
                if let user = model.user {
                    AccountIdentityCard(user: user)
                    notificationCard(user)
                    if model.resetDocument != nil {
                        AccountResetCard { restore in
                            run { await model.resolveReset(restore: restore) }
                        }
                    }
                    AccountSyncCard(paused: model.resetDocument != nil,
                        sync: { run { await model.sync(upload: true) } },
                        refresh: { run { await model.sync(upload: false) } },
                        importFavorites: { run { await model.importDeviceFavorites(favorites) } },
                        copy: { run { await model.copyToDevice(favorites) } })
                    AccountSelectionsCard(keys: model.favoriteKeys, claimed: model.claimed,
                        paused: model.resetDocument != nil) { key in
                        run { await model.removeFavorite(key) }
                    }
                    Button(role: .destructive) {
                        showsSignOut = true
                    } label: {
                        Text("Sign out")
                    }
                    .buttonStyle(StandaloneActionButtonStyle())
                    .confirmationDialog("Sign out of CoreScope?", isPresented: $showsSignOut, titleVisibility: .visible) {
                        Button("Sign out", role: .destructive) { run { await model.logout() } }
                        Button("Copy selections and sign out") { run { await model.logout(copyTo: favorites) } }
                    } message: {
                        Text("Downloaded account selections and pending account edits will be removed from this device. Existing device favorites are preserved.")
                    }
                } else if model.supported {
                    AccountSignInCard(email: $email, password: $password, website: settings.baseURL) {
                        let secret = password
                        password = ""
                        run { await model.login(email: email, password: secret, favorites: favorites) }
                    }
                } else if !model.busy {
                    Button {
                        run { await model.open(settings: settings, favorites: favorites) }
                    } label: {
                        Label("Try again", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(StandaloneActionButtonStyle())
                }
            }
            .padding(16)
            .disabled(model.busy || scenePhase != .active)
        }
        .adaptiveScrollContentWidth()
        .scrollDismissesKeyboard(.interactively)
        .meshPageBackground()
        .tint(NodeScopeStyle.signal)
        .navigationTitle("CoreScope account")
        .toolbar(.visible, for: .navigationBar)
        .task(id: AccountLoadIdentity(session: settings.sourceSession.id, active: scenePhase == .active)) {
            guard scenePhase == .active else { return }
            await model.open(settings: settings, favorites: favorites)
        }
        .task(id: notificationTaskID) {
            guard model.user != nil, scenePhase == .active else { return }
            await model.loadNotifications()
            if !attemptedRequestedWatch { await finishRequestedWatch() }
        }
        .onChange(of: model.busy) { _, busy in
            if !busy, watchNode != nil, !attemptedRequestedWatch, model.notificationStatus == .ready {
                run { await finishRequestedWatch() }
            }
        }
        .sheet(isPresented: $showsPushGuide) {
            NavigationStack {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text("Share these steps with your CoreScope administrator.")
                        Text(Self.pushSetupSteps)
                        ShareLink(item: "\(String(localized: Self.pushSetupSteps))\n\nhttps://nodescope.app/push-setup/") {
                            Label("Share setup steps", systemImage: "square.and.arrow.up")
                        }
                    }
                    .padding(20)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .meshPageBackground()
                .navigationTitle("NodeScope push setup")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { showsPushGuide = false }
                    }
                }
            }
        }
        .onDisappear { operation?.cancel(); password = "" }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { operation?.cancel(); password = "" }
        }

    }

    private static let pushSetupSteps: LocalizedStringResource = """
    1. Run a CoreScope version with NodeScope push support and enable account notifications.
    2. Open Admin → Notifications → NodeScope Push and tap Enable.
    3. Confirm the card shows Connected. Users can then enable push on their devices in NodeScope.
    """

    private var notificationTaskID: String {
        let account = model.user?.id ?? 0
        #if NODESCOPE_PUSH_STAGING || NODESCOPE_PUSH_PRODUCTION
        let linked = StagingPush.shared.enabled(origin: settings.baseURL, account: account)
        #else
        let linked = false
        #endif
        return "\(settings.sourceSession.id)|\(scenePhase)|\(account)|\(linked)"
    }

    private func notificationCard(_ user: CoreScopeAccountUser) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Watched-node alerts", systemImage: "bell")
                .font(.monaspace(.headline))
                .accessibilityAddTraits(.isHeader)
            switch model.notificationStatus {
            case .checking:
                ProgressView("Checking alert availability…")
            case .failed:
                Text("Couldn’t check alert availability.")
                Button("Retry") {
                    run {
                        await model.loadNotifications()
                        if !attemptedRequestedWatch { await finishRequestedWatch() }
                    }
                }
            case .unavailable:
                Text("This analyzer has CoreScope account notifications turned off.")
            case .ready:
                if let notifications = model.notifications {
                    watchControls(notifications)
                    Divider()
                    Link("Manage email alerts in CoreScope", destination: URL(string: "#/account?section=notifications", relativeTo: settings.baseURL)!.absoluteURL)
                    if !notifications.enabled {
                        Text("Email alerts are off for this account.")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                    #if NODESCOPE_PUSH_STAGING || NODESCOPE_PUSH_PRODUCTION
                    let linked = StagingPush.shared.enabled(origin: settings.baseURL, account: user.id)
                    if linked || notifications.nodeScopePush?.supports("ios") == true {
                        Button(linked ? "Disable push on this device" : "Enable push on this device") {
                            run { await model.setPush(!linked) }
                        }
                        .buttonStyle(.bordered)
                    }
                    if notifications.nodeScopePush == nil {
                        Text("Push alerts aren’t available on this analyzer. Ask your CoreScope administrator to enable NodeScope Push. You can still watch nodes. To receive email alerts, your administrator must have set up email delivery, and email alerts must be on in your CoreScope account.")
                            .font(.subheadline).foregroundStyle(.secondary)
                        Button("View push setup guide") { showsPushGuide = true }
                    } else if notifications.nodeScopePush?.supports("ios") != true {
                        Text("This analyzer’s push relay isn’t supported by this NodeScope build.")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                    #endif
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .instrumentCard()
    }

    private func finishRequestedWatch() async {
        guard let key = watchNode?.publicKey.lowercased(), model.user != nil,
              model.notificationStatus == .ready, !model.busy, !Task.isCancelled else { return }
        attemptedRequestedWatch = true
        if model.notifications?.watches.contains(where: { $0.pubkey == key }) != true {
            await model.setWatch(key, watched: true)
        }
        if model.notifications?.watches.contains(where: { $0.pubkey == key }) == true, !Task.isCancelled {
            dismiss()
            onWatchAdded?()
        }
    }

    @ViewBuilder
    private func watchControls(_ notifications: CoreScopeAccountNotifications) -> some View {
        if notifications.watches.isEmpty && watchNode == nil {
            Text("No nodes watched. Open a node and tap the bell to add it to your watch list.")
                .font(.subheadline).foregroundStyle(.secondary)
        }
        ForEach(notifications.watches) { watch in
            HStack {
                Text(watch.name.isEmpty ? watch.pubkey : watch.name)
                    .font(.monaspace(.subheadline)).lineLimit(2).truncationMode(.middle)
                Spacer()
                Button("Stop watching") { run { await model.setWatch(watch.pubkey, watched: false) } }
                    .accessibilityLabel(Text("Stop watching \(watch.name.isEmpty ? watch.pubkey : watch.name)"))
            }
        }
    }

    private struct AccountLoadIdentity: Equatable {
        let session: UUID
        let active: Bool
    }

    private func run(_ work: @escaping @MainActor () async -> Void) {
        guard !model.busy else { return }
        operation = Task { await work() }
    }
}

private struct AccountSourceCard: View {
    let host: String
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("ACCOUNT CONNECTION", systemImage: "person.crop.circle")
                .font(.caption.weight(.bold)).foregroundStyle(NodeScopeStyle.signal)
                .accessibilityAddTraits(.isHeader)
            FavoriteSourceIdentity(host: host)
            Text("Manage account node selections separately from favorites saved on this device.")
                .font(.subheadline).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16).instrumentCard()
    }
}

private struct AccountIdentityCard: View {
    let user: CoreScopeAccountUser
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "person.crop.circle.fill").font(.monaspace(.largeTitle)).foregroundStyle(NodeScopeStyle.signal)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(user.displayName.isEmpty ? user.email : user.displayName).font(.monaspace(.headline))
                Text(user.email).font(.subheadline).foregroundStyle(.secondary).textSelection(.enabled)
                Label("Signed in", systemImage: "checkmark.circle").font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16).instrumentCard()
    }
}

private struct AccountStatusCard: View {
    let busy: Bool
    let message: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if busy { ProgressView("Connecting…") }
            if let message { Text(message).font(.subheadline) }
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16).instrumentCard()
    }
}

private struct AccountSyncCard: View {
    let paused: Bool
    let sync: () -> Void
    let refresh: () -> Void
    let importFavorites: () -> Void
    let copy: () -> Void
    @State private var showsImport = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("Sync & device favorites", systemImage: "arrow.triangle.2.circlepath").font(.monaspace(.headline))
            .accessibilityAddTraits(.isHeader)
            Text("Sync uploads only account edits. Device favorites stay local unless you import them.")
                .font(.subheadline).foregroundStyle(.secondary)
            Button("Sync now", systemImage: "arrow.triangle.2.circlepath", action: sync)
                .buttonStyle(.borderedProminent).disabled(paused)
            Button("Refresh from account", systemImage: "arrow.clockwise", action: refresh).buttonStyle(.bordered)
            Divider()
            Button("Import device node favorites", systemImage: "arrow.up.circle") { showsImport = true }
                .disabled(paused)
                .confirmationDialog("Import device favorites into this account?", isPresented: $showsImport, titleVisibility: .visible) {
                    Button("Import node favorites", action: importFavorites)
                } message: {
                    Text("This adds device node favorites to this account’s pending selections. Choose Sync now to upload. Observer and channel favorites stay local.")
                }
            Button("Copy account selections to device", systemImage: "arrow.down.circle", action: copy)
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16).instrumentCard()
    }
}

private struct AccountResetCard: View {
    let resolve: (Bool) -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Account settings were reset", systemImage: "exclamationmark.triangle").font(.monaspace(.headline))
            .accessibilityAddTraits(.isHeader)
            Text("Sync is paused. Use the server’s selections or restore your saved account favorites. Device favorites are unaffected.")
                .font(.subheadline).foregroundStyle(.secondary)
            Button("Use server selections") { resolve(false) }.buttonStyle(.borderedProminent)
            Button("Restore saved account favorites") { resolve(true) }.buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16).instrumentCard()
    }
}

private struct AccountSelectionsCard: View {
    let keys: [String]
    let claimed: [String: String]
    let paused: Bool
    let remove: (String) -> Void
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(FavoritesStore.self) private var favorites
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var resolvedNames: [String: String] = [:]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Account nodes", systemImage: "star").font(.monaspace(.headline))
            .accessibilityAddTraits(.isHeader)
            Text("\(keys.count) favorites · \(claimed.count) claimed nodes").font(.subheadline).foregroundStyle(.secondary)
            ForEach(keys, id: \.self) { key in
                let layout = dynamicTypeSize.isAccessibilitySize
                    ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
                    : AnyLayout(HStackLayout(alignment: .top, spacing: 8))
                layout {
                    AccountNodeIdentity(name: name(for: key), publicKey: key)
                    if !dynamicTypeSize.isAccessibilitySize { Spacer(minLength: 8) }
                    Button("Remove", role: .destructive) { remove(key) }.disabled(paused)
                        .accessibilityLabel("Remove account favorite \(name(for: key) ?? key)")
                }
            }
            if !claimed.isEmpty {
                Divider()
                Text("Claimed nodes are managed on CoreScope. Removing an account favorite does not unclaim a node.")
                    .font(.footnote).foregroundStyle(.secondary)
                ForEach(claimed.keys.sorted(), id: \.self) { key in
                    HStack(alignment: .top) {
                        Image(systemName: "checkmark.seal").foregroundStyle(NodeScopeStyle.signal)
                            .accessibilityLabel("Claimed node")
                        AccountNodeIdentity(name: name(for: key), publicKey: key)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16).instrumentCard()
        .task(id: NameLoadIdentity(session: settings.sourceSession.id,
                                  keys: Array(Set(keys).union(claimed.keys)).sorted(),
                                  active: scenePhase == .active)) {
            await loadNames()
        }
    }

    private struct NameLoadIdentity: Equatable {
        let session: UUID
        let keys: [String]
        let active: Bool
    }

    private func name(for key: String) -> String? {
        if let name = nonempty(claimed[key]) { return name }
        if let name = resolvedNames[key] { return name }
        let source = AnalyzerSettings.normalizedHost(settings.host)
        let local = favorites.items.first {
            $0.source == source && $0.kind == .node && $0.entityID.lowercased() == key
        }
        return nonempty(local?.node?.name)
    }

    private func nonempty(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else { return nil }
        return trimmed
    }

    @MainActor private func loadNames() async {
        guard scenePhase == .active, settings.sourceIsReady else { return }
        let session = settings.sourceSession
        let allKeys = Array(Set(keys).union(claimed.keys)).sorted()
        do {
            let repositories = try await CacheStorageService.shared.myNodesCoreScope(
                client: APIClient(baseURL: settings.baseURL), session: session)
            let cached = try await repositories.base.cached(publicKeys: allKeys)
            try Task.checkCancellation()
            try session.check()
            for (key, snapshot) in cached {
                if let name = nonempty(snapshot.node.name) { resolvedNames[key] = name }
            }
            let missing = allKeys.filter { name(for: $0) == nil }
            guard !missing.isEmpty else { return }
            // Share the Activity repository's bounded lookups and cache, without an account write.
            let rows = try await repositories.base.load(publicKeys: missing)
            try Task.checkCancellation()
            try session.check()
            try repositories.lifetime.check()
            favorites.refreshNodes(rows.compactMap { $0.snapshot?.node }, source: AnalyzerSettings.normalizedHost(settings.host))
            for row in rows {
                if let name = nonempty(row.snapshot?.node.name) { resolvedNames[row.publicKey] = name }
            }
        } catch {
            // Missing or unavailable metadata keeps the public key visible as the stable identity.
        }
    }
}

private struct AccountNodeIdentity: View {
    let name: String?
    let publicKey: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let name { Text(name).font(.monaspace(.subheadline).weight(.semibold)) }
            else { Text("Name unavailable").font(.subheadline).foregroundStyle(.secondary) }
            Text("\(publicKey.prefix(12))…\(publicKey.suffix(6))")
                .font(.monaspace(.caption)).foregroundStyle(.secondary)
                .accessibilityLabel("Public key \(publicKey)")
        }
        .accessibilityElement(children: .combine)
    }
}

private struct AccountSignInCard: View {
    @Binding var email: String
    @Binding var password: String
    let website: URL
    let signIn: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Label("Sign in", systemImage: "person.crop.circle.badge.checkmark").font(.monaspace(.headline))
            .accessibilityAddTraits(.isHeader)
            TextField("Email", text: $email).textContentType(.username)
                .keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
                .textFieldStyle(.roundedBorder)
            SecureField("Password", text: $password).textContentType(.password).textFieldStyle(.roundedBorder)
            Button("Sign in", action: signIn).buttonStyle(.borderedProminent).disabled(email.isEmpty || password.isEmpty)
            Text("Use an existing account on this source. Your password is not saved.").font(.footnote).foregroundStyle(.secondary)
            Link("Open CoreScope", destination: website).font(.subheadline)
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16).instrumentCard()
    }
}
