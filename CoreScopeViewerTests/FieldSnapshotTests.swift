import Foundation
import Testing
@testable import CoreScopeViewer

struct FieldSnapshotTests {
    private let source = FieldSnapshot.Source(
        backend: "corescope",
        origin: "https://example.test",
        displayName: "Example",
        regionScope: "SAT"
    )
    private let state = FieldSnapshot.State(
        cached: false,
        partial: true,
        unsupported: ["coordinateTimestamp"],
        limitations: ["Position age unknown"]
    )

    @Test func referenceCoordinateIsOmittedByDefault() throws {
        let snapshot = FieldSnapshot(
            activity: .nearby,
            capturedAt: "2026-10-22T20:00:00Z",
            appVersion: "0.10.0",
            source: source,
            state: state
        )
        let data = try JSONEncoder().encode(snapshot)
        let object = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        #expect(object["reference"] == nil)
        #expect(object["schema"] as? String == FieldSnapshot.schema)
        #expect(object["version"] as? Int == FieldSnapshot.version)
    }

    @Test func decodesAdditiveFieldsAndMissingOptionalArrays() throws {
        let json = """
        {
          "schema":"org.nodescope.field.snapshot","version":1,"activity":"nearby",
          "capturedAt":"2026-10-22T20:00:00Z","appVersion":"0.10.0",
          "source":{"backend":"corescope","origin":"https://example.test","displayName":"Example"},
          "state":{"cached":false,"partial":false,"unsupported":[],"limitations":[]},
          "futureEvidence":{"value":42}
        }
        """
        let snapshot = try JSONDecoder().decode(FieldSnapshot.self, from: Data(json.utf8))
        #expect(snapshot.packetIdentifiers.isEmpty)
        #expect(snapshot.routeIdentifiers.isEmpty)
        #expect(snapshot.reference == nil)
    }

    @Test func rejectsUnknownSchemaVersions() {
        let json = """
        {"schema":"org.nodescope.field.snapshot","version":2,"activity":"nearby",
         "capturedAt":"2026-10-22T20:00:00Z","appVersion":"0.10.0",
         "source":{"backend":"corescope","origin":"https://example.test","displayName":"Example"},
         "state":{"cached":false,"partial":false,"unsupported":[],"limitations":[]}}
        """
        #expect(throws: DecodingError.self) {
            try JSONDecoder().decode(FieldSnapshot.self, from: Data(json.utf8))
        }
    }
}
