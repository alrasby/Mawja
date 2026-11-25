package com.mawja.player.data.youtube

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StreamProbeTest {
    @Test fun rejectsForbiddenUrlSoCallerCanTryAnotherProvider() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.start()
            try { StreamProbe(OkHttpClient()).check(server.url("/audio").toString(), "test-agent"); fail("403 accepted") }
            catch (expected: IOException) { assertTrue(expected.message!!.contains("403")) }
        }
    }
    @Test fun acceptsRangeAudioAndSendsPlaybackHeaders() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type", "audio/mpeg").setBody("ID3audio"))
            server.start()
            StreamProbe(OkHttpClient()).check(server.url("/audio").toString(), "test-agent")
            val request = server.takeRequest()
            assertEquals("bytes=0-1023", request.getHeader("Range"))
            assertEquals("test-agent", request.getHeader("User-Agent"))
            assertEquals("https://www.youtube.com/", request.getHeader("Referer"))
        }
    }
    @Test fun rejectsHtmlMasqueradingAsMedia() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("<html>blocked</html>"))
            server.start()
            try { StreamProbe(OkHttpClient()).check(server.url("/audio").toString(), "test-agent"); fail("HTML accepted") }
            catch (_: IOException) { }
        }
    }
}
