package com.vasthread.webviewtv.startup

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.vasthread.webviewtv.R

/** Keep this system-permission explanation independent of AndroidAutoSize's shared metrics. */
object AutoStartPermissionDialog {
    @Suppress("DEPRECATION")
    fun show(activity: Activity, requestPermission: () -> Unit) {
        val metrics = DisplayMetrics()
        activity.windowManager.defaultDisplay.getRealMetrics(metrics)
        fun dp(value: Int) = (value * metrics.density).toInt()
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            setBackgroundColor(Color.rgb(33, 36, 46))
        }
        panel.addView(TextView(activity).apply {
            setText(R.string.auto_start_permission_title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(22).toFloat())
        }, LinearLayout.LayoutParams(-1, -2))
        panel.addView(TextView(activity).apply {
            id = android.R.id.message
            setText(R.string.auto_start_permission_message)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(18).toFloat())
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        panel.addView(buttons, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(18) })
        val cancel = Button(activity).apply { id = android.R.id.button2; setText(android.R.string.cancel) }
        val confirm = Button(activity).apply { id = android.R.id.button1; setText(R.string.auto_start_permission_settings) }
        listOf(cancel, confirm).forEach {
            it.isAllCaps = false
            it.minHeight = 0
            it.minimumHeight = 0
            it.gravity = Gravity.CENTER
            it.setPadding(dp(8), 0, dp(8), 0)
            it.setTextColor(Color.WHITE)
            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(18).toFloat())
            it.setBackgroundResource(R.drawable.bg_channel_option)
            buttons.addView(it, LinearLayout.LayoutParams(0, -1, 1f))
        }
        val dialog = AlertDialog.Builder(activity).setView(panel).create()
        cancel.setOnClickListener { dialog.dismiss() }
        confirm.setOnClickListener { dialog.dismiss(); requestPermission() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.setPadding(0, 0, 0, 0)
            setLayout(minOf((metrics.widthPixels * .8f).toInt(), dp(720)), WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }
}
