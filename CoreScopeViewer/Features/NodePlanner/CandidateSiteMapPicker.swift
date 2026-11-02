import SwiftUI
import MapKit

/// Tentative map coordinates are local to this sheet until explicitly confirmed.
struct CandidateSiteMapPicker: View {
    let confirm: (Double, Double) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var position: MapCameraPosition
    @State private var selected: CLLocationCoordinate2D?
    @AppStorage(MapDisplayStyle.defaultsKey) private var style = MapDisplayStyle.standard

    init(latitude: Double?, longitude: Double?, confirm: @escaping (Double, Double) -> Void) {
        self.confirm = confirm
        let coordinate: CLLocationCoordinate2D?
        if let latitude, let longitude, latitude.isFinite, longitude.isFinite,
           (-90...90).contains(latitude), (-180...180).contains(longitude) {
            coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude == 180 ? -180 : longitude)
        } else { coordinate = nil }
        _selected = State(initialValue: coordinate)
        _position = State(initialValue: .region(MKCoordinateRegion(
            center: coordinate ?? CLLocationCoordinate2D(latitude: 0, longitude: 0),
            span: MKCoordinateSpan(latitudeDelta: coordinate == nil ? 150 : 0.05,
                                   longitudeDelta: coordinate == nil ? 300 : 0.05))))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Text("Touch and hold to place a tentative pin. Pan and zoom to find the site.")
                    .font(.subheadline).padding()
                MapReader { proxy in
                    Map(position: $position) {
                        if let selected {
                            Annotation("Proposed site", coordinate: selected) {
                                VStack(spacing: 3) {
                                    Text("Proposed site").font(.monaspace(.caption))
                                        .padding(4).background(.regularMaterial, in: Capsule())
                                    Image(systemName: "mappin.circle.fill").font(.title).foregroundStyle(.orange)
                                }.accessibilityLabel("Tentative site")
                            }
                        }
                    }
                    .mapStyle(style.style)
                    .mapControls { MapCompass(); MapScaleView() }
                    .gesture(LinkProfileMapLongPress(action: { point in
                        guard let coordinate = proxy.convert(point, from: .local),
                              CLLocationCoordinate2DIsValid(coordinate) else { return }
                        selected = CLLocationCoordinate2D(latitude: coordinate.latitude,
                            longitude: coordinate.longitude == 180 ? -180 : coordinate.longitude)
                    }, ended: {}))
                }
                VStack(spacing: 8) {
                    if let selected {
                        Text("\(selected.latitude.formatted()), \(selected.longitude.formatted())")
                            .font(.monaspace(.subheadline))
                    } else { Text("No location selected").foregroundStyle(.secondary) }
                    Text("Use coordinates updates the site draft. Apply the site, then Save plan to keep it.")
                        .font(.caption).foregroundStyle(.secondary)
                }.padding()
            }
            .background(Color(uiColor: .systemBackground))
            .navigationTitle("Choose site location").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Use coordinates") {
                        if let selected { confirm(selected.latitude, selected.longitude); dismiss() }
                    }.disabled(selected == nil)
                }
                ToolbarItem(placement: .secondaryAction) {
                    Picker("Map style", selection: $style) {
                        ForEach(MapDisplayStyle.allCases) { option in Text(option.title).tag(option) }
                    }
                }
            }
        }
    }
}
