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

/** Store directory tokens, display localized names; blank/unknown legacy values mean all. */
internal fun selectedRadioLanguage(value: String?): String? = value?.let(::normalizeRadioLanguage)
    ?.takeIf { normalized -> normalized.isNotBlank() && radioLanguages.any { it.name == normalized } }

internal fun radioLanguageLabel(value: String, locale: Locale): String {
    val entry = radioLanguages.firstOrNull { it.name == normalizeRadioLanguage(value) } ?: return value.trim()
    val language = Locale.forLanguageTag(entry.code)
    val translated = language.getDisplayLanguage(locale).trim()
    val native = language.getDisplayLanguage(language).trim()
    return if (translated.equals(native, true)) translated else "$translated · $native"
}

internal fun radioLanguageOptions(values: List<String>, locale: Locale): List<Pair<String, String>> {
    val collator = java.text.Collator.getInstance(locale)
    return values.mapNotNull(::selectedRadioLanguage).distinct()
        .map { it to radioLanguageLabel(it, locale) }
        .sortedWith { a, b -> collator.compare(a.second, b.second) }
}

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
    val isTvAudio: Boolean = false,
) {
    fun matches(filter: RadioFilter, country: String?, requestedLanguage: String?): Boolean =
        (country.isNullOrBlank() || countryCode.equals(country, true)) &&
            (filter.countryCodes.isEmpty() || countryCode in filter.countryCodes) &&
            matchesRadioLanguage(language, requestedLanguage ?: filter.language) &&
            (filter.tag == null || filter.id in categories)

    fun track(tvAudioLabel: String = "TV broadcast audio") = Track(
        id = "radio:official-$id", source = SourceType.RADIO, title = name,
        subtitle = listOf(if (isTvAudio) tvAudioLabel else "", Locale("", countryCode).displayCountry, city, language).filter { it.isNotBlank() }.joinToString(" • "),
        streamUrl = streamUrl, isLive = true,
    )
}

/** Allow the verified TV audio without re-admitting old radio or English-service links. */
internal fun isBbcArabicTvStream(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    uri.scheme == "https" && uri.rawUserInfo == null && uri.host in setOf(
        "vs-cmaf-pushb-ww-live.akamaized.net", "vs-cmaf-pushb-ww.live.cf.md.bbci.co.uk",
    ) && uri.path.startsWith("/x=4/i=urn:bbc:pips:service:bbc_arabic_tv/") && uri.path.endsWith(".m3u8")
}.getOrDefault(false)

internal fun isRetiredRadioEntry(name: String, stream: String): Boolean {
    // BBC ended its Arabic radio service in January 2023. Some directory entries
    // now point at the English World Service; do not mislabel these as Arabic.
    val normalized = name.lowercase(Locale.ROOT)
    return stream.contains("bbc_arabic_radio", true) ||
        ((normalized.contains("bbc") || normalized.contains("بي بي سي")) &&
            (normalized.contains("arabic") || normalized.contains("عرب")) && !isBbcArabicTvStream(stream))
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
        "music" -> setOf("music", "pop", "rock", "jazz", "classical", "oldies", "dance", "electronic", "hip hop", "hip-hop", "soul", "r&b", "arabic music", "موسيقى", "أغاني", "اغاني")
        else -> setOf(filter.tag)
    }
    return tags.any { it in aliases } &&
        (filter.id != "music" || tags.none { it in setOf("quran", "coran", "religion", "religious", "islamic", "قرآن", "قران", "القرآن الكريم", "قرآن كريم", "دين", "ديني", "إسلامي", "اسلامي") })
}
