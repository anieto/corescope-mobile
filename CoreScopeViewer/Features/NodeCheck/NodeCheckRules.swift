import Foundation

/// Node Check's findings. Mirrors the meshtexas.org Node Check rules
/// (functions/_shared/node-check.js in meshtexas-site) so a node gets the same
/// verdict on the website and in the app; change both together.
struct NodeCheckReport: Sendable {
    enum Level: Int, Sendable, Comparable {
        case bad = 0, warn, good, info

        static func < (lhs: Level, rhs: Level) -> Bool { lhs.rawValue < rhs.rawValue }
    }

    struct Finding: Sendable, Identifiable, Hashable {
        let level: Level
        let title: String
        let detail: String
        let fix: String?

        var id: String { title }
    }

    struct HeardObserver: Sendable, Identifiable, Hashable {
        let observerID: String
        let name: String
        let region: String?
        let hops: Int?
        let snr: Double?
        let rssi: Double?
        var packets: Int? = nil

        var id: String { observerID }
    }

    struct AdvertReach: Sendable, Hashable {
        let sentAt: Date?
        let kind: String?
        /// Closest first.
        let observers: [HeardObserver]
        let regions: [String]
    }

    let node: NodeCheckInput.Node
    let level: Level
    let headline: String
    /// Problems first, then what's working, then notes.
    let findings: [Finding]
    let latestAdvert: AdvertReach?
    /// Observers that heard the node itself, strongest first.
    let direct: [HeardObserver]
    let lastHeard: Date?
}

enum NodeCheckRules {
    private static let hour: TimeInterval = 3_600
    private static let day: TimeInterval = 86_400
    private static let infrastructure: Set<String> = ["repeater", "room"]

