import OSLog
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
    private var favoritesStore: FavoritesStore?
    // Serialize account operations across windows, including logout and local edits.
    private static var activeOrigins: Set<String> = []
    private static var epochs: [String: UUID] = [:]
    private var epoch: UUID?

    init(store: CoreScopeAccountSelectionStore = .shared,
         client: CoreScopeAccountClient? = nil, origin: String = "", source: String = "",
         user: CoreScopeAccountUser? = nil, favorites: FavoritesStore? = nil) {
        self.store = store
        self.favoritesStore = favorites
        self.client = client
        self.origin = origin
        self.source = source.isEmpty ? AnalyzerSettings.normalizedHost(origin) : source
        self.user = user
        self.epoch = Self.epochs[origin]
    }

    var currentSelection: CoreScopeAccountSelectionState? {
        guard user != nil else { return nil }
        return store.visibleState(source: source)
    }

    var deviceFavoritesOffer: Set<String> {
        guard let user, let state = currentSelection, let favoritesStore, resetDocument == nil else { return [] }
        return store.offeredDeviceFavorites(favoritesStore.nodeKeys(source: source), state: state,
            origin: origin, userID: user.id)
    }

    func open(settings: AnalyzerSettings, favorites: FavoritesStore) async {
        guard !busy else { return }
        favoritesStore = favorites
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
                keepFavoritesOnDevice()
                store.deactivate(origin: origin)
                user = nil
                selection = nil
                resetDocument = nil
            }
        }
    }

    func login(email: String, password: String, favorites: FavoritesStore) async {
        favoritesStore = favorites
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
            guard authenticated.id == previousUser.id else { throw CoreScopeAccountError.signedOut }
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
        if favoritesStore != nil && deviceFavoritesOffer.isEmpty {
            store.finishDeviceFavoritesOffer(origin: origin, userID: user.id)
        }
        if upload && next.hasPendingEdits {
            let (document, _) = try CoreScopeFavoriteMerge.document(base: next.baseline, local: next.favorites, remote: remote, removingMyNodes: next.removedMyNodes ?? [])
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
            message = upload ? String(localized: "Account favorites synced.") : String(localized: "Account favorites refreshed.")
        }
    }

    private func persist(_ state: CoreScopeAccountSelectionState) throws {
        guard let user else { throw CoreScopeAccountError.signedOut }
        try store.save(state, origin: origin, userID: user.id)
        selection = state
    }

    func answerDeviceFavoritesOffer(add: Bool) async {
        await action {
            guard let user, resetDocument == nil, var state = try store.load(origin: origin, userID: user.id) else { return }
            let keys = deviceFavoritesOffer
            if add && !keys.isEmpty {
                state.favorites.formUnion(keys)
                try persist(state)
            }
            store.finishDeviceFavoritesOffer(origin: origin, userID: user.id)
            if add && !keys.isEmpty { try await reconcile(upload: true) }
        }
    }

    func addFavorite(_ key: String) async {
        await edit {
            guard CoreScopeFavoriteMerge.validKey(key) else { throw CoreScopeAccountError.response }
            $0.favorites.insert(key.lowercased())
        }
    }

    func removeFavorite(_ key: String) async {
        await edit { $0.removeFavorite(key) }
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

    private func keepFavoritesOnDevice() {
        guard let state = store.visibleState(source: source)
                ?? user.flatMap({ try? store.load(origin: origin, userID: $0.id) }) else { return }
        favoritesStore?.keepAccountFavoritesOnDevice(state, source: source)
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
            if let capability = current.pushRelay {
                await StagingPush.shared.renew(client: client, user: user, capability: capability)
            }
            #endif
        } catch CoreScopeAccountError.signedOut {
            guard !Task.isCancelled, self.user?.id == user.id else { return }
            keepFavoritesOnDevice()
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
            store.widgetWatchesChanged()
        }
    }

    #if NODESCOPE_PUSH_STAGING || NODESCOPE_PUSH_PRODUCTION
    func setPush(_ enable: Bool) async {
        await action {
            guard let client, let user else { return }
            if enable, let pushCapability = notifications?.pushRelay {
                try await StagingPush.shared.enable(client: client, user: user, capability: pushCapability)
                message = String(localized: "Watched-node alerts enabled on this device.")
            } else {
                try await StagingPush.shared.disable(origin: client.origin, account: user.id, client: client, csrf: user.csrfToken)
                message = String(localized: "Watched-node alerts disabled on this device.")
            }
        }
    }
    #endif

    func logout() async {
        await action {
            guard let client, let user else { return }
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
            keepFavoritesOnDevice()
            store.remove(origin: origin, userID: user.id)
            selection = nil
            resetDocument = nil
            self.user = nil
            notifications = nil
            notificationStatus = .checking
            message = revocationFailed
                ? String(localized: "Signed out on this device. Server revocation was not confirmed; the session may remain active until revoked on CoreScope or expired.")
                : String(localized: "Signed out. Favorites were kept on this device.")
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
                keepFavoritesOnDevice()
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
                }
                let source = AnalyzerSettings.normalizedHost(settings.host)
                let favoriteKeys = model.user != nil
                    ? model.currentSelection?.favoriteKeys ?? []
                    : favorites.nodeKeys(source: source)
                AccountNodesCard(keys: favoriteKeys.sorted(), myNodeNames: model.currentSelection?.myNodeNames ?? [:],
                    userID: model.user?.id, paused: model.resetDocument != nil,
                    add: { key in run { await model.addFavorite(key) } }) { key in
                    if model.user != nil {
                        run { await model.removeFavorite(key) }
                    } else if let item = favorites.deviceItems.first(where: {
                        $0.source == source && $0.kind == .node && $0.entityID.lowercased() == key
                    }) {
                        favorites.remove(item)
                    }
                }
                .id(model.user?.id)
                if let user = model.user {
                    let offer = model.deviceFavoritesOffer
                    if !offer.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Add \(offer.count) favorites from this device to your account?")
                            HStack {
                                Button("Add") { run { await model.answerDeviceFavoritesOffer(add: true) } }
                                    .buttonStyle(.borderedProminent)
                                Button("Not now") { run { await model.answerDeviceFavoritesOffer(add: false) } }
                                    .buttonStyle(.bordered)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading).padding(16).instrumentCard()
                    }
                    notificationCard(user)
                    if model.resetDocument != nil {
                        AccountResetCard { restore in
                            run { await model.resolveReset(restore: restore) }
                        }
                    }
                    AccountSyncCard(paused: model.resetDocument != nil,
                        sync: { run { await model.sync(upload: true) } },
                        refresh: { run { await model.sync(upload: false) } })
                    Button(role: .destructive) {
                        showsSignOut = true
                    } label: {
                        Text("Sign out")
                    }
                    .buttonStyle(StandaloneActionButtonStyle())
                    .confirmationDialog("Sign out of CoreScope?", isPresented: $showsSignOut, titleVisibility: .visible) {
                        Button("Sign out", role: .destructive) { run { await model.logout() } }
                    } message: {
                        Text("Your current favorites will stay on this device.")
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
                    if linked || notifications.pushRelay?.supports("ios") == true {
                        Button(linked ? "Disable push on this device" : "Enable push on this device") {
                            run { await model.setPush(!linked) }
                        }
                        .buttonStyle(.bordered)
                    }
                    if notifications.pushRelay == nil {
                        Text("Push alerts aren’t available on this analyzer. Ask your CoreScope administrator to enable NodeScope Push. You can still watch nodes. To receive email alerts, your administrator must have set up email delivery, and email alerts must be on in your CoreScope account.")
                            .font(.subheadline).foregroundStyle(.secondary)
                        Button("View push setup guide") { showsPushGuide = true }
                    } else if notifications.pushRelay?.supports("ios") != true {
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

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("Sync", systemImage: "arrow.triangle.2.circlepath").font(.monaspace(.headline))
            .accessibilityAddTraits(.isHeader)
            Button("Sync now", systemImage: "arrow.triangle.2.circlepath", action: sync)
                .buttonStyle(.borderedProminent).disabled(paused)
            Button("Refresh from account", systemImage: "arrow.clockwise", action: refresh).buttonStyle(.bordered)

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

private struct AccountNodesCard: View {
    let keys: [String]
    let myNodeNames: [String: String]
    let userID: Int64?
    let paused: Bool
    let add: (String) -> Void
    let remove: (String) -> Void
    @Environment(AnalyzerSettings.self) private var settings
    @Environment(FavoritesStore.self) private var favorites
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var resolvedNames: [String: String] = [:]
    @State private var repeaterClient: CoreScopeAccountClient?
    @State private var repeaters: [AccountRepeater]?
    @State private var selectedRepeater: AccountRepeater?
    @State private var favoriteToRemove: FavoriteRemoval?

    private struct FavoriteRemoval {
        let key: String
        let name: String
        let isClaimedRepeater: Bool
    }
    @State private var checkFailed = false
    @State private var listError: String?
    @State private var retry = 0
    private static let logger = Logger(subsystem: "com.btdev.nodescope", category: "account")

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("My Nodes", systemImage: "star").font(.monaspace(.headline))
            .accessibilityAddTraits(.isHeader)
            let favoritesCount = String(localized: "\(keys.count) favorites")
            let summary = repeaters.map {
                let claimedCount = String(localized: "\($0.count) claimed repeaters")
                return String(localized: "\(claimedCount) · \(favoritesCount)")
            } ?? favoritesCount
            Text(summary).font(.monaspace(.subheadline)).foregroundStyle(.secondary)
            repeaterSection
            let visibleRepeaters = listError == nil ? repeaters : nil
            let claimedKeys = Set(visibleRepeaters?.map { $0.pubkey.lowercased() } ?? [])
            let otherFavorites = keys.filter { !claimedKeys.contains($0) }
            if keys.isEmpty || !otherFavorites.isEmpty {
                if visibleRepeaters?.isEmpty == false { Divider() }
                if userID == nil {
                    Text("Favorites saved on this device").font(.monaspace(.subheadline).weight(.semibold))
                        .accessibilityAddTraits(.isHeader)
                } else {
                    Text("Favorites").font(.monaspace(.subheadline).weight(.semibold))
                        .accessibilityAddTraits(.isHeader)
                }
                if keys.isEmpty { Text("No favorites yet.").font(.footnote).foregroundStyle(.secondary) }
                ForEach(otherFavorites, id: \.self) { key in
                    let layout = dynamicTypeSize.isAccessibilitySize
                        ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
                        : AnyLayout(HStackLayout(alignment: .top, spacing: 8))
                    layout {
                        AccountNodeIdentity(name: name(for: key), publicKey: key)
                        if !dynamicTypeSize.isAccessibilitySize { Spacer(minLength: 8) }
                        Button("Remove", role: .destructive) {
                            favoriteToRemove = FavoriteRemoval(key: key, name: name(for: key) ?? key, isClaimedRepeater: false)
                        }
                        .disabled(paused)
                        .accessibilityLabel("Remove favorite \(name(for: key) ?? key)")
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16).instrumentCard()
        .task(id: NameLoadIdentity(session: settings.sourceSession.id,
                                  keys: keys,
                                  active: scenePhase == .active)) {
            await loadNames()
        }
        .task(id: "\(settings.sourceSession.id)|\(settings.sourceIsReady)|\(userID ?? 0)|\(retry)") {
            await checkRepeaters()
        }
        .alert("Remove from Favorites?", isPresented: Binding(
            get: { favoriteToRemove != nil },
            set: { if !$0 { favoriteToRemove = nil } }
        ), presenting: favoriteToRemove) { favorite in
            Button("Remove Favorite", role: .destructive) { remove(favorite.key) }
            Button("Cancel", role: .cancel) {}
        } message: { favorite in
            if favorite.isClaimedRepeater {
                Text(verbatim: favorite.name + "\n\n" + String(localized: "The repeater will stay claimed."))
            } else {
                Text(verbatim: favorite.name)
            }
        }
        .sheet(item: $selectedRepeater, onDismiss: {
            if let repeaterClient { Task { await loadRepeaters(repeaterClient) } }
        }) { repeater in
            if let repeaterClient {
                AccountRepeaterManagementSheet(client: repeaterClient, publicKey: repeater.pubkey, name: repeater.title)
            }
        }
    }

    @ViewBuilder private var repeaterSection: some View {
        if checkFailed {
            HStack {
                Text("Couldn't check repeater management").font(.footnote).foregroundStyle(.secondary)
                Spacer(minLength: 8)
                Button("Retry") { retry += 1 }.buttonStyle(.bordered)
            }
            Divider()
        } else if repeaterClient != nil {
            Text("Claimed Repeaters").font(.monaspace(.subheadline).weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            if let listError {
                Text(listError)
                Button("Try Again") {
                    if let repeaterClient { Task { await loadRepeaters(repeaterClient) } }
                }
            } else if let repeaters {
                if repeaters.isEmpty { Text("You haven’t claimed any repeaters.") }
                ForEach(repeaters) { repeater in
                    let key = repeater.pubkey.lowercased()
                    let rowLayout = dynamicTypeSize.isAccessibilitySize
                        ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
                        : AnyLayout(HStackLayout(alignment: .top, spacing: 8))
                    let isFavorite = keys.contains(key)
                    rowLayout {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(repeater.title).font(.monaspace(.headline))
                            if !repeater.known || repeater.inactive == true {
                                Text("No longer in the node list")
                            }
                            if let lastHeard = repeater.lastHeard {
                                Text("Last heard \(lastHeard, style: .relative) ago")
                            } else {
                                Text("Never heard")
                            }
                            if let status = repeater.status {
                                Text("Reported status: \(status.capitalized)")
                            }
                            if !repeater.comaintainers.isEmpty {
                                Text("Also maintained by \(repeater.comaintainers.formatted(.list(type: .and)))")
                            }
                        }
                        .font(.subheadline)
                        .foregroundStyle(.primary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                        .onTapGesture { selectedRepeater = repeater }
                        HStack(alignment: .center, spacing: 4) {
                            Button {
                                selectedRepeater = repeater
                            } label: {
                                Label("Edit", systemImage: "pencil")
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(.tint)
                                    .frame(minHeight: 44)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            Button {
                                if isFavorite {
                                    favoriteToRemove = FavoriteRemoval(key: key, name: repeater.title, isClaimedRepeater: true)
                                } else {
                                    add(key)
                                }
                            } label: {
                                Image(systemName: isFavorite ? "star.fill" : "star")
                                    .foregroundStyle(paused ? Color.secondary : NodeScopeStyle.signal)
                                    .frame(minWidth: 44, minHeight: 44)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .disabled(paused)
                            .accessibilityLabel(isFavorite
                                ? Text("Remove favorite \(repeater.title)") : Text("Add favorite node"))
                        }
                    }
                }
            } else {
                ProgressView()
            }
        }
    }

    private func checkRepeaters() async {
        repeaterClient = nil
        repeaters = nil
        listError = nil
        checkFailed = false
        guard userID != nil, settings.sourceIsReady else { return }

        var step = "restore account session"
        do {
            guard let restored = try CoreScopeAccountClient.restoredRepeaterClient(settings: settings) else { return }
            step = "check repeater capability"
            guard try await restored.supportsRepeaterManagement(), !Task.isCancelled else { return }
            repeaterClient = restored
            await loadRepeaters(restored)
        } catch {
            guard !Task.isCancelled, !(error is CancellationError) else { return }
            let failure = error as NSError
            Self.logger.error("My Repeaters: \(step, privacy: .public) failed (\(failure.domain, privacy: .public) \(failure.code)): \(error, privacy: .private)")
            checkFailed = true
        }
    }

    private func loadRepeaters(_ client: CoreScopeAccountClient) async {
        listError = nil
        do {
            let rows = try await client.repeaters()
            try Task.checkCancellation()
            repeaters = rows
        } catch {
            guard !Task.isCancelled, !(error is CancellationError) else { return }
            listError = error.localizedDescription
        }
    }

    private struct NameLoadIdentity: Equatable {
        let session: UUID
        let keys: [String]
        let active: Bool
    }

    private func name(for key: String) -> String? {
        if let name = nonempty(myNodeNames[key]) { return name }
        if let name = resolvedNames[key] { return name }
        let source = AnalyzerSettings.normalizedHost(settings.host)
        let local = favorites.items.first {
            $0.source == source && $0.kind == .node && $0.entityID.lowercased() == key
        }
        return nonempty(userID == nil ? local?.title : local?.node?.name)
    }

    private func nonempty(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else { return nil }
        return trimmed
    }

    @MainActor private func loadNames() async {
        guard userID != nil, !keys.isEmpty, scenePhase == .active, settings.sourceIsReady else { return }
        let session = settings.sourceSession
        let allKeys = keys
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

struct AccountRepeaterManagementSheet: View {
    let client: CoreScopeAccountClient
    let publicKey: String
    let name: String

    @Environment(\.dismiss) private var dismiss
    @State private var details: AccountRepeaterDetails?
    @State private var height: Double?
    @State private var gain = 0.0
    @State private var status = ""
    @State private var power = ""
    @State private var notes = ""
    @State private var busy = false
    @State private var conflict = false
    @State private var error: String?
    @State private var showClaimConfirmation = false

    private var canSave: Bool {
        (height.map { (0...300).contains($0) } ?? true) &&
            (-10...30).contains(gain) && notes.unicodeScalars.count <= 500
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    repeaterIdentityCard
                    if busy || error != nil {
                        VStack(alignment: .leading, spacing: 8) {
                            if busy { ProgressView() }
                            if let error { Text(error) }
                            if conflict || details == nil {
                                Button(conflict ? "Reload Latest Details" : "Try Again") {
                                    Task { await perform { try await reload() } }
                                }
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .instrumentCard()
                    }
                    if let details {
                        VStack(alignment: .leading, spacing: 14) {
                            Text("Reported Details").font(.monaspace(.headline))
                                .accessibilityAddTraits(.isHeader)
                            if details.canEdit {
                                labeledField("Antenna height above ground (m)") {
                                    TextField("", value: $height, format: .number, prompt: Text("Optional"))
                                        .font(.monaspace(.body))
                                        .keyboardType(.numbersAndPunctuation)
                                }
                                labeledField("Antenna gain (dBi)") {
                                    TextField("", value: $gain, format: .number)
                                        .font(.monaspace(.body))
                                        .keyboardType(.numbersAndPunctuation)
                                }
                                LabeledContent("Reported status") {
                                    Picker("Reported status", selection: $status) {
                                        Text("Unspecified").tag("")
                                        Text("Active").tag("active")
                                        Text("Maintenance").tag("maintenance")
                                        Text("Decommissioned").tag("decommissioned")
                                    }
                                    .pickerStyle(.menu)
                                    .labelsHidden()
                                }
                                LabeledContent("Power source") {
                                    Picker("Power source", selection: $power) {
                                        Text("Unspecified").tag("")
                                        Text("Mains").tag("mains")
                                        Text("Battery").tag("battery")
                                        Text("Solar").tag("solar")
                                    }
                                    .pickerStyle(.menu)
                                    .labelsHidden()
                                }
                                labeledField("Notes") {
                                    TextField("", text: $notes, prompt: Text("Optional"), axis: .vertical)
                                }
                                Button {
                                    Task {
                                        await perform {
                                            let draft = AccountRepeaterEdit(baseRevision: details.revision, heightM: height,
                                                gainDbi: gain, status: status, power: power, notes: notes)
                                            update(try await client.saveRepeater(publicKey, edit: draft))
                                        }
                                    }
                                } label: {
                                    Text("Save Details").frame(maxWidth: .infinity)
                                }
                                .buttonStyle(.borderedProminent)
                                .controlSize(.large)
                                .disabled(conflict || !canSave)
                                if !canSave {
                                    Text("Height must be 0–300 m, gain must be −10–30 dBi, and notes can contain up to 500 characters.")
                                        .font(.caption)
                                }
                            } else {
                                Text("This account doesn’t have permission to edit the repeater’s details.")
                            }
                            Text("These details describe this repeater to other mesh users. Changes are saved on the selected CoreScope analyzer.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .instrumentCard()
                        if details.claimedByMe || details.claimers.isEmpty {
                            Button(details.claimedByMe ? "Unclaim Repeater" : "Claim Repeater",
                                role: details.claimedByMe ? .destructive : nil
                            ) { showClaimConfirmation = true }
                            .confirmationDialog(details.claimedByMe ? "Remove your claim?" : "Claim this repeater?",
                                isPresented: $showClaimConfirmation, titleVisibility: .visible
                            ) {
                                Button(details.claimedByMe ? "Unclaim Repeater" : "Claim Repeater",
                                    role: details.claimedByMe ? .destructive : nil
                                ) {
                                    Task {
                                        await perform {
                                            try await client.setRepeaterClaim(publicKey, claimed: !details.claimedByMe)
                                            try await reload()
                                        }
                                    }
                                }
                            } message: {
                                if !details.claimedByMe {
                                    Text("Only claim repeaters you run and maintain. Site admins can remove an incorrect claim.")
                                }
                            }
                        }
                    }
                }
                .padding(16)
                .adaptiveContentWidth()
            }
            .scrollDismissesKeyboard(.interactively)
            .disabled(busy)
            .meshPageBackground()
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.disabled(busy) }
            }
            .task { await perform { try await reload() } }
        }
        .interactiveDismissDisabled(busy)
    }

    private var repeaterIdentityCard: some View {
        let color = NodeRoleStyle.color(for: "repeater")
        return VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                Image(systemName: NodeRoleStyle.symbolName(for: "repeater"))
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(width: 50, height: 50)
                    .background(color, in: Circle())
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(name)
                        .font(.monaspace(.title2).bold())
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityAddTraits(.isHeader)
                    Text(NodeRoleStyle.label(for: "repeater"))
                        .font(.caption.weight(.bold))
                        .foregroundStyle(color)
                }
            }
            Divider().opacity(0.35)
            VStack(alignment: .leading, spacing: 5) {
                Text("Public Key").font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
                Text(publicKey.uppercased())
                    .font(.monaspace(.caption))
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .instrumentCard()
    }

    private func labeledField<Field: View>(_ title: LocalizedStringKey, @ViewBuilder field: () -> Field) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .accessibilityHidden(true)
            field()
                .accessibilityLabel(Text(title))
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(NodeScopeStyle.signal.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
        }
    }

    private func reload() async throws {
        update(try await client.repeaterDetails(publicKey))
    }

    private func update(_ value: AccountRepeaterDetails) {
        details = value
        height = value.heightM
        gain = value.gainDbi
        status = value.status ?? ""
        power = value.power ?? ""
        notes = value.notes
        conflict = false
    }

    private func perform(_ work: () async throws -> Void) async {
        guard !busy else { return }
        busy = true
        error = nil
        defer { busy = false }
        do { try await work() }
        catch is CancellationError {}
        catch {
            if error is AccountRepeaterError { conflict = true }
            self.error = error.localizedDescription
        }
    }
}
