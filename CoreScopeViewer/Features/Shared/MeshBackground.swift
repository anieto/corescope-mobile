import SwiftUI

extension View {
    /// Applies the quiet mesh to a page while preserving solid list rows and cards.
    func meshPageBackground() -> some View {
        scrollContentBackground(.hidden)
            .background {
                MeshBackground(subdued: true)
                    .ignoresSafeArea()
            }
    }
}

/// A fixed, decorative network pattern that stays still as the list scrolls.
struct MeshBackground: View {
    var subdued = false

    @Environment(\.colorScheme) private var colorScheme
    @AppStorage(AppearanceSettings.backgroundTextureDefaultsKey) private var backgroundTextureEnabled = true

    private let nodes: [CGPoint] = [
        CGPoint(x: -0.08, y: 0.12), CGPoint(x: 0.18, y: 0.07),
        CGPoint(x: 0.34, y: 0.25), CGPoint(x: 0.08, y: 0.43),
        CGPoint(x: 0.25, y: 0.68), CGPoint(x: -0.06, y: 0.84),
        CGPoint(x: 0.48, y: 0.91), CGPoint(x: 0.73, y: 0.76),
        CGPoint(x: 0.94, y: 0.94), CGPoint(x: 1.06, y: 0.58),
        CGPoint(x: 0.78, y: 0.44), CGPoint(x: 0.92, y: 0.19),
        CGPoint(x: 0.68, y: 0.06)
    ]

    private let links: [(Int, Int)] = [
        (0, 1), (0, 3), (1, 2), (2, 3), (3, 4), (4, 5),
        (4, 6), (5, 6), (6, 7), (7, 8), (7, 9), (8, 9),
        (9, 10), (10, 11), (11, 12), (10, 12)
    ]

    var body: some View {
        let isDark = colorScheme == .dark
        let linkColor = isDark
            ? Color(red: 0.24, green: 0.65, blue: 0.96)
            : Color(red: 0.08, green: 0.40, blue: 0.72)
        let nodeColor = Color.orange

        LinearGradient(
            colors: isDark
                ? [Color(red: 0.065, green: 0.10, blue: 0.16),
                   Color(red: 0.035, green: 0.085, blue: 0.10)]
                : [Color(red: 0.97, green: 0.965, blue: 0.95),
                   Color(red: 0.87, green: 0.94, blue: 0.96)],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .overlay {
            Canvas { context, size in
                guard backgroundTextureEnabled else { return }
                // Repeat at a consistent scale on large windows without stretching the links.
                let tileWidth = min(size.width, 520)
                let tileHeight: CGFloat = 760
                guard tileWidth > 0 else { return }

                for x in stride(from: CGFloat.zero, to: size.width, by: tileWidth) {
                    for y in stride(from: CGFloat.zero, to: size.height, by: tileHeight) {
                        let points = nodes.map {
                            CGPoint(x: x + $0.x * tileWidth, y: y + $0.y * tileHeight)
                        }
                        var paths = Path()
                        for (start, end) in links {
                            paths.move(to: points[start])
                            paths.addLine(to: points[end])
                        }
                        context.stroke(paths, with: .color(linkColor.opacity(isDark ? 0.38 : 0.32)), lineWidth: 1)

                        for point in points {
                            let dot = Path(ellipseIn: CGRect(
                                x: point.x - 2.5, y: point.y - 2.5, width: 5, height: 5
                            ))
                            context.fill(dot, with: .color(nodeColor.opacity(isDark ? 0.45 : 0.50)))
                        }
                    }
                }
            }
            .opacity(subdued ? 0.5 : 1)
            .mask {
                LinearGradient(
                    colors: [.white, .white.opacity(0.45), .white],
                    startPoint: .leading,
                    endPoint: .trailing
                )
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
