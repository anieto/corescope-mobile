import CoreLocation
import Foundation

/// One observer's report of a packet, used to say which observer heard which route.
struct RouteHearing: Hashable, Sendable {
    let observer: String
    var region: String? = nil
    var snr: Double? = nil
    var rssi: Double? = nil
    /// How many hops this observer's own path had.
    var hops = 0
    /// The observer's id when known (live packets carry it; the packet API names observers only).
    var observerId: String? = nil

    /// Signal for display: RSSI when reported, else SNR.
    var signalText: String? {
        if let rssi { return String(format: "%.0f dBm", rssi) }
        if let snr { return String(format: "%.1f dB", snr) }
        return nil
    }
}

/// A reported path and who reported it (nil when unknown).
struct HeardPath: Sendable {
    let path: [String?]
    let hearing: RouteHearing?
}

/// One distinct route of a packet (node public keys) and who heard it: `heardBy` reported exactly
/// this path, strongest signal first; `alongTheWay` reported a shorter part of it, i.e. heard the
/// packet earlier on its way, longest first. Mirrors Android's `RouteOption`.
struct RouteOption: Hashable, Sendable {
    let keys: [String]
    var heardBy: [RouteHearing] = []
    var alongTheWay: [RouteHearing] = []

    var hops: Int { keys.count }

    /// The observer that heard this route, or "Route N" when none is known.
    func name(index: Int) -> String {
        heardBy.first?.observer ?? "Route \(index + 1)"
    }

    /// "+N" when more than one observer heard this exact route.
    var othersBadge: String? {
        heardBy.count > 1 ? "+\(heardBy.count - 1)" : nil
    }

    /// Hops, region and signal of the strongest observer, for the line under a route's name.
    var summary: String {
        ["\(hops) hops", heardBy.first?.region, heardBy.first?.signalText]
            .compactMap { $0 }
            .joined(separator: " · ")
    }

    func spokenLabel(index: Int) -> String {
        let who: String
        if let first = heardBy.first {
            who = heardBy.count > 1 ? "\(first.observer) and \(heardBy.count - 1) more" : first.observer
        } else {
            who = "Route \(index + 1)"
        }
        return "\(who), \(summary)"
    }

    /// Where a route ends: the observers that heard exactly this path, at their positions, so a
    /// replay can finish with the hop to each of them. Observers without a known position are
    /// left out.
    func receivers(
        coordinateById: [String: CLLocationCoordinate2D],
        coordinateByName: [String: CLLocationCoordinate2D]
    ) -> [(name: String, coordinate: CLLocationCoordinate2D)] {
        var seen = Set<String>()
        return heardBy.compactMap { hearing in
            let coordinate = hearing.observerId.flatMap { coordinateById[$0] ?? coordinateById[$0.lowercased()] }
                ?? coordinateByName[hearing.observer]
                ?? coordinateByName[hearing.observer.lowercased()]
            guard let coordinate,
                  seen.insert("\(coordinate.latitude),\(coordinate.longitude)").inserted else { return nil }
            return (hearing.observer, coordinate)
        }
    }
}

