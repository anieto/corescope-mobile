import SwiftUI
import UIKit

/// Monaspace Neon for headings and the values people scan (counts, SNR, hashes, keys); body text
/// stays in the system font. Sizes follow the system text styles, a touch smaller because the
/// monospaced letters are wider, and scale with Dynamic Type.
enum AppFont {
    static func uiFont(_ style: UIFont.TextStyle) -> UIFont {
        let (size, face) = metrics(style)
        guard let font = UIFont(name: face, size: size) else {
            return .preferredFont(forTextStyle: style)
        }
        return UIFontMetrics(forTextStyle: style).scaledFont(for: font)
    }

    /// Large and inline navigation titles, which SwiftUI draws through UIKit.
    static func styleNavigationTitles() {
        let appearance = UINavigationBar.appearance()
        appearance.largeTitleTextAttributes = [.font: uiFont(.largeTitle)]
        appearance.titleTextAttributes = [.font: uiFont(.headline)]
    }

    fileprivate static func metrics(_ style: UIFont.TextStyle) -> (CGFloat, String) {
        let scale: CGFloat = 0.92
        let (size, face): (CGFloat, String) = switch style {
        case .largeTitle: (34, "MonaspaceNeon-Bold")
        case .title1: (28, "MonaspaceNeon-Bold")
        case .title2: (22, "MonaspaceNeon-SemiBold")
        case .title3: (20, "MonaspaceNeon-SemiBold")
        case .headline: (17, "MonaspaceNeon-SemiBold")
        case .body: (17, "MonaspaceNeon-Regular")
        case .callout: (16, "MonaspaceNeon-Regular")
        case .subheadline: (15, "MonaspaceNeon-Regular")
        case .footnote: (13, "MonaspaceNeon-Regular")
        case .caption1: (12, "MonaspaceNeon-Regular")
        default: (11, "MonaspaceNeon-Regular")
        }
        return (size * scale, face)
    }
}

extension Font {
    /// The app's Monaspace Neon at a system text style's size; see `AppFont`.
    static func monaspace(_ style: Font.TextStyle) -> Font {
        let uiStyle: UIFont.TextStyle = switch style {
        case .largeTitle: .largeTitle
        case .title: .title1
        case .title2: .title2
        case .title3: .title3
        case .headline: .headline
        case .body: .body
        case .callout: .callout
        case .subheadline: .subheadline
        case .footnote: .footnote
        case .caption: .caption1
        default: .caption2
        }
        let (size, face) = AppFont.metrics(uiStyle)
        return .custom(face, size: size, relativeTo: style)
    }
}
