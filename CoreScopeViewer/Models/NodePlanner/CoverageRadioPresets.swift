import Foundation

/// A region or modem preset for Estimated Coverage, after the link-budget sheet on meshpeaks.com:
/// it fixes the frequency, spreading factor and bandwidth, and the receiver sensitivity follows
/// from those. Values are starting points for planning, not a radio's reported configuration.
struct CoverageRadioPreset: Identifiable, Hashable, Sendable {
    enum Family: String, CaseIterable, Identifiable, Sendable {
        case meshCore
        case meshtastic

        var id: String { rawValue }

        var title: String {
            switch self {
            case .meshCore: String(localized: "MeshCore")
            case .meshtastic: String(localized: "Meshtastic")
            }
        }
    }

    let id: String
    let family: Family
    let title: String
    let frequencyMHz: Double
    let spreadingFactor: Int
    let bandwidthKHz: Double
    let transmitPowerDbm: Double

    var sensitivityDbm: Double {
        Self.sensitivity(spreadingFactor: spreadingFactor, bandwidthKHz: bandwidthKHz)
    }

    /// "SF7 · 62.5 kHz", the modem settings behind the sensitivity.
    var modemSummary: String {
        let bandwidth = bandwidthKHz.formatted(.number.precision(.fractionLength(0...1)))
        return "SF\(spreadingFactor) · \(bandwidth) kHz"
    }

    /// LoRa receiver sensitivity: thermal noise over the bandwidth, a 6 dB receiver noise figure
    /// and the demodulator's SNR limit of 10 − 2.5·SF dB, rounded to 0.5 dB. The same estimate
    /// meshpeaks.com uses; a radio's datasheet figure may differ by a few dB.
    static func sensitivity(spreadingFactor: Int, bandwidthKHz: Double, noiseFigureDb: Double = 6) -> Double {
        let thermalNoise = -174 + 10 * log10(bandwidthKHz * 1000)
        let snrLimit = 10 - 2.5 * Double(spreadingFactor)
        return ((thermalNoise + noiseFigureDb + snrLimit) * 2).rounded() / 2
    }

    static let all: [Self] = [
        Self(id: "meshcore-us-ca", family: .meshCore, title: String(localized: "USA/Canada"),
             frequencyMHz: 910.525, spreadingFactor: 7, bandwidthKHz: 62.5, transmitPowerDbm: 22),
        Self(id: "meshcore-eu-uk-narrow", family: .meshCore, title: String(localized: "EU/UK (Narrow)"),
             frequencyMHz: 869.618, spreadingFactor: 8, bandwidthKHz: 62.5, transmitPowerDbm: 14),
        Self(id: "meshcore-eu-433-narrow", family: .meshCore, title: String(localized: "EU 433 MHz (Narrow)"),
             frequencyMHz: 433.65, spreadingFactor: 8, bandwidthKHz: 62.5, transmitPowerDbm: 10),
        Self(id: "meshcore-au-narrow", family: .meshCore, title: String(localized: "Australia (Narrow)"),
             frequencyMHz: 916.575, spreadingFactor: 7, bandwidthKHz: 62.5, transmitPowerDbm: 22),
        Self(id: "meshcore-nz-narrow", family: .meshCore, title: String(localized: "New Zealand (Narrow)"),
             frequencyMHz: 917.375, spreadingFactor: 7, bandwidthKHz: 62.5, transmitPowerDbm: 22),
        Self(id: "meshtastic-us-long-fast", family: .meshtastic, title: String(localized: "US Long Fast"),
             frequencyMHz: 906.875, spreadingFactor: 11, bandwidthKHz: 250, transmitPowerDbm: 22),
        Self(id: "meshtastic-eu-868-long-fast", family: .meshtastic, title: String(localized: "EU 868 Long Fast"),
             frequencyMHz: 869.525, spreadingFactor: 11, bandwidthKHz: 250, transmitPowerDbm: 27)
    ]

    /// Last-resort planning preset.
    static var standard: Self { all[0] }

    static func preset(id: String?) -> Self? {
        guard let id else { return nil }
        return all.first { $0.id == id }
    }

    static func presets(in family: Family) -> [Self] {
        all.filter { $0.family == family }
    }
}

/// The radio defaults shown when a new planner opens.
struct PlannerRadioDefault: Equatable {
    enum Origin: Equatable {
        case observers, analyzerCountry, deviceRegion, fallback

        var label: LocalizedStringResource {
            switch self {
            case .observers: "Based on this analyzer’s observers"
            case .analyzerCountry: "Based on this analyzer’s country"
            case .deviceRegion: "Based on your region"
            case .fallback: "Using the USA/Canada default"
            }
        }
    }

    let preset: CoverageRadioPreset
    let origin: Origin

    private static let europeanCountries: Set<String> = [
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE",
        "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
        "GB", "CH", "NO", "IS", "LI"
    ]

