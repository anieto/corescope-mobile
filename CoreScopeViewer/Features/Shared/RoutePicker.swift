import SwiftUI

/// Chooses between a packet's routes by who heard them: each choice leads with the observer that
/// heard that path (+N when several did), then hops, region and signal. Used by packet details,
/// live packet details and the map's replay controls, so a route is named the same everywhere.
struct RoutePicker: View {
    let options: [RouteOption]
    @Binding var selection: Int
    var compact = false

    private var index: Int {
        options.indices.contains(selection) ? selection : 0
    }

    var body: some View {
        if options.isEmpty {
            EmptyView()
        } else {
            Menu {
                ForEach(options.indices, id: \.self) { index in
                    let option = options[index]
                    // A toggle shows the menu's checkmark; its two texts give the item a title
                    // and a subtitle line.
                    Toggle(isOn: Binding(
                        get: { index == self.index },
                        set: { if $0 { selection = index } }
                    )) {
                        Text([option.name(index: index), option.othersBadge].compactMap { $0 }.joined(separator: "  "))
                        Text(option.summary)
                    }
                    .accessibilityLabel(option.spokenLabel(index: index))
                }
            } label: {
                RouteName(option: options[index], index: index, compact: compact)
            }
            .accessibilityLabel("Route: \(options[index].spokenLabel(index: index)). Change route")
        }
    }
}

/// The observer that heard a route, shortened to fit, with "+N" and the hop count.
private struct RouteName: View {
    let option: RouteOption
    let index: Int
    let compact: Bool

    var body: some View {
        HStack(spacing: 4) {
            if compact {
                Image(systemName: "point.topleft.down.curvedto.point.bottomright.up")
            }
            Text(option.name(index: index))
                .lineLimit(1)
                .truncationMode(.tail)
            if let badge = option.othersBadge {
                Text(badge)
                    .foregroundStyle(.secondary)
                    .fixedSize()
            }
            // The map's compact pill leaves hops to the menu's subtitles to fit narrow phones.
            if !compact {
                Text("· \(option.hops) hops")
                    .foregroundStyle(.secondary)
                    .fixedSize()
            }
            Image(systemName: "chevron.down")
                .font(.caption2.weight(.semibold))
        }
        .font(compact ? .caption.weight(.semibold) : .subheadline.weight(.semibold))
        .foregroundStyle(.primary)
    }
}

/// Who heard the chosen route: each observer that reported exactly this path, with signal, then
/// those that heard it earlier on its way (with how many hops they saw).
struct RouteHearersView: View {
    let option: RouteOption

    var body: some View {
        if !option.heardBy.isEmpty || !option.alongTheWay.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                if !option.heardBy.isEmpty {
                    Text("Heard by")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    ForEach(option.heardBy, id: \.self) { hearing in
                        HStack(alignment: .firstTextBaseline) {
                            Text(hearing.observer)
                                .font(.subheadline)
                                .lineLimit(1)
                            Spacer(minLength: 8)
                            Text([hearing.region, hearing.signalText].compactMap { $0 }.joined(separator: " · "))
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                if !option.alongTheWay.isEmpty {
                    Text("Also heard along the way by "
                        + option.alongTheWay.map { "\($0.observer) (\($0.hops) hops)" }.joined(separator: ", "))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

/// Links an observer to the route it heard: "Show route" selects it; `shownText` (e.g. "Shown
/// above") when it is the route on display.
struct RouteTag: View {
    let isShown: Bool
    let shownText: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(isShown ? shownText : "Show route")
                .font(.caption2.weight(.bold))
                .foregroundStyle(isShown ? Color.secondary : NodeScopeStyle.signal)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(NodeScopeStyle.signal.opacity(isShown ? 0.04 : 0.1), in: Capsule())
        }
        .buttonStyle(.plain)
        .disabled(isShown)
        .accessibilityLabel(isShown ? "This observer's route is \(shownText.lowercased())" : "Show this observer's route")
    }
}
