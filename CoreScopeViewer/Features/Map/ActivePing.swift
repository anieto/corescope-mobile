import CoreLocation
import Foundation
import MapKit
import SwiftUI

/// A short-lived animated marker of live packet traffic passing through the network.
///
/// Created from live WebSocket packets or recent packet history. When traffic passes through a hop path,
/// an ActivePing progressively draws one hop, then leaves a short-lived completed segment.
struct ActivePing: Identifiable, Sendable {
    let id = UUID()
    let start: CLLocationCoordinate2D
    let end: CLLocationCoordinate2D
    let isPulse: Bool
    let createdAt: Date
    /// When this segment's path/trail *fade* begins — separate from
    /// `createdAt`, which only drives the traveling signal dot. Defaults to
    /// `createdAt` so ordinary live pings fade the instant they're created,
    /// same as before. A route replay staggers this per segment (hop 1's
    /// fade starts, then hop 2's once hop 1 has fully dissolved, etc.) so
    /// the route dissolves in hop order instead of every segment fading on
    /// the same clock.
    let fadeStartsAt: Date
    let duration: TimeInterval
    let travelDuration: TimeInterval
    let pathColor: Color
    let signalColor: Color
    let routeID: String?
    let segmentIndex: Int
    let packetHash: String?
    let observerName: String?
    let observedAt: Date
    let snr: Double?
    let rssi: Double?
    let sender: String?
    let messageText: String?
    /// How far the hop bows to its left, as a share of its length (0 = straight). A replay bends
    /// its hops so a route that doubles back between nearby nodes shows both directions side by
    /// side instead of one line drawn twice.
    let curvature: Double
    /// Bridges hops the analyzer couldn't resolve: from the last known node to the next. Drawn
    /// dashed and faint, with no arrival ring, since no specific relay is claimed.
    let isUnknownRelay: Bool
    /// A replay hop reaching a node or observer outside the selected region: drawn faded, so the
    /// packet can be followed out of the region while the region's own part stands out.
    let isOutsideRegion: Bool

    init(
        from start: CLLocationCoordinate2D,
        to end: CLLocationCoordinate2D,
        createdAt: Date = .now,
        fadeStartsAt: Date? = nil,
        duration: TimeInterval = 12.0,
        travelDuration: TimeInterval = 2.25,
        color: Color = .yellow,
        signalColor: Color? = nil,
        routeID: String? = nil,
        segmentIndex: Int = 0,
        packetHash: String? = nil,
        observerName: String? = nil,
        observedAt: Date? = nil,
        snr: Double? = nil,
        rssi: Double? = nil,
        sender: String? = nil,
        messageText: String? = nil,
        curvature: Double = 0,
        isUnknownRelay: Bool = false,
        isOutsideRegion: Bool = false
    ) {
        self.start = start
        self.end = end
        self.curvature = curvature
        self.isUnknownRelay = isUnknownRelay
        self.isOutsideRegion = isOutsideRegion
        self.isPulse = false
        self.createdAt = createdAt
        self.fadeStartsAt = fadeStartsAt ?? createdAt
        self.duration = duration
        self.travelDuration = travelDuration
        self.pathColor = color
        self.signalColor = signalColor ?? color
        self.routeID = routeID
        self.segmentIndex = segmentIndex
        self.packetHash = packetHash
        self.observerName = observerName
        self.observedAt = observedAt ?? createdAt
        self.snr = snr
        self.rssi = rssi
        self.sender = sender
        self.messageText = messageText
    }

    init(
        pulseAt point: CLLocationCoordinate2D,
        createdAt: Date = .now,
        duration: TimeInterval = 3.0,
        color: Color = .yellow
    ) {
        self.start = point
        self.end = point
        self.curvature = 0
        self.isUnknownRelay = false
        self.isOutsideRegion = false
        self.isPulse = true
        self.createdAt = createdAt
        self.fadeStartsAt = createdAt
        self.duration = duration
        self.travelDuration = 0
        self.pathColor = color
        self.signalColor = color
        self.routeID = nil
        self.segmentIndex = 0
        self.packetHash = nil
        self.observerName = nil
        self.observedAt = createdAt
        self.snr = nil
        self.rssi = nil
        self.sender = nil
        self.messageText = nil
    }

    /// Fade progress (0 = fully visible, 1 = fully dissolved). Clamped at 0
    /// before `fadeStartsAt`, so a segment whose turn hasn't come up yet in
    /// a staggered sequence just sits at full opacity rather than fading
    /// early or going negative.
    /// How strongly the hop is drawn: faded outside the selected region.
    var regionEmphasis: Double { isOutsideRegion ? 0.45 : 1 }

