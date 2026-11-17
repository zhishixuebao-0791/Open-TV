package com.vasthread.webviewtv.widget

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import com.vasthread.webviewtv.R

@SuppressLint("SetTextI18n")
class AppInfoView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        isClickable = true
        isFocusable = false
        setBackgroundResource(R.drawable.bg_app_info_panel)
        LayoutInflater.from(context).inflate(R.layout.widget_app_info, this)

        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "-" }

        findViewById<TextView>(R.id.tvAppInfo).text = context.getString(
            R.string.app_meta_format, versionName
        )
        findViewById<TextView>(R.id.tvSystemInfo).visibility = GONE
    }
}
