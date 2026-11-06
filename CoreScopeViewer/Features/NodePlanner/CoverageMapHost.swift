import SwiftUI
import MapKit

/// Build the immutable raster on a worker, using the completed study's execution identity.
/// A new identity is required whenever the study inputs or results change.
struct CoverageMapLayer {
    let executionID: UUID
    let raster: CoverageRaster
}

/// A map owned only by Estimated Coverage; it never installs or alters live-traffic overlays.
struct CoverageMapHost: UIViewRepresentable {
    let coordinate: CLLocationCoordinate2D?
    let tentative: CLLocationCoordinate2D?
    let nodes: [MeshNode]
    let style: MapDisplayStyle
    var layer: CoverageMapLayer? = nil
    let selectTentative: (CLLocationCoordinate2D) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(selectTentative: selectTentative) }

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        map.delegate = context.coordinator
        map.showsCompass = true
        map.showsScale = true
        map.addGestureRecognizer(UILongPressGestureRecognizer(
            target: context.coordinator, action: #selector(Coordinator.chooseLocation(_:))))
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        let coordinator = context.coordinator
        coordinator.selectTentative = selectTentative
        let mapType: MKMapType = switch style {
        case .standard: .standard
        case .imagery: .satellite
        case .hybrid: .hybrid
        }
        if map.mapType != mapType { map.mapType = mapType }
        coordinator.updatePins(map, coordinate: coordinate, tentative: tentative, nodes: nodes)
        coordinator.updateLayer(map, layer: layer)
    }

    static func dismantleUIView(_ map: MKMapView, coordinator: Coordinator) {
        coordinator.removeLayer(map)
        map.delegate = nil
        map.gestureRecognizers?.filter { $0 is UILongPressGestureRecognizer }.forEach(map.removeGestureRecognizer)
    }

    @MainActor
    final class Pin: MKPointAnnotation {
        let id: String
        let kind: Kind
        enum Kind { case node, transmitter, tentative }
        init(id: String, title: String, coordinate: CLLocationCoordinate2D, kind: Kind) {
            self.id = id
            self.kind = kind
            super.init()
            self.title = title
            self.coordinate = coordinate
            if kind == .node { subtitle = String(localized: "Not evaluated") }
        }
    }

    @MainActor
    final class Coordinator: NSObject, MKMapViewDelegate {
        var selectTentative: (CLLocationCoordinate2D) -> Void
        private var pins: [String: Pin] = [:]
        private var lastTransmitter: CLLocationCoordinate2D?
        private var centered = false
        private var executionID: UUID?
        private var overlay: CoverageMapKitOverlay?

        init(selectTentative: @escaping (CLLocationCoordinate2D) -> Void) {
            self.selectTentative = selectTentative
        }

        @objc func chooseLocation(_ gesture: UILongPressGestureRecognizer) {
            guard gesture.state == .began, let map = gesture.view as? MKMapView else { return }
            let point = map.convert(gesture.location(in: map), toCoordinateFrom: map)
            if CLLocationCoordinate2DIsValid(point) { selectTentative(point) }
        }

        func updatePins(_ map: MKMapView, coordinate: CLLocationCoordinate2D?,
                        tentative: CLLocationCoordinate2D?, nodes: [MeshNode]) {
            var desired = nodes.compactMap { node -> Pin? in
                guard let point = node.coordinate else { return nil }
                return Pin(id: "node:" + node.id, title: node.name ?? String(localized: "Known node"),
                           coordinate: point, kind: .node)
            }
            if let coordinate {
                desired.append(Pin(id: "transmitter", title: String(localized: "Transmitter"),
                                   coordinate: coordinate, kind: .transmitter))
            }
            if let tentative {
                desired.append(Pin(id: "tentative", title: String(localized: "Proposed location"),
                                   coordinate: tentative, kind: .tentative))
            }
            let ids = Set(desired.map(\.id))
            for id in Array(pins.keys) where !ids.contains(id) {
                if let pin = pins.removeValue(forKey: id) { map.removeAnnotation(pin) }
            }
            for pin in desired {
                if let existing = pins[pin.id] {
                    if !same(existing.coordinate, pin.coordinate) { existing.coordinate = pin.coordinate }
                    if existing.title != pin.title {
                        existing.title = pin.title
                        (map.view(for: existing) as? PinView)?.configure(existing)
                    }
                } else {
                    pins[pin.id] = pin
                    map.addAnnotation(pin)
                }
            }
            if let coordinate, lastTransmitter == nil || !same(lastTransmitter, coordinate) {
                map.setRegion(MKCoordinateRegion(center: coordinate,
                    latitudinalMeters: 20_000, longitudinalMeters: 20_000), animated: centered)
                centered = true
            } else if !centered && !desired.isEmpty {
                map.showAnnotations(desired, animated: false)
                centered = true
            }
            lastTransmitter = coordinate
        }

        private func same(_ left: CLLocationCoordinate2D?, _ right: CLLocationCoordinate2D) -> Bool {
            left?.latitude == right.latitude && left?.longitude == right.longitude
        }

        func updateLayer(_ map: MKMapView, layer: CoverageMapLayer?) {
            guard executionID != layer?.executionID else { return }
            removeLayer(map)
            guard let layer else { return }
            do {
                let newOverlay = try CoverageMapKitOverlay(raster: layer.raster, cacheBytes: 8 * 1024 * 1024)
                executionID = layer.executionID
                overlay = newOverlay
                map.addOverlay(newOverlay, level: .aboveRoads)
            } catch {
                // The adapter's only initialization failure is an invalid constant cache capacity.
                assertionFailure("Invalid coverage overlay cache capacity: \(error)")
            }
        }

        func removeLayer(_ map: MKMapView) {
            overlay?.invalidate()
            if let overlay { map.removeOverlay(overlay) }
            overlay = nil
            executionID = nil
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let tiles = overlay as? CoverageMapKitOverlay {
                return MKTileOverlayRenderer(tileOverlay: tiles)
            }
            return MKOverlayRenderer(overlay: overlay)
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            guard let pin = annotation as? Pin else { return nil }
            let view = (mapView.dequeueReusableAnnotationView(withIdentifier: "coverage-pin") as? PinView)
                ?? PinView(annotation: pin, reuseIdentifier: "coverage-pin")
            view.configure(pin)
            return view
        }
    }

    @MainActor
    final class PinView: MKAnnotationView {
        private let glyph = UIImageView()
        private let name = UILabel()

        override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
            super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
            addSubview(glyph)
            addSubview(name)
            glyph.contentMode = .scaleAspectFit
            name.textAlignment = .center
            name.numberOfLines = 2
            name.adjustsFontForContentSizeCategory = true
            name.backgroundColor = .secondarySystemBackground
            name.layer.cornerRadius = 4
            name.clipsToBounds = true
            isAccessibilityElement = true
            collisionMode = .rectangle
        }

        required init?(coder: NSCoder) { return nil }

        func configure(_ pin: Pin) {
            annotation = pin
            name.text = pin.title
            name.font = pin.kind == .node ? AppFont.uiFont(.caption1) : .preferredFont(forTextStyle: .caption1)
            glyph.image = UIImage(systemName: pin.kind == .node ? "questionmark.circle.fill" :
                pin.kind == .transmitter ? "antenna.radiowaves.left.and.right.circle.fill" : "mappin.circle.fill")
            glyph.tintColor = switch pin.kind {
            case .node: .systemGray
            case .transmitter: .systemOrange
            case .tentative: .systemBlue
            }
            accessibilityLabel = [pin.title, pin.subtitle].compactMap { $0 }.joined(separator: ", ")
            updateSize()
            setNeedsLayout()
        }

        private func updateSize() {
            let size = name.sizeThatFits(CGSize(width: 180, height: CGFloat.greatestFiniteMagnitude))
            bounds.size = CGSize(width: max(34, size.width + 8), height: 38 + size.height)
            centerOffset = CGPoint(x: 0, y: bounds.height / 2 - 15)
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            updateSize()
            glyph.frame = CGRect(x: (bounds.width - 30) / 2, y: 0, width: 30, height: 30)
            name.frame = CGRect(x: 0, y: 34, width: bounds.width, height: bounds.height - 34)
        }
    }
}
