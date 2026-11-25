package com.mawja.player.data

import org.junit.Assert.*
import org.junit.Test

class EpisodeDurationTest {
    @Test fun elapsedDurationsDoNotWrapAfterAnHour() {
        assertEquals("0:00", episodeDurationLabel(0))
        assertEquals("1:05", episodeDurationLabel(65_999))
        assertEquals("1:02:03", episodeDurationLabel(3_723_000))
        assertEquals("", episodeDurationLabel(-1))
    }
}
