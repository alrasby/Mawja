package com.mawja.player.data.youtube

import org.junit.Assert.*
import org.junit.Test

class YouTubeDiscoveryTest {
    @Test fun acceptsGlobalCountryChoicesWithoutDefaultingToOman() {
        assertEquals("FR", normalizeYouTubeCountry(" fr "))
        assertEquals("JP", normalizeYouTubeCountry("JP"))
        assertEquals("", normalizeYouTubeCountry(""))
        assertEquals("", normalizeYouTubeCountry("ZZ"))
    }

    @Test fun keepsLanguageIndependentOfCountry() {
        assertEquals("ar", contentLanguage("ar", "en", "fr-FR"))
        assertEquals("fr", contentLanguage("", "fr", "ar-OM"))
        assertEquals("ja", contentLanguage("", "", "ja-JP"))
        assertEquals("zh-Hant", contentLanguage("zh-TW", "en", "en"))
    }

    @Test fun discoveryUsesTheChosenCountryAndInterest() {
        val french = discoveryQuery(YouTubeRegion("FR", "en"), "music")
        assertTrue(french.contains("France"))
        assertFalse(french.contains("Oman"))
        assertTrue(discoveryQuery(YouTubeRegion("", "ar"), "quran").contains("القرآن"))
        assertFalse(discoveryQuery(YouTubeRegion("", "en"), "music").contains("Oman"))
        assertTrue(discoveryQuery(YouTubeRegion("DE", "en"), "podcasts").contains("podcast"))
    }
}