    static func resolve(observerRadios: [String?], sourceCountry: String?, deviceRegion: String?) -> Self {
        let reports = observerRadios.compactMap(SignalQualityMath.observerRadio)
        // Group observer reports at 1 kHz precision to absorb firmware float noise.
        func frequencyKHz(_ radio: SignalQualityMath.ObserverRadio) -> Double {
            (radio.frequencyMHz * 1000).rounded()
        }
        if let frequency = mostCommon(reports.map(frequencyKHz)),
           let modem = mostCommon(reports.filter { frequencyKHz($0) == frequency }),
           let preset = nearest(to: modem) {
            return .init(preset: preset, origin: .observers)
        }
        if let preset = countryPreset(sourceCountry) {
            return .init(preset: preset, origin: .analyzerCountry)
        }
        if let preset = countryPreset(deviceRegion) {
            return .init(preset: preset, origin: .deviceRegion)
        }
        return .init(preset: .standard, origin: .fallback)
    }

    @MainActor
    static func current(settings: AnalyzerSettings, registry: AnalyzerSourceRegistry,
                        observers: ObserverRegionLookup) -> Self {
        let host = AnalyzerSettings.normalizedHost(settings.host)
        let source = registry.sources.first {
            AnalyzerSettings.normalizedHost($0.host).caseInsensitiveCompare(host) == .orderedSame
        }
        let region = AnalyzerSourceChoices(sources: []).userCountry
        return resolve(observerRadios: observers.currentObservers(for: settings.sourceSession).map(\.radio),
                       sourceCountry: source?.country, deviceRegion: region)
    }

    private static func countryPreset(_ country: String?) -> CoverageRadioPreset? {
        guard let country = AnalyzerSourceChoices.countryCode(country) else { return nil }
        switch country {
        case "US", "CA": return .standard
        case "AU": return CoverageRadioPreset.preset(id: "meshcore-au-narrow")
        case "NZ": return CoverageRadioPreset.preset(id: "meshcore-nz-narrow")
        case _ where europeanCountries.contains(country): return CoverageRadioPreset.preset(id: "meshcore-eu-uk-narrow")
        default: return nil
        }
    }

    private static func nearest(to radio: SignalQualityMath.ObserverRadio) -> CoverageRadioPreset? {
        func rank(_ preset: CoverageRadioPreset) -> (Double, Int, Double) {
            (abs(preset.frequencyMHz - radio.frequencyMHz),
             abs(preset.spreadingFactor - radio.spreadingFactor),
             abs(preset.bandwidthKHz - radio.bandwidthKHz))
        }
        return CoverageRadioPreset.presets(in: .meshCore)
            .filter { abs($0.frequencyMHz - radio.frequencyMHz) <= 1 }
            .min { rank($0) < rank($1) }
    }

    /// Break equal vote counts by first occurrence in the analyzer's observer list.
    private static func mostCommon<Value: Hashable>(_ values: [Value]) -> Value? {
        let counts = Dictionary(grouping: values, by: { $0 }).mapValues(\.count)
        let maximum = counts.values.max()
        return values.first { counts[$0] == maximum }
    }
}

/// Common antenna types and their nominal gains, as offered on meshpeaks.com.
struct CoverageAntennaPreset: Identifiable, Hashable, Sendable {
    let id: String
    let title: String
    let gainDbi: Double

    static let all: [Self] = [
        Self(id: "quarter-wave", title: String(localized: "1/4-wave ground plane"), gainDbi: 1.5),
        Self(id: "half-wave-dipole", title: String(localized: "1/2-wave dipole"), gainDbi: 2.15),
        Self(id: "omni-3", title: String(localized: "3 dBi omni"), gainDbi: 3),
        Self(id: "omni-5", title: String(localized: "5 dBi omni"), gainDbi: 5),
        Self(id: "omni-6", title: String(localized: "6 dBi omni"), gainDbi: 6),
        Self(id: "yagi-small", title: String(localized: "Small Yagi"), gainDbi: 4.5),
        Self(id: "yagi-high", title: String(localized: "High-gain Yagi"), gainDbi: 9)
    ]

    /// The preset with this gain, if one matches to within a hundredth of a dB.
    static func matching(gainDbi: Double) -> Self? {
        all.first { abs($0.gainDbi - gainDbi) < 0.01 }
    }
}

/// Typical antenna heights above ground. Stored in meters; shown in the preferred unit.
struct CoverageHeightPreset: Identifiable, Hashable, Sendable {
    let id: String
    let title: String
    let meters: Double

    static let handheld = Self(id: "handheld", title: String(localized: "Handheld"), meters: 1.5)
    static let vehicle = Self(id: "vehicle", title: String(localized: "Vehicle"), meters: 2)
    static let rooftop = Self(id: "rooftop", title: String(localized: "Rooftop"), meters: 6)
    static let mast = Self(id: "mast", title: String(localized: "Mast"), meters: 10)
    static let tower = Self(id: "tower", title: String(localized: "Tower"), meters: 30)

    static let transmitter: [Self] = [handheld, rooftop, mast, tower]
    static let receiver: [Self] = [handheld, vehicle, rooftop]

    /// The preset at this height, if one matches to within 15 cm.
    static func matching(meters: Double, in presets: [Self]) -> Self? {
        presets.first { abs($0.meters - meters) < 0.15 }
    }
}

/// Planning reserve (fade margin) choices. None is a valid, explicit choice.
enum CoverageReservePreset {
    static let choicesDb: [Double] = [0, 6, 10]
}
