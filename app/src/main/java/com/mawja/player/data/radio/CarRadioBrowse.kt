package com.mawja.player.data.radio

import java.util.Locale

/** IDs stay compatible with existing Auto bookmarks; the taxonomy is owned by RadioFilters. */
internal object CarRadioBrowse {
    fun id(filter: RadioFilter): String = "radio:${filter.id}"
    fun category(mediaId: String): RadioFilter? = RadioFilters.all.firstOrNull { id(it) == mediaId }

    fun selection(filter: RadioFilter, appLanguage: String, option: (String, String) -> String): Pair<String?, String?> {
        if (filter.id == RadioFilters.local.id) return null to null
        val country = option("country_${filter.id}", "").ifBlank { null }
        val language = option("language_${filter.id}", radioDefaultLanguage(filter.id, appLanguage)).ifBlank { null }
        return country to language
    }
}

internal fun radioDefaultLanguage(filterId: String, appLanguage: String): String =
    if (filterId == RadioFilters.news.id) Locale.forLanguageTag(appLanguage.substringBefore('-'))
        .getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT) else ""
