import Foundation

struct StagingPushCapability: Decodable, Sendable {
    let relayOrigin: String
    let analyzerId: String
    let contractVersion: Int
    let platforms: [String]

    #if NODESCOPE_PUSH_STAGING || NODESCOPE_PUSH_PRODUCTION
    func supports(_ platform: String) -> Bool {
        (relayOrigin == StagingPush.origin || relayOrigin == StagingPush.origin + "/")
            && contractVersion == 1 && StagingPush.validID(analyzerId) && platforms.contains(platform)
    }
    #endif
}

#if NODESCOPE_PUSH_STAGING || NODESCOPE_PUSH_PRODUCTION
import Observation
import OSLog
import UIKit
import UserNotifications

private let pushLogger = Logger(subsystem: "com.btdev.nodescope", category: "push")

struct StagingPushLink: Decodable, Sendable {
    let subscriptionID: String
    let status: String
    let expiresAt: String

    enum CodingKeys: String, CodingKey {
        case subscriptionID = "subscription_id", status, expiresAt = "expires_at"
    }
}

struct StagingPushRoute: Equatable, Sendable {
    let analyzer: String
    let subscription: String
    let node: String

    init?(_ values: [AnyHashable: Any]) {
        guard (values["version"] as? String) == "1",
              let analyzer = values["analyzer_id"] as? String, StagingPush.validID(analyzer),
              let subscription = values["subscription_id"] as? String, StagingPush.validID(subscription),
              let node = values["node_pubkey"] as? String,
              node.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { return nil }
        self.analyzer = analyzer
        self.subscription = subscription
        self.node = node
    }
}

private final class RelayRedirectPolicy: NSObject, URLSessionTaskDelegate, Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping @Sendable (URLRequest?) -> Void) { completionHandler(nil) }
}

@MainActor @Observable
final class StagingPush {
    #if NODESCOPE_PUSH_PRODUCTION
    nonisolated static let origin = "https://push.nodescope.app"
    private static let environment = "production"
    private let storageKey = "nodescope-push-production-v1"
    #else
    nonisolated static let origin = "https://push-staging.nodescope.app"
    private static let environment = "development"
    private let storageKey = "nodescope-push-staging-v1"
    #endif
    static let shared = StagingPush()

    nonisolated static func validID(_ value: String) -> Bool {
        value.range(of: "^[A-Za-z0-9_-]{1,128}$", options: .regularExpression) != nil
    }

    private struct Installation: Codable {
        let installationID: String
        let credential: String
        enum CodingKeys: String, CodingKey {
            case installationID = "installation_id", credential = "installation_credential"
        }
    }

    struct Binding: Codable, Equatable {
        let analyzer: String
        let account: Int64
        let subscription: String
        let expiresAt: Date?
    }

    enum Notice: Equatable {
        case switchSource(origin: URL, route: StagingPushRoute)
        case unavailable, unsavedDrafts
    }

    static func matchingOrigin(for route: StagingPushRoute, selected: String, bindings: [String: Binding]) -> String? {
        func matches(_ binding: Binding) -> Bool {
            binding.analyzer == route.analyzer && binding.subscription == route.subscription
        }
        if let binding = bindings[selected], matches(binding) { return selected }
        let candidates = bindings.filter { matches($0.value) }
        return candidates.count == 1 ? candidates.keys.first : nil
    }

    static func isStale(_ binding: Binding, capability: StagingPushCapability?) -> Bool {
        capability?.analyzerId != binding.analyzer
    }

    private struct RelayError: Decodable {
        struct Detail: Decodable { let code: String }
        let error: Detail
    }

    static func isInactiveAnalyzer(status: Int, data: Data, linkProof: Bool) -> Bool {
        let code = (try? JSONDecoder().decode(RelayError.self, from: data))?.error.code
        return (status == 410 && code == "analyzer_inactive")
            || (linkProof && status == 404 && code == "unknown_analyzer")
    }

    private struct Saved: Codable {
        var installation: Installation?
        var bindings: [String: Binding] = [:]
        var revocations: Set<String> = []
    }

    private enum Failure: LocalizedError {
        case permission, registration, relay, inactiveAnalyzer
        var errorDescription: String? {
            switch self {
            case .permission: "Allow notifications in iOS Settings to enable watched-node alerts."
            case .registration: "Couldn’t register this device for Apple push notifications."
            case .relay, .inactiveAnalyzer: "Couldn’t update watched-node alerts. Check the relay and try again."
            }
        }
    }

