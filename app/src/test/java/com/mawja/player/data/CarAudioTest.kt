package com.mawja.player.data

import org.junit.Assert.*
import org.junit.Test

class CarAudioTest {
    @Test fun youtubeVideoBecomesPlayableAudioWithoutChangingIdOrOriginal() {
        val original = Track("yt:abcdefghijk", SourceType.YOUTUBE, "Video", isVideo = true,
            artworkUrl = "https://example.org/art.jpg", durationMs = 100_000)
        val audio = original.forCarAudio()
        assertFalse(audio.isVideo)
        assertTrue(original.isVideo)
        assertEquals(original.id, audio.id)
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", audio.playbackUri)
        assertFalse(audio.playbackUri!!.contains("mawja_video"))
        assertEquals(original.artworkUrl, audio.artworkUrl)
        assertEquals(original.durationMs, audio.durationMs)
        assertEquals(audio, audio.forCarAudio())
    }
    @Test fun localVideoKeepsAudioFilterPrefixAndContentUri() {
        val original = Track("local:42", SourceType.LOCAL, "Local", isVideo = true, streamUrl = "content://media/video/42")
        val audio = original.forCarAudio()
        assertEquals(CAR_LOCAL_VIDEO_AUDIO_PREFIX + original.id, audio.id)
        assertFalse(audio.isVideo)
        assertEquals(original.playbackUri, audio.playbackUri)
        assertEquals(audio, audio.forCarAudio())
    }
    @Test fun existingAudioAndRadioStayUnchanged() {
        listOf(SourceType.RADIO, SourceType.LOCAL, SourceType.YOUTUBE, SourceType.PODCAST).forEach { source ->
            val audio = Track("item", source, "Audio")
            assertSame(audio, audio.forCarAudio())
        }
    }
}
