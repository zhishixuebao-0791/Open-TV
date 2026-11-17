package com.vasthread.webviewtv

import com.vasthread.webviewtv.misc.RemotePress
import com.vasthread.webviewtv.misc.adjacentSourceIndex
import org.junit.Assert.*
import org.junit.Test

class RemotePressTest {
    @Test fun releaseRequiresItsOwnDownEventAndRunsOnce() {
        val press = RemotePress()
        assertFalse(press.release(23, 1, 100, false))
        press.begin(23, 1, 100)
        assertFalse(press.release(23, 2, 100, false))
        assertFalse(press.release(23, 1, 101, false))
        assertFalse(press.release(66, 1, 100, false))
        assertTrue(press.release(23, 1, 100, false))
        assertFalse(press.release(23, 1, 100, false))
    }

    @Test fun longPressRunsOnceAndSuppressesShortRelease() {
        val press = RemotePress()
        press.begin(23, 1, 100)
        assertFalse(press.takeLong(599, 500))
        assertTrue(press.takeLong(600, 500))
        assertFalse(press.takeLong(900, 500))
        assertFalse(press.release(23, 1, 100, false))
    }

    @Test fun cancelledOrInvalidatedPressCannotActOnAnotherPanel() {
        val press = RemotePress()
        press.begin(22, 1, 100)
        press.invalidate()
        assertTrue(press.matches(22, 1, 100))
        assertFalse(press.takeLong(1000, 500))
        assertFalse(press.release(22, 1, 100, false))
        press.begin(22, 1, 200)
        assertFalse(press.release(22, 1, 200, true))
        press.begin(22, 1, 300)
        press.reset()
        assertFalse(press.release(22, 1, 300, false))
    }

    @Test fun sourceNavigationWrapsAndSkipsEmptyEntries() {
        val urls = listOf("a", " ", "b", "c")
        assertEquals(3, adjacentSourceIndex(urls, 0, false))
        assertEquals(2, adjacentSourceIndex(urls, 0, true))
        assertEquals(0, adjacentSourceIndex(urls, 3, true))
        assertEquals(0, adjacentSourceIndex(urls, -1, true))
        assertEquals(3, adjacentSourceIndex(urls, -1, false))
        assertNull(adjacentSourceIndex(emptyList(), -1, true))
        assertNull(adjacentSourceIndex(listOf("", "a"), 1, false))
    }
}
