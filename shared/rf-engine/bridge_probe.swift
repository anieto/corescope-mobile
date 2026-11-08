// Host-only bridge check; does not launch or install an app.
import Foundation
import NodeScopeRF

let distances = KotlinDoubleArray(size: 101)
let elevations = KotlinDoubleArray(size: 101)
for index in Int32(0)..<101 {
    distances.set(index: index, value: Double(index) * 100)
    elevations.set(index: index, value: 100)
}
func predict(_ frequency: Double = 915) -> CoverageKernelResult {
    CoverageKernel.shared.predict(distancesMeters: distances, elevationsMeters: elevations,
        frequencyMHz: frequency, transmitHeightMeters: 10, receiveHeightMeters: 1.5,
        pathCenterLatitude: 47.6)
}
let valid = predict()
guard valid.state == .predicted, let loss = valid.lossDb?.doubleValue else {
    fatalError("Expected a finite prediction through the Swift bridge")
}
precondition(abs(loss - 137.09304306564067) < 1e-6)
let invalidFrequency = predict(.nan)
precondition(invalidFrequency.state == .invalidInput && invalidFrequency.lossDb == nil)
elevations.set(index: 50, value: .nan)
let invalidTerrain = predict()
precondition(invalidTerrain.state == .invalidInput && invalidTerrain.lossDb == nil)
print("Swift bridge: reference loss \(loss); invalid frequency/terrain returned typed failures")
