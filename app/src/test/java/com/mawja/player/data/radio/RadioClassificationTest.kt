package com.mawja.player.data.radio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioClassificationTest {
    @Test fun rejectsMixedAndUnreviewedStations() {
        assertFalse(isNewsStation("Al Wisal", "arabic,music,news,talk"))
        assertFalse(isNewsStation("Qatar Radio", "news,talk"))
        assertFalse(isNewsStation("Coran", "coran,news,religion"))
        assertFalse(isNewsStation("Unknown broadcaster", "news"))
        assertFalse(isNewsStation("Al Arabiya FM", "news,music"))
    }

    @Test fun acceptsReviewedNewsFeeds() {
        assertTrue(isNewsStation("MC doualiya", "news"))
        assertTrue(isNewsStation("Radio Sawa", "news,talk"))
        assertTrue(isNewsStation("Al Arabiya FM", "news"))
        assertTrue(isNewsStation("Al araby", "news,news talk,world news"))
        assertTrue(isNewsStation("BBC Arabic ", "news"))
        assertTrue(isNewsStation("BBC Arabic Radio", "arabic,news,talk"))
    }

    @Test fun respectsAndroidTransportPolicy() {
        assertTrue(isPlayableRadioUrl("https://example.org/live"))
        assertTrue(isPlayableRadioUrl("http://uk7.internet-radio.com:8067/;"))
        assertFalse(isPlayableRadioUrl("http://example.org/live"))
        assertFalse(isPlayableRadioUrl("http://internet-radio.com.evil.example/live"))
        assertFalse(isPlayableRadioUrl("https://user:password@example.org/live"))
        assertFalse(isPlayableRadioUrl("file:///private"))
    }
}
