import SwiftUI

struct AboutScreen: View {
    var body: some View {
        List {
            Section {
                VStack(spacing: 10) {
                    Image(systemName: "antenna.radiowaves.left.and.right.circle.fill")
                        .font(.system(size: 56))
                        .foregroundStyle(Color.accentColor)
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
                    "not affiliated with the CoreScope project or MeshTexas."
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
        }
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
    }

    private static var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
    }

    private static var buildNumber: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "—"
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
