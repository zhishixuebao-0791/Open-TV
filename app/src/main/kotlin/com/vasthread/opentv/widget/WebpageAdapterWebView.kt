package com.vasthread.webviewtv.widget

import java.net.HttpURLConnection
import java.net.URL
import java.io.FilterInputStream
import com.google.gson.Gson
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import com.tencent.smtt.export.external.extension.interfaces.IX5WebSettingsExtension
import com.tencent.smtt.export.external.extension.proxy.ProxyWebChromeClientExtension
import com.tencent.smtt.export.external.extension.proxy.ProxyWebViewClientExtension
import com.tencent.smtt.export.external.interfaces.ConsoleMessage
import com.tencent.smtt.export.external.interfaces.IX5WebChromeClient
import com.tencent.smtt.export.external.interfaces.JsResult
import com.tencent.smtt.export.external.interfaces.MediaAccessPermissionsCallback
import com.tencent.smtt.export.external.interfaces.PermissionRequest
import com.tencent.smtt.export.external.interfaces.SslError
import com.tencent.smtt.export.external.interfaces.SslErrorHandler
import com.tencent.smtt.export.external.interfaces.WebResourceRequest
import com.tencent.smtt.export.external.interfaces.WebResourceResponse
import com.tencent.smtt.sdk.WebChromeClient
import com.tencent.smtt.sdk.WebSettings
import com.tencent.smtt.sdk.WebView
import com.tencent.smtt.sdk.WebViewClient
import com.vasthread.webviewtv.adapter.WebpageAdapterManager
import com.vasthread.webviewtv.misc.isMainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.commons.lang3.math.Fraction
import kotlin.system.measureTimeMillis

typealias LP = FrameLayout.LayoutParams