enum RouteOptions {
    /// Distinct resolved routes (at least two nodes), longest first, dropping any route contained
    /// in a longer one. Who heard each route is kept: a dropped shorter route's observers heard
    /// the longer one along the way.
    static func make(from reports: [HeardPath]) -> [RouteOption] {
        struct Entry {
            let keys: [String]
            let lower: [String]
            var hearers: [RouteHearing] = []
        }

        var entries: [Entry] = []
        var indexByPath: [[String]: Int] = [:]
        for report in reports {
            let keys = report.path.compactMap { $0 }.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            guard keys.count >= 2 else { continue }
            let lower = keys.map { $0.lowercased() }
            let index: Int
            if let existing = indexByPath[lower] {
                index = existing
            } else {
                index = entries.count
                indexByPath[lower] = index
                entries.append(Entry(keys: keys, lower: lower))
            }
            if var hearing = report.hearing {
                hearing.hops = keys.count
                entries[index].hearers.append(hearing)
            }
        }

        // Stable sort, so equal-length routes keep the order they were first reported in.
        let sorted = entries.enumerated()
            .sorted { $0.element.keys.count != $1.element.keys.count
                ? $0.element.keys.count > $1.element.keys.count
                : $0.offset < $1.offset }
            .map(\.element)
        let isKept = sorted.map { candidate in
            !sorted.contains { contains($0.lower, candidate.lower) }
        }
        let kept = sorted.indices.filter { isKept[$0] }
        var along = Dictionary(uniqueKeysWithValues: kept.map { ($0, [RouteHearing]()) })
        for part in sorted.indices where !isKept[part] {
            if let container = kept.first(where: { contains(sorted[$0].lower, sorted[part].lower) }) {
                along[container, default: []] += sorted[part].hearers
            }
        }
        return kept.map { index in
            RouteOption(
                keys: sorted[index].keys,
                heardBy: strongestFirst(distinctObservers(sorted[index].hearers)),
                alongTheWay: longestFirst(distinctObservers(along[index] ?? []))
            )
        }
    }

    /// Adds a packet's complete routes to the ones already offered, keeping their order so a route
    /// that is playing stays put. Observers of a known route join it; a route already contained in
    /// a known one adds its observers as heard along the way; only new routes are appended.
    static func merge(_ existing: [RouteOption], _ complete: [RouteOption]) -> [RouteOption] {
        var merged = existing
        for option in complete {
            let lower = option.keys.map { $0.lowercased() }
            if let same = merged.firstIndex(where: { $0.keys.map { $0.lowercased() } == lower }) {
                merged[same].heardBy = strongestFirst(distinctObservers(merged[same].heardBy + option.heardBy))
                merged[same].alongTheWay = longestFirst(distinctObservers(merged[same].alongTheWay + option.alongTheWay))
            } else if let container = merged.firstIndex(where: { contains($0.keys.map { $0.lowercased() }, lower) }) {
                merged[container].alongTheWay = longestFirst(distinctObservers(
                    merged[container].alongTheWay + option.heardBy + option.alongTheWay
                ))
            } else {
                merged.append(option)
            }
        }
        return merged
    }

    /// Which route (index) a reported path belongs to: the same route, or one it is part of.
    static func index(for path: [String?], in options: [RouteOption]) -> Int? {
        let keys = path.compactMap { $0 }
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            .map { $0.lowercased() }
        guard keys.count >= 2 else { return nil }
        return options.firstIndex { $0.keys.map { $0.lowercased() } == keys }
            ?? options.firstIndex { contains($0.keys.map { $0.lowercased() }, keys) }
    }

    /// Whether `route` is longer than `part` and holds it as a contiguous run.
    private static func contains(_ route: [String], _ part: [String]) -> Bool {
        guard route.count > part.count, !part.isEmpty else { return false }
        return (0...(route.count - part.count)).contains { start in
            route[start..<(start + part.count)].elementsEqual(part)
        }
    }

    private static func distinctObservers(_ hearings: [RouteHearing]) -> [RouteHearing] {
        var seen = Set<String>()
        return hearings.filter { seen.insert($0.observer.lowercased()).inserted }
    }

    private static func strongestFirst(_ hearings: [RouteHearing]) -> [RouteHearing] {
        hearings.enumerated().sorted { lhs, rhs in
            let l = lhs.element, r = rhs.element
            let lRssi = l.rssi ?? -.infinity, rRssi = r.rssi ?? -.infinity
            if lRssi != rRssi { return lRssi > rRssi }
            let lSnr = l.snr ?? -.infinity, rSnr = r.snr ?? -.infinity
            if lSnr != rSnr { return lSnr > rSnr }
            return lhs.offset < rhs.offset
        }.map(\.element)
    }

    private static func longestFirst(_ hearings: [RouteHearing]) -> [RouteHearing] {
        hearings.enumerated().sorted { lhs, rhs in
            lhs.element.hops != rhs.element.hops ? lhs.element.hops > rhs.element.hops : lhs.offset < rhs.offset
        }.map(\.element)
    }
}
