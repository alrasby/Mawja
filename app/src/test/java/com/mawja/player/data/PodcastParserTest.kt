package com.mawja.player.data

import org.junit.Assert.*
import org.junit.Test

class PodcastParserTest {
    private fun parse(xml: String) = PodcastParser.parse(xml.toByteArray(), "https://example.org/feed.xml")
    @Test fun rssEnclosuresAndDuration() {
        val tracks = parse("""<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>Show</title><item><title><![CDATA[Episode & one]]></title><enclosure url="/audio.mp3" type="audio/mpeg"/><itunes:duration>01:02:03</itunes:duration></item></channel></rss>""")
        assertEquals("https://example.org/audio.mp3", tracks.single().streamUrl)
        assertEquals("Episode & one", tracks.single().title)
        assertEquals(3723000L, tracks.single().durationMs)
        assertEquals(SourceType.PODCAST, tracks.single().source)
    }
    @Test fun atomAndDuplicateEnclosures() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Show</title><entry><title>Episode</title><link rel="enclosure" type="audio/ogg" href="https://example.org/1.ogg"/></entry><entry><link rel="enclosure" href="https://example.org/1.ogg"/></entry></feed>"""
        assertEquals(1, parse(xml).size)
    }
    @Test fun rejectsNonMediaAndUnsafeSchemes() {
        assertTrue(parse("""<rss><channel><item><enclosure url="file:///etc/passwd"/></item><item><enclosure url="https://example.org/video" type="video/mp4"/></item></channel></rss>""").isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsEntityDeclarations() {
        parse("""<!DOCTYPE rss [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss/>""")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsExcessiveDepth() { parse("<rss>" + "<x>".repeat(65) + "</x>".repeat(65) + "</rss>") }
}
