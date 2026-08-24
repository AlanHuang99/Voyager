package com.voyagerfiles.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteSelectKeyHandlerTest {
    @Test
    fun shortPressClicksOnRelease() {
        val down = reduceRemoteSelect(
            RemoteSelectState.Idle,
            RemoteSelectEvent.Down(repeatCount = 0),
        )
        val up = reduceRemoteSelect(down.state, RemoteSelectEvent.Up)

        assertNull(down.action)
        assertEquals(RemoteSelectAction.Click, up.action)
        assertEquals(RemoteSelectState.Idle, up.state)
    }

    @Test
    fun repeatedDownLongClicksExactlyOnceAndSuppressesReleaseClick() {
        val down = reduceRemoteSelect(RemoteSelectState.Idle, RemoteSelectEvent.Down(0))
        val repeated = reduceRemoteSelect(down.state, RemoteSelectEvent.Down(1))
        val duplicate = reduceRemoteSelect(repeated.state, RemoteSelectEvent.Down(2))
        val up = reduceRemoteSelect(duplicate.state, RemoteSelectEvent.Up)

        assertEquals(RemoteSelectAction.LongClick, repeated.action)
        assertNull(duplicate.action)
        assertNull(up.action)
        assertEquals(RemoteSelectState.Idle, up.state)
    }

    @Test
    fun cancellationClearsAStartedPressWithoutAction() {
        val down = reduceRemoteSelect(RemoteSelectState.Idle, RemoteSelectEvent.Down(0))
        val cancelled = reduceRemoteSelect(down.state, RemoteSelectEvent.Cancel)

        assertNull(cancelled.action)
        assertEquals(RemoteSelectState.Idle, cancelled.state)
    }

    @Test
    fun strayReleaseAndRepeatDoNotCreateActions() {
        val release = reduceRemoteSelect(RemoteSelectState.Idle, RemoteSelectEvent.Up)
        val repeat = reduceRemoteSelect(
            RemoteSelectState.Idle,
            RemoteSelectEvent.Down(repeatCount = 1),
        )

        assertNull(release.action)
        assertNull(repeat.action)
        assertEquals(RemoteSelectState.Idle, repeat.state)
    }
}
