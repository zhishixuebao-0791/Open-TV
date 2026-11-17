package com.vasthread.webviewtv.startup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vasthread.webviewtv.activity.MainActivity
import com.vasthread.webviewtv.settings.SettingsManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !SettingsManager.isAutoStartEnabled()) return
        if (!BootAutoStart.canStartFromBackground(context)) {
            Log.w(TAG, "开机自启未执行：后台启动授权不可用")
            return
        }
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ))
            Log.i(TAG, "已收到系统开机广播并请求启动应用")
        } catch (error: RuntimeException) {
            Log.e(TAG, "开机启动应用失败", error)
        }
    }

    companion object { private const val TAG = "BootAutoStart" }
}
