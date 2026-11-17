package com.vasthread.webviewtv.playlist

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import com.vasthread.webviewtv.misc.HttpFetch
import com.vasthread.webviewtv.misc.application
import com.vasthread.webviewtv.misc.preference
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PlaylistManager {
    private const val KEY_URL = "playlist_url"
    private const val KEY_ONLINE = "online_playlist_url"
    private const val KEY_BACKUP = "backup_playlist_url"
    private const val POLL_MS = 30 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cacheMutex = Mutex()
    private var updateJob: Job? = null
    private var watchJob: Job? = null
    private var generation = 0
    private var foreground = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastAttempt = -POLL_MS
    private var nextCheck = 0L
    private var lastFailureRefresh = -POLL_MS
    private var failures = 0
    private var usingBuiltInFallback = false
    private val completions = mutableListOf<() -> Unit>()
    private var message = ""
    var onPlaylistChange: ((Playlist) -> Unit)? = null
    var onUpdatePlaylistJobStateChange: ((Boolean) -> Unit)? = null
    var onUpdateError: ((String) -> Unit)? = null
    var onStatusChange: (() -> Unit)? = null
    var shouldActivateChange: Boolean = false
        private set
    val isUpdating: Boolean get() = updateJob?.isActive == true

    // 公开候选版使用独立 applicationId；无私人默认地址或迁移入口。
    fun getOnlineUrl(): String = preference.getString(KEY_ONLINE, "").orEmpty()
    fun getBackupUrl(): String = preference.getString(KEY_BACKUP, "").orEmpty()
    fun getCustomOnlineUrl(): String = getOnlineUrl()
    fun getCustomBackupUrl(): String = getBackupUrl()
    fun getBuiltInPlaylists() = listOf("自定义订阅" to getOnlineUrl())
    fun getPlaylistUrl(): String = getOnlineUrl()

    fun setOnlineUrl(url: String) = setPlaylistUrl(url.trim())

    fun setBackupUrl(url: String) {
        val custom = url.trim()
        require(custom.isEmpty() || M3uParser.isSupportedUrl(custom)) { "请输入完整的 HTTP 或 HTTPS 地址" }
        cancelUpdate()
        preference.edit().putString(KEY_BACKUP, custom).apply()
        refresh()
    }

    fun setPlaylistUrl(url: String) {
        require(url.isEmpty() || M3uParser.isSupportedUrl(url))
        cancelUpdate()
        preference.edit().putString(KEY_URL, url).apply()
        preference.edit().putString(KEY_ONLINE, url).apply()
        failures = 0
        lastFailureRefresh = -POLL_MS
        val playlist = loadPlaylist()
        publish(playlist, activate = true)
        onStatusChange?.invoke()
    }

    fun setLastUpdate(time: Long, requestUpdate: Boolean = false) {
        preference.edit().putLong("last_update", time).apply()
        if (requestUpdate) refresh()
    }

    private fun publish(playlist: Playlist, activate: Boolean) {
        shouldActivateChange = activate
        try { onPlaylistChange?.invoke(playlist) }
        finally { shouldActivateChange = false }
    }

    private data class Snapshot(val text: String, val hash: String, val playlist: Playlist)
    private fun readCache(url: String): Snapshot? = runCatching {
        val text = cache(url).openRead().bufferedReader().use { it.readText() }
        Snapshot(text, hash(text), M3uParser.requireValid(text))
    }.getOrNull()

    fun loadPlaylist(): Playlist {
        val url = getPlaylistUrl()
        usingBuiltInFallback = false
        if (url.isBlank()) {
            message = "未配置订阅地址，请在应用设置中添加你有权使用的 M3U 订阅"
            return Playlist()
        }
        val cached = readCache(url)
        message = if (cached == null) "正在获取在线列表…" else "已加载上次有效的在线列表"
        check(force = true)
        return cached?.playlist ?: Playlist()
    }


    fun statusText(): String {
        if (getPlaylistUrl().isBlank()) return "未配置订阅地址，请在应用设置中添加你有权使用的 M3U 订阅"
        val stamp = preference.getLong("checked[${hash(getPlaylistUrl())}]", 0)
        val time = if (stamp == 0L) "尚未成功检查" else "最近检查 " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(stamp))
        return "$message\n$time"
    }

    fun refresh() = check(force = true, notifyErrors = true)

    fun refreshAfterPlaybackFailure(complete: () -> Unit): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (getPlaylistUrl().isBlank() || now - lastFailureRefresh < 5 * 60_000L) return false
        lastFailureRefresh = now
        check(force = true, complete = complete)
        return true
    }

    fun cancelUpdate() {
        generation++
        updateJob?.cancel()
        updateJob = null
        completions.clear()
        onUpdatePlaylistJobStateChange?.invoke(false)
    }

    private fun check(force: Boolean = false, notifyErrors: Boolean = false, complete: (() -> Unit)? = null) {
        val url = getPlaylistUrl()
        if (url.isBlank()) {
            message = "未配置订阅地址，请在应用设置中添加你有权使用的 M3U 订阅"
            onStatusChange?.invoke()
            complete?.invoke()
            return
        }
        if (complete != null) completions.add(complete)
        if (isUpdating) return
        if (!force && SystemClock.elapsedRealtime() < nextCheck) return
        val requestGeneration = generation
        lastAttempt = SystemClock.elapsedRealtime()
        updateJob = scope.launch {
            message = "正在检查在线播放列表…"
            onUpdatePlaylistJobStateChange?.invoke(true)
            onStatusChange?.invoke()
            var lastError: Exception? = null
            try {
                val cached = withContext(Dispatchers.IO) { readCache(url) }
                val endpoints = listOf(url, getBackupUrl()).filter { it.isNotBlank() }.distinct()
                for (endpoint in endpoints) {
                    try {
                        val key = hash(endpoint)
                        val etag = if (cached != null && preference.getString("etag_hash[$key]", null) == cached.hash)
                            preference.getString("etag[$key]", null) else null
                        val response = HttpFetch.read(endpoint, 4 * 1024 * 1024, etag = etag, revalidate = force)
                        val snapshot = if (response.status == 304) requireNotNull(cached) else withContext(Dispatchers.Default) {
                            require(!response.truncated) { "播放列表超过 4 MB 限制" }
                            val text = response.bytes.toString(Charsets.UTF_8)
                            Snapshot(text, hash(text), M3uParser.requireValid(text))
                        }
                        ensureActive()
                        if (generation != requestGeneration) return@launch
                        if (snapshot.hash != cached?.hash) cacheMutex.withLock {
                            withContext(Dispatchers.IO) {
                                val file = cache(url)
                                val output = file.startWrite()
                                try { output.write(snapshot.text.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
                                catch (e: Exception) { file.failWrite(output); throw e }
                            }
                        }
                        ensureActive()
                        if (generation != requestGeneration) return@launch
                        val now = System.currentTimeMillis()
                        preference.edit().putString("etag[$key]", response.etag)
                            .putString("etag_hash[$key]", snapshot.hash).putLong("checked[${hash(url)}]", now).apply()
                        val changed = snapshot.hash != cached?.hash
                        if (changed) setLastUpdate(now)
                        failures = 0
                        nextCheck = SystemClock.elapsedRealtime() + POLL_MS
                        val via = if (endpoint == url) "在线" else "备用"
                        val restored = usingBuiltInFallback
                        usingBuiltInFallback = false
                        message = if (restored) "$via 列表已恢复：${snapshot.playlist.getAllChannels().size} 个频道"
                            else if (changed) "$via 列表已更新：${snapshot.playlist.getAllChannels().size} 个频道" else "$via 列表已检查，内容未变化"
                        Log.i("PlaylistManager", "subscription status=${response.status} changed=$changed channels=${snapshot.playlist.getAllChannels().size} via=$via")
                        if (changed || restored) publish(snapshot.playlist, activate = cached == null && !restored)
                        lastError = null
                        break
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: Exception) { lastError = e }
                }
                if (lastError != null) {
                    failures++
                    nextCheck = SystemClock.elapsedRealtime() + when (failures) { 1 -> 5 * 60_000L; 2 -> 15 * 60_000L; else -> POLL_MS }
                    val reason = when (lastError) {
                        is java.net.UnknownHostException -> "域名解析失败"
                        is java.net.SocketTimeoutException -> "连接超时"
                        is IllegalArgumentException -> "播放列表无效"
                        else -> lastError.message?.takeIf { it.matches(Regex("HTTP \\d{3}")) } ?: "网络连接失败"
                    }
                    message = if (cached != null) "更新失败：$reason；继续使用上次有效列表"
                        else "更新失败：$reason；没有可用列表，请检查订阅配置"
                    if (cached == null && !usingBuiltInFallback) {
                        usingBuiltInFallback = true
                        publish(Playlist(), activate = false)
                    }
                    Log.w("PlaylistManager", "subscription failed: ${lastError.javaClass.simpleName}")
                    if (notifyErrors) onUpdateError?.invoke(message)
                }
            } finally {
                if (generation == requestGeneration) {
                    onUpdatePlaylistJobStateChange?.invoke(false)
                    onStatusChange?.invoke()
                    val callbacks = completions.toList()
                    completions.clear()
                    callbacks.forEach { it() }
                }
            }
        }
    }

    fun startWatching() {
        foreground = true
        val now = SystemClock.elapsedRealtime()
        if (now - lastAttempt >= 15 * 60_000L || now >= nextCheck) check(force = true)
        if (watchJob?.isActive != true) watchJob = scope.launch {
            while (isActive) { delay(60_000L); check() }
        }
        if (networkCallback == null) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { if (foreground && SystemClock.elapsedRealtime() - lastAttempt >= 60_000L) check(force = true) }
                }
            }
            runCatching {
                (application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).registerNetworkCallback(
                    NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
                networkCallback = callback
            }
        }
    }

    fun stopWatching() {
        foreground = false
        watchJob?.cancel()
        watchJob = null
        networkCallback?.let { callback -> runCatching {
            (application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(callback)
        } }
        networkCallback = null
    }

    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun cache(url: String) = AtomicFile(File(application.filesDir, "playlist-${hash(url)}.m3u"))
}