@Suppress("unused", "DEPRECATION")
@SuppressLint("SetJavaScriptEnabled")
class WebpageAdapterWebView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "WebpageAdapterWebView"
        const val URL_BLANK = "about:blank"
        private const val SHOW_WAITING_VIEW_DELAY = 100L
        private const val MAX_ZOOM_OUT_LEVEL = 3
        private const val CHECK_PAGE_LOADING_INTERVAL = 50L
        private const val BLANK_PAGE_WAIT = 800L
        val RATIO_16_9 = Fraction.getFraction(16, 9)!!
        val RATIO_4_3 = Fraction.getFraction(4, 3)!!
    }

    @Volatile private var requestedUrl = ""
    @Volatile private var currentMimeType: String? = null
    private var session = 0L
    private val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var fullscreenJob: Job? = null
    private val connections = java.util.Collections.newSetFromMap(ConcurrentHashMap<HttpURLConnection, Boolean>())
    private var destroyed = false
    private val videoSize = Point()
    private var isInFullscreen = false
    private val loadingInfo = PageLoadingInfo("", false)
    private val showWaitingViewAction = Runnable { onWaitingStateChanged?.invoke(true) }
    private val dismissWaitingViewAction = Runnable { onWaitingStateChanged?.invoke(false) }

    lateinit var fullscreenContainer: FrameLayout
    var onWaitingStateChanged: ((Boolean) -> Unit)? = null
    var onPlaybackActive: (() -> Unit)? = null
    var onPlaybackError: ((String) -> Unit)? = null
    var onPageFinished: ((String) -> Unit)? = null
    var onProgressChanged: ((Int) -> Unit)? = null
    var onFullscreenStateChanged: ((Boolean) -> Unit)? = null
    var onVideoRatioChanged: ((Fraction) -> Unit)? = null

    private val client = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val path = request.url.path.orEmpty().lowercase()
            val media = request.url.scheme in setOf("http", "https") && !request.isForMainFrame && (path.endsWith(".m3u8") || path.endsWith(".ts") ||
                path.endsWith(".m4s") || path.endsWith(".mp4") || path.endsWith(".aac") || path.endsWith(".key") ||
                currentMimeType == "application/x-mpegURL")
            if (!media || request.method !in setOf("GET", "OPTIONS")) return null
            val cors = mapOf("Access-Control-Allow-Origin" to "*", "Access-Control-Allow-Methods" to "GET, OPTIONS",
                "Access-Control-Allow-Headers" to "*", "Access-Control-Expose-Headers" to "Content-Range, Content-Length")
            if (request.method == "OPTIONS") return WebResourceResponse("text/plain", "UTF-8", 200, "OK", cors, null)
            var connection: HttpURLConnection? = null
            return try {
                val conn = URL(request.url.toString()).openConnection() as HttpURLConnection
                connection = conn
                connections.add(conn)
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                request.requestHeaders.forEach { (key, value) -> conn.setRequestProperty(key, value) }
                val status = conn.responseCode
                if (status in 300..399) { conn.disconnect(); connections.remove(conn); return null }
                val input = if (status < 400) conn.inputStream else conn.errorStream
                val stream = input?.let { object : FilterInputStream(it) {
                    override fun close() { try { super.close() } finally { conn.disconnect(); connections.remove(conn) } }
                } }
                val headers = conn.headerFields.filterKeys { it != null }.mapValues { it.value.firstOrNull().orEmpty() }.toMutableMap()
                headers.putAll(cors)
                val type = conn.contentType?.substringBefore(';') ?: when {
                    path.endsWith(".ts") -> "video/mp2t"
                    path.endsWith(".m3u8") || request.url.toString() == requestedUrl -> "application/vnd.apple.mpegurl"
                    else -> "application/octet-stream"
                }
                val reason = conn.responseMessage?.ifBlank { "HTTP $status" } ?: "HTTP $status"
                if (stream == null) { conn.disconnect(); connections.remove(conn) }
                WebResourceResponse(type, null, status, reason, headers, stream)
            } catch (e: Exception) {
                connection?.let { it.disconnect(); connections.remove(it) }
                Log.w(TAG, "Media interception failed", e)
                null
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            //view.loadUrl(url)
            return false
        }

        override fun shouldOverrideKeyEvent(view: WebView, event: KeyEvent): Boolean {
            //Log.i(TAG, "shouldOverrideKeyEvent $event")
            return super.shouldOverrideKeyEvent(view, event)
        }

        override fun onUnhandledKeyEvent(view: WebView, event: KeyEvent) {
            //Log.i(TAG, "onUnhandledKeyEvent $event")
            super.onUnhandledKeyEvent(view, event)
        }

        override fun onLoadResource(view: WebView, url: String) {
            //Log.i(TAG, "onLoadResource, $url")
            super.onLoadResource(view, url)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (error.url == requestedUrl) onPlaybackError?.invoke("TLS certificate rejected")
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            Log.i(TAG, "onPageStarted, $url")
            loadingInfo.set(url, true)
            disablePlayCheck()
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            Log.i(TAG, "onPageFinished, $url")
            loadingInfo.set(url, false)
            if (url != URL_BLANK && requestedUrl != URL_BLANK && view.url == url) {
                val expectedUrl = Gson().toJson(url)
                val token = Gson().toJson(session.toString())
                val script = WebpageAdapterManager.get(requestedUrl).javascript()
                evaluateJavascript("if(document.URL === $expectedUrl) { window.wvt_session = $token; $script }", null)
                onPageFinished?.invoke(url)
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            super.onReceivedHttpError(view, request, response)
            Log.i(TAG, "Http error: ${response.statusCode} ${request.url}")
        }
    }

    private val clientExtension = object : ProxyWebViewClientExtension() {

    }

    private val chromeClient = object : WebChromeClient() {

        private var view: View? = null
        private var callback: IX5WebChromeClient.CustomViewCallback? = null
        private var lastUrl = ""
        private var lastProgress = 0
        var videoRatio = RATIO_16_9
            set(value) {
                if (field == value) return
                field = value
                onVideoRatioChanged?.invoke(value)
                if (isInFullscreen) {
                    view?.layoutParams = generateLayoutParams()
                }
            }

        fun markNewPage() {
            lastUrl = ""
        }

        override fun onJsAlert(view: WebView, url: String, message: String?, result: JsResult): Boolean {
            result.cancel()
            return true
        }

        override fun onProgressChanged(view: WebView, progress: Int) {
            super.onProgressChanged(view, progress)
            Log.i(TAG, "$url, progress=$progress")
            if (url == URL_BLANK) {
                disablePlayCheck()
                return
            }
            if (view.url != lastUrl || progress > lastProgress) {
                onProgressChanged?.invoke(progress)
                disablePlayCheck()
                adjustWideViewPort()
                lastUrl = view.url
                lastProgress = progress
            }
        }

        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
            when (msg.messageLevel()) {
                ConsoleMessage.MessageLevel.DEBUG, null -> Log.d(TAG, msg.message())
                ConsoleMessage.MessageLevel.LOG, ConsoleMessage.MessageLevel.TIP -> Log.i(TAG, msg.message())
                ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, msg.message())
                ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, msg.message())
            }
            return true
        }

        @Suppress("RemoveRedundantQualifierName")
        fun generateLayoutParams(): FrameLayout.LayoutParams {
            val screenRadio = Fraction.getFraction(fullscreenContainer.width, fullscreenContainer.height)
            val compare = screenRadio.compareTo(videoRatio)
            return if (compare == 0) LP(screenRadio.numerator, screenRadio.denominator, Gravity.CENTER)
            else if (compare > 0) LP(screenRadio.denominator * videoRatio.numerator / videoRatio.denominator, screenRadio.denominator, Gravity.CENTER)
            else LP(screenRadio.numerator, screenRadio.numerator * videoRatio.denominator / videoRatio.numerator, Gravity.CENTER)
        }

        override fun onShowCustomView(view: View, callback: IX5WebChromeClient.CustomViewCallback) {
            Log.i(TAG, "onShowCustomView")
            this.view = view
            this.callback = callback
            fullscreenContainer.addView(view, generateLayoutParams())
            isInFullscreen = true
            onFullscreenStateChanged?.invoke(true)
        }

        override fun onHideCustomView() {
            Log.i(TAG, "onHideCustomView")
            fullscreenContainer.removeView(view)
            callback?.onCustomViewHidden()
            view = null
            callback = null
            isInFullscreen = false
            onFullscreenStateChanged?.invoke(false)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            super.onPermissionRequest(request)
        }
    }

    private val chromeClientExtension = object : ProxyWebChromeClientExtension() {

        override fun jsRequestFullScreen() {
            Log.i(TAG, "jsRequestFullScreen")
        }

        override fun h5videoRequestFullScreen(s: String) {
            Log.i(TAG, "h5videoRequestFullScreen")
        }

        override fun onPermissionRequest(origin: String, resources: Long, callback: MediaAccessPermissionsCallback): Boolean {
            Log.i(TAG, "onPermissionRequest, origin=$origin, resources=$resources")
            callback.invoke(origin, 0, true)
            return true
        }
    }

    init {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        
            // 2. 核心优化：缓存策略 (对 M3U8/TS 频繁切片下载极其重要)
            cacheMode = WebSettings.LOAD_DEFAULT
            databaseEnabled = true
            val cacheDirPath = context.getDir("webview_video_cache", Context.MODE_PRIVATE).path
            // setAppCachePath 和 setAppCacheEnabled 在高版本虽被废弃，但 X5 内核依然依赖它们进行视频分片缓存
            @Suppress("DEPRECATION")
            setAppCachePath(cacheDirPath)
            @Suppress("DEPRECATION")
            setAppCacheEnabled(true)
            // 3. 渲染性能优化
            setRenderPriority(WebSettings.RenderPriority.HIGH) // 提高渲染优先级
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(false)
            // 4. 关键点：User-Agent 伪装优化
            // 之前可能伪装成了 Windows PC，这会导致 X5 内核以为在电脑上，从而禁用安卓原生的硬解引擎。
            // 现在改为伪装成 Android 智能电视 (Sony Bravia 4K)，既能骗过部分服务器，又能完美调用 X5 视频硬解。
            userAgentString = "Mozilla/5.0 (Linux; Android 11; BRAVIA 4K UR3 Build/PTT1.190515.001) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Safari/537.36"
        }
        // 强制开启硬件加速
        setLayerType(View.LAYER_TYPE_HARDWARE, null)

        apply {
            webViewClient = client
            webViewClientExtension = clientExtension
            webChromeClient = chromeClient
            webChromeClientExtension = chromeClientExtension
            setBackgroundColor(Color.BLACK)
            addJavascriptInterface(this, "main")
        }
    }

    private fun isM3u8(url: String): Boolean = currentMimeType == "application/x-mpegURL" ||
        android.net.Uri.parse(url).path.orEmpty().endsWith(".m3u8", true)

    private fun loadM3u8ByHls(url: String) {
        val safeUrl = Gson().toJson(url)
        val sessionId = session.toString()
        val hlsLibrary = context.assets.open("hls-1.5.13.min.js").bufferedReader().use { it.readText() }
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    html, body { margin: 0; padding: 0; background: black; height: 100%; overflow: hidden; }
                    video { width: 100%; height: 100%; background: black; }
                </style>
            </head>
            <body>
                <video id="video" autoplay playsinline></video>
                <script>$hlsLibrary</script>
                <script>
                    var video = document.getElementById('video');
                    var isWaiting = null;
                    var lastCurrentTime = 0;
                    var freezeTick = 0;

                    function setWaiting(waiting) {
                        if (isWaiting === waiting) return;
                        isWaiting = waiting;
                        if (waiting) {
                            window.main.reportPlayback("$sessionId", "waiting", video.videoWidth, video.videoHeight);
                        } else {
                            window.main.reportPlayback("$sessionId", "playing", video.videoWidth, video.videoHeight);
                        }
                    }

                    function ensurePlay() {
                        video.play().catch(function (e) { console.log(e); });
                    }

                    if (Hls.isSupported()) {
                        var fragLoadTs = 0;
                        var hls = new Hls({
                            enableWorker: true,
                            lowLatencyMode: false,
                            liveSyncDurationCount: 3,
                            liveMaxLatencyDurationCount: 6,
                            maxBufferLength: 20,
                            maxMaxBufferLength: 40,
                            backBufferLength: 30,
                            manifestLoadingMaxRetry: 6,
                            levelLoadingMaxRetry: 6,
                            fragLoadingMaxRetry: 6,
                            startFragPrefetch: true
                        });

                        hls.loadSource($safeUrl);
                        hls.attachMedia(video);

                        hls.on(Hls.Events.MANIFEST_PARSED, function () {
                            ensurePlay();
                        });

                        hls.on(Hls.Events.FRAG_LOADING, function () {
                            fragLoadTs = Date.now();
                        });

                        hls.on(Hls.Events.FRAG_LOADED, function (event, data) {
                            var cost = Date.now() - fragLoadTs;
                            console.log("frag loaded, bytes=" + (data.stats ? data.stats.total : 0) + ", costMs=" + cost);
                        });

                        hls.on(Hls.Events.FRAG_BUFFERED, function () {
                            if (video.paused) ensurePlay();
                        });

                        hls.on(Hls.Events.ERROR, function (event, data) {
                            console.error('HLS error', data ? data.details : 'unknown');
                            if (data && data.fatal) window.main.reportPlayback("$sessionId", "error", 0, 0);
                        });
                    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
                        video.src = $safeUrl;
                        ensurePlay();
                    }

                    video.addEventListener('waiting', function () { setWaiting(true); });
                    video.addEventListener('playing', function () { setWaiting(false); });

                    video.addEventListener('timeupdate', function () { setWaiting(false); });
                    video.addEventListener('stalled', function () { setWaiting(true); });
                    video.addEventListener('error', function () { window.main.reportPlayback("$sessionId", "error", 0, 0); });

                    setInterval(function () {
                        if (video.paused || video.seeking) { setWaiting(true); return; }
                        var current = video.currentTime || 0;
                        if (Math.abs(current - lastCurrentTime) < 0.05) {
                            freezeTick += 1;
                            if (freezeTick >= 2) setWaiting(true);
                        } else {
                            freezeTick = 0;
                            setWaiting(false);
                        }
                        lastCurrentTime = current;
                    }, 1000);
                </script>
            </body>
            </html>
        """.trimIndent()

        loadDataWithBaseURL(url, html, "text/html", "UTF-8", null)
    }

    override fun loadUrl(url: String) = loadMedia(url, null)

    fun loadMedia(url: String, mimeType: String?) {
        session++
        fullscreenJob?.cancel()
        connections.forEach { it.disconnect() }
        connections.clear()
        requestedUrl = url
        currentMimeType = mimeType
        removeCallbacks(showWaitingViewAction)
        removeCallbacks(dismissWaitingViewAction)
        super.stopLoading()
        if (isInFullscreen) chromeClient.onHideCustomView()
        setVideoSize(0, 0)
        chromeClient.markNewPage()
        if (url == URL_BLANK) { super.loadUrl(url); return }
        settings.userAgentString = WebpageAdapterManager.get(url).userAgent()
        if (isM3u8(url)) loadM3u8ByHls(url) else super.loadUrl(url)
    }

    @JavascriptInterface
    fun reportPlayback(token: String, state: String, width: Int, height: Int) {
        post {
            if (destroyed || token != session.toString() || requestedUrl == URL_BLANK) return@post
            videoSize.set(width, height)
            when (state) {
                "playing" -> { onWaitingStateChanged?.invoke(false); onPlaybackActive?.invoke() }
                "waiting" -> onWaitingStateChanged?.invoke(true)
                "error" -> onPlaybackError?.invoke("media error")
                "fullscreen" -> schemeEnterFullscreen()
            }
        }
    }

    fun releaseResources() {
        if (destroyed) return
        loadMedia(URL_BLANK, null)
        destroyed = true
        pageScope.cancel()
        removeCallbacks(showWaitingViewAction)
        removeCallbacks(dismissWaitingViewAction)
        removeJavascriptInterface("main")
        (parent as? android.view.ViewGroup)?.removeView(this)
        destroy()
    }

    override fun reload() {
        chromeClient.markNewPage()
        super.reload()
    }

    override fun stopLoading() {
        super.stopLoading()
        disablePlayCheck()
    }

    fun isInFullscreen() = isInFullscreen

    fun setVideoRatio(ratio: Fraction) {
        chromeClient.videoRatio = ratio
    }

    fun getVideoRatio() = chromeClient.videoRatio

    @JavascriptInterface
    fun schemeEnterFullscreen() {
        if (isInFullscreen()) return
        Log.i(TAG, "schemeEnterFullscreen")
        if (fullscreenJob?.isActive == true || requestedUrl == URL_BLANK) return
        fullscreenJob = pageScope.launch {
            WebpageAdapterManager.get(requestedUrl).tryEnterFullscreen(this@WebpageAdapterWebView)
        }
    }

    @JavascriptInterface
    fun notifyVideoPlaying() {
        post { onPlaybackActive?.invoke() }
        disablePlayCheck()
    }

    @JavascriptInterface
    fun enablePlayCheck() {
        postDelayed(showWaitingViewAction, SHOW_WAITING_VIEW_DELAY)
    }

    @JavascriptInterface
    fun disablePlayCheck() {
        removeCallbacks(showWaitingViewAction)
        post(dismissWaitingViewAction)
    }

    @JavascriptInterface
    fun setVideoSize(width: Int, height: Int) {
        videoSize.set(width, height)
    }

    fun getVideoSize() = videoSize

    private fun adjustWideViewPort() {
        var level = 0
        while (canZoomOut() && level < 3) {
            zoomOut()
            ++level
        }
    }

    private class PageLoadingInfo(var url: String, var isPageLoading: Boolean) {
        fun set(url: String, isPageLoading: Boolean) {
            this.url = url
            this.isPageLoading = isPageLoading
        }
    }
}
