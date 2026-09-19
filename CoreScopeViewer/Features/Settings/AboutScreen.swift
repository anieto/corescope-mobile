import SwiftUI

struct AboutScreen: View {
    var body: some View {
        List {
            Section {
                VStack(spacing: 10) {
                    if let appIcon = Self.appIcon {
                        Image(uiImage: appIcon)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 72, height: 72)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .overlay {
                                RoundedRectangle(cornerRadius: 16, style: .continuous)
                                    .stroke(.white.opacity(0.15), lineWidth: 1)
                            }
                            .shadow(color: .black.opacity(0.15), radius: 6, y: 3)
                    } else {
                        Image(systemName: "antenna.radiowaves.left.and.right.circle.fill")
                            .font(.system(size: 56))
                            .foregroundStyle(Color.accentColor)
                    }
                    Text("NodeScope")
                        .font(.title3.weight(.semibold))
                    Text("Version \(Self.appVersion) (\(Self.buildNumber))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .listRowBackground(Color.clear)
            }

            Section("What This Is") {
                Text(
                    "NodeScope is an unofficial, read-only companion app for " +
                    "CoreScope, the mesh network analyzer used by MeshTexas and other " +
                    "MeshCore communities. It talks directly to a CoreScope server's " +
                    "public API to show the same live nodes, packet traffic, channel " +
                    "messages, and observer data you'd see on the CoreScope web " +
                    "dashboard — just in a native app."
                )
                Text(
                    "This app has no backend of its own. Everything shown here comes " +
                    "from whatever analyzer host is configured in Settings, and it's " +
                    "not affiliated with the CoreScope or MeshCore projects."
                )
                .foregroundStyle(.secondary)
            }

            Section("How to Use") {
                HowToRow(
                    icon: "map",
                    title: "Map",
                    detail: "Every known node, color-coded by role. Live packet traffic animates as it hops between nodes. Tap a node for its details."
                )
                HowToRow(
                    icon: "number",
                    title: "Channels",
                    detail: "Group channel traffic as a chat feed. Tap a message to replay how it reached you on the map."
                )
                HowToRow(
                    icon: "antenna.radiowaves.left.and.right",
                    title: "Observers",
                    detail: "The physical devices feeding this analyzer — hardware, firmware, and recent signal stats."
                )
                HowToRow(
                    icon: "line.3.horizontal.decrease.circle",
                    title: "Region Filter",
                    detail: "Available on Map, Channels, and Observers. Narrows everything to one region and zooms the map there automatically."
                )
            }

            Section("Project Links") {
                if let coreScopeURL = URL(string: "https://github.com/Kpa-clawbot/CoreScope") {
                    Link(destination: coreScopeURL) {
                        ProjectLinkRow(icon: "shippingbox.fill", title: "CoreScope Project")
                    }
                }
                if let nodeScopeURL = URL(string: "https://github.com/anieto/corescope-mobile") {
                    Link(destination: nodeScopeURL) {
                        ProjectLinkRow(icon: "chevron.left.forwardslash.chevron.right", title: "NodeScope on GitHub")
                    }
                }
            }

            Section("License") {
                VStack(alignment: .leading, spacing: 6) {
                    Text("NodeScope · Copyright © 2026 BetweenThieves Development")
                        .font(.subheadline.weight(.semibold))
                    Text(
                        "Free and open-source software licensed under GNU GPL version 3 with an application-store distribution exception. You may share and modify it under those terms. NodeScope comes with absolutely no warranty."
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
                .padding(.vertical, 2)

                if let licenseURL = URL(string: "https://www.gnu.org/licenses/gpl-3.0.html") {
                    Link(destination: licenseURL) {
                        ProjectLinkRow(icon: "doc.text.fill", title: "View GPLv3 License")
                    }
                }
            }
        }
        .adaptiveContentWidth()
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .floatingDockScrollClearance()
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
    }

    private static var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
    }

    private static var buildNumber: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "—"
    }

    /// `Image("AppIcon")` can't resolve an asset catalog app-icon set
    /// directly, so this reads the actual rendered icon filename Info.plist
    /// records for the app (the same one iOS shows on the home screen) and
    /// loads it by name instead.
    private static var appIcon: UIImage? {
        guard let icons = Bundle.main.infoDictionary?["CFBundleIcons"] as? [String: Any],
              let primary = icons["CFBundlePrimaryIcon"] as? [String: Any],
              let files = primary["CFBundleIconFiles"] as? [String],
              let lastFile = files.last else {
            return nil
        }
        return UIImage(named: lastFile)
    }
}

private struct ProjectLinkRow: View {
    let icon: String
    let title: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.body)
                .foregroundStyle(.secondary)
                .frame(width: 24)
            Text(title)
                .font(.subheadline)
            Spacer()
            Image(systemName: "arrow.up.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
        }
        .foregroundStyle(.secondary)
    }
}

private struct HowToRow: View {
    let icon: String
    let title: String
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.body)
                .foregroundStyle(Color.accentColor)
                .frame(width: 24)
                .padding(.top, 2)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }
}
