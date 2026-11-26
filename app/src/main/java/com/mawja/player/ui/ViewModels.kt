package com.mawja.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mawja.player.AppContainer
import com.mawja.player.R
import com.mawja.player.data.ListState
import com.mawja.player.data.SourceType
import com.mawja.player.data.Track
import com.mawja.player.data.radio.RadioCountry
import com.mawja.player.data.radio.RadioFilter
import com.mawja.player.data.radio.RadioFilters
import com.mawja.player.data.youtube.YouTubeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class HomeSection(val titleRes: Int, val items: List<Track>)
data class HomeState(val loading: Boolean = false, val sections: List<HomeSection> = emptyList(), val error: String? = null)

class VmFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(RadioVm::class.java) -> RadioVm(container)
        modelClass.isAssignableFrom(OnlineVm::class.java) -> OnlineVm(container)
        modelClass.isAssignableFrom(LocalVm::class.java) -> LocalVm(container)
        else -> throw IllegalArgumentException("Unknown ViewModel: $modelClass")
    } as T
}

class RadioVm(private val c: AppContainer) : ViewModel() {
    val filter = MutableStateFlow(RadioFilters.local)
    val state = MutableStateFlow(ListState())
    val worldCountries = MutableStateFlow<List<RadioCountry>>(emptyList())
    val languages = MutableStateFlow<List<com.mawja.player.data.radio.RadioRepository.RadioLanguage>>(emptyList())
    val country = MutableStateFlow<String?>(null)
    val language = MutableStateFlow<String?>(null)
    val locationCountry = MutableStateFlow(c.preferences.lastCountryCode())
    private var localLocation = c.preferences.lastLocation()
    private var job: Job? = null
    private var locationJob: Job? = null

    init {
        restoreSelection()
        viewModelScope.launch {
            try { worldCountries.value = c.radio.countries() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
        }
        viewModelScope.launch {
            try { languages.value = c.radio.languages() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
        }
        load()
    }

    private fun restoreSelection() {
        val id = filter.value.id
        country.value = if (id == RadioFilters.local.id) null else c.preferences.radioOption("country_$id").ifBlank { null }
        val defaultLanguage = com.mawja.player.data.radio.radioDefaultLanguage(id, c.preferences.language.value)
        language.value = com.mawja.player.data.radio.selectedRadioLanguage(c.preferences.radioOption("language_$id", defaultLanguage))
    }

    fun select(f: RadioFilter) {
        filter.value = f
        restoreSelection()
        load()
    }
    fun selectCountry(value: String?) {
        if (filter.value.id == RadioFilters.local.id) return
        country.value = value
        c.preferences.setRadioOption("country_${filter.value.id}", value.orEmpty())
        load()
    }
    fun selectLanguage(value: String?) {
        val canonical = com.mawja.player.data.radio.selectedRadioLanguage(value)
        language.value = canonical
        c.preferences.setRadioOption("language_${filter.value.id}", canonical.orEmpty())
        load()
    }
    fun setLocalLocation(latitude: Double, longitude: Double) {
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            val resolved = c.radio.countryCodeFor(latitude, longitude) ?: return@launch
            localLocation = latitude to longitude
            locationCountry.value = resolved
            c.preferences.saveLastLocation(latitude, longitude, resolved)
            if (filter.value.id == RadioFilters.local.id && country.value == null) load()
        }
    }

    fun load() {
        job?.cancel()
        val f = filter.value
        val chosenCountry = com.mawja.player.data.radio.radioBrowseCountry(f.id == RadioFilters.local.id, country.value, locationCountry.value)
        if (f.id == RadioFilters.local.id && chosenCountry == null) {
            state.value = ListState()
            return
        }
        val chosenLanguage = language.value.takeUnless { f.id == RadioFilters.local.id }
        val coordinates = localLocation?.takeIf { f.id == RadioFilters.local.id }
        job = viewModelScope.launch {
            state.value = ListState(loading = true)
            state.value = try {
                ListState(items = c.radio.stations(f, coordinates?.first, coordinates?.second, chosenCountry, chosenLanguage))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { ListState(error = "radio") }
        }
    }
}

class OnlineVm(private val c: AppContainer) : ViewModel() {
    val selectedSource = MutableStateFlow<SourceType?>(null)
    val query = MutableStateFlow("")
    val state = MutableStateFlow(HomeState(loading = true))
    private var job: Job? = null
    private var debounceJob: Job? = null
    private val cache = object : LinkedHashMap<String, CacheEntry>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean = size > 24
    }

    private data class CacheEntry(val createdAt: Long, val state: HomeState)

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 450L
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
    }

