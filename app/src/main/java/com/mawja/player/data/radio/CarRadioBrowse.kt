package com.mawja.player.data.radio

import java.util.Locale

/** IDs stay compatible with existing Auto bookmarks; the taxonomy is owned by RadioFilters. */
internal object CarRadioBrowse {
    fun id(filter: RadioFilter): String = "radio:${filter.id}"
    fun category(mediaId: String): RadioFilter? = if (mediaId == "radio:tv_audio") RadioFilters.news else RadioFilters.all.firstOrNull { id(it) == mediaId }

    data class Route(val filter: RadioFilter, val country: String?, val language: String?)
    fun languagesId(filter: RadioFilter, country: String?) = "radio-languages:${filter.id}:${country ?: "all"}"
    fun choiceId(filter: RadioFilter, country: String?, language: String?) =
        "radio-choice:${filter.id}:${country ?: "all"}:${language ?: "all"}"
    fun route(id: String): Route? {
        val parts = id.split(':')
        if (parts.firstOrNull() !in setOf("radio-languages", "radio-choice")) return null
        if (parts.size != if (parts[0] == "radio-languages") 3 else 4) return null
        val filterId = if (parts[1] == "tv_audio") "news" else parts[1]
        val filter = RadioFilters.all.firstOrNull { it.id == filterId && it != RadioFilters.local } ?: return null
        val country = parts[2].takeUnless { it == "all" }
        if (country != null && !country.matches(Regex("[A-Z]{2}"))) return null
        val language = parts.getOrNull(3)?.takeUnless { it == "all" }
        if (language != null && language !in radioLanguages.map { it.name }) return null
        return Route(filter, country, language)
    }

    fun selection(filter: RadioFilter, appLanguage: String, option: (String, String) -> String): Pair<String?, String?> {
        if (filter.id == RadioFilters.local.id) return null to null
        val country = option("country_${filter.id}", "").ifBlank { null }
        val language = selectedRadioLanguage(option("language_${filter.id}", radioDefaultLanguage(filter.id, appLanguage)))
        return country to language
    }
}

internal fun radioDefaultLanguage(filterId: String, appLanguage: String): String =
    if (filterId == RadioFilters.news.id) Locale.forLanguageTag(appLanguage.substringBefore('-'))
        .getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT) else ""
