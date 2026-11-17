package com.vasthread.webviewtv.activity

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.Settings
import android.content.res.Configuration
import android.media.AudioManager
import android.view.ViewGroup
import com.vasthread.webviewtv.misc.RemoteAction
import com.vasthread.webviewtv.misc.RemoteKeys
import com.vasthread.webviewtv.misc.RemotePress
import com.vasthread.webviewtv.misc.adjacentSourceIndex
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.Toast
import com.vasthread.webviewtv.playlist.Playlist
import com.vasthread.webviewtv.playlist.ChannelIdentity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vasthread.webviewtv.R
import com.vasthread.webviewtv.misc.preference
import com.vasthread.webviewtv.playlist.Channel
import com.vasthread.webviewtv.playlist.Playlist.Companion.firstChannel
import com.vasthread.webviewtv.playlist.PlaylistManager
import com.vasthread.webviewtv.settings.SettingsManager
import com.vasthread.webviewtv.startup.BootAutoStart
import com.vasthread.webviewtv.startup.AutoStartPermissionDialog
import com.vasthread.webviewtv.widget.AppSettingsView
import com.vasthread.webviewtv.widget.AppInfoView
import com.vasthread.webviewtv.widget.ChannelPlayerView
import com.vasthread.webviewtv.widget.ChannelSettingsView
import com.vasthread.webviewtv.widget.PlaylistView
import com.vasthread.webviewtv.widget.StatusInfoBarView
import me.jessyan.autosize.AutoSize

class MainActivity : AppCompatActivity() {

    companion object {
        private const val LAST_CHANNEL = "last_channel"

        private enum class UiMode {
            STANDARD, CHANNELS, BACK_INFO, APP_SETTINGS, CHANNEL_SETTINGS
        }

        private const val OPERATION_TIMEOUT = 5000L
        private const val BACK_INFO_WINDOW_MS = 2000L

    }

    private lateinit var playerView: ChannelPlayerView
    private lateinit var mainLayout: FrameLayout
    private lateinit var uiLayout: FrameLayout
    private lateinit var playlistView: PlaylistView
    private lateinit var appInfoView: AppInfoView
    private lateinit var channelSettingsView: ChannelSettingsView
    private lateinit var appSettingsView: AppSettingsView
    private lateinit var statusInfoBarView: StatusInfoBarView

