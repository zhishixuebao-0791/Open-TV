package com.vasthread.webviewtv.widget

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import com.vasthread.webviewtv.R
import com.vasthread.webviewtv.misc.getTrafficBytes
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.system.measureTimeMillis

class StatusInfoBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val UPDATE_PERIOD_MS = 1000L
    }

    private val tvStatusTime: TextView
    private val tvStatusNetworkSpeed: TextView
    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    private var lastTrafficBytes = 0L
    private var lastTrafficBytesUpdateTime = 0L

    private lateinit var updateAction: Runnable

    init {
        isClickable = false
        isFocusable = false
        LayoutInflater.from(context).inflate(R.layout.widget_status_info, this)
        tvStatusTime = findViewById(R.id.tvStatusTime)
        tvStatusNetworkSpeed = findViewById(R.id.tvStatusNetworkSpeed)
        updateAction = Runnable {
            val cost: Long = measureTimeMillis {
                tvStatusTime.text = sdf.format(System.currentTimeMillis())
                updateNetworkSpeed()
            }
            val nextDelay = (UPDATE_PERIOD_MS - cost).coerceAtLeast(80L)
            postDelayed(updateAction, nextDelay)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(updateAction)
        post(updateAction)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(updateAction)
        super.onDetachedFromWindow()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        removeCallbacks(updateAction)
        if (visibility == VISIBLE && isAttachedToWindow) {
            post(updateAction)
        }
    }

    private fun updateNetworkSpeed() {
        val trafficBytes = getTrafficBytes()
        if (lastTrafficBytes != 0L) {
            val duration = (SystemClock.uptimeMillis() - lastTrafficBytesUpdateTime) / 1000f
            var speed = (trafficBytes - lastTrafficBytes) / duration / 1024f
            if (speed >= 1024f) {
                speed /= 1024f
                val speedString = if (speed >= 10f) "%.0f".format(speed) else "%.1f".format(speed)
                tvStatusNetworkSpeed.text = "$speedString MB/s"
            } else {
                tvStatusNetworkSpeed.text = "${speed.toInt()} KB/s"
            }
        }
        lastTrafficBytes = trafficBytes
        lastTrafficBytesUpdateTime = SystemClock.uptimeMillis()
    }
}
