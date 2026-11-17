package com.vasthread.webviewtv.misc

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.RecyclerView

enum class RemoteAction { UP, DOWN, LEFT, RIGHT, SELECT, MENU, SETTINGS, BACK, CHANNEL_UP, CHANNEL_DOWN }

object RemoteKeys {
    fun channelForward(action: RemoteAction, reversed: Boolean): Boolean? = when (action) {
        RemoteAction.UP, RemoteAction.CHANNEL_UP -> reversed
        RemoteAction.DOWN, RemoteAction.CHANNEL_DOWN -> !reversed
        else -> null
    }

    fun action(key: Int): RemoteAction? = when (key) {
        KeyEvent.KEYCODE_DPAD_UP -> RemoteAction.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> RemoteAction.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> RemoteAction.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RemoteAction.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT -> RemoteAction.SELECT
        KeyEvent.KEYCODE_MENU -> RemoteAction.MENU
        KeyEvent.KEYCODE_SETTINGS -> RemoteAction.SETTINGS
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> RemoteAction.BACK
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> RemoteAction.CHANNEL_UP
        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> RemoteAction.CHANNEL_DOWN
        else -> null
    }

    fun shouldAct(action: RemoteAction, eventAction: Int, repeat: Int, cancelled: Boolean): Boolean =
        !cancelled && eventAction == KeyEvent.ACTION_DOWN &&
            (repeat == 0 || action in setOf(RemoteAction.UP, RemoteAction.DOWN, RemoteAction.LEFT,
                RemoteAction.RIGHT, RemoteAction.CHANNEL_UP, RemoteAction.CHANNEL_DOWN))
}

/** Keep directional focus inside the visible panel, including rows with hidden controls. */
fun ViewGroup.navigateRemoteControls(rows: List<List<View>>, default: View, action: RemoteAction) {
    val visibleRows = rows.map { row -> row.filter { it.isShown && it.isEnabled } }.filter { it.isNotEmpty() }
    val controls = visibleRows.flatten()
    if (controls.isEmpty()) return
    val current = controls.firstOrNull { it.hasFocus() } ?: default.takeIf { it in controls } ?: controls.first()
    if (action == RemoteAction.SELECT) {
        current.performClick()
        return
    }
    val row = visibleRows.indexOfFirst { current in it }
    val column = visibleRows[row].indexOf(current)
    val nextRow = when (action) {
        RemoteAction.UP -> (row - 1).coerceAtLeast(0)
        RemoteAction.DOWN -> (row + 1).coerceAtMost(visibleRows.lastIndex)
        else -> row
    }
    val nextColumn = when (action) {
        RemoteAction.LEFT -> (column - 1).coerceAtLeast(0)
        RemoteAction.RIGHT -> (column + 1).coerceAtMost(visibleRows[nextRow].lastIndex)
        else -> column.coerceAtMost(visibleRows[nextRow].lastIndex)
    }
    visibleRows[nextRow][nextColumn].requestFocus()
}

fun RecyclerView.focusRemoteItem(position: Int, stillRequested: () -> Boolean) {
    val holder = findViewHolderForAdapterPosition(position)
    if (holder != null && isShown && stillRequested()) {
        holder.itemView.requestFocus()
        return
    }
    doOnNextLayout {
        if (isShown && stillRequested()) findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
    }
    scrollToPosition(position)
    requestLayout()
}
