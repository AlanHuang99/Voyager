package com.voyagerfiles.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserCreateMenuModelTest {

    @Test
    fun remoteMenuIncludesCreateAndUploadActions() {
        assertEquals(
            listOf(
                BrowserCreateAction.NEW_FOLDER,
                BrowserCreateAction.NEW_FILE,
                BrowserCreateAction.UPLOAD_FILES,
            ),
            BrowserCreateMenuModel.forState(isRemote = true).actions,
        )
    }

    @Test
    fun localMenuOmitsUploadAction() {
        assertEquals(
            listOf(
                BrowserCreateAction.NEW_FOLDER,
                BrowserCreateAction.NEW_FILE,
            ),
            BrowserCreateMenuModel.forState(isRemote = false).actions,
        )
    }
}