    private val autoStartPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val granted = BootAutoStart.canStartFromBackground(this)
        BootAutoStart.setEnabled(this, granted)
        appSettingsView.refreshPreferenceChoices()
        uiMode = UiMode.APP_SETTINGS
        appSettingsView.focusSettingTitle(R.string.auto_start)
        Toast.makeText(this, if (granted) R.string.auto_start_enabled else R.string.auto_start_permission_denied,
            Toast.LENGTH_SHORT).show()
    }

    private var lastChannel: Channel? = null
    private var pendingPlaylist: Playlist? = null
    private val playlistListener: (Playlist) -> Unit = {
        if (playerView.hasHealthyPlayback && !PlaylistManager.shouldActivateChange) pendingPlaylist = it
        else { pendingPlaylist = null; applyPlaylist(it) }
    }
    private fun ownsPlaylistCallbacks() = PlaylistManager.onPlaylistChange === playlistListener
    private val locationBufferA = IntArray(2)
    private val locationBufferB = IntArray(2)
    private val safeOverlayInsets = Rect()
    private val syncOverlayAction = Runnable { updateOverlayLayoutToVideoRect() }
    private var hasValidOverlayLayout = false
    private var backInfoExpiresAtUptime = 0L
    private var outsideSettingsGesture = false
    private val selectPress = RemotePress()
    private val sourcePress = RemotePress()
    private val selectLongPressAction = Runnable {
        handleLongSelect(SystemClock.uptimeMillis())
    }

    private fun handleLongSelect(now: Long) {
        if (selectPress.takeLong(now, ViewConfiguration.getLongPressTimeout().toLong()) && isPlaybackMode()) {
            handleRemoteAction(RemoteAction.MENU)
        }
    }

    private fun invalidateRemotePresses() {
        selectPress.invalidate()
        sourcePress.invalidate()
        playerView.removeCallbacks(selectLongPressAction)
    }

    private fun isPlaybackMode() = uiMode == UiMode.STANDARD || uiMode == UiMode.BACK_INFO

    private var uiMode = UiMode.STANDARD
        set(value) {
            if (field == value) return
            invalidateRemotePresses()
            field = value
            if (value == UiMode.CHANNELS || value == UiMode.CHANNEL_SETTINGS || value == UiMode.BACK_INFO || value == UiMode.APP_SETTINGS) {
                updateOverlayLayoutToVideoRect()
                requestOverlayLayoutSync()
            }
            if (value != UiMode.BACK_INFO) {
                backInfoExpiresAtUptime = 0L
                playerView.removeCallbacks(hideBackInfoAction)
            }
            playlistView.visibility = if (value == UiMode.CHANNELS) View.VISIBLE else View.GONE
            appInfoView.visibility = if (value == UiMode.BACK_INFO) View.VISIBLE else View.GONE
            channelSettingsView.visibility = if (value == UiMode.CHANNEL_SETTINGS) View.VISIBLE else View.GONE
            if (value == UiMode.CHANNEL_SETTINGS) updateSourceControls()
            appSettingsView.visibility = if (value == UiMode.APP_SETTINGS) View.VISIBLE else View.GONE
            if (value == UiMode.APP_SETTINGS) playerView.removeCallbacks(backToStandardModeAction)
            statusInfoBarView.visibility = if (
                value == UiMode.CHANNELS ||
                value == UiMode.APP_SETTINGS ||
                value == UiMode.BACK_INFO
            ) View.GONE else View.VISIBLE
            playerView.descendantFocusability = if (value == UiMode.STANDARD) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
            if (value == UiMode.CHANNELS && playlistView.playlist.firstChannel() == null) playerView.clearFocus()
            restoreRemoteFocus()
        }

    private val backToStandardModeAction = Runnable { uiMode = UiMode.STANDARD }
    private val hideBackInfoAction = Runnable {
        if (uiMode == UiMode.BACK_INFO) {
            uiMode = UiMode.STANDARD
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        android.webkit.WebView.setWebContentsDebuggingEnabled(isDebuggable)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        volumeControlStream = AudioManager.STREAM_MUSIC
        setupUi()
        setupBackNavigation()
        setupListener()
        initChannels()
    }

    private fun setupUi() {
        mainLayout = findViewById(R.id.mainLayout)
        uiLayout = findViewById(R.id.uiLayout)
        playerView = findViewById(R.id.player)
        playlistView = findViewById(R.id.playlist)
        appInfoView = findViewById(R.id.appInfo)
        channelSettingsView = findViewById(R.id.channelSettings)
        appSettingsView = findViewById(R.id.appSettings)
        appSettingsView.onAutoStartEnableRequested = ::requestAutoStart
        statusInfoBarView = findViewById(R.id.statusInfoBar)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        applyImmersiveMode()
        ViewCompat.setOnApplyWindowInsetsListener(uiLayout) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            safeOverlayInsets.set(insets.left, insets.top, insets.right, insets.bottom)
            requestOverlayLayoutSync()
            windowInsets
        }
        ViewCompat.requestApplyInsets(uiLayout)
        requestOverlayLayoutSync()
    }

    private fun requestAutoStart() {
        if (BootAutoStart.canStartFromBackground(this)) {
            BootAutoStart.setEnabled(this, true)
            appSettingsView.refreshPreferenceChoices()
            return
        }
        AutoStartPermissionDialog.show(this) {
            try {
                autoStartPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.auto_start_settings_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applyImmersiveMode() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBackNavigation()
        })
    }

    private fun setupListener() {
        playlistView.onChannelSelectCallback = {
            preference.edit().putString(LAST_CHANNEL, it.stableId).apply()
            playerView.channel = it
            channelSettingsView.setSelectedChannelSource(
                playerView.currentSourceIndex.coerceAtLeast(0), it.urls.size)
        }
        playlistView.onChannelActivated = { uiMode = UiMode.STANDARD }
        channelSettingsView.onAspectRatioSelected = {
            playerView.setVideoRatio(it)
            uiMode = UiMode.STANDARD
        }
        channelSettingsView.onAppSettingsSelected = { uiMode = UiMode.APP_SETTINGS }
        channelSettingsView.onChannelSourceSelected = { index ->
            consumePendingPlaylist()
            currentCatalogChannel()?.let { channel ->
                if (channel.urls.isNotEmpty() && (playerView.currentSourceUrl != channel.urls.getOrNull(index) || playerView.channel?.urls != channel.urls)) {
                    playerView.switchSource(channel, index.coerceIn(0, channel.urls.lastIndex))
                }
                updateSourceControls()
            }
            uiMode = UiMode.STANDARD
        }
        channelSettingsView.onGetVideoSize = { playerView.getVideoSize() }
        playerView.dismissAllViewCallback = { uiMode = UiMode.STANDARD }
        playerView.clickCallback = { x, _ ->
            val overlayReady = hasValidOverlayLayout || updateOverlayLayoutToVideoRect()
            if (!overlayReady) {
                requestOverlayLayoutSync()
            } else {
                // A GONE panel has no current layout position. Use its assigned
                // right-side bounds so the first left tap opens the channel list.
                uiLayout.getLocationInWindow(locationBufferA)
                playerView.getLocationInWindow(locationBufferB)
                val settingsParams = channelSettingsView.layoutParams as FrameLayout.LayoutParams
                val settingsLeft = locationBufferA[0] - locationBufferB[0] +
                    uiLayout.width - settingsParams.marginEnd - settingsParams.width
                uiMode = if (uiMode == UiMode.STANDARD)
                    if (x < settingsLeft) UiMode.CHANNELS else UiMode.CHANNEL_SETTINGS
                else
                    UiMode.STANDARD
            }
        }
        playerView.onVideoRatioChanged = {
            channelSettingsView.setSelectedAspectRatio(it)
            requestOverlayLayoutSync()
        }
        playerView.onVideoDisplayRectChanged = { requestOverlayLayoutSync() }
        playerView.onChannelReload = {
            updateSourceControls()
        }
        playerView.onChannelPlaybackFailed = {
            val failed = it
            val updated = pendingPlaylist?.findChannel(failed.stableId)
            consumePendingPlaylist()
            if (updated != null && updated.urls != failed.urls) {
                playlistView.currentChannel = updated
            } else {
                val token = playerView.playbackGeneration
                val next = {
                    if (playerView.playbackGeneration == token && !playerView.hasHealthyPlayback &&
                        (playlistView.playlist?.getAllChannels()?.size ?: 0) > 1) playlistView.nextChannel()
                }
                if (!PlaylistManager.refreshAfterPlaybackFailure(next)) next()
            }
        }
        bindPlaylistCallbacks()
    }

    private fun bindPlaylistCallbacks() {
        PlaylistManager.onPlaylistChange = playlistListener
        PlaylistManager.onUpdateError = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        PlaylistManager.onStatusChange = { appSettingsView.refreshSubscriptionStatus(pendingPlaylist != null) }
        PlaylistManager.onUpdatePlaylistJobStateChange = {
            runOnUiThread {
                playlistView.updating = it
                if (lastChannel == null && playlistView.playlist?.getAllChannels().isNullOrEmpty()) {
                    if (it) playerView.showPlaylistLoading() else playerView.showEmptyPlaylist()
                }
            }
        }
    }

    private fun initChannels() {
        val playlist = PlaylistManager.loadPlaylist()
        playlistView.playlist = playlist
        val saved = preference.getString(LAST_CHANNEL, "").orEmpty()
        val stableId = if (saved.contains(", ")) {
            val parts = saved.split(", ", limit = 2)
            ChannelIdentity.key(parts[0], parts[1])
        } else saved
        lastChannel = playlist.findChannel(stableId) ?: playlist.firstChannel()
        if (lastChannel == null) {
            if (PlaylistManager.isUpdating) playerView.showPlaylistLoading() else playerView.showEmptyPlaylist()
            channelSettingsView.setSelectedChannelSource(0, 0)
        }
    }

    internal fun applyPlaylist(playlist: Playlist, keepPlaying: Boolean = false) {
        val previousId = (playerView.channel ?: lastChannel)?.stableId
        val shouldPlay = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        playlistView.playlist = playlist
        lastChannel = playlist.findChannel(previousId) ?: playlist.firstChannel()
        if (lastChannel == null) {
            if (PlaylistManager.isUpdating) playerView.showPlaylistLoading() else playerView.showEmptyPlaylist()
            channelSettingsView.setSelectedChannelSource(0, 0)
        }
        else if (keepPlaying && playerView.channel != null) playlistView.syncSelectionWithoutPlayback(playlist.findChannel(previousId))
        else if (shouldPlay) playlistView.currentChannel = lastChannel
        restoreRemoteFocus()
    }

    private fun consumePendingPlaylist() {
        val playlist = pendingPlaylist ?: return
        pendingPlaylist = null
        applyPlaylist(playlist, keepPlaying = true)
        appSettingsView.refreshSubscriptionStatus(false)
    }

    private fun currentCatalogChannel(): Channel? {
        val current = playerView.channel ?: return null
        return pendingPlaylist?.findChannel(current.stableId)
            ?: playlistView.playlist?.findChannel(current.stableId) ?: current
    }

    private fun updateSourceControls() {
        val channel = currentCatalogChannel()
        channelSettingsView.setSelectedChannelSource(
            channel?.urls?.indexOf(playerView.currentSourceUrl)?.coerceAtLeast(0) ?: 0, channel?.urls?.size ?: 0)
    }

    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
        AutoSize.autoConvertDensityOfGlobal(this)
        return super.onCreateView(parent, name, context, attrs)
    }

    override fun onResume() {
        super.onResume()
        BootAutoStart.reconcile(this)
        appSettingsView.refreshPreferenceChoices()
        if (!ownsPlaylistCallbacks()) bindPlaylistCallbacks()
        consumePendingPlaylist()
        hasValidOverlayLayout = false
        if (lastChannel != null && playerView.channel == null) {
            playlistView.currentChannel = lastChannel
        }
        requestOverlayLayoutSync()
        PlaylistManager.startWatching()
    }

    override fun onPause() {
        super.onPause()
        if (ownsPlaylistCallbacks()) PlaylistManager.stopWatching()
        consumePendingPlaylist()
        invalidateRemotePresses()
        selectPress.reset()
        sourcePress.reset()
        hasValidOverlayLayout = false
        uiLayout.removeCallbacks(syncOverlayAction)
        playerView.removeCallbacks(hideBackInfoAction)
        uiMode = UiMode.STANDARD
        lastChannel = playlistView.playlist?.findChannel(playerView.channel?.stableId) ?: playlistView.playlist.firstChannel()
        playerView.channel = null
    }

    override fun onDestroy() {
        invalidateRemotePresses()
        playerView.removeCallbacks(hideBackInfoAction)
        playerView.channel = null
        playerView.release()
        if (ownsPlaylistCallbacks()) {
            PlaylistManager.cancelUpdate()
            PlaylistManager.stopWatching()
            PlaylistManager.onStatusChange = null
            PlaylistManager.onUpdateError = null
            PlaylistManager.onPlaylistChange = null
            PlaylistManager.onUpdatePlaylistJobStateChange = null
        }
        playerView.removeCallbacks(backToStandardModeAction)
        super.onDestroy()
    }

    private fun handleBackNavigation() {
        when (uiMode) {
            UiMode.STANDARD -> showBackInfoPanel()
            UiMode.BACK_INFO -> {
                val now = SystemClock.uptimeMillis()
                if (now <= backInfoExpiresAtUptime) {
                    exitApplication()
                } else {
                    showBackInfoPanel()
                }
            }
            else -> uiMode = UiMode.STANDARD
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
            restoreRemoteFocus()
            requestOverlayLayoutSync()
        } else {
            invalidateRemotePresses()
            if (uiMode == UiMode.BACK_INFO) uiMode = UiMode.STANDARD
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        onMotionEvent(event)
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean {
        return super.dispatchPopulateAccessibilityEvent(event)
    }

    override fun dispatchTrackballEvent(event: MotionEvent): Boolean {
        onMotionEvent(event)
        return super.dispatchTrackballEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        onMotionEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            outsideSettingsGesture = uiMode == UiMode.APP_SETTINGS && isOutsideAppSettings(event)
        }
        if (outsideSettingsGesture) {
            // Consume the whole gesture so its UP cannot activate the player.
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    outsideSettingsGesture = false
                    if (uiMode == UiMode.APP_SETTINGS && isOutsideAppSettings(event)) uiMode = UiMode.STANDARD
                }
                MotionEvent.ACTION_CANCEL -> outsideSettingsGesture = false
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isOutsideAppSettings(event: MotionEvent): Boolean {
        appSettingsView.getLocationOnScreen(locationBufferA)
        return event.rawX < locationBufferA[0] || event.rawX >= locationBufferA[0] + appSettingsView.width ||
            event.rawY < locationBufferA[1] || event.rawY >= locationBufferA[1] + appSettingsView.height
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) android.util.Log.d("RemoteControl",
            "key=${event.keyCode} action=${event.action} repeat=${event.repeatCount} cancelled=${event.isCanceled} device=${event.deviceId} down=${event.downTime} time=${event.eventTime} mode=$uiMode")
        val action = RemoteKeys.action(event.keyCode) ?: return super.dispatchKeyEvent(event)
        if (action != RemoteAction.SELECT) {
            selectPress.invalidate()
            playerView.removeCallbacks(selectLongPressAction)
        }
        if (action != RemoteAction.LEFT && action != RemoteAction.RIGHT) sourcePress.invalidate()
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (action == RemoteAction.SELECT) {
            handleSelectKey(event)
            return true
        }
        if ((action == RemoteAction.LEFT || action == RemoteAction.RIGHT) &&
            (isPlaybackMode() || sourcePress.matches(event.keyCode, event.deviceId, event.downTime))) {
            handleSourceKey(event, action == RemoteAction.RIGHT)
            return true
        }
        if (!RemoteKeys.shouldAct(action, event.action, event.repeatCount, event.isCanceled)) return true
        handleRemoteAction(action)
        return true
    }

    private fun handleSelectKey(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !event.isCanceled) {
            if (uiMode == UiMode.BACK_INFO) uiMode = UiMode.STANDARD
            selectPress.begin(event.keyCode, event.deviceId, event.downTime)
            playerView.removeCallbacks(selectLongPressAction)
            playerView.postDelayed(selectLongPressAction, ViewConfiguration.getLongPressTimeout().toLong())
            repostBackToStandardModeAction()
        } else if (event.action == KeyEvent.ACTION_UP) {
            // A long press can open another panel; its release must never activate that panel.
            if (selectPress.matches(event.keyCode, event.deviceId, event.downTime)) {
                playerView.removeCallbacks(selectLongPressAction)
                // Account for an overdue timer before deciding whether this was a short press.
                if (!event.isCanceled) handleLongSelect(event.eventTime)
                if (selectPress.release(event.keyCode, event.deviceId, event.downTime, event.isCanceled)) {
                    handleRemoteAction(RemoteAction.SELECT)
                }
            }
        }
    }

    private fun handleSourceKey(event: KeyEvent, forward: Boolean) {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !event.isCanceled && isPlaybackMode()) {
            if (uiMode == UiMode.BACK_INFO) uiMode = UiMode.STANDARD
            sourcePress.begin(event.keyCode, event.deviceId, event.downTime)
        } else if (event.action == KeyEvent.ACTION_UP &&
            sourcePress.release(event.keyCode, event.deviceId, event.downTime, event.isCanceled) && isPlaybackMode()) {
            consumePendingPlaylist()
            val channel = currentCatalogChannel() ?: return
            val current = channel.urls.indexOf(playerView.currentSourceUrl)
            val index = adjacentSourceIndex(channel.urls, current, forward)
                ?: if (current < 0 && channel.urls.size == 1) 0 else {
                    playerView.showCurrentChannelInfo()
                    return
                }
            playerView.switchSource(channel, index)
        }
    }

    private fun handleRemoteAction(action: RemoteAction) {
        if (action in listOf(RemoteAction.UP, RemoteAction.DOWN, RemoteAction.CHANNEL_UP,
                RemoteAction.CHANNEL_DOWN, RemoteAction.SELECT)) consumePendingPlaylist()
        repostBackToStandardModeAction()
        when (action) {
            RemoteAction.BACK -> handleBackNavigation()
            RemoteAction.SETTINGS -> uiMode = if (uiMode == UiMode.APP_SETTINGS) UiMode.STANDARD else UiMode.APP_SETTINGS
            RemoteAction.MENU -> uiMode = when (uiMode) {
                UiMode.CHANNEL_SETTINGS, UiMode.APP_SETTINGS -> UiMode.STANDARD
                else -> UiMode.CHANNEL_SETTINGS
            }
            RemoteAction.CHANNEL_UP, RemoteAction.CHANNEL_DOWN -> {
                uiMode = UiMode.STANDARD
                changeChannelFromRemote(action)
            }
            else -> when (uiMode) {
                UiMode.CHANNELS -> playlistView.handleRemoteAction(action)
                UiMode.CHANNEL_SETTINGS -> channelSettingsView.handleRemoteAction(action)
                UiMode.APP_SETTINGS -> appSettingsView.handleRemoteAction(action)
                else -> {
                    uiMode = UiMode.STANDARD
                    when (action) {
                        RemoteAction.UP, RemoteAction.DOWN -> changeChannelFromRemote(action)
                        RemoteAction.SELECT -> uiMode = UiMode.CHANNELS
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun changeChannelFromRemote(action: RemoteAction) {
        val forward = RemoteKeys.channelForward(action, SettingsManager.isChannelDirectionReversed()) ?: return
        if (forward) playlistView.nextChannel() else playlistView.previousChannel()
    }

    private fun restoreRemoteFocus() {
        if (!::playerView.isInitialized) return
        val owner = uiMode
        uiLayout.post {
            if (uiMode != owner || !hasWindowFocus()) return@post
            when (owner) {
                UiMode.CHANNELS -> playlistView.requestRemoteFocus()
                UiMode.CHANNEL_SETTINGS -> channelSettingsView.requestRemoteFocus()
                UiMode.APP_SETTINGS -> appSettingsView.requestRemoteFocus()
                else -> playerView.requestFocus()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestOverlayLayoutSync()
        restoreRemoteFocus()
    }

    private fun onMotionEvent(event: MotionEvent) {
        if (event.eventTime - event.downTime >= 1000L || event.action == KeyEvent.ACTION_UP) {
            repostBackToStandardModeAction()
        }
    }

    private fun repostBackToStandardModeAction() {
        playerView.removeCallbacks(backToStandardModeAction)
        if (uiMode == UiMode.APP_SETTINGS) return
        playerView.postDelayed(backToStandardModeAction, OPERATION_TIMEOUT)
    }

    private fun updateOverlayLayoutToVideoRect(): Boolean {
        if (!::playerView.isInitialized || !::uiLayout.isInitialized) return false
        if (playerView.width <= 0 || playerView.height <= 0 || uiLayout.width <= 0 || uiLayout.height <= 0) return false
        val playerRect = playerView.getStableVideoDisplayRectOrNull() ?: return false
        if (playerRect.width() <= 0 || playerRect.height() <= 0) return false

        playerView.getLocationInWindow(locationBufferA)
        uiLayout.getLocationInWindow(locationBufferB)
        val offsetX = locationBufferA[0] - locationBufferB[0]
        val offsetY = locationBufferA[1] - locationBufferB[1]

        val rawRect = Rect(
            playerRect.left + offsetX,
            playerRect.top + offsetY,
            playerRect.right + offsetX,
            playerRect.bottom + offsetY
        )
        val safeRect = resolveSafeVideoRect(rawRect)
        applyPlaylistBounds(safeRect)
        applyAppInfoBounds(safeRect)
        applyChannelSettingsBounds(safeRect)
        applyAppSettingsBounds(safeRect)
        applyStatusInfoBarPosition(safeRect)
        hasValidOverlayLayout = true
        return true
    }

    private fun applyAppInfoBounds(videoRect: Rect) {
        val params = appInfoView.layoutParams as FrameLayout.LayoutParams
        val minWidth = (videoRect.width() * 0.32f).toInt()
        val maxWidthByVideo = (videoRect.width() * 0.58f).toInt()
        val maxWidthByScreen = (uiLayout.width * 0.44f).toInt()
        val maxWidth = minOf(maxWidthByVideo, maxWidthByScreen)
        val targetWidth = (videoRect.width() * 0.36f).toInt()
        params.width = targetWidth.coerceIn(minWidth, maxWidth.coerceAtLeast(minWidth))
        params.height = videoRect.height()
        params.gravity = Gravity.START or Gravity.TOP
        params.marginStart = videoRect.left
        params.topMargin = videoRect.top
        appInfoView.layoutParams = params
    }

    private fun applyAppSettingsBounds(videoRect: Rect) {
        val params = appSettingsView.layoutParams as FrameLayout.LayoutParams
        params.width = (videoRect.width() * 0.9f).toInt()
        params.height = (videoRect.height() * 0.9f).toInt()
        params.gravity = Gravity.START or Gravity.TOP
        params.marginStart = videoRect.left + (videoRect.width() - params.width) / 2
        params.topMargin = videoRect.top + (videoRect.height() - params.height) / 2
        appSettingsView.layoutParams = params
    }

    private fun applyPlaylistBounds(videoRect: Rect) {
        val params = playlistView.layoutParams as FrameLayout.LayoutParams
        val minWidth = (videoRect.width() * 0.33f).toInt()
        val maxWidthByVideo = (videoRect.width() * 0.66f).toInt()
        val maxWidthByScreen = (uiLayout.width * 0.54f).toInt()
        val maxWidth = minOf(maxWidthByVideo, maxWidthByScreen)
        val targetWidth = (videoRect.width() * 0.44f).toInt()
        params.width = targetWidth.coerceIn(minWidth, maxWidth.coerceAtLeast(minWidth))
        params.height = videoRect.height()
        params.gravity = Gravity.START or Gravity.TOP
        params.marginStart = videoRect.left
        params.topMargin = videoRect.top
        playlistView.layoutParams = params
    }

    private fun applyChannelSettingsBounds(videoRect: Rect) {
        val params = channelSettingsView.layoutParams as FrameLayout.LayoutParams
        val minWidth = (videoRect.width() * 0.32f).toInt()
        val maxWidthByVideo = (videoRect.width() * 0.58f).toInt()
        val maxWidthByScreen = (uiLayout.width * 0.54f).toInt()
        val maxWidth = minOf(maxWidthByVideo, maxWidthByScreen)
        val targetWidth = (videoRect.width() * 0.41f).toInt()
        params.width = targetWidth.coerceIn(minWidth, maxWidth.coerceAtLeast(minWidth))
        params.height = videoRect.height()
        params.gravity = Gravity.END or Gravity.TOP
        params.topMargin = videoRect.top
        params.marginEnd = (uiLayout.width - videoRect.right).coerceAtLeast(0)
        channelSettingsView.layoutParams = params
    }

    private fun applyStatusInfoBarPosition(videoRect: Rect) {
        val params = statusInfoBarView.layoutParams as FrameLayout.LayoutParams
        params.gravity = Gravity.START or Gravity.TOP
        params.marginStart = videoRect.left
        params.topMargin = videoRect.top
        statusInfoBarView.layoutParams = params
    }

    private fun resolveSafeVideoRect(rawRect: Rect): Rect {
        val bounds = Rect(
            safeOverlayInsets.left,
            safeOverlayInsets.top,
            uiLayout.width - safeOverlayInsets.right,
            uiLayout.height - safeOverlayInsets.bottom
        )
        if (bounds.width() <= 0 || bounds.height() <= 0) return rawRect
        val rect = Rect(rawRect)
        rect.left = rect.left.coerceIn(bounds.left, bounds.right - 1)
        rect.top = rect.top.coerceIn(bounds.top, bounds.bottom - 1)
        rect.right = rect.right.coerceIn(rect.left + 1, bounds.right)
        rect.bottom = rect.bottom.coerceIn(rect.top + 1, bounds.bottom)
        val minW = (bounds.width() * 0.45f).toInt()
        val minH = (bounds.height() * 0.45f).toInt()
        return if (rect.width() < minW || rect.height() < minH) bounds else rect
    }

    private fun requestOverlayLayoutSync() {
        if (!::uiLayout.isInitialized) return
        uiLayout.removeCallbacks(syncOverlayAction)
        uiLayout.post(syncOverlayAction)
        uiLayout.postDelayed(syncOverlayAction, 80L)
        uiLayout.postDelayed(syncOverlayAction, 220L)
    }

    private fun showBackInfoPanel() {
        backInfoExpiresAtUptime = SystemClock.uptimeMillis() + BACK_INFO_WINDOW_MS
        playerView.removeCallbacks(hideBackInfoAction)
        uiMode = UiMode.BACK_INFO
        playerView.postDelayed(hideBackInfoAction, BACK_INFO_WINDOW_MS)
    }

    private fun exitApplication() {
        invalidateRemotePresses()
        playerView.removeCallbacks(hideBackInfoAction)
        playerView.channel = null
        playerView.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
        window?.decorView?.postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 120L)
    }

}