    func progress(at date: Date = .now) -> Double {
        min(max(date.timeIntervalSince(fadeStartsAt) / duration, 0), 1)
    }

    func travelProgress(at date: Date = .now) -> Double {
        guard travelDuration > 0 else { return 1.0 }
        return min(max(date.timeIntervalSince(createdAt) / travelDuration, 0), 1)
    }

    func hasCompletedTravel(at date: Date = .now) -> Bool {
        travelProgress(at: date) >= 1.0
    }

    func completedPathOpacity(at date: Date = .now) -> Double {
        guard hasCompletedTravel(at: date) else { return 0 }
        return max(0, 1 - progress(at: date))
    }

    /// A short arrival pulse begins when the packet reaches the end of this hop.
    func arrivalPulseProgress(at date: Date = .now, duration: TimeInterval = 0.8) -> Double? {
        let arrivalDate = createdAt.addingTimeInterval(travelDuration)
        let elapsed = date.timeIntervalSince(arrivalDate)
        guard elapsed >= 0, elapsed < duration else { return nil }
        return elapsed / duration
    }

    func hasStarted(at date: Date = .now) -> Bool {
        date >= createdAt
    }

    func isExpired(at date: Date = .now) -> Bool {
        progress(at: date) >= 1.0
    }

    func currentCoordinate(at date: Date = .now) -> CLLocationCoordinate2D {
        coordinate(atFraction: travelProgress(at: date))
    }

    func coordinate(atFraction fraction: Double) -> CLLocationCoordinate2D {
        guard curvature != 0 else {
            return CLLocationCoordinate2D(
                latitude: start.latitude + (end.latitude - start.latitude) * fraction,
                longitude: start.longitude + (end.longitude - start.longitude) * fraction
            )
        }
        // A quadratic curve in map (Mercator) space, so it looks the same at every zoom.
        let a = MKMapPoint(start), b = MKMapPoint(end)
        let dx = b.x - a.x, dy = b.y - a.y
        let control = MKMapPoint(x: (a.x + b.x) / 2 + dy * curvature, y: (a.y + b.y) / 2 - dx * curvature)
        let t = fraction, u = 1 - fraction
        return MKMapPoint(
            x: u * u * a.x + 2 * u * t * control.x + t * t * b.x,
            y: u * u * a.y + 2 * u * t * control.y + t * t * b.y
        ).coordinate
    }

    /// The hop's path from its start up to `fraction` of the way: two points for a straight hop,
    /// enough to look smooth for a curved one.
    func pathCoordinates(through fraction: Double = 1) -> [CLLocationCoordinate2D] {
        guard curvature != 0 else { return [start, coordinate(atFraction: fraction)] }
        let samples = max(2, Int((16 * fraction).rounded(.up)))
        return (0...samples).map { coordinate(atFraction: fraction * Double($0) / Double(samples)) }
    }

    static func color(forHash hashString: String?, colorScheme: ColorScheme) -> Color {
        guard let hashString, !hashString.isEmpty else {
            return colorScheme == .dark ? .yellow : Color(red: 0.75, green: 0.45, blue: 0.0)
        }

        // Keep live routes dark and saturated on light maps, but luminous on
        // dark maps where the original colors can otherwise disappear.
        let lightColors: [Color] = [
            Color(red: 0.0, green: 0.32, blue: 0.72),
            Color(red: 0.0, green: 0.48, blue: 0.22),
            Color(red: 0.76, green: 0.32, blue: 0.0),
            Color(red: 0.67, green: 0.08, blue: 0.46),
            Color(red: 0.36, green: 0.20, blue: 0.74),
            Color(red: 0.78, green: 0.18, blue: 0.05),
            Color(red: 0.0, green: 0.40, blue: 0.58),
            Color(red: 0.34, green: 0.48, blue: 0.0)
        ]
        let darkColors: [Color] = [
            Color(red: 0.35, green: 0.82, blue: 1.0),
            Color(red: 0.30, green: 0.95, blue: 0.54),
            Color(red: 1.0, green: 0.84, blue: 0.25),
            Color(red: 1.0, green: 0.48, blue: 0.80),
            Color(red: 0.70, green: 0.58, blue: 1.0),
            Color(red: 1.0, green: 0.56, blue: 0.30),
            Color(red: 0.42, green: 0.72, blue: 1.0),
            Color(red: 0.74, green: 1.0, blue: 0.34)
        ]
        let colors = colorScheme == .dark ? darkColors : lightColors
        let hashValue = abs(hashString.lowercased().hashValue)
        return colors[hashValue % colors.count]
    }
}
