import Foundation

/// A "time ago" string in the same abbreviated style as SwiftUI's built-in
/// `Text(_:style: .relative)`, with two differences it doesn't offer:
/// seconds are only shown once they're the sole meaningful unit (under a
/// minute) rather than tagging along next to a larger unit like "3 min, 24
/// sec", and the result always ends in "ago" so it reads unambiguously on
/// its own rather than relying on surrounding words like "Seen".
enum RelativeTime {
    static func string(from date: Date, relativeTo reference: Date = .now) -> String {
        let elapsed = max(reference.timeIntervalSince(date), 0)
        let formatter = DateComponentsFormatter()
        formatter.unitsStyle = .abbreviated
        if elapsed < 60 {
            formatter.allowedUnits = [.second]
            formatter.maximumUnitCount = 1
        } else {
            formatter.allowedUnits = [.year, .month, .weekOfMonth, .day, .hour, .minute]
            formatter.maximumUnitCount = 2
        }
        let formatted = formatter.string(from: elapsed) ?? "0 sec"
        return "\(formatted) ago"
    }
}
