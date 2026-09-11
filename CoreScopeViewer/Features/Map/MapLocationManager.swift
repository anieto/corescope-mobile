@preconcurrency import CoreLocation

@MainActor
final class MapLocationManager: NSObject, @preconcurrency CLLocationManagerDelegate {
    private let locationManager = CLLocationManager()
    private var completion: ((CLLocationCoordinate2D) -> Void)?
    private var failure: (() -> Void)?
    private var timeoutTask: Task<Void, Never>?

    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyHundredMeters
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

        if let cachedLocation = locationManager.location,
           Date().timeIntervalSince(cachedLocation.timestamp) < 5 * 60 {
            finish(with: cachedLocation.coordinate)
            return
        }

        switch locationManager.authorizationStatus {
        case .notDetermined:
            locationManager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            startLocationUpdate()
        case .denied, .restricted:
            finishWithFailure()
        @unknown default:
            finishWithFailure()
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard completion != nil else { return }

        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            startLocationUpdate()
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
        finish(with: coordinate)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        finishWithFailure()
    }

    private func startLocationUpdate() {
        locationManager.startUpdatingLocation()
        timeoutTask?.cancel()
        timeoutTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(12))
            guard !Task.isCancelled else { return }
            self?.finishWithFailure()
        }
    }

    private func finish(with coordinate: CLLocationCoordinate2D) {
        timeoutTask?.cancel()
        timeoutTask = nil
        locationManager.stopUpdatingLocation()
        completion?(coordinate)
        completion = nil
        failure = nil
    }

    private func finishWithFailure() {
        timeoutTask?.cancel()
        timeoutTask = nil
        locationManager.stopUpdatingLocation()
        failure?()
        completion = nil
        failure = nil
    }
}
