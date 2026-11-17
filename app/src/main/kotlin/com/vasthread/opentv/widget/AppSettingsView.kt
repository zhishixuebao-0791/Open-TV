package com.vasthread.webviewtv.widget

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.WindowManager
import android.view.ContextThemeWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.vasthread.webviewtv.R
import com.vasthread.webviewtv.misc.RemoteAction
import com.vasthread.webviewtv.misc.focusRemoteItem
import com.vasthread.webviewtv.activity.TbsDebugActivity
import com.vasthread.webviewtv.misc.adjustValue
import com.vasthread.webviewtv.playlist.PlaylistManager
import com.vasthread.webviewtv.settings.SettingItem
import com.vasthread.webviewtv.settings.SettingsManager
import com.vasthread.webviewtv.startup.BootAutoStart

class AppSettingsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val rvSettings: RecyclerView
    private lateinit var subscriptionStatus: TextView
    private var focusedSetting = 0
    private var pendingSubscriptionUpdate = false
    var onAutoStartEnableRequested: (() -> Unit)? = null

    private val settings = arrayOf(
        SettingItem(
            R.string.channel_list,
            SettingsManager.getPlaylistNames(),
            SettingsManager.getSelectedPlaylistPosition(),
            onItemSelect = SettingsManager::setSelectedPlaylistPosition
        ),
        SettingItem(
            R.string.max_loading_time,
            context.resources.getStringArray(R.array.loading_time_text),
            context.resources.getIntArray(R.array.loading_time_value).indexOf(SettingsManager.getMaxLoadingTime()),
            onItemSelect = {
                SettingsManager.setMaxLoadingTime(context.resources.getIntArray(R.array.loading_time_value)[it])
            }
        ),
        SettingItem(
            R.string.auto_start,
            arrayOf(context.getString(R.string.off), context.getString(R.string.on)),
            if (SettingsManager.isAutoStartEnabled()) 1 else 0,
            onItemSelect = {
                if (it != 0) {
                    refreshPreferenceChoices()
                    onAutoStartEnableRequested?.invoke()
                }
                else BootAutoStart.setEnabled(context, false)
                refreshPreferenceChoices()
            }
        ),
        SettingItem(
            R.string.reverse_channel_direction,
            arrayOf(context.getString(R.string.off), context.getString(R.string.on)),
            if (SettingsManager.isChannelDirectionReversed()) 1 else 0,
            onItemSelect = { SettingsManager.setChannelDirectionReversed(it != 0) }
        ),
        SettingItem(
            R.string.refresh_channel_list,
            onClick = { PlaylistManager.setLastUpdate(0, true) }
        ),
        SettingItem(
            R.string.tbs_debug,
            onClick = {
                context.startActivity(Intent(context, TbsDebugActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        ),
        SettingItem(
            R.string.web_view_touchable,
            arrayOf(context.getString(R.string.off), context.getString(R.string.on)),
            if (SettingsManager.isWebViewTouchable()) 1 else 0,
            onItemSelect = { SettingsManager.setWebViewTouchable(it != 0) }
        ),
        SettingItem(R.string.subscription_address, onClick = { editSubscriptionAddress(false) }),
        SettingItem(R.string.backup_address, onClick = { editSubscriptionAddress(true) })
    )

    init {
        isClickable = true
        isFocusable = false
        setBackgroundResource(R.drawable.bg)
        LayoutInflater.from(context).inflate(R.layout.widget_settings, this)
        rvSettings = findViewById(R.id.rvSettings)
        rvSettings.adapter = SettingsAdapter()
        subscriptionStatus = findViewById(R.id.tvSubscriptionStatus)
        refreshSubscriptionStatus()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == VISIBLE) {
            refreshSubscriptionStatus()
            refreshPreferenceChoices()
            focusedSetting = 0
            post { requestRemoteFocus() }
        }
    }

    fun refreshSubscriptionStatus(pending: Boolean? = null) {
        if (!::subscriptionStatus.isInitialized) return
        if (pending != null) pendingSubscriptionUpdate = pending
        subscriptionStatus.text = PlaylistManager.statusText() + if (pendingSubscriptionUpdate) " · 换台后使用新表" else ""
        settings[0].selectedItemPosition = SettingsManager.getSelectedPlaylistPosition()
        rvSettings.adapter?.notifyItemChanged(0)
    }

    fun refreshPreferenceChoices() {
        if (!::subscriptionStatus.isInitialized) return
        for (title in listOf(R.string.auto_start, R.string.reverse_channel_direction)) {
            val index = settings.indexOfFirst { it.titleRes == title }
            settings[index].selectedItemPosition = if (when (title) {
                R.string.auto_start -> SettingsManager.isAutoStartEnabled()
                else -> SettingsManager.isChannelDirectionReversed()
            }) 1 else 0
            (rvSettings.findViewHolderForAdapterPosition(index) as? ViewHolder)?.bind(settings[index])
            rvSettings.adapter?.notifyItemChanged(index)
        }
    }

    fun focusSettingTitle(title: Int) {
        val index = settings.indexOfFirst { it.titleRes == title }
        if (index >= 0) post { focusSetting(index) }
    }

    private fun editSubscriptionAddress(backup: Boolean) {
        // Keep the system keyboard/dialog dimensions independent of the TV canvas scaling.
        val configuration = Configuration(resources.configuration).apply {
            densityDpi = Resources.getSystem().displayMetrics.densityDpi
        }
        val dialogContext = ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .apply { applyOverrideConfiguration(configuration) }
        fun dp(value: Int) = (value * dialogContext.resources.displayMetrics.density).toInt()
        val panel = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(android.graphics.Color.rgb(33, 36, 46))
        }
        panel.addView(TextView(dialogContext).apply {
            setText(if (backup) R.string.backup_address else R.string.subscription_address)
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
        })
        val input = EditText(dialogContext).apply {
            id = R.id.etSubscriptionAddress
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            textSize = 14f
            minimumHeight = 0
            setPadding(dp(4), 0, dp(4), 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.LTGRAY)
            setText(if (backup) PlaylistManager.getCustomBackupUrl() else PlaylistManager.getCustomOnlineUrl())
        }
        panel.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)))
        val buttons = LinearLayout(dialogContext).apply { orientation = LinearLayout.HORIZONTAL }
        panel.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(26)))
        val cancel = Button(dialogContext).apply {
            id = R.id.btnCancelSubscription
            setText(android.R.string.cancel)
        }
        val save = Button(dialogContext).apply {
            id = R.id.btnSaveSubscription
            setText(R.string.save_address)
        }
        listOf(cancel, save).forEach {
            it.textSize = 14f
            it.minHeight = 0
            it.minimumHeight = 0
            it.setPadding(0, 0, 0, 0)
            it.setTextColor(android.graphics.Color.WHITE)
            it.setBackgroundResource(R.drawable.bg_channel_option)
            buttons.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        val scroll = ScrollView(dialogContext).apply { addView(panel) }
        val dialog = AlertDialog.Builder(dialogContext).setView(scroll).create()
        cancel.setOnClickListener { dialog.dismiss() }
        save.setOnClickListener {
            val url = input.text.toString().trim()
            try {
                if (backup) PlaylistManager.setBackupUrl(url) else PlaylistManager.setOnlineUrl(url)
                refreshSubscriptionStatus()
                dialog.dismiss()
            } catch (e: IllegalArgumentException) {
                input.error = e.message
            }
        }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                save.performClick()
                true
            } else false
        }
        dialog.show()
        dialog.window?.apply {
            setLayout((resources.displayMetrics.widthPixels * 0.8f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    fun requestRemoteFocus() {
        if (!isShown || findFocus() != null) return
        focusSetting(focusedSetting)
    }

    private fun focusSetting(position: Int) {
        val target = position.coerceIn(0, settings.lastIndex)
        focusedSetting = target
        rvSettings.focusRemoteItem(target) { isShown && focusedSetting == target }
    }

    fun handleRemoteAction(action: RemoteAction) {
        when (action) {
            RemoteAction.UP -> focusSetting((focusedSetting - 1).coerceAtLeast(0))
            RemoteAction.DOWN -> focusSetting((focusedSetting + 1).coerceAtMost(settings.lastIndex))
            RemoteAction.SELECT -> {
                settings[focusedSetting].let { setting ->
                    if (setting.items.isNullOrEmpty()) setting.onClick?.invoke()
                    else adjustSetting(focusedSetting, true)
                }
            }
            RemoteAction.LEFT, RemoteAction.RIGHT -> adjustSetting(focusedSetting, action == RemoteAction.RIGHT)
            else -> Unit
        }
    }

    private fun adjustSetting(position: Int, next: Boolean) {
        val setting = settings[position]
        if ((setting.items?.size ?: 0) <= 1) return
        setting.selectedItemPosition = adjustValue(setting.selectedItemPosition, setting.items!!.size, next)
        (rvSettings.findViewHolderForAdapterPosition(position) as? ViewHolder)?.bind(setting)
        setting.onItemSelect?.invoke(setting.selectedItemPosition)
    }

    private inner class SettingsAdapter : RecyclerView.Adapter<ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_settings, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount() = settings.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(settings[position])
        }

    }

    private inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val btnLeft: Button = itemView.findViewById(R.id.btnLeft)
        private val btnRight: Button = itemView.findViewById(R.id.btnRight)
        private val tvItem: TextView = itemView.findViewById(R.id.tvItem)
        private val llItem: LinearLayout = itemView.findViewById(R.id.llItem)
        private lateinit var setting: SettingItem

        init {
            itemView.setOnClickListener { setting.onClick?.invoke() }
            itemView.isFocusableInTouchMode = true
            itemView.setOnFocusChangeListener { _, hasFocus -> if (hasFocus && bindingAdapterPosition >= 0) focusedSetting = bindingAdapterPosition }
            btnLeft.isFocusable = false
            btnRight.isFocusable = false
            btnLeft.setOnClickListener { adjustItem(false) }
            btnRight.setOnClickListener { adjustItem(true) }
        }

        fun bind(setting: SettingItem) {
            this.setting = setting
            tvTitle.text = rootView.context.getString(setting.titleRes)
            if (setting.items.isNullOrEmpty()) {
                llItem.visibility = GONE
            } else {
                llItem.visibility = VISIBLE
                tvItem.text = setting.items[setting.selectedItemPosition.coerceIn(0, setting.items.lastIndex)]
                btnLeft.isEnabled = setting.items.size > 1
                btnRight.isEnabled = setting.items.size > 1
            }
        }

        private fun adjustItem(next: Boolean) {
            if (bindingAdapterPosition >= 0) {
                focusSetting(bindingAdapterPosition)
                adjustSetting(bindingAdapterPosition, next)
            }
        }

    }
}
