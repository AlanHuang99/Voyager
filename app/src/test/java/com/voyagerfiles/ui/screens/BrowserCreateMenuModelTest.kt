package com.voyagerfiles.ui.screens

import com.voyagerfiles.R
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserCreateMenuModelTest {

    @Test
    fun actionsUseResourceLabels() {
        assertEquals(R.string.create_new_folder, BrowserCreateAction.NEW_FOLDER.labelRes)
        assertEquals(R.string.create_new_file, BrowserCreateAction.NEW_FILE.labelRes)
        assertEquals(R.string.create_upload_files, BrowserCreateAction.UPLOAD_FILES.labelRes)
    }

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
