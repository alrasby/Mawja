package com.mawja.player.data.radio

import org.junit.Assert.*
import org.junit.Test

class CarRadioBrowseTest {
    @Test fun everyPhoneCategoryHasAnAutoRouteAndOldIdsStayStable() {
        assertEquals(listOf("local", "world", "news", "music", "sports", "talk", "culture", "quran"), RadioFilters.all.map { it.id })
        RadioFilters.all.forEach { assertSame(it, CarRadioBrowse.category(CarRadioBrowse.id(it))) }
        assertEquals("radio:local", CarRadioBrowse.id(RadioFilters.local))
        assertEquals("radio:world", CarRadioBrowse.id(RadioFilters.world))
        assertEquals("radio:news", CarRadioBrowse.id(RadioFilters.news))
        assertNull(CarRadioBrowse.category("radio:unknown"))
    }
    @Test fun readsPerCategoryCountryAndLanguageLikePhone() {
        val options = mapOf("country_music" to "OM", "language_music" to "arabic")
        assertEquals("OM" to "arabic", CarRadioBrowse.selection(RadioFilters.music, "en") { key, default -> options[key] ?: default })
        assertEquals(null to "arabic", CarRadioBrowse.selection(RadioFilters.news, "ar") { _, default -> default })
        assertEquals(null to null, CarRadioBrowse.selection(RadioFilters.news, "ar") { _, _ -> "" })
        assertEquals(null to null, CarRadioBrowse.selection(RadioFilters.local, "ar") { _, _ -> "US" })
    }
}
