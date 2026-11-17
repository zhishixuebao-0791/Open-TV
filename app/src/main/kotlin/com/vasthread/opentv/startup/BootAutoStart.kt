package com.vasthread.webviewtv.startup

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.vasthread.webviewtv.settings.SettingsManager

object BootAutoStart {
    fun canStartFromBackground(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)

    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (enabled && !canStartFromBackground(context)) return false
        SettingsManager.saveAutoStartEnabled(enabled)
        syncReceiver(context, enabled)
        return true
    }

    fun reconcile(context: Context) {
        val requested = SettingsManager.isAutoStartEnabled()
        val enabled = requested && canStartFromBackground(context)
        if (requested != enabled) SettingsManager.saveAutoStartEnabled(enabled)
        syncReceiver(context, enabled)
    }

    private fun syncReceiver(context: Context, enabled: Boolean) {
        val component = ComponentName(context, BootReceiver::class.java)
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (context.packageManager.getComponentEnabledSetting(component) != state) {
            context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }
}
