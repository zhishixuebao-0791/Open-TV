package com.vasthread.webviewtv.widget

import android.content.Context
import android.graphics.Point
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.vasthread.webviewtv.R
import com.vasthread.webviewtv.misc.RemoteAction
import com.vasthread.webviewtv.misc.navigateRemoteControls
import kotlin.system.measureTimeMillis

@Suppress("PrivatePropertyName", "LocalVariableName")
class ChannelSettingsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val UPDATE_PERIOD = 1000L
    }

    private val tvSource: TextView
    private val llSource: LinearLayout
    private val btnSourcePrev: Button
    private val btnSourceNext: Button
    private val tvSourceCurrent: TextView
    private val rbAspectRatio_16_9: Button
    private val rbAspectRatio_4_3: Button
    private val btnAppSettings: Button
    private val tvVideoSize: TextView
    private var currentSourceIndex = 0
    private var currentSourceSize = 0

    var onChannelSourceSelected: ((Int) -> Unit)? = null
    var onAspectRatioSelected: ((Boolean) -> Unit)? = null
    var onGetVideoSize: (() -> Point)? = null
    var onAppSettingsSelected: (() -> Unit)? = null

    private lateinit var updateAction: Runnable

    init {
        isClickable = true
        isFocusable = false
        LayoutInflater.from(context).inflate(R.layout.widget_channel_settings, this)

        tvSource = findViewById(R.id.tvSource)
        llSource = findViewById(R.id.llSource)
        btnSourcePrev = findViewById(R.id.btnSourcePrev)
        btnSourceNext = findViewById(R.id.btnSourceNext)
        tvSourceCurrent = findViewById(R.id.tvSourceCurrent)
        rbAspectRatio_16_9 = findViewById(R.id.rbAspectRatio_16_9)
        rbAspectRatio_4_3 = findViewById(R.id.rbAspectRatio_4_3)
        tvVideoSize = findViewById(R.id.tvVideoSize)
        btnAppSettings = findViewById(R.id.btnAppSettings)
        btnAppSettings.setOnClickListener { onAppSettingsSelected?.invoke() }
        listOf(btnSourcePrev, btnSourceNext, rbAspectRatio_16_9, rbAspectRatio_4_3, btnAppSettings).forEach { it.isFocusableInTouchMode = true }

        btnSourcePrev.setOnClickListener { moveSource(-1) }
        btnSourceNext.setOnClickListener { moveSource(1) }
        rbAspectRatio_16_9.setOnClickListener { onAspectRatioSelected?.invoke(true) }
        rbAspectRatio_4_3.setOnClickListener { onAspectRatioSelected?.invoke(false) }

        updateAction = Runnable {
            val time = measureTimeMillis {
                onGetVideoSize?.invoke()?.let {
                    tvVideoSize.text =
                        if (it.x == 0 || it.y == 0) context.getString(R.string.unknown)
                        else "${it.x}x${it.y}"
                }
            }
            postDelayed(updateAction, (UPDATE_PERIOD - time).coerceAtLeast(80L))
        }
        setSelectedAspectRatio(true)
        setSelectedChannelSource(0, 0)
    }

    fun setSelectedAspectRatio(is_16_9: Boolean) {
        rbAspectRatio_16_9.isSelected = is_16_9
        rbAspectRatio_4_3.isSelected = !is_16_9
    }

    fun setSelectedChannelSource(sourceIndex: Int, sourceSize: Int) {
        currentSourceSize = sourceSize.coerceAtLeast(0)
        currentSourceIndex = if (currentSourceSize == 0) 0 else sourceIndex.coerceIn(0, currentSourceSize - 1)
        val hasMultipleSources = currentSourceSize >= 2
        tvSource.isVisible = currentSourceSize > 0
        llSource.isVisible = currentSourceSize > 0
        tvSourceCurrent.text = buildSourceLabel()
        btnSourcePrev.isVisible = hasMultipleSources
        btnSourceNext.isVisible = hasMultipleSources
        if (isShown) post { requestRemoteFocus() }
    }

    private fun moveSource(delta: Int) {
        if (currentSourceSize < 2) return
        val nextIndex = (currentSourceIndex + delta + currentSourceSize) % currentSourceSize
        onChannelSourceSelected?.invoke(nextIndex)
    }

    private fun buildSourceLabel(): String {
        if (currentSourceSize <= 0) return "\u7EBF\u8DEF1"
        return "\u7EBF\u8DEF${currentSourceIndex + 1}"
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == VISIBLE) {
            post(updateAction)
            post { requestRemoteFocus() }
        } else {
            removeCallbacks(updateAction)
        }
    }

    private fun defaultControl() = if (rbAspectRatio_16_9.isSelected) rbAspectRatio_16_9 else rbAspectRatio_4_3

    fun requestRemoteFocus() {
        if (isShown && findFocus() == null) defaultControl().requestFocus()
    }

    fun handleRemoteAction(action: RemoteAction) {
        navigateRemoteControls(listOf(listOf(btnSourcePrev, btnSourceNext),
            listOf(rbAspectRatio_16_9, rbAspectRatio_4_3), listOf(btnAppSettings)), defaultControl(), action)
    }
}
