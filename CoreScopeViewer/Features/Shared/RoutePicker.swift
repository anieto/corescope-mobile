import SwiftUI

/// Chooses between a packet's routes by who heard them: each choice leads with the observer that
/// heard that path (+N when several did), then hops, region and signal. Used by packet details,
/// live packet details and the map's replay controls, so a route is named the same everywhere.
struct RoutePicker: View {
    let options: [RouteOption]
    @Binding var selection: Int
    var compact = false
    /// Offers "All Routes" first (the map's replay), selected as `PacketReplayStore.allRoutes`.
    var allowsAll = false

    private var index: Int {
        if allowsAll, selection == PacketReplayStore.allRoutes, options.count > 1 { return selection }
        return options.indices.contains(selection) ? selection : 0
    }

    var body: some View {
        if options.isEmpty {
            EmptyView()
        } else {
            // Equatable so the parent's frequent redraws (live map, replay) leave an open menu
            // alone; rebuilding its items scrolled a long list back to the bottom.
            RouteMenu(options: options, selected: index, compact: compact, allowsAll: allowsAll && options.count > 1) { selection = $0 }
                .equatable()
        }
    }
}

private struct RouteMenu: View, Equatable {
    let options: [RouteOption]
    let selected: Int
    let compact: Bool
    let allowsAll: Bool
    let select: (Int) -> Void

    nonisolated static func == (lhs: RouteMenu, rhs: RouteMenu) -> Bool {
        lhs.options == rhs.options && lhs.selected == rhs.selected && lhs.compact == rhs.compact
            && lhs.allowsAll == rhs.allowsAll
    }

    private var showsAll: Bool { allowsAll && selected == PacketReplayStore.allRoutes }

    var body: some View {
        Menu {
            if allowsAll {
                Toggle(isOn: Binding(
                    get: { showsAll },
                    set: { if $0 { select(PacketReplayStore.allRoutes) } }
                )) {
                    Text("All Routes")
                    Text("\(options.count) routes together")
                }
                Divider()
            }
            ForEach(options.indices, id: \.self) { index in
                let option = options[index]
                // A toggle shows the menu's checkmark; its two texts give the item a title
                // and a subtitle line.
                Toggle(isOn: Binding(
                    get: { index == selected },
                    set: { if $0 { select(index) } }
                )) {
                    Text([option.name(index: index), option.othersBadge].compactMap { $0 }.joined(separator: "  "))
                    Text(option.summary)
                }
                .accessibilityLabel(option.spokenLabel(index: index))
            }
        } label: {
            if showsAll {
                HStack(spacing: 4) {
                    if compact {
                        Image(systemName: "point.topleft.down.curvedto.point.bottomright.up")
                    }
                    Text("All \(options.count) routes")
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.caption2.weight(.semibold))
                }
                .font(compact ? .caption.weight(.semibold) : .subheadline.weight(.semibold))
                .foregroundStyle(.primary)
            } else {
                RouteName(option: options[selected], index: selected, compact: compact)
            }
        }
        .accessibilityLabel(showsAll
            ? "Route: all \(options.count) routes. Change route"
            : "Route: \(options[selected].spokenLabel(index: selected)). Change route")
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
                    HStack(spacing: 4) {
                        Text("Heard by")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                        LearnButton(term: .heardBy)
                    }
                    ForEach(option.heardBy, id: \.self) { hearing in
                        HStack(alignment: .firstTextBaseline) {
                            Text(hearing.observer)
                                .font(.monaspace(.subheadline))
                                .lineLimit(1)
                            Spacer(minLength: 8)
                            Text([hearing.region, hearing.signalText].compactMap { $0 }.joined(separator: " · "))
                                .font(.monaspace(.caption))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                if !option.alongTheWay.isEmpty {
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text("Also heard along the way by "
                            + option.alongTheWay.map { "\($0.observer) (\($0.hops) hops)" }.joined(separator: ", "))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                        LearnButton(term: .alongTheWay)
                    }
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

/// Shown in place of one route's details while All Routes is chosen.
struct AllRoutesNote: View {
    let count: Int

    var body: some View {
        Text("All \(count) routes replay together. Choose one to see its hops and who heard it.")
            .font(.footnote)
            .foregroundStyle(.secondary)
    }
}