    private let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false
        config.httpCookieStorage = nil
        config.timeoutIntervalForRequest = 12
        return URLSession(configuration: config, delegate: RelayRedirectPolicy(), delegateQueue: nil)
    }()

    private var continuation: CheckedContinuation<String, Error>?
    private var registrationTimeout: Task<Void, Never>?
    private var refreshing = false
    var notice: Notice?
    private(set) var routeID = UUID()
    private(set) var pendingRoute: StagingPushRoute?
    private(set) var working = false
    private(set) var revision = 0

    private func load() throws -> Saved {
        guard let raw = try AccountSessionKeychain.load(origin: storageKey) else { return Saved() }
        return try JSONDecoder().decode(Saved.self, from: Data(raw.utf8))
    }

    private func save(_ value: Saved) throws {
        try AccountSessionKeychain.save(String(decoding: JSONEncoder().encode(value), as: UTF8.self), origin: storageKey)
        revision += 1
    }

    func enabled(origin: URL, account: Int64) -> Bool {
        _ = revision
        return (try? load().bindings[origin.absoluteString]?.account) == account
    }

    func renew(client: CoreScopeAccountClient, user: CoreScopeAccountUser, capability: StagingPushCapability) async {
        guard let binding = try? load().bindings[client.origin.absoluteString], binding.account == user.id,
              binding.analyzer == capability.analyzerId,
              binding.expiresAt.map({ $0.timeIntervalSinceNow < 7 * 86_400 }) ?? true,
              await UNUserNotificationCenter.current().notificationSettings().authorizationStatus == .authorized else { return }
        try? await enable(client: client, user: user, capability: capability, requestPermission: false)
    }

    func enable(client: CoreScopeAccountClient, user: CoreScopeAccountUser, capability: StagingPushCapability,
                requestPermission: Bool = true) async throws {
        guard !working, !refreshing, capability.supports("ios") else { throw Failure.relay }
        working = true
        defer { working = false }
        let center = UNUserNotificationCenter.current()
        if requestPermission {
            guard try await center.requestAuthorization(options: [.alert, .sound, .badge]) else { throw Failure.permission }
        }
        let token = try await register()
        var state = try load()
        let installation: Installation
        if let saved = state.installation {
            installation = saved
            try await updateToken(token, installation: installation)
        } else {
            let data = try await request("v1/installations", "POST", body: [
                "provider": "apns", "environment": Self.environment, "token": token
            ])
            installation = try JSONDecoder().decode(Installation.self, from: data)
            guard Self.validID(installation.installationID), Self.validID(installation.credential) else { throw Failure.relay }
            state.installation = installation
            try save(state)
        }
        try await flushRevocations(installation)
        let origin = client.origin.absoluteString
        let existing = try load().bindings[origin]
        let proofPath = "v1/installations/\(installation.installationID)/link-proofs"
        func linkProof() async throws -> Data {
            do {
                return try await request(proofPath, "POST", credential: installation.credential,
                                         body: ["analyzer_id": capability.analyzerId])
            } catch Failure.inactiveAnalyzer {
                if let existing, existing.analyzer == capability.analyzerId, existing.account == user.id {
                    try removeBinding(origin: origin, binding: existing)
                    try? await flushRevocations(installation)
                }
                throw Failure.inactiveAnalyzer
            }
        }
        let proofData = try await linkProof()
        struct Proof: Decodable { let proof: String }
        let proof = try JSONDecoder().decode(Proof.self, from: proofData).proof
        let link: StagingPushLink
        do {
            link = try await client.linkPushDevice(installation.installationID, proof: proof, csrf: user.csrfToken)
        } catch CoreScopeAccountError.pushLinkGone {
            // CoreScope forwards 410 without the relay's error code. Check the analyzer directly.
            _ = try await linkProof()
            throw CoreScopeAccountError.pushLinkGone
        }
        guard Self.validID(link.subscriptionID), link.status == "active" else { throw Failure.relay }
        var latest = try load()
        latest.bindings[client.origin.absoluteString] = Binding(analyzer: capability.analyzerId, account: user.id,
                                                                subscription: link.subscriptionID,
                                                                expiresAt: ISO8601DateFormatter().date(from: link.expiresAt))
        try save(latest)
    }

    func disable(origin: URL, account: Int64, client: CoreScopeAccountClient? = nil, csrf: String? = nil) async throws {
        let bindingOrigin = origin.absoluteString
        var state = try load()
        guard let binding = state.bindings[bindingOrigin], binding.account == account else { return }
        state.bindings.removeValue(forKey: bindingOrigin)
        state.revocations.insert(binding.subscription)
        try save(state)
        if let installation = state.installation {
            if let client, let csrf { try? await client.unlinkPushDevice(installation.installationID, csrf: csrf) }
            try await flushRevocations(installation)
        }
    }

    private func removeBinding(origin: String, binding: Binding) throws {
        var state = try load()
        guard state.bindings[origin] == binding else { return }
        state.bindings.removeValue(forKey: origin)
        state.revocations.insert(binding.subscription)
        try save(state)
    }

    private func validateBinding(origin: String, binding: Binding) async throws {
        guard let url = URL(string: origin), let cookie = try AccountSessionKeychain.load(origin: origin) else { return }
        let client = try CoreScopeAccountClient(origin: url, cookie: cookie)
        guard try await client.me().id == binding.account else { return }
        let capability = try await client.pushCapability()
        try Task.checkCancellation()
        guard try AccountSessionKeychain.load(origin: origin) == cookie,
              Self.isStale(binding, capability: capability) else { return }
        try removeBinding(origin: origin, binding: binding)
    }

    func refresh() async {
        guard !working, !refreshing else { return }
        refreshing = true
        defer { refreshing = false }
        guard let initial = try? load(), let installation = initial.installation else { return }
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        if settings.authorizationStatus == .denied {
            for (origin, binding) in initial.bindings {
                if let url = URL(string: origin) { try? await disable(origin: url, account: binding.account) }
            }
        } else {
            for (origin, binding) in initial.bindings {
                if Task.isCancelled { return }
                try? await validateBinding(origin: origin, binding: binding)
            }
            if (try? load().bindings.isEmpty) == false, let token = try? await register() {
                try? await updateToken(token, installation: installation)
            }
        }
        try? await flushRevocations(installation)
    }

    func receive(_ result: Result<String, Error>) {
        registrationTimeout?.cancel()
        registrationTimeout = nil
        let waiting = continuation
        continuation = nil
        waiting?.resume(with: result)
    }

    private func register() async throws -> String {
        try await withCheckedThrowingContinuation { waiting in
            continuation = waiting
            UIApplication.shared.registerForRemoteNotifications()
            registrationTimeout = Task { @MainActor in
                try? await Task.sleep(for: .seconds(12))
                if !Task.isCancelled { receive(.failure(Failure.registration)) }
            }
        }
    }

    func accept(_ route: StagingPushRoute) {
        notice = nil
        pendingRoute = route
        routeID = UUID()
    }

    func switchSource(for route: StagingPushRoute, to origin: URL, settings: AnalyzerSettings) {
        let host = AnalyzerSettings.normalizedHost(origin.absoluteString)
        guard !host.isEmpty, URL(string: "https://\(host)") == origin,
              let bindings = try? load().bindings,
              Self.matchingOrigin(for: route, selected: origin.absoluteString, bindings: bindings) == origin.absoluteString else {
            pushLogger.notice("Notification switch discarded: stored binding changed")
            notice = .unavailable
            return
        }
        guard StudyDraftRegistry.shared.flushAll().isEmpty else {
            notice = .unsavedDrafts
            return
        }
        settings.host = host
        accept(route)
    }

    func owns(_ route: StagingPushRoute) -> Bool {
        (try? load().bindings.values.contains {
            $0.analyzer == route.analyzer && $0.subscription == route.subscription
        }) == true
    }

    // Return true for a transient failure that the active scene can retry.
    func consume(for settings: AnalyzerSettings, navigation: AppNavigationStore,
                 onboarded: Bool, active: Bool) async -> Bool {
        guard let route = pendingRoute else {
            pushLogger.debug("Route check skipped: no pending notification")
            return false
        }
        guard onboarded, active else {
            pushLogger.info("Notification route waiting: onboarding or scene not ready")
            return false
        }
        guard UIApplication.shared.isProtectedDataAvailable else {
            pushLogger.info("Notification route waiting: protected data unavailable")
            return true
        }

        let requestID = routeID
        func discard(_ feedback: Notice? = nil) {
            guard routeID == requestID else { return }
            pendingRoute = nil
            notice = feedback
        }

        let sourceSession = settings.sourceSession
        let origin = settings.baseURL
        let originKey = origin.absoluteString
        var step = "load local binding"
        do {
            let state = try load()
            guard let matched = Self.matchingOrigin(for: route, selected: originKey, bindings: state.bindings),
                  let binding = state.bindings[matched], let matchedURL = URL(string: matched) else {
                pushLogger.notice("Notification route discarded: no matching stored analyzer and subscription")
                discard(.unavailable)
                return false
            }
            guard matched == originKey else {
                discard(.switchSource(origin: matchedURL, route: route))
                return false
            }
            guard settings.sourceIsReady, sourceSession.isCurrent else {
                if settings.backendSelectionError != nil {
                    pushLogger.notice("Notification route discarded: analyzer source could not be resolved")
                    discard(.unavailable)
                }
                return false
            }
            guard settings.beaconBackend == nil else {
                pushLogger.notice("Notification route discarded: selected analyzer is not CoreScope")
                discard(.unavailable)
                return false
            }
            step = "load account session from Keychain"
            guard let cookie = try AccountSessionKeychain.load(origin: originKey) else {
                pushLogger.notice("Notification route discarded: no account session in Keychain")
                discard(.unavailable)
                return false
            }
            step = "create account client"
            let client = try CoreScopeAccountClient(origin: origin, cookie: cookie, lifetime: sourceSession)
            step = "verify analyzer account via /api/auth/me"
            let user = try await client.me()
            guard !Task.isCancelled, routeID == requestID, sourceSession.isCurrent,
                  settings.sourceSession.id == sourceSession.id, origin == settings.baseURL else {
                pushLogger.info("Notification route paused: task or analyzer session changed during account check")
                return false
            }
            guard user.id == binding.account else {
                pushLogger.notice("Notification route discarded: signed-in account does not own the subscription")
                discard(.unavailable)
                return false
            }
            step = "recheck local binding"
            guard try load().bindings[originKey] == binding else {
                pushLogger.notice("Notification route discarded: local binding changed during account check")
                discard(.unavailable)
                return false
            }
            step = "recheck account session in Keychain"
            guard try AccountSessionKeychain.load(origin: originKey) == cookie else {
                pushLogger.info("Notification route waiting: saved account session changed during verification")
                return true
            }
            pushLogger.info("Notification route verified: opening watched node")
            navigation.openWatchedNode(publicKey: route.node, sourceSessionID: sourceSession.id)
            discard()
        } catch is CancellationError {
            pushLogger.info("Notification route paused: account verification cancelled")
        } catch CoreScopeAccountError.signedOut {
            guard !Task.isCancelled, routeID == requestID, sourceSession.isCurrent else {
                pushLogger.info("Notification route paused: session changed before sign-out could be confirmed")
                return false
            }
            pushLogger.notice("Notification route discarded: analyzer rejected the signed-in session")
            discard(.unavailable)
        } catch CoreScopeAccountError.storage {
            pushLogger.error("Notification route waiting: Keychain read failed at \(step, privacy: .public)")
            return true
        } catch {
            pushLogger.error("Notification route waiting: failed to \(step, privacy: .public)")
            return true
        }
        return false
    }

    private func updateToken(_ token: String, installation: Installation) async throws {
        _ = try await request("v1/installations/\(installation.installationID)/token", "PUT",
                              credential: installation.credential, body: ["token": token])
    }

    private func flushRevocations(_ installation: Installation) async throws {
        for id in try load().revocations {
            guard Self.validID(id) else { continue }
            _ = try await request("v1/subscriptions/\(id)", "DELETE", credential: installation.credential, revoking: true)
            var state = try load()
            state.revocations.remove(id)
            try save(state)
        }
    }

    private func request(_ path: String, _ method: String, credential: String? = nil,
                         body: [String: String]? = nil, revoking: Bool = false) async throws -> Data {
        var request = URLRequest(url: URL(string: Self.origin + "/" + path)!)
        request.httpMethod = method
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(body)
        }
        if let credential { request.setValue("Bearer \(credential)", forHTTPHeaderField: "Authorization") }
        let (data, result) = try await session.data(for: request)
        guard let response = result as? HTTPURLResponse, data.count < 16_384 else { throw Failure.relay }
        if !(200..<300).contains(response.statusCode) {
            if revoking && [404, 410].contains(response.statusCode) { return data }
            if Self.isInactiveAnalyzer(status: response.statusCode, data: data, linkProof: path.hasSuffix("/link-proofs")) {
                throw Failure.inactiveAnalyzer
            }
            throw Failure.relay
        }
        return data
    }
}

final class StagingPushDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken token: Data) {
        StagingPush.shared.receive(.success(token.map { String(format: "%02x", $0) }.joined()))
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        StagingPush.shared.receive(.failure(error))
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping @Sendable () -> Void) {
        let isDefaultTap = response.actionIdentifier == UNNotificationDefaultActionIdentifier
        let route = isDefaultTap ? StagingPushRoute(response.notification.request.content.userInfo) : nil

        // UIKit updates its scene snapshot from this completion.
        Task { @MainActor in
            defer { completionHandler() }
            pushLogger.info("Notification response received")
            guard isDefaultTap else {
                pushLogger.debug("Notification response ignored: not the default tap action")
                return
            }
            guard let route else {
                pushLogger.notice("Notification response ignored: missing or invalid routing metadata")
                return
            }
            StagingPush.shared.accept(route)
            pushLogger.info("Notification response queued for routing")
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping @Sendable (UNNotificationPresentationOptions) -> Void) {
        let route = StagingPushRoute(notification.request.content.userInfo)
        Task { @MainActor in
            guard let route, StagingPush.shared.owns(route) else {
                completionHandler([])
                return
            }
            completionHandler([.banner, .list, .sound])
        }
    }
}
#endif
