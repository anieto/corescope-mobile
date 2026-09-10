import SwiftUI

/// Role-based icon/tint used for map `Marker`s. Native `Marker` (backed by
/// MKMarkerAnnotationView) is what makes ~1,000 pins performant — it
/// supports clustering and is far cheaper than a custom SwiftUI `Annotation`
/// view repeated at that scale, which is what made the map sluggish.
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
