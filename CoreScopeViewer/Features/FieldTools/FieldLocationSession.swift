@preconcurrency import CoreLocation
import CoreMotion
import Observation
import simd

struct FieldLocationSample: Equatable, Sendable {
    let coordinate: FieldCoordinate
    let timestamp: Date
    let horizontalAccuracyMeters: Double
}

struct FieldHeadingSample: Equatable, Sendable {
    let trueHeadingDegrees: Double
    let accuracyDegrees: Double
    let timestamp: Date
}

enum FieldHeadingStatus: Equatable, Sendable {
    case idle
    case unavailable
    case calibrating
    case active
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
    private(set) var headingSample: FieldHeadingSample?
    private(set) var headingStatus: FieldHeadingStatus = .idle

    private let manager: CLLocationManager
    private let motion = CMMotionManager()
    private var wantsUpdates = false
    /// Compass accuracy from Core Location; Core Motion's attitude doesn't report one.
    private var compassAccuracyDegrees: Double?
    /// Whether the attitude matrix rows hold the device axes, learned from gravity once the phone tilts.
    private var deviceAxesUseMatrixRows: Bool?
    private var motionProvidesHeading = false

    init(manager: CLLocationManager = CLLocationManager()) {
        self.manager = manager
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 5
        manager.headingFilter = 2
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
        manager.stopUpdatingHeading()
        motion.stopDeviceMotionUpdates()
        headingSample = nil
        compassAccuracyDegrees = nil
        motionProvidesHeading = false
        headingStatus = .idle
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

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        guard wantsUpdates,
              newHeading.trueHeading >= 0,
              newHeading.headingAccuracy >= 0 else { return }

        compassAccuracyDegrees = newHeading.headingAccuracy
        // Core Location measures the top edge of the phone, which is only meaningful while the phone
        // is roughly flat. Prefer the motion-based heading once it is running.
        if motionProvidesHeading { return }
        headingSample = FieldHeadingSample(
            trueHeadingDegrees: newHeading.trueHeading,
            accuracyDegrees: newHeading.headingAccuracy,
            timestamp: newHeading.timestamp
        )
        headingStatus = .active
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
        if CLLocationManager.headingAvailable() {
            headingStatus = headingSample == nil ? .calibrating : .active
            manager.startUpdatingHeading()
            startDeviceMotion()
        } else {
            headingStatus = .unavailable
        }
    }

    /// Follows the direction the phone is pointing whether it is held flat or upright, like the
    /// Compass app, instead of Core Location's top-edge heading.
    private func startDeviceMotion() {
        guard motion.isDeviceMotionAvailable,
              !motion.isDeviceMotionActive,
              CMMotionManager.availableAttitudeReferenceFrames().contains(.xTrueNorthZVertical) else { return }
        motion.deviceMotionUpdateInterval = 1.0 / 15
        motion.startDeviceMotionUpdates(using: .xTrueNorthZVertical, to: .main) { [weak self] deviceMotion, _ in
            guard let deviceMotion else { return }
            let matrix = deviceMotion.attitude.rotationMatrix
            let rows = [
                SIMD3(matrix.m11, matrix.m12, matrix.m13),
                SIMD3(matrix.m21, matrix.m22, matrix.m23),
                SIMD3(matrix.m31, matrix.m32, matrix.m33),
            ]
            let gravity = SIMD3(deviceMotion.gravity.x, deviceMotion.gravity.y, deviceMotion.gravity.z)
            let timestamp = Date()
            MainActor.assumeIsolated {
                self?.updateHeading(rows: rows, gravity: gravity, timestamp: timestamp)
            }
        }
    }

    private func updateHeading(rows: [SIMD3<Double>], gravity: SIMD3<Double>, timestamp: Date) {
        guard wantsUpdates else { return }
        if deviceAxesUseMatrixRows == nil {
            deviceAxesUseMatrixRows = FieldGeometry.deviceAxesUseMatrixRows(rows: rows, gravity: gravity)
        }
        // Until the phone has tilted enough to tell the matrix layout apart, keep Core Location's heading.
        guard let useRows = deviceAxesUseMatrixRows, let compassAccuracyDegrees else { return }
        let axes = useRows
            ? rows
            : (0..<3).map { index in SIMD3(rows[0][index], rows[1][index], rows[2][index]) }
        guard let heading = FieldGeometry.pointingHeadingDegrees(
            deviceTopAxis: axes[1],
            deviceScreenAxis: axes[2]
        ) else { return }
        motionProvidesHeading = true
        // Skip sub-degree changes so the view isn't redrawn fifteen times a second for nothing.
        if let current = headingSample,
           abs(FieldGeometry.relativeHeadingRotation(trueBearingDegrees: heading, deviceTrueHeadingDegrees: current.trueHeadingDegrees)
               .map { $0 > 180 ? 360 - $0 : $0 } ?? 0) < 1 {
            return
        }
        headingSample = FieldHeadingSample(
            trueHeadingDegrees: heading,
            accuracyDegrees: compassAccuracyDegrees,
            timestamp: timestamp
        )
        headingStatus = .active
    }
}
