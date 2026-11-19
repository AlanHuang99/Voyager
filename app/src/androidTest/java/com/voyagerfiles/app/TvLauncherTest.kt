package com.voyagerfiles.app

import android.content.Context
import android.content.Intent
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

class TvLauncherTest {
    @Test fun leanbackLaunchResolvesWithBannerAndOptionalFeatures() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(context.packageName)
        val activities = context.packageManager.queryIntentActivities(intent, 0)
        assertEquals(1, activities.size)
        assertEquals(MainActivity::class.java.name, activities.single().activityInfo.name)
        val banner = activities.single().activityInfo.loadBanner(context.packageManager)
        assertNotNull(banner)
        assertEquals(16.0 / 9, banner.intrinsicWidth.toDouble() / banner.intrinsicHeight, 0.01)
        val features = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_CONFIGURATIONS).reqFeatures.orEmpty()
        for (name in listOf("android.software.leanback", "android.hardware.touchscreen")) {
            assertEquals(0, features.single { it.name == name }.flags and FeatureInfo.FLAG_REQUIRED)
        }
    }
}