    static func report(for input: NodeCheckInput, now: Date = .now) -> NodeCheckReport {
        let role = input.node.role.lowercased()
        let latestAdvert = input.adverts.first.map { reach(of: [$0], regions: input.observerRegions) }
        let latestFlood = input.adverts.first { $0.kind == "flood" }.map { reach(of: [$0], regions: input.observerRegions) }
        let direct = directObservers(input)

        let lastHeard = input.node.lastHeard ?? input.healthLastHeard ?? input.node.lastSeen
        let quiet = lastHeard.map { now.timeIntervalSince($0) } ?? .infinity
        var findings: [NodeCheckReport.Finding] = []
        func add(_ level: NodeCheckReport.Level, _ title: String, _ detail: String, _ fix: String? = nil) {
            findings.append(.init(level: level, title: title, detail: detail, fix: fix))
        }

        // Last heard.
        if infrastructure.contains(role) {
            let quietAfter = day
            let silentAfter = role == "room" ? 3 * day : 2 * day
            if let lastHeard, quiet <= quietAfter {
                add(.good, String(localized: "Heard \(ago(lastHeard, now: now))"),
                    String(localized: "Observers are hearing this node regularly."))
            } else if let lastHeard, quiet <= silentAfter {
                add(.warn, String(localized: "Quiet: last heard \(ago(lastHeard, now: now))"),
                    role == "room"
                        ? String(localized: "A working room server usually sends an advert at least twice a day.")
                        : String(localized: "A working repeater usually turns up at least once a day, from its own adverts or the traffic it relays."),
                    String(localized: "Check that it still has power and its antenna is connected. If it runs on solar, check the battery."))
            } else {
                add(.bad,
                    lastHeard.map { String(localized: "Not heard for \(duration(now.timeIntervalSince($0)))") }
                        ?? String(localized: "No recent traffic"),
                    role == "room"
                        ? String(localized: "Nothing from this node has reached an observer in the last three days.")
                        : String(localized: "Nothing from this node has reached an observer in the last two days."),
                    String(localized: "Check power, the antenna and the radio settings, then send a flood advert."))
            }
        } else if let lastHeard {
            add(quiet <= 14 * day ? .info : .warn, String(localized: "Last heard \(ago(lastHeard, now: now))"),
                String(localized: "Companions are only heard when they send something, so a quiet spell is normal."),
                quiet > 14 * day ? String(localized: "Send a flood advert to check that it still reaches the mesh.") : nil)
        }

        // Reach of the most recent flood advert.
        if let flood = latestFlood, !flood.observers.isEmpty {
            let count = flood.observers.count
            let regionText = flood.regions.isEmpty ? ""
                : String(localized: " in \(plural(flood.regions.count, one: "region", many: "regions")) (\(flood.regions.joined(separator: ", ")))")
            add(.good, String(localized: "Last flood advert reached \(plural(count, one: "observer", many: "observers"))\(regionText)"),
                flood.sentAt.map { String(localized: "Sent \(ago($0, now: now)).") } ?? String(localized: "The time it was sent is unknown."),
                count < 3 ? String(localized: "Only a few observers heard it. That can simply mean there are few observers near you, or that it needs another repeater in range to travel further.") : nil)
        } else if !input.adverts.isEmpty {
            add(.info, String(localized: "No recent flood advert"),
                String(localized: "Its recent adverts were zero-hop, which only reach radios in direct range, so they don’t show how far it gets."),
                String(localized: "Send a flood advert to see how far it travels."))
        } else {
            add(.info, String(localized: "No recent adverts"),
                String(localized: "The analyzer has no recent adverts from this node."),
                String(localized: "Send a flood advert to see how far it travels."))
        }

        // Nearest observer.
        if let best = direct.first {
            let signal = best.snr.map { String(localized: " at \(signedDecibels($0)) SNR") } ?? ""
            add(.good,
                direct.count == 1 ? String(localized: "1 observer hears it directly")
                    : String(localized: "\(direct.count) observers hear it directly"),
                String(localized: "The strongest is \(best.name)\(signal)."))
        } else if let nearest = latestFlood?.observers.first?.hops, nearest > 0 {
            add(.info, String(localized: "Nearest observer is \(plural(nearest, one: "hop", many: "hops")) away"),
                String(localized: "No observer hears this node directly, so it reaches the mesh through repeaters. That’s normal unless you expected an observer nearby to hear it."))
        }

        // Repeaters should be relaying.
        if role == "repeater", quiet <= 2 * day {
            if let lastRelayed = input.node.lastRelayed {
                if now.timeIntervalSince(lastRelayed) > 2 * day {
                    add(.warn, String(localized: "Last relayed \(ago(lastRelayed, now: now))"),
                        String(localized: "It’s still heard, but hasn’t relayed traffic for a while."),
                        String(localized: "Check that repeating is still on and nothing changed in its settings."))
                } else {
                    add(.good, String(localized: "Relaying traffic"),
                        String(localized: "Other nodes’ packets are passing through it. It last relayed one \(ago(lastRelayed, now: now))."))
                }
            } else {
                add(.warn, String(localized: "Not seen relaying"),
                    String(localized: "Observers hear this repeater, but haven’t seen it pass on anyone else’s traffic."),
                    String(localized: "Check that repeating is turned on. If it is, there may simply be no traffic near it yet."))
            }
        }

        // Clock.
        if let clock = input.clock {
            let clockFix = String(localized: "Sync the clock from the MeshCore app, or by running “clock sync” on the node.")
            switch clock.severity {
            case "warning", "critical":
                add(.warn, String(localized: "Clock is off by \(describeOffset(clock.offsetSeconds))"),
                    String(localized: "Nodes with the wrong time can have their adverts treated as old."), clockFix)
            case "bimodal_clock":
                add(.warn, String(localized: "Clock keeps jumping"),
                    String(localized: "Its clock sometimes reads right and sometimes far off, which usually means it loses time on restart."), clockFix)
            case "absurd", "no_clock":
                add(.warn, String(localized: "Clock isn’t set"),
                    String(localized: "Its clock is far from the real time, as if it was never set."), clockFix)
            case "ok" where infrastructure.contains(role):
                add(.good, String(localized: "Clock is right"),
                    String(localized: "Its clock is within a few minutes of the real time."))
            default:
                break
            }
        }

        // Location, only for nodes meant to be found on a map.
        let hasLocation = input.node.lat != nil && input.node.lon != nil && !(input.node.lat == 0 && input.node.lon == 0)
        if infrastructure.contains(role), !hasLocation {
            add(.info, String(localized: "No location set"),
                String(localized: "It won’t appear on the live map or the analyzer’s map."),
                String(localized: "Set a location in the node’s settings if you’re comfortable sharing it."))
        }

        let sorted = findings.enumerated()
            .sorted { $0.element.level == $1.element.level ? $0.offset < $1.offset : $0.element.level < $1.element.level }
            .map(\.element)
        let level = sorted.first?.level ?? .info
        let headline = switch level {
        case .bad: String(localized: "This node has gone quiet")
        case .warn: String(localized: "Heard, with something to check")
        case .good: String(localized: "This node is getting out")
        case .info: String(localized: "Heard before, but nothing recent to go on")
        }
        return NodeCheckReport(
            node: input.node, level: level, headline: headline, findings: sorted,
            latestAdvert: latestFlood ?? latestAdvert, direct: direct, lastHeard: lastHeard
        )
    }

