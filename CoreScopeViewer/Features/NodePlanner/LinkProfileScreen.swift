import Observation
import SwiftUI

struct LinkProfileScreen: View {
    @State private var model = LinkProfileModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                LinkProfileIntroduction()
                LinkProfileEndpointLayout(model: model)
                LinkProfileRadioSettings(model: model)
                LinkProfilePrivacyNotice()
                LinkProfileAction(model: model)
                if let result = model.result {
                    LinkProfileResultCard(result: result)
                }
            }
            .frame(maxWidth: 900, alignment: .leading)
            .padding()
            .frame(maxWidth: .infinity)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("Link Profile")
        .navigationBarTitleDisplayMode(.inline)
    }
}

@MainActor
@Observable
private final class LinkProfileModel {
    var latitudeA = 30.2672
    var longitudeA = -97.7431
    var latitudeB = 29.4241
    var longitudeB = -98.4936
    var heightA = 10.0
    var heightB = 10.0
    var frequencyMHz = 915.0
    var isAnalyzing = false
    var result: NodePlannerLinkAnalysis?
    var errorMessage: LocalizedStringResource?

    private let analyzer: NodePlannerLinkAnalyzer

    init(analyzer: NodePlannerLinkAnalyzer = NodePlannerLinkAnalyzer(
        elevationProvider: OpenMeteoElevationProvider()
    )) {
        self.analyzer = analyzer
    }

    func analyze() async {
        guard !isAnalyzing else { return }
        isAnalyzing = true
        result = nil
        errorMessage = nil
        defer { isAnalyzing = false }

        do {
            let pointA = try NodePlannerCoordinate(latitude: latitudeA, longitude: longitudeA)
            let pointB = try NodePlannerCoordinate(latitude: latitudeB, longitude: longitudeB)
            result = try await analyzer.analyze(
                NodePlannerLinkAnalysisRequest(
                    pointA: pointA,
                    pointB: pointB,
                    heightAMeters: heightA,
                    heightBMeters: heightB,
                    frequencyMHz: frequencyMHz,
                    refractionK: 4.0 / 3.0,
                    linkBudget: nil,
                    sampleCount: nil
                )
            )
        } catch is CancellationError {
            errorMessage = "Analysis was cancelled."
        } catch NodePlannerElevationError.invalidCoordinate {
            errorMessage = "Enter valid latitude and longitude values."
        } catch NodePlannerElevationError.unavailable {
            errorMessage = "Elevation data is unavailable. Try again when you have a connection."
        } catch {
            errorMessage = "The link could not be analyzed with these settings."
        }
    }
}

private struct LinkProfileIntroduction: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("Estimate a path before deployment", systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                .font(.title2.bold())
            Text("Compare terrain clearance and modeled path loss between any two locations. Results are planning estimates, not proof that a radio link will work.")
                .foregroundStyle(.secondary)
        }
    }
}

private struct LinkProfileEndpointLayout: View {
    @Bindable var model: LinkProfileModel

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: 16) {
                LinkProfileEndpointCard(
                    title: "Point A",
                    latitude: $model.latitudeA,
                    longitude: $model.longitudeA,
                    height: $model.heightA
                )
                LinkProfileEndpointCard(
                    title: "Point B",
                    latitude: $model.latitudeB,
                    longitude: $model.longitudeB,
                    height: $model.heightB
                )
            }
            VStack(spacing: 16) {
                LinkProfileEndpointCard(
                    title: "Point A",
                    latitude: $model.latitudeA,
                    longitude: $model.longitudeA,
                    height: $model.heightA
                )
                LinkProfileEndpointCard(
                    title: "Point B",
                    latitude: $model.latitudeB,
                    longitude: $model.longitudeB,
                    height: $model.heightB
                )
            }
        }
    }
}

