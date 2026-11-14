import SwiftUI
import MapKit

/// Build the immutable raster on a worker, using the completed study's execution identity.
/// A new identity is required whenever the study inputs or results change.
struct CoverageMapLayer {
    let executionID: UUID
    let raster: CoverageRaster
    var budget: CoverageLinkBudget? = nil
}

/// A map owned only by Estimated Coverage; it never installs or alters live-traffic overlays.
struct CoverageMapHost: UIViewRepresentable {
    let coordinate: CLLocationCoordinate2D?
    let nodes: [MeshNode]
    let style: MapDisplayStyle
    var layer: CoverageMapLayer? = nil
    var nodeStatuses: [String: CoverageNodeStatus] = [:]
    /// Where the map opens before anything is placed: the live map's region, when known.
    var openingRegion: MKCoordinateRegion? = nil
    /// How far the panel over the map's bottom edge reaches up; the camera keeps clear of it.
    var bottomInset: CGFloat = 0
    var placementRequest: UUID? = nil
    var placementSettled: (UUID) -> Void = { _ in }
    /// A touch & hold on the map, with the known node whose marker was held, if any.
    let place: (CLLocationCoordinate2D, MeshNode?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(place: place) }

    func makeUIView(context: Context) -> MKMapView {
        let map = TracedMapView()
        map.trace = context.coordinator.trace
        context.coordinator.trace.lifecycle("map created")
        map.delegate = context.coordinator
        // The style menu takes the compass's corner; two-finger rotation still works.
        map.showsCompass = false
        map.showsScale = true
        let hold = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.chooseLocation(_:)))
        hold.minimumPressDuration = 0.5
        map.addGestureRecognizer(hold)
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.inspectSample(_:)))
        tap.cancelsTouchesInView = false
        tap.require(toFail: hold)
        map.addGestureRecognizer(tap)
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        let coordinator = context.coordinator
        coordinator.trace.record("update layer=\(layer?.executionID.uuidString ?? "none") size=\(map.bounds.size) window=\(map.window != nil)")
        coordinator.placementSettled = placementSettled
        if coordinator.lastPlacementRequest != placementRequest {
            coordinator.lastPlacementRequest = placementRequest
            coordinator.pendingPlacement = placementRequest
            coordinator.needsPlacementFrame = placementRequest != nil
        }
        coordinator.place = place
        coordinator.bottomInset = bottomInset
        let mapType: MKMapType = switch style {
        case .standard: .standard
        case .imagery: .satellite
        case .hybrid: .hybrid
        }
        if map.mapType != mapType { map.mapType = mapType }
        coordinator.updateLayer(map, layer: layer)
        coordinator.updatePins(map, coordinate: coordinate, nodes: nodes, statuses: nodeStatuses, openingRegion: openingRegion)
    }

    static func dismantleUIView(_ map: MKMapView, coordinator: Coordinator) {
        coordinator.trace.lifecycle("map dismantled")
        coordinator.removeLayer(map)
        map.delegate = nil
        map.gestureRecognizers?.filter { $0 is UILongPressGestureRecognizer }.forEach(map.removeGestureRecognizer)
    }

    @MainActor
    final class Pin: MKPointAnnotation {
        let id: String
        let kind: Kind
        var nodeState: CoverageNodeState = .notEvaluated
        var marginDb: Double?
        var role = ""
        enum Kind { case node, transmitter, sample }
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
        let trace = CoverageRenderTrace()
        var place: (CLLocationCoordinate2D, MeshNode?) -> Void
        var lastPlacementRequest: UUID?
        var pendingPlacement: UUID?
        var needsPlacementFrame = false
        var placementSettled: (UUID) -> Void = { _ in }
        var bottomInset: CGFloat = 0
        private var pins: [String: Pin] = [:]
        private var nodesByPin: [String: MeshNode] = [:]
        private var lastTransmitter: CLLocationCoordinate2D?
        private var centered = false
        private var executionID: UUID?
        private var overlay: CoverageMapKitOverlay?
        private weak var renderer: CoverageRasterRenderer?
        private var layer: CoverageMapLayer?
        private var inspectionPin: Pin?
        private var sampleDetails: UIView?

        init(place: @escaping (CLLocationCoordinate2D, MeshNode?) -> Void) {
            self.place = place
        }

        @objc func inspectSample(_ gesture: UITapGestureRecognizer) {
            guard gesture.state == .ended, let map = gesture.view as? MKMapView, let layer else { return }
            let location = gesture.location(in: map)
            if let sampleDetails, sampleDetails.frame.contains(location) { return }
            // Let MapKit open known-node callouts without placing a second annotation.
            guard !pins.values.contains(where: { map.view(for: $0)?.frame.contains(location) ?? false }) else { return }
            if let inspectionPin, map.view(for: inspectionPin)?.frame.contains(location) == true { return }
            sampleDetails?.removeFromSuperview()
            sampleDetails = nil
            if let inspectionPin { map.removeAnnotation(inspectionPin) }
            inspectionPin = nil
            let coordinate = map.convert(location, toCoordinateFrom: map)
            let sample = layer.raster.sample(latitude: coordinate.latitude, longitude: coordinate.longitude)
            guard sample.state != .outsideAnalysis else { return }
            let pin = Pin(id: "sample", title: String(localized: "Nearby grid sample"),
                          coordinate: coordinate, kind: .sample)
            pin.marginDb = sample.marginDb
            pin.nodeState = sample.marginDb.map { $0 >= 0 ? .inCoverage : .belowThreshold } ?? .unknown
            if let loss = sample.lossDb, let budget = layer.budget,
               let reception = try? budget.reception(pathLossDb: loss) {
                pin.subtitle = String(localized: "Predicted RX") + String(format: " %.1f dBm · ", reception.receiveLevelDbm)
                    + String(localized: "Margin after reserve") + String(format: " %.1f dB", reception.adjustedMarginDb)
            } else {
                pin.subtitle = String(localized: "No usable prediction")
            }
            let unit = DistanceUnit(rawValue: UserDefaults.standard.string(forKey: DistanceUnit.defaultsKey) ?? "") ?? .imperial
            pin.subtitle = (pin.subtitle ?? "") + " · " + String(localized: "Grid spacing")
                + " " + unit.format(kilometers: Double(layer.raster.grid.spacingMeters) / 1000)
            inspectionPin = pin
            map.addAnnotation(pin)
            showSampleDetails(pin, on: map)
        }

        /// Places the transmitter where the finger is; on a known node's marker, on that node.
        @objc func chooseLocation(_ gesture: UILongPressGestureRecognizer) {
            guard gesture.state == .began, let map = gesture.view as? MKMapView else { return }
            let location = gesture.location(in: map)
            if let held = pins.values.first(where: { pin in
                pin.kind == .node && (map.view(for: pin)?.frame.contains(location) ?? false)
            }), let node = nodesByPin[held.id] {
                place(held.coordinate, node)
                return
            }
            let point = map.convert(location, toCoordinateFrom: map)
            if CLLocationCoordinate2DIsValid(point) { place(point, nil) }
        }

        func updatePins(_ map: MKMapView, coordinate: CLLocationCoordinate2D?, nodes: [MeshNode],
                        statuses: [String: CoverageNodeStatus], openingRegion: MKCoordinateRegion?) {
            nodesByPin = [:]
            var desired = nodes.compactMap { node -> Pin? in
                guard let point = node.coordinate else { return nil }
                let pin = Pin(id: "node:" + node.id, title: node.name ?? String(localized: "Known node"),
                           coordinate: point, kind: .node)
                pin.role = node.role
                nodesByPin[pin.id] = node
                if let status = statuses[node.id] {
                    pin.nodeState = status.state
                    pin.marginDb = status.adjustedMarginDb
                    pin.subtitle = status.coverageLabel
                    if let margin = status.adjustedMarginDb {
                        pin.subtitle = status.coverageLabel + String(format: " · %.1f dB", margin)
                        if let budget = layer?.budget {
                            pin.subtitle = (pin.subtitle ?? "") + String(format: " · RX %.1f dBm", margin + budget.sensitivityDbm + budget.reserveDb)
                        }
                    }
                }
                return pin
            }
            if let coordinate {
                desired.append(Pin(id: "transmitter", title: String(localized: "Transmitter"),
                                   coordinate: coordinate, kind: .transmitter))
            }
            let ids = Set(desired.map(\.id))
            for id in Array(pins.keys) where !ids.contains(id) {
                if let pin = pins.removeValue(forKey: id) { map.removeAnnotation(pin) }
            }
            for pin in desired {
                if let existing = pins[pin.id] {
                    if !same(existing.coordinate, pin.coordinate) { existing.coordinate = pin.coordinate }
                    if existing.title != pin.title || existing.subtitle != pin.subtitle || existing.nodeState != pin.nodeState || existing.marginDb != pin.marginDb || existing.role != pin.role {
                        existing.subtitle = pin.subtitle
                        existing.nodeState = pin.nodeState
                        existing.marginDb = pin.marginDb
                        existing.role = pin.role
                        existing.title = pin.title
                        (map.view(for: existing) as? PinView)?.configure(existing)
                    }
                } else {
                    pins[pin.id] = pin
                    map.addAnnotation(pin)
                }
            }
            if layer != nil {
                // The result owns the initial camera; do not replace its extent with the pin's default zoom.
                centered = true
            } else if let coordinate, lastTransmitter == nil || !same(lastTransmitter, coordinate) || needsPlacementFrame {
                needsPlacementFrame = false
                let animated = centered && map.window != nil && !UIAccessibility.isReduceMotionEnabled
                frame(map, center: coordinate, meters: 20_000, animated: animated)
                if !animated { finishPlacement() }
                centered = true
            } else if !centered, let openingRegion {
                map.setRegion(openingRegion, animated: false)
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

        /// Frames a square around the center, keeping it clear of the controls above and the
        /// panel below instead of under them.
        private func frame(_ map: MKMapView, center: CLLocationCoordinate2D, meters: Double, animated: Bool) {
            trace.record("camera frame meters=\(meters) size=\(map.bounds.size) bottomInset=\(bottomInset) animated=\(animated)")
            let region = MKCoordinateRegion(center: center, latitudinalMeters: meters, longitudinalMeters: meters)
            let padding = UIEdgeInsets(top: 72, left: 16, bottom: bottomInset + 16, right: 16)
            map.setVisibleMapRect(Self.mapRect(region), edgePadding: padding, animated: animated)
        }

        static func mapRect(_ region: MKCoordinateRegion) -> MKMapRect {
            let topLeft = MKMapPoint(CLLocationCoordinate2D(
                latitude: region.center.latitude + region.span.latitudeDelta / 2,
                longitude: region.center.longitude - region.span.longitudeDelta / 2))
            let bottomRight = MKMapPoint(CLLocationCoordinate2D(
                latitude: region.center.latitude - region.span.latitudeDelta / 2,
                longitude: region.center.longitude + region.span.longitudeDelta / 2))
            return MKMapRect(x: min(topLeft.x, bottomRight.x), y: min(topLeft.y, bottomRight.y),
                             width: abs(bottomRight.x - topLeft.x), height: abs(bottomRight.y - topLeft.y))
        }

        func updateLayer(_ map: MKMapView, layer: CoverageMapLayer?) {
            guard executionID != layer?.executionID else { return }
            removeLayer(map)
            guard let layer else { return }
            self.layer = layer
            do {
                let id = layer.executionID
                let newOverlay = try CoverageMapKitOverlay(raster: layer.raster, cacheBytes: 8 * 1024 * 1024, trace: trace)
                executionID = layer.executionID
                overlay = newOverlay
                trace.record("install overlay execution=\(id) size=\(map.bounds.size) window=\(map.window != nil)")
                map.addOverlay(newOverlay, level: .aboveRoads)
                frame(map, center: CLLocationCoordinate2D(latitude: layer.raster.transmitter.latitude,
                    longitude: layer.raster.transmitter.longitude), meters: Double(layer.raster.grid.radiusMeters) * 2.4, animated: centered && map.window != nil)
            } catch {
                // The adapter's only initialization failure is an invalid constant cache capacity.
                assertionFailure("Invalid coverage overlay cache capacity: \(error)")
            }
        }

        func removeLayer(_ map: MKMapView) {
            trace.lifecycle("remove layer execution=\(executionID?.uuidString ?? "none")")
            sampleDetails?.removeFromSuperview()
            sampleDetails = nil
            if let inspectionPin { map.removeAnnotation(inspectionPin) }
            inspectionPin = nil
            layer = nil
            renderer?.invalidate()
            renderer = nil
            overlay?.invalidate()
            if let overlay { map.removeOverlay(overlay) }
            overlay = nil
            executionID = nil
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let tiles = overlay as? CoverageMapKitOverlay {
                trace.record("renderer created size=\(mapView.bounds.size) window=\(mapView.window != nil)")
                let renderer = CoverageRasterRenderer(source: tiles, trace: trace)
                self.renderer = renderer
                return renderer
            }
            return MKOverlayRenderer(overlay: overlay)
        }

        private func finishPlacement() {
            guard let request = pendingPlacement else { return }
            pendingPlacement = nil
            // Do not publish SwiftUI state inside updateUIView.
            DispatchQueue.main.async { [weak self] in self?.placementSettled(request) }
        }

        func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
            finishPlacement()
            trace.record("region settled size=\(mapView.bounds.size) window=\(mapView.window != nil)")
        }

        func mapViewDidFinishLoadingMap(_ mapView: MKMapView) {
            trace.record("map loaded size=\(mapView.bounds.size)")
        }

        func mapViewDidFinishRenderingMap(_ mapView: MKMapView, fullyRendered: Bool) {
            trace.record("map rendered fully=\(fullyRendered) size=\(mapView.bounds.size)")
        }

        private func showSampleDetails(_ pin: Pin, on map: MKMapView) {
            sampleDetails?.removeFromSuperview()
            let card = UIVisualEffectView(effect: UIBlurEffect(style: .systemMaterial))
            card.layer.cornerRadius = 16
            card.clipsToBounds = true
            card.translatesAutoresizingMaskIntoConstraints = false
            let title = UILabel()
            title.text = pin.title
            title.font = AppFont.uiFont(.headline)
            title.adjustsFontForContentSizeCategory = true
            title.numberOfLines = 0
            let detail = UILabel()
            detail.text = pin.subtitle?.replacingOccurrences(of: " · ", with: "\n")
            detail.font = .preferredFont(forTextStyle: .subheadline)
            detail.adjustsFontForContentSizeCategory = true
            detail.numberOfLines = 0
            let close = UIButton(type: .system)
            close.setImage(UIImage(systemName: "xmark.circle.fill"), for: .normal)
            close.accessibilityLabel = String(localized: "Close sample details")
            close.addAction(UIAction { [weak self, weak map] _ in
                guard let self else { return }
                self.sampleDetails?.removeFromSuperview()
                self.sampleDetails = nil
                if let pin = self.inspectionPin { map?.removeAnnotation(pin) }
                self.inspectionPin = nil
            }, for: .touchUpInside)
            close.widthAnchor.constraint(equalToConstant: 44).isActive = true
            close.heightAnchor.constraint(equalToConstant: 44).isActive = true
            let header = UIStackView(arrangedSubviews: [title, close])
            header.spacing = 8
            let stack = UIStackView(arrangedSubviews: [header, detail])
            stack.axis = .vertical
            stack.spacing = 4
            stack.translatesAutoresizingMaskIntoConstraints = false
            card.contentView.addSubview(stack)
            map.addSubview(card)
            NSLayoutConstraint.activate([
                // The compact workspace reserves its result panel in the map's safe area.
                // Anchor here so the card follows that panel and window-size changes.
                card.bottomAnchor.constraint(equalTo: map.safeAreaLayoutGuide.bottomAnchor, constant: -12),
                card.leadingAnchor.constraint(equalTo: map.safeAreaLayoutGuide.leadingAnchor, constant: 16),
                card.trailingAnchor.constraint(lessThanOrEqualTo: map.safeAreaLayoutGuide.trailingAnchor, constant: -16),
                card.widthAnchor.constraint(lessThanOrEqualToConstant: 320),
                stack.leadingAnchor.constraint(equalTo: card.contentView.leadingAnchor, constant: 12),
                stack.trailingAnchor.constraint(equalTo: card.contentView.trailingAnchor, constant: -12),
                stack.topAnchor.constraint(equalTo: card.contentView.topAnchor, constant: 8),
                stack.bottomAnchor.constraint(equalTo: card.contentView.bottomAnchor, constant: -12)
            ])
            sampleDetails = card
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
    final class TracedMapView: MKMapView {
        var trace: CoverageRenderTrace?
        private var lastSize: CGSize?

        // Presentation animations can propose many subpixel sizes. MapKit forces a
        // render on each size change; keep its drawable size stable within a pixel.
        override var frame: CGRect {
            get { super.frame }
            set {
                let scale = max(1, traitCollection.displayScale)
                var aligned = newValue
                aligned.size.width = (newValue.width * scale).rounded() / scale
                aligned.size.height = (newValue.height * scale).rounded() / scale
                super.frame = aligned
            }
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            if lastSize != bounds.size {
                lastSize = bounds.size
                trace?.lifecycle("layout size=\(bounds.size) safeInsets=\(safeAreaInsets) window=\(window != nil)")
            }
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            trace?.lifecycle("window changed attached=\(window != nil) size=\(bounds.size)")
        }
    }

    @MainActor
    final class PinView: MKMarkerAnnotationView {
        override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
            super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
            isAccessibilityElement = true
            canShowCallout = true
            collisionMode = .rectangle
            titleVisibility = .adaptive
            subtitleVisibility = .adaptive
        }

        required init?(coder: NSCoder) { return nil }

        func configure(_ pin: Pin) {
            annotation = pin
            canShowCallout = pin.kind != .sample
            titleVisibility = pin.kind == .sample ? .hidden : .adaptive
            subtitleVisibility = pin.kind == .sample ? .hidden : .adaptive
            glyphImage = UIImage(systemName: pin.kind == .node
                ? NodeRoleStyle.symbolName(for: pin.role)
                : pin.kind == .sample ? "scope" : "antenna.radiowaves.left.and.right")
            glyphTintColor = .white
            markerTintColor = switch pin.kind {
            case .node, .sample:
                switch pin.nodeState {
                case .inCoverage: (pin.marginDb ?? 0) <= 10 ? .systemOrange : .systemGreen
                case .belowThreshold: .systemRed
                case .notEvaluated: UIColor(NodeScopeStyle.signal)
                case .unknown, .stale: .systemGray
                }
            case .transmitter: .systemPurple
            }
            displayPriority = pin.kind == .transmitter ? .required : .defaultHigh
            accessibilityLabel = [pin.title, pin.subtitle].compactMap { $0 }.joined(separator: ", ")
        }
    }
}
