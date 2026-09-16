import SwiftUI

struct DataLoadStatusView: View {
    let lastUpdatedAt: Date?
    let errorMessage: String?
    let hasContent: Bool
    let retry: () -> Void

    var body: some View {
        if let errorMessage {
            DataLoadErrorCard(
                message: errorMessage,
                isShowingSavedData: hasContent,
                retry: retry
            )
        } else if let lastUpdatedAt {
            DataFreshnessLabel(lastUpdatedAt: lastUpdatedAt)
        }
    }
}

private struct DataFreshnessLabel: View {
    let lastUpdatedAt: Date

    var body: some View {
        TimelineView(.periodic(from: .now, by: 60)) { _ in
            Label {
                Text("Updated \(lastUpdatedAt, style: .relative) ago")
            } icon: {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(NodeScopeStyle.healthy)
            }
            .font(.caption2.weight(.medium))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(.thinMaterial, in: Capsule())
            .accessibilityElement(children: .combine)
        }
    }
}

private struct DataLoadErrorCard: View {
    let message: String
    let isShowingSavedData: Bool
    let retry: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: isShowingSavedData ? "clock.arrow.circlepath" : "wifi.exclamationmark")
                .font(.body.weight(.semibold))
                .foregroundStyle(NodeScopeStyle.activity)

            VStack(alignment: .leading, spacing: 2) {
                Text(isShowingSavedData ? "Showing saved data" : "Unable to update")
                    .font(.caption.weight(.bold))
                Text(message)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            Spacer(minLength: 8)

            Button(action: retry) {
                Image(systemName: "arrow.clockwise")
                    .font(.caption.weight(.bold))
                    .frame(width: 32, height: 32)
                    .background(NodeScopeStyle.signal.opacity(0.12), in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Retry update")
        }
        .padding(12)
        .instrumentCard()
    }
}
