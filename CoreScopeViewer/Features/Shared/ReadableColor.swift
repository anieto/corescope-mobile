import SwiftUI
import UIKit

extension Color {
    /// Clamps this color's brightness (HSB "value") into a range that stays
    /// legible as foreground text/icon color against the app's background,
    /// regardless of the color's original hue. A color chosen purely by hue —
    /// deterministically per sender, say — can otherwise land on something
    /// too pale to read on a light background or too dark to read on a dark
    /// one; a fixed named color (like the app's bright accent orange) can
    /// have the same problem. Saturation and hue are left untouched, so a
    /// neutral gray stays neutral and a color's identity doesn't change,
    /// only how light or dark it renders.
    func readableForeground(for colorScheme: ColorScheme) -> Color {
        guard let (hue, saturation, brightness) = UIColor(self).hsbComponents else {
            return self
        }
        let clampedBrightness = colorScheme == .dark
            ? max(brightness, 0.78)
            : min(brightness, 0.62)
        return Color(hue: hue, saturation: saturation, brightness: clampedBrightness)
    }
}

private extension UIColor {
    var hsbComponents: (hue: Double, saturation: Double, brightness: Double)? {
        var hue: CGFloat = 0
        var saturation: CGFloat = 0
        var brightness: CGFloat = 0
        var alpha: CGFloat = 0
        guard getHue(&hue, saturation: &saturation, brightness: &brightness, alpha: &alpha) else {
            return nil
        }
        return (Double(hue), Double(saturation), Double(brightness))
    }
}
