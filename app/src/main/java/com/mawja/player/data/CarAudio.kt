package com.mawja.player.data

/** A car library has its own audio presentation; never mutate the phone's video item. */
internal const val CAR_LOCAL_VIDEO_AUDIO_PREFIX = "auto:video-audio:"

internal fun Track.forCarAudio(): Track = when {
    source == SourceType.YOUTUBE && isVideo -> copy(isVideo = false)
    source == SourceType.LOCAL && isVideo -> copy(
        id = if (id.startsWith(CAR_LOCAL_VIDEO_AUDIO_PREFIX)) id else CAR_LOCAL_VIDEO_AUDIO_PREFIX + id,
        artworkUrl = null,
        isVideo = false,
    )
    else -> this
}