private struct LinkProfileEndpointCard: View {
    let title: LocalizedStringResource
    @Binding var latitude: Double
    @Binding var longitude: Double
    @Binding var height: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.headline)
            LabeledContent("Latitude") {
                TextField("Latitude", value: $latitude, format: .number.precision(.fractionLength(0...6)))
                    .multilineTextAlignment(.trailing)
                    .keyboardType(.numbersAndPunctuation)
            }
            LabeledContent("Longitude") {
                TextField("Longitude", value: $longitude, format: .number.precision(.fractionLength(0...6)))
                    .multilineTextAlignment(.trailing)
                    .keyboardType(.numbersAndPunctuation)
            }
            LabeledContent("Antenna height") {
                HStack(spacing: 4) {
                    TextField("Height", value: $height, format: .number.precision(.fractionLength(0...1)))
                        .multilineTextAlignment(.trailing)
                        .keyboardType(.decimalPad)
                    Text("m")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.background, in: RoundedRectangle(cornerRadius: 16))
    }
}

private struct LinkProfileRadioSettings: View {
    @Bindable var model: LinkProfileModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Radio settings")
                .font(.headline)
            LabeledContent("Frequency") {
                HStack(spacing: 4) {
                    TextField("Frequency", value: $model.frequencyMHz, format: .number.precision(.fractionLength(0...3)))
                        .multilineTextAlignment(.trailing)
                        .keyboardType(.decimalPad)
                    Text("MHz")
                        .foregroundStyle(.secondary)
                }
            }
            Text("Standard atmospheric refraction (k = 4/3) is used for this first version.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding()
        .background(.background, in: RoundedRectangle(cornerRadius: 16))
    }
}

private struct LinkProfilePrivacyNotice: View {
    var body: some View {
        Label {
            Text("Analyzing sends sampled path coordinates to Open-Meteo for Copernicus elevation lookup. Successful results are cached on this device.")
        } icon: {
            Image(systemName: "hand.raised.fill")
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .accessibilityElement(children: .combine)
    }
}

private struct LinkProfileAction: View {
    @Bindable var model: LinkProfileModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                Task { await model.analyze() }
            } label: {
                HStack {
                    if model.isAnalyzing {
                        ProgressView()
                    }
                    Text(model.isAnalyzing ? "Analyzing…" : "Analyze Link")
                        .frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(model.isAnalyzing)

            if let errorMessage = model.errorMessage {
                Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                    .font(.callout)
                    .foregroundStyle(.red)
            }
        }
    }
}

private struct LinkProfileResultCard: View {
    let result: NodePlannerLinkAnalysis

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label(statusTitle, systemImage: statusSymbol)
                    .font(.headline)
                Spacer()
                Text("Estimate")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
            Grid(alignment: .leading, horizontalSpacing: 20, verticalSpacing: 8) {
                LinkProfileMetricRow(
                    label: "Distance",
                    value: Measurement(value: result.result.distanceMeters, unit: UnitLength.meters)
                        .formatted(.measurement(width: .abbreviated, usage: .road))
                )
                LinkProfileMetricRow(
                    label: "Path loss",
                    value: result.result.totalPathLossDB.formatted(.number.precision(.fractionLength(1))) + " dB"
                )
                LinkProfileMetricRow(
                    label: "Worst Fresnel clearance",
                    value: result.result.worstClearancePercent.formatted(.number.precision(.fractionLength(0))) + "%"
                )
                LinkProfileMetricRow(
                    label: "Terrain source",
                    value: "Copernicus GLO-90 · Open-Meteo"
                )
            }
        }
        .padding()
        .background(.background, in: RoundedRectangle(cornerRadius: 16))
    }

    private var statusTitle: LocalizedStringResource {
        switch result.result.clearanceStatus {
        case .clear: "Clear terrain path"
        case .marginal: "Marginal clearance"
        case .partialObstruction: "Partially obstructed"
        case .blocked: "Blocked by terrain"
        }
    }

    private var statusSymbol: String {
        switch result.result.clearanceStatus {
        case .clear: "checkmark.circle.fill"
        case .marginal: "exclamationmark.circle.fill"
        case .partialObstruction: "circle.lefthalf.filled"
        case .blocked: "xmark.octagon.fill"
        }
    }
}

private struct LinkProfileMetricRow: View {
    let label: LocalizedStringResource
    let value: String

    var body: some View {
        GridRow {
            Text(label)
                .foregroundStyle(.secondary)
            Text(value)
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
    }
}
