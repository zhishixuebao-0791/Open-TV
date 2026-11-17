package com.vasthread.webviewtv.playlist

import java.net.URI
import java.util.LinkedHashMap

object M3uParser {
    data class Result(val playlist: Playlist, val rejectedEntries: Int, val hasHeader: Boolean)

    fun parse(content: String): Result {
        val merged = LinkedHashMap<String, Channel>()
        var name = ""
        var group = "默认"
        var rejected = 0
        var header = false
        for (raw in content.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            when {
                line.isBlank() -> Unit
                line.startsWith("#EXTM3U", true) -> header = true
                line.startsWith("#EXTINF", true) -> {
                    name = line.substringAfterLast(',', "").trim()
                    group = Regex("group-title=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.get(1)?.trim().orEmpty().ifEmpty { "默认" }
                }
                line.startsWith('#') -> Unit
                else -> {
                    if (name.isBlank() || !isSupportedUrl(line)) {
                        rejected++
                    } else {
                        val id = ChannelIdentity.key(group, name)
                        val channel = merged.getOrPut(id) { Channel(name, group) }
                        if (line !in channel.urls) channel.urls = channel.urls + line
                        if (name.length < channel.name.length) channel.name = name
                    }
                    name = ""
                }
            }
        }
        return Result(Playlist.createFromAllChannels("default", merged.values), rejected, header)
    }

    fun requireValid(content: String): Playlist {
        val result = parse(content)
        require(result.hasHeader && result.playlist.getAllChannels().isNotEmpty()) {
            "播放列表不是有效的 M3U 或没有可用频道"
        }
        return result.playlist
    }

    fun isSupportedUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
