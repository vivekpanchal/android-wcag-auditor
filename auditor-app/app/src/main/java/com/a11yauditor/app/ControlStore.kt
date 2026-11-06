package com.a11yauditor.app

import android.content.SharedPreferences
import android.util.Base64
import java.security.SecureRandom

/** Target/auditing state in the service's prefs; the service's prefs listener reacts to writes. */
class ControlStore(private val prefs: SharedPreferences) {
    fun get(): Control = Control(
        prefs.getString(AuditorAccessibilityService.KEY_TARGET_PACKAGE, null),
        prefs.getBoolean(AuditorAccessibilityService.KEY_IS_AUDITING, false),
    )

    fun set(c: Control) {
        prefs.edit()
            .putString(AuditorAccessibilityService.KEY_TARGET_PACKAGE, c.targetPackage)
            .putBoolean(AuditorAccessibilityService.KEY_IS_AUDITING, c.auditing)
            .apply()
    }
}

/** Per-install secret the dashboard exchanges for the session cookie. */
object DashboardToken {
    const val KEY = "dashboard_token"

    @Synchronized fun getOrCreate(prefs: SharedPreferences): String {
        prefs.getString(KEY, null)?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        prefs.edit().putString(KEY, token).commit()
        return token
    }
}
