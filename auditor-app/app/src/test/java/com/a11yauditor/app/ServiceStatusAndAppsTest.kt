package com.a11yauditor.app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceStatusAndAppsTest {
    private val service = ComponentName("com.a11yauditor.app", "com.a11yauditor.app.AuditorAccessibilityService")

    @Test fun `setting matches the full form`() =
        assertTrue(isServiceInSetting("com.other/.S:com.a11yauditor.app/com.a11yauditor.app.AuditorAccessibilityService", service))

    @Test fun `setting matches the short form`() =
        assertTrue(isServiceInSetting("com.a11yauditor.app/.AuditorAccessibilityService", service))

    @Test fun `setting without the service, empty or null does not match`() {
        assertFalse(isServiceInSetting("com.other/.AuditorAccessibilityService", service))
        assertFalse(isServiceInSetting("", service))
        assertFalse(isServiceInSetting(null, service))
    }

    @Test fun `search matches app name or package, ignoring case`() {
        val icon = android.graphics.drawable.ColorDrawable()
        val adapter = AppListAdapter(
            listOf(InstalledAppInfo("Maps", "com.ex.maps", icon), InstalledAppInfo("Notes", "com.ex.notes", icon)),
        ) {}
        adapter.filter(" maps ")
        assertEquals(1, adapter.itemCount)
        adapter.filter("com.ex")
        assertEquals(2, adapter.itemCount)
        adapter.filter("zzz")
        assertEquals(0, adapter.itemCount)
    }

    private fun resolve(pkg: String, appFlags: Int) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = "$pkg.Main"
            applicationInfo = ApplicationInfo().apply { packageName = pkg; flags = appFlags }
        }
    }

    @Test fun `launchable apps keep only user-installed packages`() {
        val pm = RuntimeEnvironment.getApplication().packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        shadowOf(pm).addResolveInfoForIntent(launcher, resolve("com.user.app", 0))
        shadowOf(pm).addResolveInfoForIntent(launcher, resolve("com.android.settings", ApplicationInfo.FLAG_SYSTEM))
        shadowOf(pm).addResolveInfoForIntent(
            launcher, resolve("com.android.chrome", ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP),
        )
        shadowOf(pm).addResolveInfoForIntent(launcher, resolve("com.a11yauditor.app", 0))
        assertEquals(listOf("com.user.app"), launchableApps(pm, "com.a11yauditor.app").map { it.packageName })
    }
}
