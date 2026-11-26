package com.mawja.player.data.radio

import org.junit.Assert.*
import org.junit.Test

class CarRadioBrowseTest {
    @Test fun everyPhoneCategoryHasAnAutoRouteAndOldIdsStayStable() {
        assertEquals(listOf("local", "world", "news", "music", "sports", "talk", "culture", "quran", "tv_audio"), RadioFilters.all.map { it.id })
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
    @Test fun languageRoutesKeepSelectionsIsolatedAndRejectInvalidIds() {
        val id = CarRadioBrowse.choiceId(RadioFilters.news, "AE", "arabic")
        val route = requireNotNull(CarRadioBrowse.route(id))
        assertEquals(RadioFilters.news, route.filter)
        assertEquals("AE", route.country)
        assertEquals("arabic", route.language)
        assertNull(CarRadioBrowse.route("radio-choice:news:AE:invalid"))
        assertNull(CarRadioBrowse.route("radio-choice:news:invalid:arabic"))
        assertNull(CarRadioBrowse.route("radio-choice:local:AE:arabic"))
        assertNull(CarRadioBrowse.route("radio-choice:news:AE"))
        assertNull(CarRadioBrowse.route(CarRadioBrowse.choiceId(RadioFilters.world, null, null))?.language)
        assertNotNull(CarRadioBrowse.route(CarRadioBrowse.languagesId(RadioFilters.news, "AE")))
    }
}
