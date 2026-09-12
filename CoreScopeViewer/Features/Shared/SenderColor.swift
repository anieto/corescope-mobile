import SwiftUI

/// A deterministic per-sender color so a conversation with multiple senders
/// reads visually like a group chat. Colors come from a continuous hue wheel
/// rather than a small fixed palette — with only a handful of named colors,
/// distinct senders collide on an identical color surprisingly often (with
/// just 4 senders against an 8-color palette, the odds of at least one
/// collision are already better than even); a 360-color wheel makes that
/// vanishingly rare. Shared so a sender's color matches wherever their
/// message reappears — e.g. the packet detail screen echoing the bubble they
/// saw in the channel.
enum SenderColor {
    static func color(for sender: String, colorScheme: ColorScheme) -> Color {
        let base = Color(hue: normalizedHue(for: sender), saturation: 0.82, brightness: 0.68)
        // Every hue on the wheel — not just the ones that happen to land on
        // a well-contrasted spot — needs to stay legible as sender-name text
        // against the app's background in either appearance.
        return base.readableForeground(for: colorScheme)
    }

    static func bubbleFill(for sender: String, colorScheme: ColorScheme) -> Color {
        color(for: sender, colorScheme: colorScheme).opacity(colorScheme == .dark ? 0.34 : 0.16)
    }

    static func bubbleStroke(for sender: String, colorScheme: ColorScheme) -> Color {
        color(for: sender, colorScheme: colorScheme).opacity(colorScheme == .dark ? 0.55 : 0.25)
    }

    private static func normalizedHue(for sender: String) -> Double {
        let bucket = ((sender.hashValue % 360) + 360) % 360
        return Double(bucket) / 360.0
    }
}
