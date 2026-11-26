package com.mawja.player.data.radio

import com.mawja.player.data.SourceType
import com.mawja.player.data.Track
import kotlinx.serialization.Serializable
import java.util.Locale

internal data class DirectoryLanguage(val code: String, val name: String)

/** ISO languages exclude arbitrary user-entered directory values from both pickers. */
internal val radioLanguages: List<DirectoryLanguage> = Locale.getISOLanguages().map { code ->
    DirectoryLanguage(code, Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT))
}.distinctBy { it.name }.sortedBy { it.name }

internal fun normalizeRadioLanguage(value: String): String {
    val language = value.trim().lowercase(Locale.ROOT)
    if (language in setOf("ar", "ara", "arabic", "العربية", "عربي", "عربية", "اللغة العربية")) return "arabic"
    return radioLanguages.firstOrNull { it.code == language || it.name == language }?.name ?: language
}

internal fun matchesRadioLanguage(value: String, requested: String?): Boolean = requested.isNullOrBlank() ||
    value.split(',', ';', '/').any { normalizeRadioLanguage(it) == normalizeRadioLanguage(requested) }

@Serializable
internal data class ReviewedRadioStation(
    val id: String,
    val name: String,
    val countryCode: String,
    val city: String = "",
    val language: String,
    val categories: List<String>,
    val streamUrl: String,
    val homepage: String,
    val sourcePage: String,
    val checkedAt: String,
    val aliases: List<String> = emptyList(),
) {
    fun matches(filter: RadioFilter, country: String?, requestedLanguage: String?): Boolean =
        (country.isNullOrBlank() || countryCode.equals(country, true)) &&
            (filter.countryCodes.isEmpty() || countryCode in filter.countryCodes) &&
            matchesRadioLanguage(language, requestedLanguage ?: filter.language) &&
            (filter.tag == null || filter.id in categories)

    fun track() = Track(
        id = "radio:official-$id", source = SourceType.RADIO, title = name,
        subtitle = listOf(Locale("", countryCode).displayCountry, city, language).filter { it.isNotBlank() }.joinToString(" • "),
        streamUrl = streamUrl, isLive = true,
    )
}

internal fun isRetiredRadioEntry(name: String, stream: String): Boolean {
    // BBC ended its Arabic radio service in January 2023. Some directory entries
    // now point at the English World Service; do not mislabel these as Arabic.
    val normalized = name.lowercase(Locale.ROOT)
    return stream.contains("bbc_arabic_radio", true) ||
        ((normalized.contains("bbc") || normalized.contains("بي بي سي")) &&
            (normalized.contains("arabic") || normalized.contains("عرب")))
}

internal fun matchesRadioCategory(filter: RadioFilter, name: String, rawTags: String): Boolean {
    if (filter.tag == null) return true
    if (filter.id == RadioFilters.news.id) return isNewsStation(name, rawTags)
    val tags = rawTags.split(',').map { it.trim().lowercase(Locale.ROOT) }
    val aliases = when (filter.id) {
        "quran" -> setOf("quran", "coran", "قرآن", "قران", "القرآن الكريم", "قرآن كريم")
        "sports" -> setOf("sports", "sport", "رياضة", "رياضي")
        "culture" -> setOf("culture", "cultural", "ثقافة", "ثقافي")
        "talk" -> setOf("talk", "news talk", "news/talk", "حوار", "منوعات")
        "music" -> setOf("music", "pop", "arabic music", "موسيقى", "أغاني", "اغاني")
        else -> setOf(filter.tag)
    }
    return tags.any { it in aliases } &&
        (filter.id != "music" || tags.none { it in setOf("quran", "coran", "religion", "religious", "القرآن الكريم") })
}