    private static func directObservers(_ input: NodeCheckInput) -> [NodeCheckReport.HeardObserver] {
        var observers: [NodeCheckReport.HeardObserver] = []
        for observer in input.directObservers {
            let snr: Double? = observer.averageSNR.map(round1)
            let rssi: Double? = observer.averageRSSI.map { $0.rounded() }
            observers.append(NodeCheckReport.HeardObserver(
                observerID: observer.observerID,
                name: observer.name ?? String(localized: "Unnamed observer"),
                region: input.observerRegions[observer.observerID],
                hops: 0, snr: snr, rssi: rssi, packets: observer.packets
            ))
        }
        return observers.sorted { ($0.snr ?? -99) > ($1.snr ?? -99) }
    }

    /// Adverts and everyone who reported them, closest observer first. Several adverts
    /// merge into one list; each observer keeps its shortest path.
    static func reach(of adverts: [NodeCheckInput.Advert], regions: [String: String]) -> NodeCheckReport.AdvertReach {
        var seen: [String: NodeCheckReport.HeardObserver] = [:]
        for advert in adverts {
            for observation in advert.observations {
                let entry = NodeCheckReport.HeardObserver(
                    observerID: observation.observerID,
                    name: observation.observerName ?? String(localized: "Unnamed observer"),
                    region: observation.region ?? regions[observation.observerID],
                    hops: observation.hops,
                    snr: observation.snr.map(round1),
                    rssi: observation.rssi
                )
                if let previous = seen[observation.observerID], (previous.hops ?? 99) <= (entry.hops ?? 99) { continue }
                seen[observation.observerID] = entry
            }
        }
        let observers = seen.values.sorted {
            ($0.hops ?? 99, -($0.snr ?? -99)) < ($1.hops ?? 99, -($1.snr ?? -99))
        }
        let regionList = Array(Set(observers.compactMap(\.region))).sorted()
        return .init(sentAt: adverts.first?.sentAt, kind: adverts.first?.kind, observers: observers, regions: regionList)
    }

    // MARK: Wording

    static func ago(_ date: Date, now: Date = .now) -> String {
        let diff = max(0, now.timeIntervalSince(date))
        if diff < 120 { return String(localized: "just now") }
        if diff < hour { return String(localized: "\(Int((diff / 60).rounded())) minutes ago") }
        if diff < 2 * hour { return String(localized: "an hour ago") }
        if diff < day { return String(localized: "\(Int((diff / hour).rounded())) hours ago") }
        if diff < 2 * day { return String(localized: "a day ago") }
        return String(localized: "\(Int((diff / day).rounded())) days ago")
    }

    private static func duration(_ seconds: TimeInterval) -> String {
        if seconds < 2 * day { return String(localized: "a day") }
        return String(localized: "\(Int((seconds / day).rounded())) days")
    }

    private static func plural(_ count: Int, one: String, many: String) -> String {
        "\(count) \(count == 1 ? one : many)"
    }

    static func signedDecibels(_ value: Double) -> String {
        let text = value.formatted(.number.precision(.fractionLength(0...1)))
        return value > 0 ? "+\(text) dB" : "\(text) dB"
    }

    private static func describeOffset(_ seconds: Double?) -> String {
        guard let seconds, seconds.isFinite else { return String(localized: "a while") }
        let magnitude = abs(seconds)
        let amount = if magnitude < hour {
            plural(Int((magnitude / 60).rounded()), one: "minute", many: "minutes")
        } else if magnitude < 2 * day {
            plural(Int((magnitude / hour).rounded()), one: "hour", many: "hours")
        } else {
            plural(Int((magnitude / day).rounded()), one: "day", many: "days")
        }
        return seconds < 0 ? String(localized: "\(amount) behind") : String(localized: "\(amount) ahead")
    }

    private static func round1(_ value: Double) -> Double { (value * 10).rounded() / 10 }
}
