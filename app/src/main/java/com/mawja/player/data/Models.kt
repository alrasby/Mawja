package com.mawja.player.data

import kotlinx.serialization.Serializable

enum class SourceType { RADIO, LOCAL, AUDIUS, YOUTUBE }

/**
 * Returns YouTube's stable thumbnail endpoint. Optional variants such as
 * maxresdefault can return 404 for ordinary videos; hqdefault is a real frame
 * and is available consistently.
 */
fun youtubeThumbnailUrl(videoId: String, candidate: String? = null): String {
    val id = videoId.trim().removePrefix("yt:")
    return if (id.length == 11) {
        "https://img.youtube.com/vi/$id/hqdefault.jpg"
    } else {
        candidate?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty()
    }
}

/** Ordered real thumbnail endpoints used when a CDN variant is unavailable. */
fun youtubeThumbnailCandidates(videoId: String): List<String> {
    val id = videoId.trim().removePrefix("yt:")
    if (id.length != 11) return emptyList()
    return listOf(
        "https://img.youtube.com/vi/$id/hqdefault.jpg",
        "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        "https://img.youtube.com/vi/$id/0.jpg",
        "https://i.ytimg.com/vi/$id/0.jpg",
    )
}

/** One playable item. IDs are prefixed by source (radio:, yt:, local:, etc.). */
@Serializable
data class Track(
    val id: String,
    val source: SourceType,
    val title: String,
    val subtitle: String = "",
    val artworkUrl: String? = null,
    val streamUrl: String? = null,
    val durationMs: Long = 0L,
    val isLive: Boolean = false,
    val album: String = "",
    val artist: String = "",
    val folder: String = "",
    val isVideo: Boolean = false,
) {
    /** YouTube starts as a watch URL and is resolved to a signed audio URL immediately before playback. */
    val playbackUri: String?
        get() = when (source) {
            SourceType.YOUTUBE -> "https://www.youtube.com/watch?v=" + id.removePrefix("yt:") + if (isVideo) "&mawja_video=1" else ""
            else -> streamUrl
        }
}

data class ListState(
    val loading: Boolean = false,
    val items: List<Track> = emptyList(),
    val error: String? = null,
)
