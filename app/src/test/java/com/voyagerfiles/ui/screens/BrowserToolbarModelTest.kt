package com.voyagerfiles.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserToolbarModelTest {

    @Test
    fun remoteToolbarMovesDisconnectToOverflow() {
        val model = BrowserToolbarModel.forState(isRemote = true)

        assertFalse(model.primaryActions.contains(BrowserToolbarAction.DISCONNECT))
        assertTrue(model.overflowActions.contains(BrowserToolbarAction.DISCONNECT))
    }

    @Test
    fun localToolbarDoesNotExposeDisconnect() {
        val model = BrowserToolbarModel.forState(isRemote = false)

        assertFalse(model.primaryActions.contains(BrowserToolbarAction.DISCONNECT))
        assertFalse(model.overflowActions.contains(BrowserToolbarAction.DISCONNECT))
    }
}
