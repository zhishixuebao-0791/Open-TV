package com.vasthread.webviewtv.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vasthread.webviewtv.R
import com.vasthread.webviewtv.misc.RemoteAction
import com.vasthread.webviewtv.misc.RemoteKeys
import com.vasthread.webviewtv.misc.focusRemoteItem
import com.vasthread.webviewtv.misc.adjustValue
import com.vasthread.webviewtv.playlist.Channel
import com.vasthread.webviewtv.playlist.ChannelGroup
import com.vasthread.webviewtv.playlist.Playlist
import com.vasthread.webviewtv.playlist.Playlist.Companion.firstChannel
import com.vasthread.webviewtv.settings.SettingsManager

class PlaylistView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        private val STATE_PRESSED = intArrayOf(android.R.attr.state_pressed)
        private val STATE_EMPTY = intArrayOf()
    }

    private val btnPageUp: Button
    private val btnPageDown: Button
    private val tvGroupName: TextView
    private val rvChannels: RecyclerView
    private val pbUpdating: ProgressBar

    var playlist: Playlist? = null
        set(value) {
            field = value
            if (value == null || value.getAllChannels().isEmpty()) {
                rvChannels.adapter = null
                tvGroupName.text = "暂无可用频道"
                currentChannel = null
                btnPageDown.visibility = GONE
                btnPageUp.visibility = GONE
            } else {
                currentPage = 0
                val singleGroup = value.groups.size <= 1
                btnPageDown.visibility = if (singleGroup) GONE else VISIBLE
                btnPageUp.visibility = if (singleGroup) GONE else VISIBLE
            }
        }

    private var selectedChannel: Channel? = null
    var currentChannel: Channel?
        get() = selectedChannel
        set(value) {
            selectedChannel = value
            requestedFocusId = value?.stableId
            updateVisibleSelection()
            if (value != null) onChannelSelectCallback?.invoke(value)
        }

    fun syncSelectionWithoutPlayback(channel: Channel?) {
        selectedChannel = channel
        updateVisibleSelection()
    }

    private fun updateVisibleSelection() {
        for (index in 0 until rvChannels.childCount) {
            (rvChannels.getChildViewHolder(rvChannels.getChildAt(index)) as? ViewHolder)?.updateSelection()
        }
    }

    private fun activateChannel(channel: Channel) {
        requestedFocusId = channel.stableId
        if (currentChannel != channel || currentChannel?.urls != channel.urls) currentChannel = channel
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun prepareTouchClick(view: View) {
        view.setOnTouchListener { target, event ->
            // Acquire focus before View handles UP. Returning false delegates
            // performClick, scrolling, and cancellation to the native handler.
            if (event.actionMasked == MotionEvent.ACTION_UP) target.requestFocus()
            false
        }
    }

    var updating = false
        set(value) {
            println("播放列表更新状态：$value")
            field = value
            pbUpdating.visibility = if (value) VISIBLE else GONE
        }

    private var currentPage: Int = 0
        set(value) {
            field = value
            val group = playlist?.groups?.getOrNull(value) ?: return
            setCurrentGroup(group)
        }
    var onChannelSelectCallback: ((Channel) -> Unit)? = null
    var onChannelActivated: (() -> Unit)? = null
    private var requestedFocusId: String? = null

    init {
        isClickable = true
        isFocusable = false
        orientation = VERTICAL
        setBackgroundResource(R.drawable.bg_side_panel_left)
        LayoutInflater.from(context).inflate(R.layout.widget_playlist, this)

        btnPageUp = findViewById(R.id.btnPageUp)
        btnPageDown = findViewById(R.id.btnPageDown)
        tvGroupName = findViewById(R.id.tvGroupName)
        rvChannels = findViewById(R.id.rvChannels)
        pbUpdating = findViewById(R.id.pbUpdating)

        btnPageUp.setOnClickListener { turnPage(false) }
        btnPageDown.setOnClickListener { turnPage(true) }
    }

    fun previousChannel() = selectChannel(false)

    fun nextChannel() = selectChannel(true)

    fun handleRemoteAction(action: RemoteAction) {
        if (action == RemoteAction.LEFT || action == RemoteAction.RIGHT) {
            turnPage(action == RemoteAction.RIGHT)
            return
        }
        val channels = playlist?.groups?.getOrNull(currentPage)?.channels.orEmpty()
        if (channels.isEmpty()) return
        var index = channels.indexOfFirst { it.stableId == requestedFocusId }
        if (index < 0) index = channels.indexOf(currentChannel).coerceAtLeast(0)
        when (action) {
            RemoteAction.UP, RemoteAction.DOWN -> {
                val forward = RemoteKeys.channelForward(action, SettingsManager.isChannelDirectionReversed()) == true
                val target = adjustValue(index, channels.size, forward)
                focusChannel(channels[target])
                activateChannel(channels[target])
            }
            RemoteAction.SELECT -> {
                activateChannel(channels[index])
                onChannelActivated?.invoke()
            }
            else -> Unit
        }
    }

    fun requestRemoteFocus() {
        if (!isShown) return
        val channels = playlist?.groups?.getOrNull(currentPage)?.channels.orEmpty()
        val target = channels.firstOrNull { it.stableId == requestedFocusId }
            ?: channels.firstOrNull { it == currentChannel } ?: channels.firstOrNull()
        if (target == null) { rvChannels.clearFocus(); return }
        focusChannel(target)
    }

    private fun focusChannel(target: Channel) {
        val channels = playlist?.groups?.getOrNull(currentPage)?.channels.orEmpty()
        val position = channels.indexOf(target)
        if (position < 0) return
        requestedFocusId = target.stableId
        rvChannels.focusRemoteItem(position) { requestedFocusId == target.stableId }
    }

    private fun turnPage(down: Boolean) {
        if ((playlist?.groups?.size ?: 0) <= 1) return
        currentPage = adjustValue(currentPage, playlist!!.groups.size, down)
        requestedFocusId = null
        requestRemoteFocus()
    }

    private fun selectChannel(next: Boolean) {
        val channels = playlist?.getAllChannels().orEmpty()
        if (channels.isEmpty()) return
        val index = channels.indexOf(currentChannel)
        currentChannel = if (index < 0) channels.first() else channels[adjustValue(index, channels.size, next)]
    }

    @SuppressLint("SetTextI18n")
    private fun setCurrentGroup(group: ChannelGroup) {
        requestedFocusId = null
        rvChannels.adapter = ChannelAdapter(group)
        val displayGroupName = when (group.name.trim()) {
            "", "默认" -> "全部频道"
            else -> group.name
        }
        tvGroupName.text = "$displayGroupName(${group.channels.size})"
    }

    private fun focusCurrentChannel() = requestRemoteFocus()

    @SuppressLint("NotifyDataSetChanged")
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && playlist != null && currentChannel != null) {
            val adapter = rvChannels.adapter as? ChannelAdapter ?: return
            if (currentChannel!!.groupName != adapter.group.name) {
                val index = playlist!!.indexOf(currentChannel!!)
                if (index != null) {
                    currentPage = index.first
                }
            } else {
                adapter.notifyDataSetChanged()
            }
            focusCurrentChannel()
        }
    }

    private inner class ChannelAdapter(val group: ChannelGroup) : RecyclerView.Adapter<ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount() = group.channels.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(position + 1, group.channels[position])
        }

    }

    private inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view), OnClickListener {

        private val tvNumber: TextView = itemView.findViewById(R.id.tvNumber)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        var channel: Channel? = null

        init {
            itemView.isFocusableInTouchMode = true
            itemView.setOnClickListener(this)
            prepareTouchClick(itemView)
            itemView.setOnFocusChangeListener { _, focused -> if (focused) requestedFocusId = channel?.stableId }
        }

        fun bind(number: Int, channel: Channel) {
            this.channel = channel

            tvNumber.text = String.format("%02d", number)
            tvTitle.text = channel.name

            updateSelection()
        }

        fun updateSelection() {
            val isSelected = channel == currentChannel
            itemView.isSelected = isSelected
            tvNumber.isSelected = isSelected
            tvTitle.isSelected = isSelected
        }

        override fun onClick(v: View) {
            channel?.let { activateChannel(it); onChannelActivated?.invoke() }
        }

    }
}
