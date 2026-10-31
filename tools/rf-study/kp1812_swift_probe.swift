// Isolated framework consumer; not part of either app target.
import Foundation
import Kp1812

let distances = KotlinDoubleArray(size: 101)
let heights = KotlinDoubleArray(size: 101)
let clutter = KotlinDoubleArray(size: 101)
let zones = KotlinIntArray(size: 101)
for index in Int32(0)..<101 {
    distances.set(index: index, value: Double(index) / 10)
    heights.set(index: index, value: 100)
    clutter.set(index: index, value: 0)
    zones.set(index: index, value: 4)
}
let path = TerrainPath(distancesKm: distances, heightsM: heights,
                       clutterHeightsM: clutter, zones: zones)
let atmosphere = Atmosphere(deltaN: 45, n0: 325,
                            distanceToCoastTxKm: 500, distanceToCoastRxKm: 500)
var loss = 0.0
let start = Date()
for _ in 0..<10_000 {
    loss = P1812.shared.predict(path: path, frequencyGhz: 0.915,
        txHeightM: 10, rxHeightM: 1.5, timePercent: 50,
        pathCenterLatitudeDeg: 47.6, polarization: .vertical,
        atmosphere: atmosphere, locationPercent: 50,
        locationVariabilityDb: 0).basicTransmissionLossDb
}
precondition(loss.isFinite)
print("flat_10km_loss_db=\(loss) iterations=10000 elapsed_seconds=\(Date().timeIntervalSince(start))")
