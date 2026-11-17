package com.vasthread.webviewtv.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Gravity
import android.widget.FrameLayout
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView
import com.vasthread.webviewtv.R
import com.vasthread.webviewtv.misc.StreamResolver
import com.vasthread.webviewtv.misc.StreamDescriptor
import com.vasthread.webviewtv.misc.StreamKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import com.vasthread.webviewtv.playlist.Channel
import com.vasthread.webviewtv.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ChannelPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "ChannelPlayer"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val MIN_BUFFER_MS = 12_000
        private const val MAX_BUFFER_MS = 50_000
        private const val PLAYBACK_BUFFER_MS = 2_500
        private const val REBUFFER_MS = 6_000
        private const val LIVE_TARGET_OFFSET_MS = 12_000L
        private const val LIVE_MIN_OFFSET_MS = 8_000L
        private const val LIVE_MAX_OFFSET_MS = 18_000L
        private const val INITIAL_SOURCE_LOAD_TIMEOUT_MS = 15_000L
        private const val PLAYBACK_STALL_TIMEOUT_MS = 10_000L
        private const val STALL_DETECT_THRESHOLD_MS = 2_500L
        private const val PLAYBACK_WATCHDOG_INTERVAL_MS = 1_000L
        private const val PLAYBACK_PROGRESS_EPSILON_MS = 120L
        private const val CHANNEL_FAIL_HINT_MS = 1_500L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val NATIVE_SUFFIXES = listOf(
            ".m3u8",
            ".mpd",
            ".ism",
            ".isml",
            ".mp4",
            ".m4v",
            ".mov",
            ".mkv",
            ".webm",
            ".ts",
            ".mp3",
            ".aac",
            ".ac3"
        )
    }

    private enum class PlaybackMode {
        NONE,
        EXO,
        WEB
    }

    private enum class PlaybackRoute {
        EXO,
        WEB
    }

    private enum class AttemptState {
        IDLE,
        PREPARING,
        PLAYING,
        STALLING
    }

    private data class RouteCandidate(
        val route: PlaybackRoute,
        val reason: String,
        val viable: Boolean,
        val descriptor: StreamDescriptor = StreamDescriptor(StreamKind.PROGRESSIVE)
    )

    private lateinit var webView: WebpageAdapterWebView
    private lateinit var waitingView: WaitingView
    private lateinit var channelBarView: ChannelBarView
    private lateinit var exoPlayerView: PlayerView
    private val videoSize = Point()
    private val videoDisplayRect = Rect()

    private var playbackMode = PlaybackMode.NONE
    private var currentAspectIs16By9 = true
    private var currentNativeUrl: String? = null
    private var isRecoveringFromExoError = false
    private var playChannelJob: Job? = null
    private var lastReadyAtMs: Long = 0L
    private var lastBufferingAtMs: Long = 0L
    private var totalRebufferCount: Long = 0L
    private var currentChannelToken: Long = 0L
    private var sourceAttemptOrder = emptyList<Int>()
    private var sourceAttemptCursor = 0
    private var currentAttemptSourceIndex = -1
    private var currentAttemptUrl = ""
    private var currentAttemptReady = false
    private var attemptState = AttemptState.IDLE
    private var pendingFailedChannel: Channel? = null
    private var lastPlaybackPositionMs = C.TIME_UNSET
    private var lastPlaybackPositionUpdateMs = 0L

    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentDescriptor = StreamDescriptor(StreamKind.PROGRESSIVE)
    private var manualSourceIndex: Int? = null
    private var released = false
    val currentSourceIndex: Int get() = currentAttemptSourceIndex
    val currentSourceUrl: String get() = currentAttemptUrl
    val hasHealthyPlayback: Boolean get() = currentAttemptReady && attemptState == AttemptState.PLAYING
    val playbackGeneration: Long get() = currentChannelToken
    private val sourceTimeoutAction = Runnable { handleSourceTimeout() }
    private val stallTimeoutAction = Runnable { handleStallTimeout() }
    private val playbackWatchdogAction = Runnable { runPlaybackWatchdog() }
    private val channelFailAction = Runnable {
        val failed = pendingFailedChannel ?: return@Runnable
        pendingFailedChannel = null
        val current = channel
        if (current != null && current.name == failed.name && current.groupName == failed.groupName) {
            onChannelPlaybackFailed?.invoke(failed)
        }
    }

    var clickCallback: ((Float, Float) -> Unit)? = null
    var dismissAllViewCallback: (() -> Unit)? = null
    var onChannelReload: ((Channel) -> Unit)? = null
    var onVideoRatioChanged: ((Boolean) -> Unit)? = null
    var onVideoDisplayRectChanged: ((Rect) -> Unit)? = null
    var onChannelPlaybackFailed: ((Channel) -> Unit)? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.OnGestureListener {
        override fun onDown(e: MotionEvent) = true
        override fun onShowPress(e: MotionEvent) = Unit

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            clickCallback?.invoke(e.x, e.y)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ) = false

        override fun onLongPress(e: MotionEvent) = Unit

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ) = false
    })

    private var exoPlayer: ExoPlayer? = null
    private lateinit var exoHttpDataSourceFactory: DefaultHttpDataSource.Factory
    private lateinit var hlsMediaSourceFactory: HlsMediaSource.Factory

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_IDLE -> {
                    if (!currentAttemptReady && channel != null) {
                        showLoadingIndicator()
                    }
                }
                Player.STATE_BUFFERING -> {
                    lastBufferingAtMs = SystemClock.elapsedRealtime()
                    if (lastReadyAtMs > 0L) {
                        totalRebufferCount++
                    }
                    // During mid-playback buffering, do not show spinner immediately.
                    // Spinner/timeout should start only after front-end frame progression is truly stalled.
                    if (!currentAttemptReady) {
                        showLoadingIndicator()
                    }
                    Log.w(TAG, "STATE_BUFFERING rebufferCount=$totalRebufferCount url=$currentNativeUrl")
                }
                Player.STATE_READY -> {
                    markCurrentSourceReady("exo_state_ready")
                    updateNativeVideoSize(exoPlayer?.videoSize ?: VideoSize.UNKNOWN)
                    val now = SystemClock.elapsedRealtime()
                    if (lastBufferingAtMs > 0L) {
                        val bufferingCost = now - lastBufferingAtMs
                        Log.w(TAG, "STATE_READY bufferedMs=$bufferingCost url=$currentNativeUrl")
                    }
                    lastReadyAtMs = now
                }
                Player.STATE_ENDED -> {
                    showLoadingIndicator()
                    tryNextSource("exo_state_ended")
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                markCurrentSourceReady("exo_is_playing")
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = updateNativeVideoSize(videoSize)

        override fun onTracksChanged(tracks: Tracks) {
            val selectedAudio = findSelectedTrack(tracks, C.TRACK_TYPE_AUDIO)
            val selectedVideo = findSelectedTrack(tracks, C.TRACK_TYPE_VIDEO)
            Log.w(
                TAG,
                "TracksChanged audio=${formatSummary(selectedAudio)} video=${formatSummary(selectedVideo)} route=$playbackMode"
            )
            if (
                playbackMode == PlaybackMode.EXO &&
                selectedAudio == null &&
                selectedVideo != null &&
                !currentNativeUrl.isNullOrBlank() &&
                !isRecoveringFromExoError
            ) {
                val url = currentNativeUrl!!
                Log.w(TAG, "No audio track in EXO route, fallback to WebView url=$url")
                isRecoveringFromExoError = true
                playWithWebView(url)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Native playback failed, fallback to WebView. ${error.errorCodeName}", error)
            showLoadingIndicator()
            val fallbackUrl = currentNativeUrl
            if (!isRecoveringFromExoError && !fallbackUrl.isNullOrBlank()) {
                isRecoveringFromExoError = true
                playWithWebView(fallbackUrl)
            } else {
                tryNextSource("exo_error_${error.errorCodeName}")
            }
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultFocusHighlightEnabled = false
        }
        LayoutInflater.from(context).inflate(R.layout.widget_channel_player, this)
        setBackgroundColor(Color.BLACK)

        webView = findViewById(R.id.webView)
        exoPlayerView = findViewById(R.id.exoPlayerView)
        channelBarView = findViewById(R.id.channelBarView)
        waitingView = findViewById(R.id.waitingView)

        webView.apply {
            fullscreenContainer = this@ChannelPlayerView.findViewById(R.id.fullscreenContainer)
            onPageFinished = {}
            onPlaybackError = { if (playbackMode == PlaybackMode.WEB) tryNextSource("web_error:$it") }
            onProgressChanged = { channelBarView.setProgress(it) }
            onFullscreenStateChanged = {}
            onPlaybackActive = {
                if (playbackMode == PlaybackMode.WEB) {
                    markCurrentSourceReady("web_notify_playing")
                }
            }
            onWaitingStateChanged = {
                if (playbackMode == PlaybackMode.WEB) {
                    if (it) {
                        if (currentAttemptReady) {
                            enterStalling("web_waiting")
                        } else {
                            showLoadingIndicator()
                        }
                    }
                }
            }
            onVideoRatioChanged = {
                if (playbackMode == PlaybackMode.WEB) {
                    currentAspectIs16By9 = it == WebpageAdapterWebView.RATIO_16_9
                    applyAspectRatio()
                    this@ChannelPlayerView.onVideoRatioChanged?.invoke(currentAspectIs16By9)
                }
            }
        }

        initExoPlayer()
        applyAspectRatio()
    }

    var channel: Channel? = null
        set(value) {
            val unchanged = field == value && field?.urls == value?.urls
            field = value
            if (!unchanged) playChannel(value)
        }

    private fun initExoPlayer() {
        exoHttpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
        hlsMediaSourceFactory = HlsMediaSource.Factory(exoHttpDataSourceFactory)
            .setAllowChunklessPreparation(false)

        val mediaSourceFactory = DefaultMediaSourceFactory(exoHttpDataSourceFactory)
        val trackSelector = DefaultTrackSelector(context).apply {
            val builder = buildUponParameters()
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
            if (isEmulator()) {
                // Emulator decode is the major bottleneck. Cap resolution/bitrate to reduce frame drops.
                builder
                    .setMaxVideoSize(1280, 720)
                    .setMaxVideoBitrate(2_500_000)
            }
            setParameters(builder)
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                PLAYBACK_BUFFER_MS,
                REBUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        exoPlayer = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()
            .apply {
                playWhenReady = true
                setAudioAttributes(audioAttributes, true)
                setWakeMode(C.WAKE_MODE_NETWORK)
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(playerListener)
            }

        exoPlayerView.player = exoPlayer
    }

    private fun playChannel(target: Channel?) {
        currentChannelToken++
        playChannelJob?.cancel()
        cancelSourceTimeout()
        cancelStallTimeout()
        cancelPlaybackWatchdog()
        removeCallbacks(channelFailAction)
        pendingFailedChannel = null
        sourceAttemptOrder = emptyList()
        sourceAttemptCursor = 0
        currentAttemptSourceIndex = -1
        currentAttemptUrl = ""
        currentAttemptReady = false
        attemptState = AttemptState.IDLE
        stopAllPlayers()
        if (target == null) {
            channelBarView.dismiss()
            playbackMode = PlaybackMode.NONE
            return
        }

        showLoadingIndicator()
        startNextSourceAttempt(target, "channel_changed")
    }

    private fun startNextSourceAttempt(target: Channel, reason: String) {
        if (channel != target) return
        if (sourceAttemptOrder.isEmpty()) {
            sourceAttemptOrder = buildSourceAttemptOrder(target)
            sourceAttemptCursor = 0
        }

        if (sourceAttemptCursor >= sourceAttemptOrder.size) {
            onAllSourcesFailed(target, "all_sources_timeout:$reason")
            return
        }

        val sourceIndex = sourceAttemptOrder[sourceAttemptCursor++]
        val sourceUrl = target.urls.getOrNull(sourceIndex)?.trim().orEmpty()
        if (sourceUrl.isBlank()) {
            startNextSourceAttempt(target, "blank_source")
            return
        }

        currentAttemptSourceIndex = sourceIndex
        currentAttemptUrl = sourceUrl
        currentAttemptReady = false
        isRecoveringFromExoError = false

        onChannelReload?.invoke(target)

        stopAllPlayers(hideWaiting = false)
        setAttemptState(AttemptState.PREPARING, "start_next_source:$reason")
        showLoadingIndicator()
        channelBarView.setCurrentChannelAndShow(target, sourceIndex, sourceUrl)
        scheduleInitialSourceTimeout()

        val token = currentChannelToken
        playChannelJob?.cancel()
        playChannelJob = playerScope.launch {
            try {
            val candidate = evaluateRouteCandidate(sourceUrl)
            if (!isActive || token != currentChannelToken || channel != target || currentAttemptUrl != sourceUrl) return@launch

            Log.w(
                TAG,
                "startSource route=${candidate.route} viable=${candidate.viable} sourceIndex=$sourceIndex reason=${candidate.reason} url=$sourceUrl"
            )

            if (!candidate.viable) {
                tryNextSource("candidate_not_viable:${candidate.reason}")
                return@launch
            }
            currentDescriptor = candidate.descriptor
            when (candidate.route) {
                PlaybackRoute.EXO -> playWithExo(sourceUrl)
                PlaybackRoute.WEB -> playWithWebView(sourceUrl)
            }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "Source preparation failed", e)
                if (token == currentChannelToken && currentAttemptUrl == sourceUrl) tryNextSource("prepare_error")
            }
        }
    }

    private fun buildSourceAttemptOrder(channel: Channel): List<Int> {
        if (channel.urls.isEmpty()) return emptyList()
        val validIndices = channel.urls.indices.filter { channel.urls[it].trim().isNotEmpty() }
        if (validIndices.isEmpty()) return emptyList()
        var preferred = (manualSourceIndex ?: SettingsManager.getChannelLastSourceIndex(channel)).coerceIn(0, channel.urls.lastIndex)
        manualSourceIndex = null
        if (preferred !in validIndices) preferred = validIndices.first()
        // Continue after the selected source and wrap only at the end of the list.
        val start = validIndices.indexOf(preferred)
        return validIndices.drop(start) + validIndices.take(start)
    }

    private suspend fun evaluateRouteCandidate(url: String): RouteCandidate {
        val descriptor = try {
            withTimeoutOrNull(6_000L) { StreamResolver.resolve(url) } ?: StreamResolver.guess(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Probe unavailable; try media extractor", e)
            StreamResolver.guess(url)
        }
        val route = if (descriptor.kind == StreamKind.HTML || descriptor.needsWebAudio) PlaybackRoute.WEB else PlaybackRoute.EXO
        return RouteCandidate(route, descriptor.kind.name, true, descriptor)
    }

    private fun showLoadingIndicator() {
        waitingView.showLoadingText()
        waitingView.visibility = VISIBLE
    }

    private fun markCurrentSourceReady(reason: String) {
        if (channel == null) return
        val firstReady = !currentAttemptReady
        currentAttemptReady = true
        if (firstReady) channel?.let { SettingsManager.setChannelLastSourceIndex(it, currentAttemptSourceIndex) }
        setAttemptState(AttemptState.PLAYING, reason)
        cancelSourceTimeout()
        cancelStallTimeout()
        waitingView.visibility = GONE
        channelBarView.notifyPlaybackReady()
        if (playbackMode == PlaybackMode.EXO) {
            val currentPos = exoPlayer?.currentPosition ?: 0L
            lastPlaybackPositionMs = currentPos
            lastPlaybackPositionUpdateMs = SystemClock.elapsedRealtime()
            schedulePlaybackWatchdog()
        } else {
            cancelPlaybackWatchdog()
        }
        Log.w(TAG, "sourceReady sourceIndex=$currentAttemptSourceIndex reason=$reason url=$currentAttemptUrl")
    }

    private fun enterStalling(reason: String) {
        if (!currentAttemptReady) return
        if (attemptState != AttemptState.STALLING) {
            setAttemptState(AttemptState.STALLING, reason)
            showLoadingIndicator()
            scheduleStallTimeout()
        }
    }

    private fun scheduleInitialSourceTimeout() {
        cancelSourceTimeout()
        postDelayed(sourceTimeoutAction, SettingsManager.getMaxLoadingTime() * 1_000L)
    }

    private fun scheduleStallTimeout() {
        cancelStallTimeout()
        postDelayed(stallTimeoutAction, PLAYBACK_STALL_TIMEOUT_MS)
    }

    private fun cancelSourceTimeout() {
        removeCallbacks(sourceTimeoutAction)
    }

    private fun cancelStallTimeout() {
        removeCallbacks(stallTimeoutAction)
    }

    private fun schedulePlaybackWatchdog() {
        cancelPlaybackWatchdog()
        postDelayed(playbackWatchdogAction, PLAYBACK_WATCHDOG_INTERVAL_MS)
    }

    private fun cancelPlaybackWatchdog() {
        removeCallbacks(playbackWatchdogAction)
    }

    private fun handleSourceTimeout() {
        if (channel == null) return
        if (attemptState != AttemptState.PREPARING) return
        tryNextSource("source_timeout_initial")
    }

    private fun handleStallTimeout() {
        if (channel == null) return
        if (attemptState != AttemptState.STALLING) return
        if (playbackMode == PlaybackMode.EXO && (exoPlayer?.isPlaying == true ||
                exoPlayer?.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE || exoPlayer?.playWhenReady == false)) {
            markCurrentSourceReady("exo_recovered_before_stall_timeout")
            return
        }
        tryNextSource("playback_stall_timeout")
    }

    private fun tryNextSource(reason: String) {
        val target = channel ?: return
        cancelSourceTimeout()
        cancelStallTimeout()
        if (sourceAttemptCursor >= sourceAttemptOrder.size) {
            onAllSourcesFailed(target, reason)
            return
        }
        Log.w(
            TAG,
            "sourceFailed sourceIndex=$currentAttemptSourceIndex reason=$reason url=$currentAttemptUrl"
        )
        startNextSourceAttempt(target, reason)
    }

    private fun onAllSourcesFailed(target: Channel, reason: String) {
        Log.e(TAG, "channelFailed name=${target.name} reason=$reason")
        playChannelJob?.cancel()
        cancelSourceTimeout()
        cancelStallTimeout()
        cancelPlaybackWatchdog()
        setAttemptState(AttemptState.IDLE, "all_sources_failed:$reason")
        stopAllPlayers(hideWaiting = false)
        waitingView.showFailedText()
        waitingView.visibility = VISIBLE
        pendingFailedChannel = target
        removeCallbacks(channelFailAction)
        postDelayed(channelFailAction, CHANNEL_FAIL_HINT_MS)
    }

    private fun playWithExo(url: String) {
        playbackMode = PlaybackMode.EXO
        currentNativeUrl = url
        isRecoveringFromExoError = false
        lastPlaybackPositionMs = C.TIME_UNSET
        lastPlaybackPositionUpdateMs = 0L

        webView.visibility = GONE
        exoPlayerView.visibility = VISIBLE
        exoPlayerView.requestFocus()

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMimeType(currentDescriptor.mimeType ?: inferMimeType(url))
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                    .setMinOffsetMs(LIVE_MIN_OFFSET_MS)
                    .setMaxOffsetMs(LIVE_MAX_OFFSET_MS)
                    .setMinPlaybackSpeed(0.98f)
                    .setMaxPlaybackSpeed(1.02f)
                    .build()
            )
            .build()

        exoPlayer?.apply {
            clearMediaItems()
            if (mediaItem.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8) {
                setMediaSource(hlsMediaSourceFactory.createMediaSource(mediaItem))
            } else {
                setMediaItem(mediaItem)
            }
            prepare()
            play()
        }
    }

    private fun inferMimeType(url: String): String? {
        val normalized = url.substringBefore('#').substringBefore('?').lowercase(Locale.US)
        return when {
            normalized.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            normalized.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            normalized.endsWith(".mp4") || normalized.endsWith(".m4v") || normalized.endsWith(".mov") -> MimeTypes.VIDEO_MP4
            normalized.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
            normalized.endsWith(".mp3") -> MimeTypes.AUDIO_MPEG
            normalized.endsWith(".aac") -> MimeTypes.AUDIO_AAC
            normalized.endsWith(".ac3") -> MimeTypes.AUDIO_AC3
            normalized.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            else -> null
        }
    }

    private fun playWithWebView(url: String) {
        currentAttemptReady = false
        setAttemptState(AttemptState.PREPARING, "web_fallback")
        scheduleInitialSourceTimeout()
        playbackMode = PlaybackMode.WEB
        currentNativeUrl = null
        cancelPlaybackWatchdog()
        lastPlaybackPositionMs = C.TIME_UNSET
        lastPlaybackPositionUpdateMs = 0L

        exoPlayer?.run {
            stop()
            clearMediaItems()
        }

        exoPlayerView.visibility = GONE
        webView.visibility = VISIBLE
        webView.requestFocus()
        webView.loadMedia(url, currentDescriptor.mimeType)
    }

    private fun stopAllPlayers(hideWaiting: Boolean = true) {
        playbackMode = PlaybackMode.NONE
        currentNativeUrl = null
        setAttemptState(AttemptState.IDLE, "stop_all_players")
        isRecoveringFromExoError = false
        videoSize.set(0, 0)
        cancelSourceTimeout()
        cancelStallTimeout()
        cancelPlaybackWatchdog()
        lastPlaybackPositionMs = C.TIME_UNSET
        lastPlaybackPositionUpdateMs = 0L

        exoPlayer?.run {
            stop()
            clearMediaItems()
        }

        webView.stopLoading()
        webView.loadUrl(WebpageAdapterWebView.URL_BLANK)
        if (hideWaiting) {
            waitingView.visibility = GONE
        }
    }

    private fun runPlaybackWatchdog() {
        if (channel == null || playbackMode != PlaybackMode.EXO || !currentAttemptReady) {
            cancelPlaybackWatchdog()
            return
        }
        val player = exoPlayer ?: return
        val now = SystemClock.elapsedRealtime()
        if (!player.playWhenReady || player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
            lastPlaybackPositionMs = player.currentPosition
            lastPlaybackPositionUpdateMs = now
            cancelStallTimeout()
            if (attemptState == AttemptState.STALLING) {
                setAttemptState(AttemptState.PLAYING, "playback_suppressed")
                waitingView.visibility = GONE
            }
            schedulePlaybackWatchdog()
            return
        }
        val position = player.currentPosition
        if (lastPlaybackPositionMs == C.TIME_UNSET) {
            lastPlaybackPositionMs = position
            lastPlaybackPositionUpdateMs = now
            schedulePlaybackWatchdog()
            return
        }
        if (position > lastPlaybackPositionMs + PLAYBACK_PROGRESS_EPSILON_MS) {
            lastPlaybackPositionMs = position
            lastPlaybackPositionUpdateMs = now
            if (attemptState == AttemptState.STALLING) {
                markCurrentSourceReady("exo_progress_recovered")
            }
            schedulePlaybackWatchdog()
            return
        }
        val stagnantMs = now - lastPlaybackPositionUpdateMs
        val shouldEnterStalling =
            player.playbackState == Player.STATE_BUFFERING ||
                !player.isPlaying
        if (shouldEnterStalling && stagnantMs >= STALL_DETECT_THRESHOLD_MS && attemptState != AttemptState.STALLING) {
            // Front-end frame progression is actually stuck now.
            enterStalling("exo_position_stall_detected")
        }
        schedulePlaybackWatchdog()
    }

    private fun setAttemptState(state: AttemptState, reason: String) {
        if (attemptState == state) return
        Log.w(
            TAG,
            "attemptState ${attemptState.name} -> ${state.name} reason=$reason sourceIndex=$currentAttemptSourceIndex url=$currentAttemptUrl"
        )
        attemptState = state
    }

    private fun updateNativeVideoSize(size: VideoSize) {
        val width = size.width
        val height = size.height
        videoSize.set(width, height)
    }

    private fun findSelectedTrack(tracks: Tracks, trackType: Int): Format? {
        tracks.groups.forEach { group ->
            if (group.type != trackType) return@forEach
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                    return group.getTrackFormat(i)
                }
            }
        }
        return null
    }

    private fun formatSummary(format: Format?): String {
        if (format == null) return "none"
        val bitrate = if (format.bitrate > 0) format.bitrate.toString() else "na"
        val channels = if (format.channelCount > 0) format.channelCount.toString() else "na"
        val sampleRate = if (format.sampleRate > 0) format.sampleRate.toString() else "na"
        val width = if (format.width > 0) format.width.toString() else "na"
        val height = if (format.height > 0) format.height.toString() else "na"
        return "mime=${format.sampleMimeType},codec=${format.codecs},bitrate=$bitrate,ch=$channels,sr=$sampleRate,wxh=${width}x$height"
    }

    private fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase(Locale.US)
        val model = Build.MODEL.lowercase(Locale.US)
        val brand = Build.BRAND.lowercase(Locale.US)
        val product = Build.PRODUCT.lowercase(Locale.US)
        return fingerprint.contains("generic") ||
            model.contains("emulator") ||
            model.contains("sdk") ||
            brand.contains("generic") ||
            product.contains("sdk")
    }

    private fun applyAspectRatio() {
        if (width == 0 || height == 0) return

        val targetWidth: Int
        val targetHeight: Int
        if (currentAspectIs16By9) {
            val heightFromWidth = width * 9 / 16
            if (heightFromWidth <= height) {
                targetWidth = width
                targetHeight = heightFromWidth
            } else {
                targetHeight = height
                targetWidth = height * 16 / 9
            }
        } else {
            val heightFromWidth = width * 3 / 4
            if (heightFromWidth <= height) {
                targetWidth = width
                targetHeight = heightFromWidth
            } else {
                targetHeight = height
                targetWidth = height * 4 / 3
            }
        }
        exoPlayerView.layoutParams = LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
        val left = (width - targetWidth) / 2
        val top = (height - targetHeight) / 2
        videoDisplayRect.set(left, top, left + targetWidth, top + targetHeight)
        onVideoDisplayRectChanged?.invoke(Rect(videoDisplayRect))
    }

    override fun requestFocus(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        return when (playbackMode) {
            PlaybackMode.EXO -> exoPlayerView.requestFocus(direction, previouslyFocusedRect)
            PlaybackMode.WEB, PlaybackMode.NONE -> webView.requestFocus(direction, previouslyFocusedRect)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return if (SettingsManager.isWebViewTouchable()) {
            dismissAllViewCallback?.invoke()
            super.dispatchTouchEvent(event)
        } else {
            gestureDetector.onTouchEvent(event)
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = false

    fun refreshChannel(sourceIndex: Int? = null) {
        val target = channel ?: return
        manualSourceIndex = sourceIndex
        playChannel(target)
    }

    fun switchSource(target: Channel, index: Int) {
        if (channel == target && channel?.urls == target.urls) refreshChannel(index)
        else {
            manualSourceIndex = index
            channel = target
        }
    }

    fun showCurrentChannelInfo() {
        val target = channel ?: return
        if (currentAttemptSourceIndex !in target.urls.indices || currentAttemptUrl.isBlank()) return
        channelBarView.setCurrentChannelAndShow(target, currentAttemptSourceIndex, currentAttemptUrl, currentAttemptReady)
    }

    fun showPlaylistLoading() {
        channel = null
        showLoadingIndicator()
    }

    fun showEmptyPlaylist() {
        channel = null
        waitingView.showMessage("暂无可用频道，请通过菜单键进入应用设置并配置订阅地址")
        waitingView.visibility = VISIBLE
    }

    fun setVideoRatio(is16By9: Boolean) {
        currentAspectIs16By9 = is16By9
        applyAspectRatio()
        webView.setVideoRatio(
            if (is16By9) WebpageAdapterWebView.RATIO_16_9 else WebpageAdapterWebView.RATIO_4_3
        )
        onVideoRatioChanged?.invoke(is16By9)
    }

    fun getVideoSize(): Point {
        return if (playbackMode == PlaybackMode.EXO) Point(videoSize) else webView.getVideoSize()
    }

    fun getVideoDisplayRect(): Rect {
        return if (videoDisplayRect.width() > 0 && videoDisplayRect.height() > 0) {
            Rect(videoDisplayRect)
        } else {
            Log.w(TAG, "getVideoDisplayRect fallback full player=${width}x${height} mode=$playbackMode")
            Rect(0, 0, width, height)
        }
    }

    fun getStableVideoDisplayRectOrNull(): Rect? {
        if (videoDisplayRect.width() <= 0 || videoDisplayRect.height() <= 0) return null
        return Rect(videoDisplayRect)
    }

    fun release() {
        if (released) return
        released = true
        playChannelJob?.cancel()
        cancelSourceTimeout()
        cancelStallTimeout()
        cancelPlaybackWatchdog()
        removeCallbacks(channelFailAction)
        pendingFailedChannel = null
        playerScope.cancel()
        stopAllPlayers()
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null
        webView.releaseResources()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyAspectRatio()
    }
}


