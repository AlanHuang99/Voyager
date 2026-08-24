package com.voyagerfiles.data.model

import com.voyagerfiles.R
import com.voyagerfiles.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class ResourceBackedModelTest {

    @Test
    fun userFacingModelLabelsUseResources() {
        assertEquals(R.string.protocol_sftp, ConnectionProtocol.SFTP.displayNameRes)
        assertEquals(R.string.view_mode_grid, ViewMode.GRID.labelRes)
        assertEquals(R.string.filter_images, FileTypeFilter.IMAGES.labelRes)
        assertEquals(R.string.home_remote_connections, HomeSection.REMOTE_CONNECTIONS.labelRes)
        assertEquals(R.string.session_timeout_15_minutes, SessionAutoCloseTimeout.FIFTEEN_MINUTES.labelRes)
        assertEquals(R.string.theme_system, AppTheme.SYSTEM.displayNameRes)
    }
}
