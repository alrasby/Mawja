package com.mawja.player.data.radio

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReviewedRadioCatalogTest {
    private val catalog = Json.decodeFromString(ListSerializer(ReviewedRadioStation.serializer()),
        File("src/main/assets/radio/reviewed-stations.json").readText())

    @Test fun curatedStationsKeepTheirCountryLanguageAndCategory() {
        assertEquals(catalog.size, catalog.map { it.id }.distinct().size)
        assertEquals(catalog.size, catalog.map { it.streamUrl }.distinct().size)
        catalog.forEach {
            assertTrue(it.streamUrl.startsWith("https://"))
            assertTrue(it.countryCode.matches(Regex("[A-Z]{2}")))
            assertFalse(it.categories.isEmpty())
            assertTrue(it.matches(RadioFilters.world, it.countryCode, "ar"))
            assertFalse(it.matches(RadioFilters.world, "ZZ", "ar"))
            assertFalse(it.matches(RadioFilters.world, it.countryCode, "english"))
            assertTrue(it.matches(RadioFilters.local, it.countryCode, null))
        }
        assertTrue(catalog.single { it.id == "sky-ar" }.matches(RadioFilters.news, "AE", "arabic"))
        assertTrue(catalog.single { it.id == "aja-ar" }.matches(RadioFilters.news, "QA", "العربية"))
        assertFalse(catalog.single { it.id == "arabiya99" }.matches(RadioFilters.news, "AE", "arabic"))
        assertFalse(catalog.single { it.id == "qatar-quran" }.matches(RadioFilters.music, "QA", null))
    }

    @Test fun languageAliasesAndRetiredBbcEntriesAreHandled() {
        listOf("ar", "ara", "عربي", "العربية", "Arabic").forEach { assertTrue(matchesRadioLanguage(it, "arabic")) }
        assertTrue(matchesRadioLanguage("english,ar", "arabic"))
        assertFalse(matchesRadioLanguage("", "arabic"))
        assertFalse(matchesRadioLanguage("french", "arabic"))
        assertTrue(radioLanguages.any { it.name == "arabic" })
        assertFalse(radioLanguages.any { it.name == "111" })
        assertTrue(isRetiredRadioEntry("BBC Arabic", "https://stream.live.vc.bbcmedia.co.uk/bbc_world_service"))
        assertFalse(isRetiredRadioEntry("BBC World Service", "https://stream.live.vc.bbcmedia.co.uk/bbc_world_service"))
    }
    @Test fun rejectedEndpointsAreExplicitAndDoNotIncludeRegionalFailures() {
        val urls = Json.decodeFromString(ListSerializer(String.serializer()), File("src/main/assets/radio/unavailable-streams.json").readText())
        assertEquals(3, urls.size)
        assertTrue(urls.any { "yasourfm" in it })
        assertFalse(urls.any { "starzplayarabia" in it })
        assertFalse(urls.any { "hlmvmp2jtth90njc1" in it }) // MFM recovered on recheck.
    }
}
