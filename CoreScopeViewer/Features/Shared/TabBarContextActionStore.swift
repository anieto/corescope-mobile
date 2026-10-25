import Observation
import SwiftUI

@MainActor
@Observable
final class TabBarContextActionStore {
    enum Kind: Equatable {
        case back
        case close

        var title: LocalizedStringResource {
            switch self {
            case .back: "Back"
            case .close: "Close"
            }
        }

        var systemImage: String {
            switch self {
            case .back: "chevron.backward"
            case .close: "xmark"
            }
        }
    }

    private(set) var kind: Kind?
    @ObservationIgnored private var action: (() -> Void)?

    func set(_ kind: Kind, action: @escaping () -> Void) {
        self.kind = kind
        self.action = action
    }

    func clear() {
        kind = nil
        action = nil
    }

    func perform() {
        action?()
    }
}
