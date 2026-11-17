package com.vasthread.webviewtv

import com.vasthread.webviewtv.misc.*
import com.vasthread.webviewtv.playlist.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class PlaybackRegressionTest {
    @Test fun m3uMergesAliasesWithoutMergingDifferentChannels() {
        val text = """
            #EXTM3U
            #EXTINF:-1,CCTV-12
            http://example.com/123
            #EXTINF:-1,CCTV12高清
            http://example.com/test.php?id=2
            #EXTINF:-1,CCTV1
            http://example.com/one.m3u8
            #EXTINF:-1,CCTV10
            http://example.com/ten.m3u8
            #EXTINF:-1,湖南卫视
            http://example.com/hn
            #EXTINF:-1,湖南卫视国际
            http://example.com/hnworld
        """.trimIndent()
        val result = M3uParser.requireValid(text).getAllChannels()
        assertEquals(5, result.size)
        assertEquals(2, result.first().urls.size)
        assertEquals(result.first(), Channel("CCTV12", "默认", listOf("http://example.com/new")))
        assertNotEquals(ChannelIdentity.normalizeName("CCTV4K"), ChannelIdentity.normalizeName("CCTV4"))
        assertEquals(ChannelIdentity.normalizeName("CCTV4K"), ChannelIdentity.normalizeName("CCTV4K高清"))
        assertEquals(ChannelIdentity.normalizeName("CCTV风云音乐"), ChannelIdentity.normalizeName("风云音乐"))
    }

    @Test fun invalidAndEmptyDataCannotBecomeRemoteCache() {
        for (text in listOf("[]", "<html>maintenance</html>", "#EXTM3U", "#EXTM3U\n#EXTINF:-1,A\nfile:///etc/passwd")) {
            assertTrue(runCatching { M3uParser.requireValid(text) }.isFailure)
        }
        assertTrue(M3uParser.parse("#EXTM3U").playlist.getAllChannels().isEmpty())
        assertEquals(1, M3uParser.parse("#EXTM3U\n#EXTINF:-1,Bad\nhttp://[broken").rejectedEntries)
    }

    @Test fun numericHlsMasterAndFragmentedMp4StayNative() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nchild?token=signed"))
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\nchunk.m4s?token=signed"))
            val result = StreamResolver.resolve(server.url("/624878405?auth=original").toString())
            assertEquals(StreamKind.HLS, result.kind)
            assertEquals("application/x-mpegURL", result.mimeType)
            assertFalse(result.needsWebAudio)
            assertEquals("/624878405?auth=original", server.takeRequest().path)
            assertEquals("/child?token=signed", server.takeRequest().path)
        }
    }

    @Test fun malformedHlsSegmentDoesNotEscapeOrForceWeb() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:4,\nhttp://[malformed"))
            val result = StreamResolver.resolve(server.url("/stream.m3u8").toString())
            assertEquals(StreamKind.HLS, result.kind)
            assertFalse(result.needsWebAudio)
        }
    }

    @Test fun realHtmlIsSeparatedFromFlvAndDash() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<!DOCTYPE html><html>Video</html>"))
            assertEquals(StreamKind.HTML, StreamResolver.resolve(server.url("/live.php").toString()).kind)
        }
        assertEquals("video/x-flv", StreamResolver.guess("http://example.com/live.flv?token=1").mimeType)
        assertEquals(StreamKind.DASH, StreamResolver.guess("http://example.com/a.mpd?token=1").kind)
        assertEquals(StreamKind.SMOOTH_STREAMING, StreamResolver.guess("http://example.com/a.ism/Manifest").kind)
    }

    @Test fun boundedHttpReadsAndCancellationFinishPromptly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(100)))
            val result = HttpFetch.read(server.url("/bounded").toString(), 10)
            assertEquals(10, result.bytes.size)
            assertTrue(result.truncated)
            server.enqueue(MockResponse().setBody("slow").setBodyDelay(2, TimeUnit.SECONDS))
            val started = System.nanoTime()
            assertNull(withTimeoutOrNull(150) { HttpFetch.read(server.url("/slow").toString(), 100) })
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500)
        }
    }

    @Test fun publicAssetsContainNoBundledPlaylist() {
        val assets = java.io.File("src/main/assets").walkTopDown().filter { it.isFile }.toList()
        assertFalse(assets.any { it.extension.lowercase() in setOf("m3u", "m3u8", "json", "dat") })
    }

    @Test fun repeatedChannelsRetainEveryDistinctSource() {
        val text = """#EXTM3U
            |#EXTINF:-1 group-title="测试组",测试频道
            |https://media.example.invalid/a.m3u8
            |#EXTINF:-1 group-title="测试组",测试频道
            |https://media.example.invalid/b.m3u8
            |#EXTINF:-1 group-title="测试组",测试频道
            |https://media.example.invalid/a.m3u8
        """.trimMargin()
        val channels = M3uParser.requireValid(text).getAllChannels()
        assertEquals(1, channels.size)
        assertEquals(listOf("https://media.example.invalid/a.m3u8", "https://media.example.invalid/b.m3u8"), channels.single().urls)
    }
}
