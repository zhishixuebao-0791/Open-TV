package com.vasthread.webviewtv.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import com.vasthread.webviewtv.R

class WaitingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val LOADING_TEXT = "\u6b63\u5728\u52a0\u8f7d..."
        private const val FAILED_TEXT = "\u8be5\u9891\u9053\u52a0\u8f7d\u5931\u8d25\uff01"
    }

    private val tvWaitingText: TextView

    init {
        isClickable = true
        isFocusable = false
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        LayoutInflater.from(context).inflate(R.layout.widget_waiting, this)
        tvWaitingText = findViewById(R.id.tvWaitingText)
        showLoadingText()
    }

    fun showLoadingText() {
        findViewById<android.view.View>(R.id.pb).visibility = VISIBLE
        tvWaitingText.text = LOADING_TEXT
    }

    fun showFailedText() {
        findViewById<android.view.View>(R.id.pb).visibility = GONE
        tvWaitingText.text = FAILED_TEXT
    }

    fun showMessage(message: String) {
        findViewById<android.view.View>(R.id.pb).visibility = GONE
        tvWaitingText.text = message
    }
}
