package com.vasthread.webviewtv.misc

/** Own one physical press until release, even when its UI owner goes away. */
class RemotePress {
    private data class Identity(val key: Int, val device: Int, val downTime: Long)
    private var identity: Identity? = null
    private var valid = false
    private var longHandled = false

    fun begin(key: Int, device: Int, downTime: Long) {
        identity = Identity(key, device, downTime)
        valid = true
        longHandled = false
    }

    fun matches(key: Int, device: Int, downTime: Long): Boolean =
        identity == Identity(key, device, downTime)

    fun takeLong(now: Long, timeout: Long): Boolean {
        val press = identity ?: return false
        if (!valid || longHandled || now - press.downTime < timeout) return false
        longHandled = true
        return true
    }

    fun release(key: Int, device: Int, downTime: Long, cancelled: Boolean): Boolean {
        if (!matches(key, device, downTime)) return false
        val shortPress = valid && !longHandled && !cancelled
        reset()
        return shortPress
    }

    fun invalidate() { valid = false }

    fun reset() {
        identity = null
        valid = false
        longHandled = false
    }
}

/** Empty/single-source channels must not restart playback when an arrow is pressed. */
fun adjacentSourceIndex(urls: List<String>, current: Int, forward: Boolean): Int? {
    val indices = urls.indices.filter { urls[it].isNotBlank() }
    if (indices.size < 2) return null
    val position = indices.indexOf(current)
    if (position < 0) return if (forward) indices.first() else indices.last()
    return indices[(position + (if (forward) 1 else -1) + indices.size) % indices.size]
}
