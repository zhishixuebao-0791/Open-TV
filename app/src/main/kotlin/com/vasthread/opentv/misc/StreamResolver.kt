package com.vasthread.webviewtv.misc

import kotlinx.coroutines.CancellationException
import java.net.URI
import java.util.Locale

enum class StreamKind { HLS, PROGRESSIVE, DASH, SMOOTH_STREAMING, HTML }

data class StreamDescriptor(
    val kind: StreamKind,
    val mimeType: String? = null,
    val needsWebAudio: Boolean = false
)

object StreamResolver {
    fun guess(url: String): StreamDescriptor {
        val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return when {
            path.endsWith(".m3u8") || path.endsWith(".php") -> StreamDescriptor(StreamKind.HLS, "application/x-mpegURL")
            path.endsWith(".mpd") -> StreamDescriptor(StreamKind.DASH, "application/dash+xml")
            path.endsWith(".ism") || path.endsWith(".isml") || path.contains(".ism/manifest") ->
                StreamDescriptor(StreamKind.SMOOTH_STREAMING, "application/vnd.ms-sstr+xml")
            path.endsWith(".flv") -> StreamDescriptor(StreamKind.PROGRESSIVE, "video/x-flv")
            path.endsWith(".ts") -> StreamDescriptor(StreamKind.PROGRESSIVE, "video/mp2t")
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") ->
                StreamDescriptor(StreamKind.PROGRESSIVE, "video/mp4")
            path.endsWith(".mp3") -> StreamDescriptor(StreamKind.PROGRESSIVE, "audio/mpeg")
            path.endsWith(".aac") -> StreamDescriptor(StreamKind.PROGRESSIVE, "audio/aac")
            else -> StreamDescriptor(StreamKind.PROGRESSIVE)
        }
    }

    suspend fun resolve(url: String): StreamDescriptor {
        val guessed = guess(url)
        val path = URI(url).path.orEmpty().lowercase(Locale.ROOT)
        if (guessed.kind in setOf(StreamKind.DASH, StreamKind.SMOOTH_STREAMING) ||
            path.endsWithAny(".flv", ".ts", ".mp4", ".m4v", ".mov", ".mkv", ".webm", ".mp3", ".aac", ".ac3")) {
            return guessed
        }
        val response = HttpFetch.read(url, 256 * 1024)
        val text = response.bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
        val type = response.contentType.substringBefore(';').lowercase(Locale.ROOT)
        return when {
            text.startsWith("#EXTM3U") || type in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl") -> {
                val probe = try {
                    probeHls(response.url, text, 0)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null // Unknown container/audio is still a native HLS candidate.
                }
                StreamDescriptor(StreamKind.HLS, "application/x-mpegURL", probe?.requiresWebViewAudioPath == true)
            }
            type.contains("dash") || text.startsWith("<MPD") || text.contains("<MPD ") ->
                StreamDescriptor(StreamKind.DASH, "application/dash+xml")
            type == "text/html" || text.startsWith("<!DOCTYPE html", true) || text.startsWith("<html", true) ->
                StreamDescriptor(StreamKind.HTML, "text/html")
            response.bytes.size >= 3 && response.bytes.copyOfRange(0, 3).contentEquals(byteArrayOf(70, 76, 86)) ->
                StreamDescriptor(StreamKind.PROGRESSIVE, "video/x-flv")
            response.bytes.firstOrNull() == 0x47.toByte() -> StreamDescriptor(StreamKind.PROGRESSIVE, "video/mp2t")
            type.startsWith("video/") || type.startsWith("audio/") -> StreamDescriptor(StreamKind.PROGRESSIVE, type)
            else -> guessed
        }
    }

    private suspend fun probeHls(base: String, content: String, depth: Int): HlsStreamProbe.ProbeResult? {
        if (depth > 3 || content.contains("#EXT-X-MAP") || content.contains("#EXT-X-KEY")) return null
        val entries = content.lineSequence().map(String::trim).filter { it.isNotBlank() && !it.startsWith('#') }.toList()
        if (entries.isEmpty()) return null
        if (content.contains("#EXT-X-STREAM-INF")) {
            val child = HttpFetch.read(resolveUri(base, entries.first()), 256 * 1024)
            return probeHls(child.url, child.bytes.toString(Charsets.UTF_8), depth + 1)
        }
        for (entry in entries.takeLast(2).asReversed()) {
            try {
                val segment = HttpFetch.read(resolveUri(base, entry), 188 * 500, range = true)
                HlsStreamProbe.parseTransportStream(segment.bytes)?.let { return it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Another recent segment may still be available near a live window edge.
            }
        }
        return null
    }

    private fun resolveUri(base: String, value: String): String {
        val resolved = URI(base).resolve(value)
        require(resolved.scheme in setOf("http", "https") && resolved.host != null)
        return resolved.toString()
    }

    private fun String.endsWithAny(vararg suffixes: String) = suffixes.any(::endsWith)
}
