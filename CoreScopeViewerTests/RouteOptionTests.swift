import CoreLocation
import Testing
@testable import CoreScopeViewer

/// Mirrors Android's route tests in `PacketGroupsTest` so both apps name routes the same way.
struct RouteOptionTests {
    @Test func routesKeepTheObserversThatHeardThem() {
        func heard(_ name: String, _ rssi: Double?) -> RouteHearing {
            RouteHearing(observer: name, region: "AUS", rssi: rssi)
        }
        let options = RouteOptions.make(from: [
            HeardPath(path: ["a", "b", "c", "d"], hearing: heard("Volente", -114)),
            HeardPath(path: ["A", "B", "C", "D"], hearing: heard("Silverado", -98)), // same path, stronger
            HeardPath(path: ["a", "b"], hearing: heard("HAL9000", -90)),             // part of the longer route
            HeardPath(path: ["x", nil, "y"], hearing: heard("ASL239", -101)),
            HeardPath(path: ["solo"], hearing: heard("Direct", -80)),                // not a route
        ])
        #expect(options.map(\.keys) == [["a", "b", "c", "d"], ["x", "y"]])
        #expect(options[0].heardBy.map(\.observer) == ["Silverado", "Volente"])
        #expect(options[0].alongTheWay.map(\.observer) == ["HAL9000"])
        #expect(options[0].alongTheWay.first?.hops == 2)
        #expect(options[0].heardBy.first?.hops == 4)
        #expect(options[0].summary == "4 hops · AUS · -98 dBm")
        #expect(options[0].name(index: 0) == "Silverado")
        #expect(options[0].othersBadge == "+1")
    }

    @Test func fullRoutesAddTheirObserversWithoutRenumbering() {
        let playing = RouteOption(keys: ["a", "b", "c"], heardBy: [RouteHearing(observer: "Volente", rssi: -110)])
        let merged = RouteOptions.merge([playing], [
            RouteOption(keys: ["A", "B", "C"], heardBy: [
                RouteHearing(observer: "Silverado", rssi: -95), RouteHearing(observer: "volente", rssi: -110),
            ]),
            RouteOption(keys: ["b", "c"], heardBy: [RouteHearing(observer: "HAL9000", rssi: -90)]),
            RouteOption(keys: ["d", "e"], heardBy: [RouteHearing(observer: "ASL239")]),
        ])
        #expect(merged.map(\.keys) == [["a", "b", "c"], ["d", "e"]])
        #expect(merged[0].heardBy.map(\.observer) == ["Silverado", "Volente"]) // one Volente, strongest first
        #expect(merged[0].alongTheWay.map(\.observer) == ["HAL9000"])
    }

    @Test func anObserversPathPointsAtItsRoute() {
        let options = [RouteOption(keys: ["a", "b", "c", "d"]), RouteOption(keys: ["x", "y"])]
        #expect(RouteOptions.index(for: ["A", "B", "C", "D"], in: options) == 0)
        #expect(RouteOptions.index(for: ["b", "c"], in: options) == 0) // heard along the way
        #expect(RouteOptions.index(for: ["x", nil, "y"], in: options) == 1)
        #expect(RouteOptions.index(for: ["q", "r"], in: options) == nil)
        #expect(RouteOptions.index(for: ["a"], in: options) == nil)
    }

    @Test func aRouteEndsAtTheObserversThatHeardIt() {
        let volente = CLLocationCoordinate2D(latitude: 30.4, longitude: -97.9)
        let silverado = CLLocationCoordinate2D(latitude: 30.2, longitude: -97.6)
        let option = RouteOption(keys: ["a", "b"], heardBy: [
            RouteHearing(observer: "Volente (renamed)", observerId: "OBS-1"),
            RouteHearing(observer: "silverado"),
            RouteHearing(observer: "Unknown"),
        ])
        let receivers = option.receivers(
            coordinateById: ["obs-1": volente],
            coordinateByName: ["Silverado": silverado, "silverado": silverado]
        )
        #expect(receivers.map(\.name) == ["Volente (renamed)", "silverado"])
        #expect(receivers.map(\.coordinate.latitude) == [30.4, 30.2])
    }

    @Test func routesWithoutObserversFallBackToNumbers() {
        let options = RouteOptions.make(from: [
            HeardPath(path: ["A", "B", "C"], hearing: nil),
            HeardPath(path: ["b", "c"], hearing: nil),
            HeardPath(path: ["X", nil, "Y"], hearing: nil),
        ])
        #expect(options.map(\.keys) == [["A", "B", "C"], ["X", "Y"]])
        #expect(options[1].name(index: 1) == "Route 2")
        #expect(options[1].summary == "2 hops")
    }
}
