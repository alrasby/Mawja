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
    @Test fun languagePickerRejectsBlankRowsAndUsesStableLocalizedValues() {
        val values = listOf("", " ", "ar", "arabic", "العربية", "english", "111", "#japan")
        val options = radioLanguageOptions(values, java.util.Locale.ENGLISH)
        assertEquals(setOf("arabic", "english"), options.map { it.first }.toSet())
        assertTrue(options.all { it.first.isNotBlank() && it.second.isNotBlank() })
        assertTrue(options.single { it.first == "arabic" }.second.contains("العربية"))
        assertTrue(radioLanguageLabel("arabic", java.util.Locale("ar")).contains("العربية"))
        assertNull(selectedRadioLanguage(" "))
        assertNull(selectedRadioLanguage("invalid-old-language"))
        assertEquals("arabic", selectedRadioLanguage(" AR "))
    }

    @Test fun everyReviewedStationAppearsOnlyInItsDeclaredCategoriesAndCountry() {
        catalog.forEach { station ->
            RadioFilters.all.filter { it.tag != null }.forEach { filter ->
                assertEquals("${station.id}/${filter.id}", filter.id in station.categories,
                    station.matches(filter, station.countryCode, "arabic"))
                assertFalse(station.matches(filter, "ZZ", "arabic"))
            }
        }
        assertTrue(matchesRadioCategory(RadioFilters.music, "Jazz station", "jazz,english"))
        assertFalse(matchesRadioCategory(RadioFilters.music, "Quran", "music,quran,religion"))
        assertFalse(matchesRadioCategory(RadioFilters.news, "Mixed", "news,pop"))
        assertFalse(matchesRadioCategory(RadioFilters.music, "قرآن", "music,قرآن"))
        assertFalse(matchesRadioCategory(RadioFilters.news, "قرآن", "news,القرآن الكريم"))
        assertFalse(matchesRadioCategory(RadioFilters.news, "أغاني", "news,أغاني"))
        listOf("jazz", "classical", "oldies", "electronic", "hip hop", "soul").forEach { genre ->
            assertTrue(matchesRadioCategory(RadioFilters.music, "Music", genre))
            assertFalse(matchesRadioCategory(RadioFilters.news, "Mixed", "news,$genre"))
        }
    }
    @Test fun tvAudioIsLabeledAndOldBbcRadioStaysExcluded() {
        val channels = catalog.filter { it.isTvAudio }
        assertEquals(setOf("bbc-ar-tv", "france24-ar-tv", "dw-ar-tv"), channels.map { it.id }.toSet())
        channels.forEach { entry ->
            assertTrue(entry.matches(RadioFilters.tvAudio, entry.countryCode, "arabic"))
            assertFalse(entry.track().isVideo)
            assertTrue(entry.track().isLive)
            assertTrue(entry.track("صوت القناة — بث مباشر").subtitle.startsWith("صوت القناة"))
            assertFalse(isRetiredRadioEntry(entry.name, entry.streamUrl))
        }
        assertFalse(catalog.single { it.id == "mcd-ar" }.matches(RadioFilters.tvAudio, "FR", "arabic"))
        val bbc = channels.single { it.id == "bbc-ar-tv" }
        assertTrue(isBbcArabicTvStream(bbc.streamUrl))
        assertFalse(isBbcArabicTvStream(bbc.streamUrl.replace("bbc_arabic_tv", "bbc_world_service")))
        assertFalse(isBbcArabicTvStream(bbc.streamUrl.replace(".akamaized.net/", ".akamaized.net.evil.example/")))
        assertFalse(isBbcArabicTvStream(bbc.streamUrl.replace("https://", "https://user:password@")))
        assertTrue(isRetiredRadioEntry("BBC Arabic", "https://stream.live.vc.bbcmedia.co.uk/bbc_arabic_radio"))
    }
}
