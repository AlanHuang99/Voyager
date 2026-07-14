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

    @Test
    fun selectionToolbarKeepsOnlyCoreDestructiveActionsVisible() {
        val model = SelectionToolbarModel.forState(isRemote = false, selectionCount = 3)

        assertTrue(model.primaryActions.contains(SelectionToolbarAction.COPY))
        assertTrue(model.primaryActions.contains(SelectionToolbarAction.CUT))
        assertTrue(model.primaryActions.contains(SelectionToolbarAction.DELETE))
        assertTrue(model.primaryActions.size <= 3)
        assertTrue(model.overflowActions.contains(SelectionToolbarAction.SELECT_ALL))
        assertFalse(model.overflowActions.contains(SelectionToolbarAction.RENAME))
    }

    @Test
    fun remoteSingleSelectionExposesDownloadAndRenameInOverflow() {
        val model = SelectionToolbarModel.forState(isRemote = true, selectionCount = 1)

        assertTrue(model.overflowActions.contains(SelectionToolbarAction.DOWNLOAD))
        assertTrue(model.overflowActions.contains(SelectionToolbarAction.RENAME))
    }
}