    init { load() }
    fun setSource(source: SourceType?) {
        debounceJob?.cancel()
        selectedSource.value = source
        load()
    }

    fun onQuery(value: String) {
        query.value = value
        debounceJob?.cancel()
        if (value.isBlank()) {
            load()
        } else {
            debounceJob = viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                load()
            }
        }
    }

    fun submitQuery() {
        debounceJob?.cancel()
        load()
    }

    fun load() {
        job?.cancel()
        val q = query.value.trim()
        val selected = selectedSource.value
        val audiusEnabled = c.preferences.audiusEnabled.value
        val youtubeEnabled = c.preferences.youtubeEnabled.value
        job = viewModelScope.launch {
            state.value = HomeState(loading = true)
            try {
                val region = c.youtubeData.resolveRegion()
                val preferenceKey = listOf(region.countryCode, region.language, c.preferences.youtubeTopic.value).joinToString("|")
                val cacheKey = buildCacheKey(q, selected, audiusEnabled, youtubeEnabled, preferenceKey)
                val now = System.currentTimeMillis()
                cache[cacheKey]?.takeIf { now - it.createdAt < CACHE_TTL_MS }?.let {
                    state.value = it.state
                    return@launch
                }
                cache.remove(cacheKey)
                val audiusJob = async<List<Track>> {
                    if (audiusEnabled && (selected == null || selected == SourceType.AUDIUS)) {
                        withTimeoutOrNull(12_000L) {
                            runCatching { if (q.isBlank()) c.audius.trending() else c.audius.search(q) }.getOrDefault(emptyList())
                        }.orEmpty()
                    } else emptyList()
                }
                val youtubeJob = async<List<Track>> {
                    if (youtubeEnabled && (selected == null || selected == SourceType.YOUTUBE)) {
                        withTimeoutOrNull(12_000L) {
                            runCatching {
                                val searchQuery = q.ifBlank { "new music" }
                                val localDiscovery = q.isBlank()
                                if (c.youtubeData.isConfigured) c.youtubeData.search(searchQuery, localDiscovery, region)
                                else YouTubeSource.search(searchQuery, localDiscovery, region)
                            }.getOrDefault(emptyList())
                        }.orEmpty()
                    } else emptyList()
                }
                val audius = audiusJob.await()
                val youtube = youtubeJob.await()
                val all = (audius + youtube).distinctBy { it.id }
                val sections = if (q.isBlank()) {
                    buildList {
                        val albums = all.filter { it.album.isNotBlank() }.distinctBy { it.album }.take(16)
                        val albumAndSingles = all.distinctBy { it.id }.take(18)
                        if (albumAndSingles.isNotEmpty()) add(HomeSection(R.string.albums_and_singles, albumAndSingles))
                        if (albums.isNotEmpty()) add(HomeSection(R.string.new_albums, albums))
                        if (youtube.isNotEmpty()) add(HomeSection(R.string.youtube_picks, youtube.take(16)))
                    }
                } else if (all.isEmpty()) emptyList() else listOf(HomeSection(R.string.search_results, all))
                val result = HomeState(sections = sections)
                cache[cacheKey] = CacheEntry(System.currentTimeMillis(), result)
                state.value = result
            } catch (e: CancellationException) { throw e } catch (e: Exception) { state.value = HomeState(error = e.message ?: "error") }
        }
    }

    private fun buildCacheKey(query: String, selected: SourceType?, audiusEnabled: Boolean, youtubeEnabled: Boolean, youtubeCountry: String): String =
        listOf("global-youtube-v1", selected?.name ?: "ALL", query.lowercase(), audiusEnabled, youtubeEnabled, youtubeCountry).joinToString("|")
}

class LocalVm(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow(ListState(loading = true))
    private var refreshJob: Job? = null

    init { refresh() }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            state.value = state.value.copy(loading = true, error = null)
            state.value = try {
                ListState(items = c.local.load())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ListState(error = "device")
            }
        }
    }
}
