import Foundation
import Testing
@testable import CoreScopeViewer

/// Mirrors Android's content translation tests: the same overlay files must give the same text.
struct ContentTranslationTests {
    @Test func languagesTryRegionThenBaseAndSkipEnglish() {
        #expect(ContentTranslation.languages(for: "pt-BR") == ["pt-BR", "pt"])
        #expect(ContentTranslation.languages(for: "pt_BR") == ["pt-BR", "pt"])
        #expect(ContentTranslation.languages(for: "es") == ["es"])
        #expect(ContentTranslation.languages(for: "en").isEmpty)
        #expect(ContentTranslation.languages(for: "en-GB").isEmpty)
    }

    @Test func pickPrefersTheMostSpecificLanguage() {
        let map = ["pt": "Brasil e Portugal", "pt-br": "Só Brasil", "es": ""]
        #expect(ContentTranslation.pick(map, languages: ["pt-BR", "pt"]) == "Só Brasil")
        #expect(ContentTranslation.pick(map, languages: ["pt-PT", "pt"]) == "Brasil e Portugal")
        #expect(ContentTranslation.pick(map, languages: ["es"]) == nil)
        #expect(ContentTranslation.pick(nil, languages: ["pt"]) == nil)
    }

    @Test func glossaryFallsBackToEnglishPerField() throws {
        let english = [GlossarySection(title: "The mesh", entries: [
            GlossaryEntry(id: "node", term: "Node", summary: "A radio.", detail: "More."),
            GlossaryEntry(id: "hop", term: "Hop", summary: "One relay.", detail: nil),
        ])]
        let json = #"{"version":1,"language":"pt-BR","sections":{"The mesh":"A malha"},"entries":{"node":{"term":"Nó","summary":"Um rádio."}}}"#
        let translation = try JSONDecoder().decode(GlossaryTranslation.self, from: Data(json.utf8))
        let result = translation.apply(to: english)
        #expect(result[0].title == "A malha")
        #expect(result[0].entries[0] == GlossaryEntry(id: "node", term: "Nó", summary: "Um rádio.", detail: "More."))
        #expect(result[0].entries[1] == english[0].entries[1])
    }

    @Test func whatsNewTranslatesTextButKeepsBehavior() throws {
        let item = WhatsNewItem(id: "saved-plans", title: "Saved Plans", detail: "Group studies.", icon: "savedPlans",
                                destination: nil, location: "Explore", platforms: ["android"])
        let json = #"{"version":1,"language":"pt-BR","items":{"saved-plans":{"title":"Planos salvos","location":""}}}"#
        let translated = try JSONDecoder().decode(WhatsNewTranslation.self, from: Data(json.utf8)).apply(to: item)
        #expect(translated.title == "Planos salvos")
        #expect(translated.detail == "Group studies.")
        #expect(translated.location == "Explore")
        #expect(translated.icon == "savedPlans" && translated.platforms == ["android"])
    }

    @Test func sourceSubtitlesAreOptional() throws {
        let json = #"{"id":"a","name":"A","host":"a.example","subtitle":"English","isDefault":false,"subtitles":{"pt-BR":"Português"}}"#
        let source = try JSONDecoder().decode(AnalyzerSource.self, from: Data(json.utf8))
        #expect(source.subtitles?["pt-BR"] == "Português")
        #expect(ContentTranslation.pick(source.subtitles, languages: ["pt-BR", "pt"]) == "Português")
        let plain = #"{"id":"b","name":"B","host":"b.example","subtitle":"English","isDefault":false}"#
        #expect(try JSONDecoder().decode(AnalyzerSource.self, from: Data(plain.utf8)).subtitles == nil)
    }
}
