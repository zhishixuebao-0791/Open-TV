package com.vasthread.webviewtv.widget

import android.content.Context
import android.util.AttributeSet
import android.view.WindowInsets
import android.widget.FrameLayout

class UiLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        // Keep overlay container aligned with the actual video surface on both landscape directions.
        // Applying asymmetric cutout left/right padding causes side panels to drift after 180° rotation.
        setPadding(0, 0, 0, 0)
        return super.onApplyWindowInsets(insets)
    }
}
