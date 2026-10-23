@preconcurrency import CoreLocation
import Observation

struct FieldLocationSample: Equatable, Sendable {
    let coordinate: FieldCoordinate
    let timestamp: Date
    let horizontalAccuracyMeters: Double
}

enum FieldLocationStatus: Equatable, Sendable {
    case idle
    case requestingPermission
    case locating
    case active
    case denied
    case unavailable
    case failed(String)
}

@MainActor
@Observable
final class FieldLocationSession: NSObject, @preconcurrency CLLocationManagerDelegate {
    private(set) var sample: FieldLocationSample?
    private(set) var status: FieldLocationStatus = .idle

    private let manager: CLLocationManager
    private var wantsUpdates = false

    init(manager: CLLocationManager = CLLocationManager()) {
        self.manager = manager
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 5
    }

    func start() {
        wantsUpdates = true
        guard CLLocationManager.locationServicesEnabled() else {
            status = .unavailable
            return
        }

        switch manager.authorizationStatus {
        case .notDetermined:
            status = .requestingPermission
            manager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            beginUpdates()
        case .denied, .restricted:
            status = .denied
        @unknown default:
            status = .unavailable
        }
    }

    func stop() {
        wantsUpdates = false
        manager.stopUpdatingLocation()
        if status == .locating || status == .active {
            status = .idle
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard wantsUpdates else { return }
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            beginUpdates()
        case .denied, .restricted:
            status = .denied
        case .notDetermined:
            status = .requestingPermission
        @unknown default:
            status = .unavailable
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard wantsUpdates,
              let location = locations.last,
              location.horizontalAccuracy >= 0,
              let coordinate = FieldCoordinate(
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude
              ) else { return }

        sample = FieldLocationSample(
            coordinate: coordinate,
            timestamp: location.timestamp,
            horizontalAccuracyMeters: location.horizontalAccuracy
        )
        status = .active
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        guard wantsUpdates else { return }
        if let locationError = error as? CLError, locationError.code == .denied {
            status = .denied
        } else {
            status = .failed(error.localizedDescription)
        }
    }

    private func beginUpdates() {
        status = sample == nil ? .locating : .active
        manager.startUpdatingLocation()
    }
}
