package com.vasthread.webviewtv.settings

import com.vasthread.webviewtv.misc.preference
import com.vasthread.webviewtv.playlist.PlaylistManager
import com.vasthread.webviewtv.playlist.Channel
import com.vasthread.webviewtv.playlist.ChannelIdentity
import java.util.UUID

object SettingsManager {

    private const val KEY_WEB_VIEW_TOUCHABLE = "web_view_touchable"
    private const val KEY_MAX_LOADING_TIME = "max_loading_time"
    private const val KEY_UUID = "uuid"
    private const val KEY_REVERSE_CHANNEL_DIRECTION = "reverse_channel_direction"
    private const val KEY_AUTO_START = "auto_start_on_boot"

    fun isAutoStartEnabled(): Boolean = preference.getBoolean(KEY_AUTO_START, false)

    internal fun saveAutoStartEnabled(enabled: Boolean) {
        // A reboot immediately after changing this option must observe the new value.
        preference.edit().putBoolean(KEY_AUTO_START, enabled).commit()
    }

    fun isChannelDirectionReversed(): Boolean = preference.getBoolean(KEY_REVERSE_CHANNEL_DIRECTION, false)

    fun setChannelDirectionReversed(reversed: Boolean) {
        preference.edit().putBoolean(KEY_REVERSE_CHANNEL_DIRECTION, reversed).apply()
    }

    fun getPlaylistNames(): Array<String> {
        val builtInPlaylists = PlaylistManager.getBuiltInPlaylists()
        val names = arrayOfNulls<String>(PlaylistManager.getBuiltInPlaylists().size)
        for (i in names.indices) {
            names[i] = builtInPlaylists[i].first
        }
        return names.requireNoNulls()
    }

    fun getSelectedPlaylistPosition(): Int {
        val playlistUrl = PlaylistManager.getPlaylistUrl()
        val builtInPlaylists = PlaylistManager.getBuiltInPlaylists()
        for (i in builtInPlaylists.indices) {
            if (builtInPlaylists[i].second == playlistUrl) {
                return i
            }
        }
        return 0
    }

    fun setSelectedPlaylistPosition(position: Int) {
        val builtInPlaylists = PlaylistManager.getBuiltInPlaylists()
        PlaylistManager.setPlaylistUrl(builtInPlaylists[position].second)
    }

    fun setWebViewTouchable(touchable: Boolean) {
        preference.edit().putBoolean(KEY_WEB_VIEW_TOUCHABLE, touchable).apply()
    }

    fun isWebViewTouchable(): Boolean {
        return preference.getBoolean(KEY_WEB_VIEW_TOUCHABLE, false)
    }

    fun setMaxLoadingTime(second: Int) {
        preference.edit().putInt(KEY_MAX_LOADING_TIME, second.coerceIn(10, 30)).apply()
    }

    fun getMaxLoadingTime(): Int {
        return preference.getInt(KEY_MAX_LOADING_TIME, 15).coerceIn(10, 30)
    }

    private fun lastSourceIndexKey(channelName: String) = "source_index[$channelName]"

    fun getChannelLastSourceIndex(channel: Channel): Int {
        val savedUrl = preference.getString("source_url[${channel.stableId}]", null)
        if (savedUrl != null) return channel.urls.indexOf(savedUrl).coerceAtLeast(0)
        val legacy = preference.all.entries.firstOrNull {
            it.key.startsWith("source_index[") &&
                ChannelIdentity.normalizeName(it.key.removePrefix("source_index[").removeSuffix("]")) ==
                ChannelIdentity.normalizeName(channel.name)
        }?.value as? Int ?: 0
        return legacy.coerceIn(0, (channel.urls.size - 1).coerceAtLeast(0))
    }

    fun setChannelLastSourceIndex(channel: Channel, index: Int) {
        val url = channel.urls.getOrNull(index) ?: return
        preference.edit().putString("source_url[${channel.stableId}]", url).apply()
    }

    fun setChannelLastSourceIndex(channelName: String, index: Int) {
        preference.edit().putInt(lastSourceIndexKey(channelName), index).apply()
    }

    fun getChannelLastSourceIndex(channelName: String): Int {
        return preference.getInt(lastSourceIndexKey(channelName), 0)
    }

    fun getUserId(): String {
        var uuid = preference.getString(KEY_UUID, null)
        if (uuid.isNullOrBlank()) {
            uuid = UUID.randomUUID().toString()
            preference.edit().putString(KEY_UUID, uuid).apply()
        }
        return uuid
    }
}
