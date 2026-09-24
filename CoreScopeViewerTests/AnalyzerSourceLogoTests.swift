import Foundation
import Testing
@testable import CoreScopeViewer

/// Mirrors Android's `SourceIconsTest`: only registry icon paths are accepted,
/// and every icon the registry names exists.
struct AnalyzerSourceLogoTests {
    @Test func onlyRegistryIconPathsAreAccepted() {
        #expect(AnalyzerSourceLogo.iconPath("icons/meshtexas.png") == "icons/meshtexas.png")
        #expect(AnalyzerSourceLogo.iconPath(" icons/gulf-coast-mesh.png ") == "icons/gulf-coast-mesh.png")
        for rejected in [nil, "", "https://example.org/logo.png", "//example.org/a.png", "icons/../secret.png",
                         "icons/a/b.png", "icons/Logo.png", "icons/logo.svg", "logo.png"] {
            #expect(AnalyzerSourceLogo.iconPath(rejected) == nil)
        }
    }

    @Test func iconIsOptionalInTheRegistry() throws {
        let withIcon = #"{"id":"a","name":"A","host":"a.example","subtitle":"","isDefault":false,"icon":"icons/a.png"}"#
        let without = #"{"id":"b","name":"B","host":"b.example","subtitle":"","isDefault":false}"#
        #expect(try JSONDecoder().decode(AnalyzerSource.self, from: Data(withIcon.utf8)).icon == "icons/a.png")
        #expect(try JSONDecoder().decode(AnalyzerSource.self, from: Data(without.utf8)).icon == nil)
    }
}
