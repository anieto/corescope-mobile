@preconcurrency import CoreLocation

@MainActor
final class MapLocationManager: NSObject, @preconcurrency CLLocationManagerDelegate {
    private let locationManager = CLLocationManager()
    private var completion: ((CLLocationCoordinate2D) -> Void)?
    private var failure: (() -> Void)?

    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyKilometer
    }

    func requestCurrentLocation(
        completion: @escaping (CLLocationCoordinate2D) -> Void,
        failure: @escaping () -> Void
    ) {
        self.completion = completion
        self.failure = failure

        guard CLLocationManager.locationServicesEnabled() else {
            finishWithFailure()
            return
        }

        switch locationManager.authorizationStatus {
        case .notDetermined:
            locationManager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            locationManager.requestLocation()
        case .denied, .restricted:
            finishWithFailure()
        @unknown default:
            finishWithFailure()
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            manager.requestLocation()
        case .denied, .restricted:
            finishWithFailure()
        case .notDetermined:
            break
        @unknown default:
            finishWithFailure()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let coordinate = locations.last?.coordinate else { return }
        completion?(coordinate)
        completion = nil
        failure = nil
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        finishWithFailure()
    }

    private func finishWithFailure() {
        failure?()
        completion = nil
        failure = nil
    }
}
