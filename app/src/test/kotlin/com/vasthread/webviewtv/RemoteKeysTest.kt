package com.vasthread.webviewtv

import android.view.KeyEvent
import com.vasthread.webviewtv.misc.RemoteAction
import com.vasthread.webviewtv.misc.RemoteKeys
import org.junit.Assert.*
import org.junit.Test

class RemoteKeysTest {
    @Test fun channelDirectionReversesBothDpadAndDedicatedKeysWithoutChangingMenuActions() {
        for (up in listOf(RemoteAction.UP, RemoteAction.CHANNEL_UP)) {
            assertEquals(false, RemoteKeys.channelForward(up, false))
            assertEquals(true, RemoteKeys.channelForward(up, true))
        }
        for (down in listOf(RemoteAction.DOWN, RemoteAction.CHANNEL_DOWN)) {
            assertEquals(true, RemoteKeys.channelForward(down, false))
            assertEquals(false, RemoteKeys.channelForward(down, true))
        }
        for (action in listOf(RemoteAction.LEFT, RemoteAction.RIGHT, RemoteAction.SELECT,
            RemoteAction.MENU, RemoteAction.SETTINGS, RemoteAction.BACK)) {
            assertNull(RemoteKeys.channelForward(action, false))
            assertNull(RemoteKeys.channelForward(action, true))
        }
    }

    @Test fun commonSelectCodesShareOneAction() {
        for (key in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT)) assertEquals(RemoteAction.SELECT, RemoteKeys.action(key))
    }

    @Test fun activationRunsOnceAndIgnoresCancelledOrKeyUpEvents() {
        for (action in listOf(RemoteAction.SELECT, RemoteAction.MENU, RemoteAction.SETTINGS, RemoteAction.BACK)) {
            assertTrue(RemoteKeys.shouldAct(action, KeyEvent.ACTION_DOWN, 0, false))
            assertFalse(RemoteKeys.shouldAct(action, KeyEvent.ACTION_UP, 0, false))
            assertFalse(RemoteKeys.shouldAct(action, KeyEvent.ACTION_DOWN, 1, false))
            assertFalse(RemoteKeys.shouldAct(action, KeyEvent.ACTION_DOWN, 0, true))
        }
        assertTrue(RemoteKeys.shouldAct(RemoteAction.DOWN, KeyEvent.ACTION_DOWN, 4, false))
    }

    @Test fun systemButtonsRemainUnhandledAndChannelKeysAreMapped() {
        for (key in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER)) assertNull(RemoteKeys.action(key))
        assertEquals(RemoteAction.CHANNEL_UP, RemoteKeys.action(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(RemoteAction.CHANNEL_UP, RemoteKeys.action(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(RemoteAction.CHANNEL_DOWN, RemoteKeys.action(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(RemoteAction.CHANNEL_DOWN, RemoteKeys.action(KeyEvent.KEYCODE_PAGE_DOWN))
    }
}
