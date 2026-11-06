package com.a11yauditor.app

import android.view.inputmethod.EditorInfo
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTargetTest {
    private val prefs = RuntimeEnvironment.getApplication()
        .getSharedPreferences(AuditorAccessibilityService.PREFS_NAME, 0)

    private fun target() = prefs.getString(AuditorAccessibilityService.KEY_TARGET_PACKAGE, null)
    private fun setTarget(pkg: String) =
        prefs.edit().putString(AuditorAccessibilityService.KEY_TARGET_PACKAGE, pkg).commit()

    private fun launch(): ActivityController<MainActivity> = Robolectric.buildActivity(MainActivity::class.java).setup()

    private fun ActivityController<MainActivity>.input() = get().findViewById<EditText>(R.id.manualPackageInput)

    private fun ActivityController<MainActivity>.commit(text: String) {
        input().setText(text)
        input().onEditorAction(EditorInfo.IME_ACTION_DONE)
    }

    @Test fun `committing manual input saves the target`() {
        launch().commit(" com.ex ")
        assertEquals("com.ex", target())
    }

    @Test fun `committing blank input clears the target`() {
        setTarget("com.ex")
        launch().commit("  ")
        assertNull(target())
    }

    @Test fun `the target is not written while auditing`() {
        setTarget("com.ex")
        prefs.edit().putBoolean(AuditorAccessibilityService.KEY_IS_AUDITING, true).commit()
        launch().commit("com.other")
        assertEquals("com.ex", target())
    }

    @Test fun `committing the saved value writes nothing`() {
        setTarget("com.ex")
        val activity = launch()
        var writes = 0
        val counter = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> writes++ }
        prefs.registerOnSharedPreferenceChangeListener(counter)
        activity.commit("com.ex")
        prefs.unregisterOnSharedPreferenceChangeListener(counter)
        assertEquals(0, writes)
    }

    @Test fun `a target set elsewhere shows in the input`() {
        val activity = launch()
        setTarget("com.dash")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals("com.dash", activity.input().text.toString())
    }

    @Test fun `a target set while the app was stopped shows on return`() {
        val activity = launch()
        activity.pause().stop()
        setTarget("com.dash")
        activity.start().resume()
        assertEquals("com.dash", activity.input().text.toString())
    }
}
