package org.nodescope.android

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.AnalyzerSource
import org.nodescope.android.core.model.ContentTranslation
import org.nodescope.android.core.network.decodeSourceRegistry
import org.nodescope.android.core.network.protocolJson
import org.nodescope.android.feature.learn.GlossaryEntry
import org.nodescope.android.feature.learn.GlossarySection
import org.nodescope.android.feature.learn.GlossaryTranslation
import org.nodescope.android.feature.settings.WhatsNewItem
import org.nodescope.android.feature.settings.WhatsNewTranslation

class ContentTranslationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun languagesTryRegionThenBaseAndSkipEnglish() {
        assertEquals(listOf("pt-BR", "pt"), ContentTranslation.languages("pt-BR"))
        assertEquals(listOf("pt-BR", "pt"), ContentTranslation.languages("pt_BR"))
        assertEquals(listOf("es"), ContentTranslation.languages("es"))
        assertTrue(ContentTranslation.languages("en").isEmpty())
        assertTrue(ContentTranslation.languages("en-GB").isEmpty())
    }

    @Test fun pickPrefersTheMostSpecificLanguage() {
        val values = mapOf("pt" to "Brasil e Portugal", "pt-br" to "Só Brasil", "es" to "")
        assertEquals("Só Brasil", ContentTranslation.pick(values, listOf("pt-BR", "pt")))
        assertEquals("Brasil e Portugal", ContentTranslation.pick(values, listOf("pt-PT", "pt")))
        assertNull(ContentTranslation.pick(values, listOf("es")))
        assertNull(ContentTranslation.pick(null, listOf("pt")))
    }

    @Test fun glossaryFallsBackToEnglishPerField() {
        val english = listOf(GlossarySection("The mesh", listOf(
            GlossaryEntry("node", "Node", "A radio.", "More."),
            GlossaryEntry("hop", "Hop", "One relay."),
        )))
        val overlay = json.decodeFromString<GlossaryTranslation>(
            """{"version":1,"language":"pt-BR","sections":{"The mesh":"A malha"},"entries":{"node":{"term":"Nó","summary":"Um rádio."}}}""",
        )
        val result = overlay.applyTo(english).single()
        assertEquals("A malha", result.title)
        assertEquals(GlossaryEntry("node", "Nó", "Um rádio.", "More."), result.entries[0])
        assertEquals(english.single().entries[1], result.entries[1])
    }

    @Test fun whatsNewTranslatesTextButKeepsBehavior() {
        val item = WhatsNewItem("saved-plans", "Saved Plans", "Group studies.", "savedPlans",
            location = "Explore", platforms = listOf("android"))
        val overlay = json.decodeFromString<WhatsNewTranslation>(
            """{"version":1,"language":"pt-BR","items":{"saved-plans":{"title":"Planos salvos","location":""}}}""",
        )
        assertEquals(item.copy(title = "Planos salvos"), overlay.applyTo(item))
    }

    @Test fun sourceSubtitlesAreOptionalAndSurviveTheRegistryCache() {
        val translated = """{"id":"a","name":"A","host":"a.example","subtitle":"English","isDefault":false,"subtitles":{"pt-BR":"Português"}}"""
        val plain = """{"id":"b","name":"B","host":"b.example","subtitle":"English","isDefault":false}"""
        val source = json.decodeFromString<AnalyzerSource>(translated)
        assertEquals("Português", source.subtitles?.get("pt-BR"))
        assertEquals("Português", ContentTranslation.pick(source.subtitles, listOf("pt-BR", "pt")))
        assertNull(json.decodeFromString<AnalyzerSource>(plain).subtitles)

        val registry = decodeSourceRegistry("""{"version":2,"sources":[$translated,$plain]}""")
        val cached = decodeSourceRegistry(protocolJson.encodeToString(registry))
        assertEquals("Português", cached.sources[0].displaySubtitle("pt-BR"))
        assertEquals("English", cached.sources[0].displaySubtitle("en-GB"))
        assertNull(cached.sources[1].subtitles)
        assertEquals("English", cached.sources[1].displaySubtitle("pt-BR"))
    }
}
