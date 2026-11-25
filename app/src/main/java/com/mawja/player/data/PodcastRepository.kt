package com.mawja.player.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** User subscriptions are durable; feed responses are expendable, bounded and expire after one hour. */
class PodcastRepository(context: Context, http: OkHttpClient) {
    private val http = http.newBuilder().callTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build()
    private val prefs = context.getSharedPreferences("podcasts", Context.MODE_PRIVATE)
    private val directory = File(context.cacheDir, "rss").apply { mkdirs() }
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val _subscriptions = MutableStateFlow(prefs.getStringSet("feeds", emptySet())!!.toList().sorted())
    val subscriptions = _subscriptions.asStateFlow()
    private val _episodes = MutableStateFlow<List<Track>>(emptyList())
    val episodes = _episodes.asStateFlow()

    suspend fun restore() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_episodes.value.isEmpty()) _episodes.value = _subscriptions.value.flatMap { cached(it) }.distinctBy { it.id }.take(1500)
        }
    }

    suspend fun subscribe(url: String) = withContext(Dispatchers.IO) {
        val normalized = java.net.URI(url.trim()).normalize().toString()
        require(java.net.URI(normalized).scheme == "https") { "HTTPS required" }
        mutex.withLock {
            require(_subscriptions.value.size < 50 || normalized in _subscriptions.value) { "Subscription limit" }
            val tracks = load(normalized, false)
            require(tracks.isNotEmpty()) { "No playable RSS episodes" }
            val next = (_subscriptions.value + normalized).distinct().sorted()
            check(prefs.edit().putStringSet("feeds", next.toSet()).commit())
            _subscriptions.value = next
            _episodes.value = (_episodes.value + tracks).distinctBy { it.id }.take(1500)
        }
    }

    suspend fun remove(url: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = _subscriptions.value - url
            check(prefs.edit().putStringSet("feeds", next.toSet()).commit())
            _subscriptions.value = next
            _episodes.value = _episodes.value.filterNot { it.folder == url }
            AtomicFile(cacheFile(url)).delete()
        }
    }

    /** Failed feeds preserve cached episodes and don't prevent other subscriptions from loading. */
    suspend fun refresh(force: Boolean = false): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            var failures = 0
            val all = _subscriptions.value.flatMap { url ->
                try { load(url, force) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) {
                    failures++
                    cached(url).ifEmpty { _episodes.value.filter { it.folder == url } }
                }
            }
            _episodes.value = all.distinctBy { it.id }.take(1500)
            trimCache()
            failures
        }
    }

    private fun cacheFile(url: String) = File(directory, digest(url) + ".json")
    private fun cached(url: String): List<Track> = try {
        json.decodeFromString<List<Track>>(AtomicFile(cacheFile(url)).readFully().decodeToString())
    } catch (_: Exception) { emptyList() }

    private fun load(url: String, force: Boolean): List<Track> {
        val file = cacheFile(url)
        if (!force && file.exists() && System.currentTimeMillis() - file.lastModified() in 0..3_600_000) {
            cached(url).takeIf { it.isNotEmpty() }?.let { return it }
        }
        val xml = http.newCall(Request.Builder().url(url).header("Accept", "application/rss+xml, application/atom+xml, application/xml").build()).execute().use { response ->
            check(response.isSuccessful && response.request.url.isHttps) { "Feed unavailable" }
            val body = requireNotNull(response.body)
            require(body.contentLength() <= 2_097_152) { "Feed too large" }
            val bytes = java.io.ByteArrayOutputStream().also { output ->
                val input = body.byteStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 2_097_152) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 2_097_153 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }.toByteArray()
            require(bytes.size <= 2_097_152) { "Feed too large" }
            bytes
        }
        val tracks = PodcastParser.parse(xml, url)
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try { out.write(json.encodeToString(tracks).toByteArray()); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
        trimCache()
        return tracks
    }

    private fun trimCache() {
        var size = 0L
        directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEachIndexed { index, file ->
            size += file.length()
            if (index >= 50 || size > 16L * 1024 * 1024) file.delete()
        }
    }
}

internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
