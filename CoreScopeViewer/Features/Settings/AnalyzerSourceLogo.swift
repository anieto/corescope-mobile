import SwiftUI
import UIKit

/// A community source's logo from the registry. Only `icons/<name>.png`
/// paths inside the registry folder are accepted, so a registry entry can
/// never make the app contact another server. Icons bundled as
/// `SourceIcon-<name>` show offline; others load from the registry through
/// the shared URL cache. Without a usable icon, the generic symbol shows.
struct AnalyzerSourceLogo: View {
    let source: AnalyzerSource

    static let registryBaseURL = URL(string: "https://raw.githubusercontent.com/anieto/corescope-mobile/main/CommunitySources/")!

    /// Same rule as Android `sourceIconPath`.
    static func iconPath(_ icon: String?) -> String? {
        guard let path = icon?.trimmingCharacters(in: .whitespaces),
              path.wholeMatch(of: /icons\/[a-z0-9][a-z0-9-]*\.png/) != nil else {
            return nil
        }
        return path
    }

    var body: some View {
        Group {
            if let path = Self.iconPath(source.icon) {
                let name = String(path.dropFirst("icons/".count).dropLast(".png".count))
                if let bundled = UIImage(named: "SourceIcon-\(name)") {
                    logo(Image(uiImage: bundled))
                } else {
                    AsyncImage(url: Self.registryBaseURL.appending(path: path)) { phase in
                        if let image = phase.image {
                            logo(image)
                        } else {
                            symbol
                        }
                    }
                }
            } else {
                symbol
            }
        }
        .frame(width: 36, height: 36)
        .accessibilityHidden(true)
    }

    private func logo(_ image: Image) -> some View {
        image
            .resizable()
            .scaledToFill()
            .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
    }

    private var symbol: some View {
        Image(systemName: source.isDefault ? "star.circle.fill" : "antenna.radiowaves.left.and.right.circle.fill")
            .font(.title3)
            .foregroundStyle(source.isDefault ? .yellow : Color.accentColor)
    }
}
