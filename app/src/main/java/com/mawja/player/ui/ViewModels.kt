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

    init { load() }

    fun selectSource(source: SourceType?) { selectedSource.value = source; load() }
    fun onQuery(q: String) { query.value = q; if (q.isBlank()) load() }

    fun load() {
        job?.cancel()
        val q = query.value.trim()
        job = viewModelScope.launch {
            state.value = HomeState(loading = true)
            state.value = try {
                withTimeoutOrNull(20_000L) {
                    if (q.isNotEmpty()) {
                        val items = when (selectedSource.value) {
                            SourceType.AUDIUS -> c.audius.search(q)
                            SourceType.YOUTUBE -> if (c.youtubeData.isConfigured) c.youtubeData.search(q) else YouTubeSource.search(q)
                            else -> (c.audius.search(q) + (if (c.youtubeData.isConfigured) c.youtubeData.search(q) else YouTubeSource.search(q))).distinctBy { it.id }
                        }
                        HomeState(sections = listOf(HomeSection(R.string.search_results, items)))
                    } else {
                        val sections = buildList {
                            if (c.preferences.audiusEnabled.value) {
                                val trending = async { c.audius.trending() }
                                add(HomeSection(R.string.new_albums, trending.await()))
                            }
                            if (c.preferences.youtubeEnabled.value) {
                                val yt = async { if (c.youtubeData.isConfigured) c.youtubeData.search("new music", localDiscovery = true) else YouTubeSource.search("new music", localDiscovery = true) }
                                add(HomeSection(R.string.youtube_picks, yt.await()))
                            }
                        }
                        HomeState(sections = sections)
                    }
                } ?: HomeState(error = "timeout")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { HomeState(error = e.message ?: "error") }
        }
    }
}

class LocalVm(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val state = MutableStateFlow(ListState(loading = true))
    private var job: Job? = null

    init { load() }

    fun onQuery(q: String) { query.value = q; load() }

    fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            state.value = ListState(loading = true)
            state.value = try {
                val all = withContext(Dispatchers.IO) { c.local.load() }
                val q = query.value.trim()
                val items = if (q.isEmpty()) all else all.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) || it.folder.contains(q, true) }
                ListState(items = items)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { ListState(error = e.message ?: "error") }
        }
    }
}
