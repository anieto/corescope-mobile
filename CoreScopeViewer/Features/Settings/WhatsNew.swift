import SwiftUI

/// A release's highlights, shown once after an update so people with automatic updates hear about
/// new features. The text is shared with Android (assets/whats-new.json); an Android test keeps the
/// two copies identical. Items can be limited to one platform with `platforms`.
struct WhatsNewRelease: Decodable, Identifiable {
    let version: String
    let items: [WhatsNewItem]
    var id: String { version }
}

struct WhatsNewItem: Decodable, Identifiable {
    let id: String
    let title: String
    let detail: String
    let icon: String
    let destination: AppFeature?
    let platforms: [String]?

    var symbol: String {
        switch icon {
        case "coverage": "antenna.radiowaves.left.and.right"
        case "savedPlans": "mappin.and.ellipse"
        case "linkProfile": "point.topleft.down.to.point.bottomright.curvepath"
        case "keyboard": "keyboard"
        case "nodeCheck": "checkmark.seal"
        default: "sparkles"
        }
    }
}

enum WhatsNew {
    static let lastSeenDefaultsKey = "whatsNewLastSeenVersion"

    static let releases: [WhatsNewRelease] = {
        struct Document: Decodable { let releases: [WhatsNewRelease] }
        guard let url = Bundle.main.url(forResource: "whats-new", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let document = try? JSONDecoder().decode(Document.self, from: data) else {
            assertionFailure("whats-new.json is missing or invalid")
            return []
        }
        return document.releases.map { release in
            WhatsNewRelease(version: release.version,
                items: release.items.filter { $0.platforms?.contains("ios") ?? true })
        }.filter { !$0.items.isEmpty }
    }()

    static var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
    }

    /// The newest highlights this build has, for Settings.
    static var latest: WhatsNewRelease? {
        releases.filter { compare($0.version, appVersion) <= 0 }.max { compare($0.version, $1.version) < 0 }
    }

    /// Highlights to show on launch: releases newer than the last one seen, up to this build.
    /// Someone updating from before this feature existed has no record, so sees only this build's.
    static func pending(lastSeen: String?) -> [WhatsNewRelease] {
        let shipped = releases.filter { compare($0.version, appVersion) <= 0 }
            .sorted { compare($0.version, $1.version) > 0 }
        guard let lastSeen, !lastSeen.isEmpty else { return Array(shipped.prefix(1)) }
        return shipped.filter { compare($0.version, lastSeen) > 0 }
    }

    /// Dotted numeric versions ("0.10.6"); missing parts count as zero.
    static func compare(_ left: String, _ right: String) -> Int {
        let a = left.split(separator: ".").map { Int($0) ?? 0 }
        let b = right.split(separator: ".").map { Int($0) ?? 0 }
        for index in 0..<max(a.count, b.count) {
            let x = index < a.count ? a[index] : 0, y = index < b.count ? b[index] : 0
            if x != y { return x < y ? -1 : 1 }
        }
        return 0
    }

    /// Finishing onboarding counts as having seen this build's highlights: new installs aren't shown them.
    static func markCurrentSeen() {
        UserDefaults.standard.set(appVersion, forKey: lastSeenDefaultsKey)
    }
}

/// The What's New sheet. Tapping an item closes it and opens that feature.
struct WhatsNewSheet: View {
    let releases: [WhatsNewRelease]
    @Environment(\.dismiss) private var dismiss
    @Environment(AppNavigationStore.self) private var appNavigationStore

    var body: some View {
        NavigationStack {
            List {
                ForEach(releases) { release in
                    Section {
                        ForEach(release.items) { item in
                            if let destination = item.destination {
                                Button {
                                    dismiss()
                                    appNavigationStore.open(destination)
                                } label: {
                                    WhatsNewRow(item: item, opens: true)
                                }
                                .buttonStyle(.plain)
                            } else {
                                WhatsNewRow(item: item, opens: false)
                            }
                        }
                    } header: {
                        if releases.count > 1 { Text("Version \(release.version)") }
                    }
                }
            }
            .meshPageBackground()
            .navigationTitle("What's New")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

private struct WhatsNewRow: View {
    let item: WhatsNewItem
    let opens: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: item.symbol)
                .font(.title3)
                .foregroundStyle(.tint)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 4) {
                Text(item.title).font(.monaspace(.headline))
                Text(item.detail).font(.subheadline).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            if opens {
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .padding(.top, 4)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint(opens ? "Opens \(item.title)" : "")
    }
}
