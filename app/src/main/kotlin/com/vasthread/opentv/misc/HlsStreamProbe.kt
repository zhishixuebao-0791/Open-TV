package com.vasthread.webviewtv.misc

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object HlsStreamProbe {

    private const val TAG = "HlsStreamProbe"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val PACKET_SIZE = 188
    private const val SAMPLE_PACKETS = 3_000
    private const val MAX_SEGMENT_CANDIDATES = 3
    private const val MAX_PROBE_ATTEMPTS = 2
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private const val TABLE_ID_PAT = 0x00
    private const val TABLE_ID_PMT = 0x02

    private const val STREAM_TYPE_MPEG1_AUDIO = 0x03
    private const val STREAM_TYPE_MPEG2_AUDIO = 0x04

    data class ProbeResult(
        val audioStreamTypes: Set<Int>,
        val videoStreamTypes: Set<Int>
    ) {
        val requiresWebViewAudioPath: Boolean
            get() = audioStreamTypes.any { it == STREAM_TYPE_MPEG1_AUDIO || it == STREAM_TYPE_MPEG2_AUDIO }
    }

    fun probe(url: String): ProbeResult? {
        if (!url.contains(".m3u8", ignoreCase = true)) return null
        repeat(MAX_PROBE_ATTEMPTS) { attempt ->
            val playlistUrl = appendProbeNonce(url, attempt)
            val playlist = runCatching { readPlaylist(playlistUrl) }
                .onFailure { Log.w(TAG, "Failed to fetch playlist for probe $url (attempt=${attempt + 1})", it) }
                .getOrNull()
                ?: return@repeat

            val segmentUrls = runCatching { parseSegmentUrls(playlist.resolvedUrl, playlist.content) }.getOrDefault(emptyList())
            for (segmentUrl in segmentUrls) {
                val result = runCatching { probeSegment(segmentUrl) }
                    .onFailure {
                        Log.w(
                            TAG,
                            "Failed probing segment for $url (attempt=${attempt + 1}) segment=$segmentUrl",
                            it
                        )
                    }
                    .getOrNull()
                if (result != null) {
                    Log.i(
                        TAG,
                        "Detected audio=${result.audioStreamTypes.joinToString()}, video=${result.videoStreamTypes.joinToString()} for $url"
                    )
                    return result
                }
            }
        }
        return null
    }

    private fun probeSegment(segmentUrl: String): ProbeResult? {
        val data = readBytes(segmentUrl, PACKET_SIZE * SAMPLE_PACKETS)
        return parseTransportStream(data)
    }

    fun parseTransportStream(data: ByteArray): ProbeResult? {
        val patSection = extractSection(data, 0, TABLE_ID_PAT) ?: return null
        val pmtPid = parsePatProgramMapPid(patSection) ?: return null
        val pmtSection = extractSection(data, pmtPid, TABLE_ID_PMT) ?: return null
        val streams = parsePmtStreamTypes(pmtSection)
        if (streams.isEmpty()) return null
        return ProbeResult(
            audioStreamTypes = streams.filter { it.isAudio() }.map { it.streamType }.toSet(),
            videoStreamTypes = streams.filter { it.isVideo() }.map { it.streamType }.toSet()
        )
    }

    private fun parseSegmentUrls(playlistUrl: String, playlistContent: String): List<String> {
        val allSegments = ArrayList<String>()
        for (line in playlistContent.lineSequence()) {
            val value = line.trim()
            if (value.isEmpty() || value.startsWith("#")) continue
            allSegments += URL(URL(playlistUrl), value).toString()
        }
        if (allSegments.isEmpty()) return emptyList()
        // Probe newest segments first to reduce false 404 from stale live-edge entries.
        return allSegments.takeLast(MAX_SEGMENT_CANDIDATES).asReversed()
    }

    private fun extractSection(data: ByteArray, targetPid: Int, tableId: Int): ByteArray? {
        val buffer = ByteArrayOutputStream()
        var collecting = false
        var expectedLength = -1

        var offset = 0
        while (offset + PACKET_SIZE <= data.size) {
            val packet = data.copyOfRange(offset, offset + PACKET_SIZE)
            offset += PACKET_SIZE
            if (packet[0].toInt() != 0x47) continue

            val pid = ((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)
            if (pid != targetPid) continue

            val payloadUnitStart = (packet[1].toInt() and 0x40) != 0
            val adaptationFieldControl = (packet[3].toInt() ushr 4) and 0x03
            if (adaptationFieldControl == 0 || adaptationFieldControl == 2) continue

            var payloadOffset = 4
            if (adaptationFieldControl == 3) {
                val adaptationFieldLength = packet[4].toInt() and 0xFF
                payloadOffset += 1 + adaptationFieldLength
            }
            if (payloadOffset >= PACKET_SIZE) continue

            if (payloadUnitStart) {
                val pointerField = packet[payloadOffset].toInt() and 0xFF
                payloadOffset += 1 + pointerField
                buffer.reset()
                collecting = true
                expectedLength = -1
            }

            if (!collecting || payloadOffset >= PACKET_SIZE) continue

            buffer.write(packet, payloadOffset, PACKET_SIZE - payloadOffset)
            val bytes = buffer.toByteArray()
            if (expectedLength == -1 && bytes.size >= 3) {
                if ((bytes[0].toInt() and 0xFF) != tableId) {
                    collecting = false
                    buffer.reset()
                    continue
                }
                expectedLength = 3 + (((bytes[1].toInt() and 0x0F) shl 8) or (bytes[2].toInt() and 0xFF))
            }
            if (expectedLength != -1 && bytes.size >= expectedLength) {
                return bytes.copyOf(expectedLength)
            }
        }
        return null
    }

    private fun parsePatProgramMapPid(section: ByteArray): Int? {
        val end = section.size - 4
        var offset = 8
        while (offset + 4 <= end) {
            val programNumber = ((section[offset].toInt() and 0xFF) shl 8) or
                (section[offset + 1].toInt() and 0xFF)
            val programMapPid = ((section[offset + 2].toInt() and 0x1F) shl 8) or
                (section[offset + 3].toInt() and 0xFF)
            if (programNumber != 0) return programMapPid
            offset += 4
        }
        return null
    }

    private fun parsePmtStreamTypes(section: ByteArray): List<ElementaryStreamInfo> {
        if (section.size < 16) return emptyList()
        val programInfoLength = ((section[10].toInt() and 0x0F) shl 8) or (section[11].toInt() and 0xFF)
        val end = section.size - 4
        var offset = 12 + programInfoLength
        val streams = ArrayList<ElementaryStreamInfo>()
        while (offset + 5 <= end) {
            val streamType = section[offset].toInt() and 0xFF
            val elementaryPid = ((section[offset + 1].toInt() and 0x1F) shl 8) or
                (section[offset + 2].toInt() and 0xFF)
            val esInfoLength = ((section[offset + 3].toInt() and 0x0F) shl 8) or
                (section[offset + 4].toInt() and 0xFF)
            streams += ElementaryStreamInfo(streamType, elementaryPid)
            offset += 5 + esInfoLength
        }
        return streams
    }

    private fun readPlaylist(url: String): PlaylistResponse {
        val conn = openConnection(url)
        conn.inputStream.use { input ->
            return PlaylistResponse(
                content = input.bufferedReader().readText(),
                resolvedUrl = conn.url.toString()
            )
        }
    }

    private fun readBytes(url: String, limit: Int): ByteArray {
        val conn = openConnection(url)
        conn.inputStream.use { input ->
            val out = ByteArrayOutputStream(limit)
            val buffer = ByteArray(8 * 1024)
            var remain = limit
            while (remain > 0) {
                val toRead = minOf(buffer.size, remain)
                val read = input.read(buffer, 0, toRead)
                if (read <= 0) break
                out.write(buffer, 0, read)
                remain -= read
            }
            return out.toByteArray()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
        }
    }

    private fun appendProbeNonce(url: String, attempt: Int): String {
        // Signed stream URLs must be requested unchanged.
        return url
    }

    private data class ElementaryStreamInfo(
        val streamType: Int,
        val pid: Int
    ) {
        fun isAudio(): Boolean = streamType in setOf(0x03, 0x04, 0x0F, 0x11, 0x81, 0x87)
        fun isVideo(): Boolean = streamType in setOf(0x01, 0x02, 0x1B, 0x24)
    }

    private data class PlaylistResponse(
        val content: String,
        val resolvedUrl: String
    )
}
