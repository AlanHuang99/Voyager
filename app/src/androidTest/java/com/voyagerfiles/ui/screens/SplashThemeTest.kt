package com.voyagerfiles.ui.screens

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.R
import org.junit.Assert.*
import org.junit.Test

class SplashThemeTest {
    @Test fun splashUsesVoyagerIconAndDistinctDayNightBackgrounds() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        fun attributes(mode: Int): Pair<Int, Int> {
            val config = Configuration(base.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            }
            val context = ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_MaterialBrowser_Splash)
            val attrs = context.obtainStyledAttributes(intArrayOf(androidx.core.splashscreen.R.attr.windowSplashScreenAnimatedIcon, androidx.core.splashscreen.R.attr.windowSplashScreenBackground))
            return try { attrs.getResourceId(0, 0) to attrs.getColor(1, 0) } finally { attrs.recycle() }
        }
        val day = attributes(Configuration.UI_MODE_NIGHT_NO)
        val night = attributes(Configuration.UI_MODE_NIGHT_YES)
        assertEquals(R.drawable.ic_splash, day.first)
        assertEquals(R.drawable.ic_splash, night.first)
        assertEquals(0xFFFFFBFE.toInt(), day.second)
        assertEquals(0xFF1C1B1F.toInt(), night.second)
        assertNotEquals(day.second, night.second)
    }
}
