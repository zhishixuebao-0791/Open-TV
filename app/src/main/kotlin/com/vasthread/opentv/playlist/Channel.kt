package com.vasthread.webviewtv.playlist

import com.google.gson.annotations.SerializedName
import com.vasthread.webviewtv.settings.SettingsManager

data class Channel @JvmOverloads constructor(
    var name: String = "",
    @SerializedName("group")
    var groupName: String = "",
    var urls: List<String> = emptyList(),
) {

    val stableId: String get() = ChannelIdentity.key(groupName, name)

    val url: String
    get() {
        if (urls.isEmpty()) return "" 
        var index = SettingsManager.getChannelLastSourceIndex(this)
        if (index >= urls.size || index < 0) {
            index = 0
        }
        return urls.getOrNull(index) ?: ""
    }

    override fun equals(other: Any?): Boolean {
        return other is Channel && stableId == other.stableId
    }

    override fun toString(): String {
        return "name=$name, groupName=$groupName, urls=$urls"
    }

    override fun hashCode(): Int {
        return stableId.hashCode()
    }
}
