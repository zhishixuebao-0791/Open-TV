package com.vasthread.webviewtv

import com.vasthread.webviewtv.misc.HttpFetch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlaylistHttpTest {
    @Test fun canonicalRawRedirectKeepsConditionalHeadersAndAccepts304() = runBlocking {
        val origin = MockWebServer()
        val raw = MockWebServer()
        try {
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", raw.url("/playlist.m3u?signature=first")))
            raw.enqueue(MockResponse().setBody("#EXTM3U\n").setHeader("ETag", "\"v1\""))
            val first = HttpFetch.read(origin.url("/owner/repo/raw/master/playlist.m3u").toString(), 100)
            assertEquals(200, first.status)
            assertEquals("#EXTM3U\n", first.bytes.toString(Charsets.UTF_8))
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", raw.url("/playlist.m3u?signature=second")))
            raw.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"v1\""))
            val second = HttpFetch.read(origin.url("/owner/repo/raw/master/playlist.m3u").toString(), 100,
                etag = first.etag, revalidate = true)
            assertEquals(304, second.status)
            assertTrue(second.bytes.isEmpty())
            raw.takeRequest()
            val request = raw.takeRequest()
            assertEquals("/playlist.m3u?signature=second", request.path)
            assertEquals(first.etag, request.getHeader("If-None-Match"))
            assertEquals("no-cache", request.getHeader("Cache-Control"))
        } finally { origin.shutdown(); raw.shutdown() }
    }

    @Test fun conditionalGetReturns304WithoutAResponseBody() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("#EXTM3U\n").setHeader("ETag", "\"v1\""))
            server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"v1\""))
            val first = HttpFetch.read(server.url("/playlist.m3u").toString(), 100)
            assertEquals("\"v1\"", first.etag)
            val second = HttpFetch.read(server.url("/playlist.m3u").toString(), 100, etag = first.etag)
            assertEquals(304, second.status)
            assertTrue(second.bytes.isEmpty())
            server.takeRequest()
            assertEquals(first.etag, server.takeRequest().getHeader("If-None-Match"))
        } finally { server.shutdown() }
    }

    @Test fun unexpected304AndServerErrorsAreRejected() = runBlocking {
        val server = MockWebServer()
        try {
            for (status in listOf(304, 503)) {
                server.enqueue(MockResponse().setResponseCode(status))
                try { HttpFetch.read(server.url("/playlist.m3u").toString(), 100); fail("HTTP $status should fail") }
                catch (expected: IOException) { assertEquals("HTTP $status", expected.message) }
            }
        } finally { server.shutdown() }
    }

    @Test fun boundedReadAndManualRevalidationPreserveExistingFetchBehavior() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("0123456789"))
            val result = HttpFetch.read(server.url("/playlist.m3u").toString(), 4, revalidate = true)
            assertTrue(result.truncated)
            assertEquals("0123", result.bytes.toString(Charsets.UTF_8))
            assertEquals("no-cache", server.takeRequest().getHeader("Cache-Control"))
        } finally { server.shutdown() }
    }
}
