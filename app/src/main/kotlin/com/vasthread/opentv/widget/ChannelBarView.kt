package com.vasthread.webviewtv.widget

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.vasthread.webviewtv.R
import com.vasthread.webviewtv.playlist.Channel

class ChannelBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val DISPLAY_DURATION_MS = 3000L
    }

    private val tvChannelName: TextView
    private val tvChannelUrl: TextView
    private val tvCurrentSource: TextView
    private val tvProgress: TextView

    private val dismissAction = Runnable { hide(animated = true) }

    init {
        isClickable = true
        isFocusable = false
        LayoutInflater.from(context).inflate(R.layout.widget_channel_bar, this)
        tvChannelName = findViewById(R.id.tvChannelName)
        tvChannelUrl = findViewById(R.id.tvChannelUrl)
        tvCurrentSource = findViewById(R.id.tvCurrentSource)
        tvProgress = findViewById(R.id.tvProgress)
        visibility = GONE
    }

    fun setCurrentChannelAndShow(channel: Channel, sourceIndex: Int, sourceUrl: String, ready: Boolean = false) {
        removeCallbacks(dismissAction)
        tvChannelName.text = sanitizeName(channel.name)
        tvCurrentSource.text = context.getString(R.string.source_position, sourceIndex + 1, channel.urls.size)
        tvChannelUrl.text = formatUrlForDisplay(sourceUrl)
        show(animated = true)
        setProgress(if (ready) 100 else 0)
        postDelayed(dismissAction, DISPLAY_DURATION_MS)
    }

    fun dismiss() {
        removeCallbacks(dismissAction)
        hide(animated = true)
    }

    fun setProgress(progress: Int) {
        if (visibility != VISIBLE) return
        val value = progress.coerceIn(0, 100)
        tvProgress.text = when {
            value >= 100 -> "已连接"
            value <= 0 -> "加载中"
            else -> "加载 $value%"
        }
    }

    fun notifyPlaybackReady() {
        setProgress(100)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(dismissAction)
        animate().cancel()
        visibility = GONE
        alpha = 1f
        super.onDetachedFromWindow()
    }

    private fun show(animated: Boolean) {
        animate().cancel()
        if (visibility != VISIBLE) {
            alpha = if (animated) 0f else 1f
            visibility = VISIBLE
        }
        if (animated) {
            animate().alpha(1f).setDuration(180L).start()
        } else {
            alpha = 1f
        }
    }

    private fun hide(animated: Boolean) {
        animate().cancel()
        if (!animated || visibility != VISIBLE) {
            alpha = 1f
            visibility = GONE
            return
        }
        animate().alpha(0f).setDuration(180L).withEndAction {
            alpha = 1f
            visibility = GONE
        }.start()
    }

    private fun sanitizeName(raw: String): String {
        return raw.replace(Regex("[\\u0000-\\u001F]"), "").trim()
    }

    private fun formatUrlForDisplay(rawUrl: String): String {
        val url = rawUrl.trim()
        if (url.isEmpty()) return ""
        return runCatching {
            val uri = Uri.parse(url)
            val host = uri.host?.trim().orEmpty()
            val path = uri.encodedPath.orEmpty()
            val query = uri.encodedQuery.orEmpty()
            if (host.isEmpty()) return@runCatching shorten(url)
            val shortPath = if (path.length > 28) {
                "${path.take(18)}...${path.takeLast(7)}"
            } else {
                path
            }
            val shortQuery = if (query.isEmpty()) {
                ""
            } else {
                val capped = if (query.length > 20) "${query.take(20)}..." else query
                "?$capped"
            }
            "$host$shortPath$shortQuery"
        }.getOrElse {
            shorten(url)
        }
    }

    private fun shorten(text: String): String {
        if (text.length <= 92) return text
        return "${text.take(44)}...${text.takeLast(36)}"
    }
}
