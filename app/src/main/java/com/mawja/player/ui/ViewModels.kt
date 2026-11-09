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
    val query = MutableStateFlow("")
    val state = MutableStateFlow(ListState(loading = true))
    val localReady = MutableStateFlow(false)
    val worldCountries = MutableStateFlow<List<RadioCountry>>(emptyList())
    val worldCountry = MutableStateFlow<String?>(null)
    private var localLocation: Pair<Double, Double>? = null
    private var localCountryCode: String? = null
    private var job: Job? = null

    init { viewModelScope.launch { worldCountries.value = c.radio.countries() }; load() }

    fun select(f: RadioFilter) {
        filter.value = f
        query.value = ""
        if (f.id != RadioFilters.world.id) worldCountry.value = null
        if (f.id == RadioFilters.local.id && localLocation == null) state.value = ListState()
        else if (f.id == RadioFilters.world.id) state.value = ListState()
        else load()
    }
    fun selectWorldCountry(countryCode: String) { worldCountry.value = countryCode; if (filter.value.id == RadioFilters.world.id) load() }
    fun setLocalLocation(latitude: Double, longitude: Double) {
        localLocation = latitude to longitude
        localReady.value = true
        viewModelScope.launch {
            localCountryCode = c.radio.countryCodeFor(latitude, longitude)
            if (filter.value.id == RadioFilters.local.id) load()
        }
    }
    fun onQuery(q: String) { query.value = q; if (q.isBlank()) load() }

    fun load() {
        job?.cancel()
        val q = query.value.trim()
        val f = filter.value
        if (f.id == RadioFilters.local.id && localLocation == null) { state.value = ListState(); return }
        if (f.id == RadioFilters.world.id && worldCountry.value.isNullOrBlank()) { state.value = ListState(); return }
        job = viewModelScope.launch {
            state.value = ListState(loading = true)
            state.value = try {
                val localItems = c.radio.stations(
                    f,
                    localLocation?.first,
                    localLocation?.second,
                    if (f.id == RadioFilters.local.id) localCountryCode else worldCountry.value,
                )
                val items = if (q.isNotEmpty() && f.id != RadioFilters.local.id) c.radio.search(q) else if (q.isNotEmpty()) {
                    localItems.filter { it.title.contains(q, true) || it.subtitle.contains(q, true) }
                } else localItems
                ListState(items = items)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { ListState(error = e.message ?: "error") }
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
        val youtubeCountry = c.preferences.youtubeCountry.value
        val cacheKey = buildCacheKey(q, selected, audiusEnabled, youtubeEnabled, youtubeCountry)
        val now = System.currentTimeMillis()
        cache[cacheKey]?.takeIf { now - it.createdAt < CACHE_TTL_MS }?.let {
            state.value = it.state
            return
        }
        cache.remove(cacheKey)
        job = viewModelScope.launch {
            state.value = HomeState(loading = true)
            try {
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
                                if (c.youtubeData.isConfigured) c.youtubeData.search(searchQuery, localDiscovery)
                                else YouTubeSource.search(searchQuery, localDiscovery)
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
        listOf("geo-ar-v5-strict", selected?.name ?: "ALL", query.lowercase(), audiusEnabled, youtubeEnabled, youtubeCountry).joinToString("|")
}

class LocalVm(private val c: AppContainer) : ViewModel() {
    private val all = MutableStateFlow<List<Track>>(emptyList())
    private val filtered = MutableStateFlow<List<Track>>(emptyList())
    private val loading = MutableStateFlow(true)
    val query = MutableStateFlow("")
    val state = combine(filtered, loading) { items, isLoading -> ListState(loading = isLoading, items = items) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListState(loading = true))
    private var searchJob: Job? = null
    init { refresh() }
    fun onQuery(value: String) {
        query.value = value
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val q = value.trim()
            val source = all.value
            filtered.value = if (q.isBlank()) source else withContext(Dispatchers.Default) {
                source.filter { track ->
                    track.title.contains(q, true) || track.subtitle.contains(q, true) ||
                        track.album.contains(q, true) || track.artist.contains(q, true) || track.folder.contains(q, true)
                }
            }
        }
    }
    fun refresh() { viewModelScope.launch { loading.value = true; val items = runCatching { c.local.load() }.getOrDefault(emptyList()); all.value = items; filtered.value = items; loading.value = false } }
}
