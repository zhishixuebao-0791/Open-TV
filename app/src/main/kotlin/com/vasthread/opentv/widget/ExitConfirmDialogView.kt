package com.vasthread.webviewtv.widget

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.Button
import android.widget.FrameLayout
import com.vasthread.webviewtv.R
import com.vasthread.webviewtv.misc.RemoteAction
import com.vasthread.webviewtv.misc.navigateRemoteControls

class ExitConfirmDialogView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class Selection { CANCEL, EXIT }

    var onUserSelection: ((Selection) -> Unit)? = null

    private val btnCancel: Button
    private val btnExit: Button

    init {
        isClickable = true
        isFocusable = false
        LayoutInflater.from(context).inflate(R.layout.widget_exit_confirm_dialog, this)
        btnCancel = findViewById(R.id.btnCancel)
        btnExit = findViewById(R.id.btnConfirmExit)
        btnCancel.isFocusableInTouchMode = true
        btnExit.isFocusableInTouchMode = true
        btnCancel.setOnClickListener { onUserSelection?.invoke(Selection.CANCEL) }
        btnExit.setOnClickListener { onUserSelection?.invoke(Selection.EXIT) }
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == VISIBLE) {
            post { if (isShown) btnCancel.requestFocus() }
        }
    }

    fun requestRemoteFocus() {
        if (isShown && findFocus() == null) btnCancel.requestFocus()
    }

    fun handleRemoteAction(action: RemoteAction) {
        navigateRemoteControls(listOf(listOf(btnCancel, btnExit)), btnCancel, action)
    }
}
