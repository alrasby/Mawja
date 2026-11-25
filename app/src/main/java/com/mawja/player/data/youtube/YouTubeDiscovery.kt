package com.mawja.player.data.youtube

import java.util.Locale

internal fun normalizeYouTubeCountry(value: String): String =
    value.trim().uppercase(Locale.ROOT).takeIf { it in Locale.getISOCountries() }.orEmpty()

internal fun contentLanguage(manual: String, appLanguage: String, systemLanguage: String): String {
    val chosen = sequenceOf(manual, appLanguage, systemLanguage).map { Locale.forLanguageTag(it) }
        .firstOrNull { it.language in Locale.getISOLanguages() } ?: Locale.ENGLISH
    if (chosen.language == "zh") return if (chosen.script == "Hant" || chosen.country in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans"
    return chosen.language
}

internal fun discoveryQuery(region: YouTubeRegion, topic: String): String {
    val locale = Locale.forLanguageTag(region.language)
    val category = when (topic) {
        "quran" -> "تلاوات القرآن الكريم"
        "podcasts" -> if (locale.language == "ar") "بودكاست" else "podcast"
        else -> if (locale.language == "ar") "موسيقى أغاني" else "music songs"
    }
    val country = region.countryCode.takeIf { it.isNotEmpty() }?.let { Locale("", it).getDisplayCountry(locale) }
    val language = locale.getDisplayLanguage(Locale.ENGLISH)
    return listOfNotNull(category, country, language).joinToString(" ")
}
