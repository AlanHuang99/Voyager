package com.voyagerfiles.ui.screens

import org.junit.Assert.assertEquals
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
        val model = SelectionToolbarModel.forState(
            isRemote = false,
            selectionCount = 3,
            canShare = false,
        )

        assertTrue(model.primaryActions.contains(SelectionToolbarAction.COPY))
        assertTrue(model.primaryActions.contains(SelectionToolbarAction.DELETE))
        assertTrue(model.primaryActions.size <= 3)
        assertTrue(model.overflowActions.contains(SelectionToolbarAction.CUT))
        assertTrue(model.overflowActions.contains(SelectionToolbarAction.SELECT_ALL))
        assertFalse(model.overflowActions.contains(SelectionToolbarAction.RENAME))
    }

    @Test
    fun remoteSingleSelectionExposesDownloadAndPrimaryRename() {
        val model = SelectionToolbarModel.forState(
            isRemote = true,
            selectionCount = 1,
            canShare = false,
        )

        assertTrue(model.overflowActions.contains(SelectionToolbarAction.DOWNLOAD))
        assertTrue(model.primaryActions.contains(SelectionToolbarAction.RENAME))
    }

    @Test
    fun shareableSingleSelectionPromotesShareRenameAndDelete() {
        val model = SelectionToolbarModel.forState(
            isRemote = false,
            selectionCount = 1,
            canShare = true,
        )

        assertEquals(
            listOf(
                SelectionToolbarAction.SHARE,
                SelectionToolbarAction.RENAME,
                SelectionToolbarAction.DELETE,
            ),
            model.primaryActions,
        )
        assertTrue(model.overflowActions.contains(SelectionToolbarAction.COPY))
        assertTrue(model.overflowActions.contains(SelectionToolbarAction.CUT))
        assertTrue(model.overflowActions.contains(SelectionToolbarAction.DETAILS))
    }

    @Test
    fun multipleRemoteSelectionKeepsShareAndRenameHidden() {
        val model = SelectionToolbarModel.forState(
            isRemote = true,
            selectionCount = 2,
            canShare = false,
        )

        assertEquals(
            listOf(SelectionToolbarAction.COPY, SelectionToolbarAction.DELETE),
            model.primaryActions,
        )
        assertFalse(model.primaryActions.contains(SelectionToolbarAction.SHARE))
        assertFalse(model.overflowActions.contains(SelectionToolbarAction.RENAME))
        assertTrue(model.overflowActions.contains(SelectionToolbarAction.DOWNLOAD))
    }
}
