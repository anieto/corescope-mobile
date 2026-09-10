import CoreLocation
import Foundation
import SwiftUI

/// A short-lived animated marker of live packet traffic passing through the network.
///
/// Created from live WebSocket packets or recent packet history. When traffic passes through a hop path,
/// an ActivePing draws a colored path segment for 60 seconds and a traveling packet dot
/// along the route from start to end, which smoothly fades out.
struct ActivePing: Identifiable, Sendable {
    static let trailFractions: [Double] = [0.25, 0.5, 0.75]

    let id = UUID()
    let start: CLLocationCoordinate2D
    let end: CLLocationCoordinate2D
    let isPulse: Bool
    let createdAt: Date
    let duration: TimeInterval
    let pathColor: Color

    init(
        from start: CLLocationCoordinate2D,
        to end: CLLocationCoordinate2D,
        createdAt: Date = .now,
        duration: TimeInterval = 12.0,
        color: Color = .yellow
    ) {
        self.start = start
        self.end = end
        self.isPulse = false
        self.createdAt = createdAt
        self.duration = duration
        self.pathColor = color
    }

    init(
        pulseAt point: CLLocationCoordinate2D,
        createdAt: Date = .now,
        duration: TimeInterval = 3.0,
        color: Color = .yellow
    ) {
        self.start = point
        self.end = point
        self.isPulse = true
        self.createdAt = createdAt
        self.duration = duration
        self.pathColor = color
    }

    func progress(at date: Date = .now) -> Double {
        min(max(date.timeIntervalSince(createdAt) / duration, 0), 1)
    }

    func travelProgress(at date: Date = .now, travelDuration: TimeInterval = 2.25) -> Double {
        min(max(date.timeIntervalSince(createdAt) / travelDuration, 0), 1)
    }

    func hasStarted(at date: Date = .now) -> Bool {
        date >= createdAt
    }

    func isExpired(at date: Date = .now) -> Bool {
        progress(at: date) >= 1.0
    }

    func currentCoordinate(at date: Date = .now) -> CLLocationCoordinate2D {
        let frac = travelProgress(at: date)
        return coordinate(atFraction: frac)
    }

    func coordinate(atFraction fraction: Double) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(
            latitude: start.latitude + (end.latitude - start.latitude) * fraction,
            longitude: start.longitude + (end.longitude - start.longitude) * fraction
        )
    }

    static func color(forHash hashString: String?) -> Color {
        guard let hashString, !hashString.isEmpty else {
            return Color.yellow
        }
        let colors: [Color] = [
            Color(red: 0.0, green: 0.85, blue: 1.0),   // Cyan / Electric Blue
            Color(red: 0.0, green: 0.95, blue: 0.5),   // Emerald / Neon Green
            Color(red: 1.0, green: 0.8, blue: 0.0),    // Gold / Amber
            Color(red: 1.0, green: 0.35, blue: 0.8),   // Magenta / Pink
            Color(red: 0.6, green: 0.4, blue: 1.0),    // Purple / Violet
            Color(red: 1.0, green: 0.45, blue: 0.1),   // Bright Orange
            Color(red: 0.2, green: 0.6, blue: 1.0),    // Sky Blue
            Color(red: 0.8, green: 1.0, blue: 0.2)     // Lime
        ]
        let hashValue = abs(hashString.lowercased().hashValue)
        let index = hashValue % colors.count
        return colors[index]
    }
}
