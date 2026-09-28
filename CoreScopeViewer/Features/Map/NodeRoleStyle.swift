import SwiftUI

/// Shared role colors and symbols used across the map, filters, and supporting UI.
enum NodeRoleStyle {
    static func color(for role: String) -> Color {
        switch role {
        case "repeater": .orange
        case "room": .blue
        case "companion": .green
        case "sensor": .purple
        default: .gray
        }
    }

    static func symbolName(for role: String) -> String {
        switch role {
        case "repeater": "antenna.radiowaves.left.and.right"
        case "room": "person.3.fill"
        case "companion": "iphone"
        case "sensor": "gauge.with.dots.needle.33percent"
        default: "questionmark"
        }
    }
}
