package com.a11yauditor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenNamingTest {

    private fun win(pkg: String?, layer: Int, title: String? = null, rootClass: String? = null, app: Boolean = true) =
        TargetWindow(pkg, app, layer, title, rootClass, root = "root-$layer")

    @Test
    fun `targetWindows keeps target application windows, activity first`() {
        val windows = listOf(
            win("com.target", 3, title = "Confirm payment"),
            win("com.google.android.inputmethod", 5),
            win("com.target", 9, app = false),
            win("com.target", 1, title = "Checkout"),
        )
        val result = ScreenNaming.targetWindows(windows, "com.target")
        assertEquals(listOf("root-1", "root-3"), result.map { it.root })
    }

    @Test
    fun `targetWindows is empty when the target has no windows`() {
        assertTrue(ScreenNaming.targetWindows(listOf(win("com.other", 1)), "com.target").isEmpty())
    }

    @Test
    fun `shouldScan accepts events from the target package`() {
        assertTrue(ScreenNaming.shouldScan("com.target", false, "com.target"))
    }

    @Test
    fun `shouldScan accepts a null-package windows-changed event`() {
        // runAudit re-selects target windows after the debounce, so no window lookup on the main thread here.
        assertTrue(ScreenNaming.shouldScan(null, true, "com.target"))
    }

    @Test
    fun `shouldScan rejects other packages' non-windows events`() {
        assertFalse(ScreenNaming.shouldScan("com.other", false, "com.target"))
    }

    @Test
    fun `shortName strips package and inner class`() {
        assertEquals("CheckoutActivity", ScreenNaming.shortName("com.shop.CheckoutActivity"))
        assertEquals("PopupWindow", ScreenNaming.shortName("android.widget.PopupWindow\$PopupDecorView"))
        assertEquals(null, ScreenNaming.shortName(null))
    }

    @Test
    fun `base window is labelled by its activity`() {
        assertEquals("CheckoutActivity", ScreenNaming.screenLabel("com.shop.CheckoutActivity", win("com.shop", 1, title = "Shop"), isBase = true))
    }

    @Test
    fun `base window falls back to its title, then a placeholder`() {
        assertEquals("Shop", ScreenNaming.screenLabel(null, win("com.shop", 1, title = "Shop"), isBase = true))
        assertEquals("Unknown screen", ScreenNaming.screenLabel(null, win("com.shop", 1), isBase = true))
    }

    @Test
    fun `dialog is labelled activity then dialog title`() {
        assertEquals(
            "CheckoutActivity › Confirm payment",
            ScreenNaming.screenLabel("com.shop.CheckoutActivity", win("com.shop", 2, title = "Confirm payment"), isBase = false),
        )
    }

    @Test
    fun `untitled popup is labelled by its root class`() {
        assertEquals(
            "CheckoutActivity › PopupWindow",
            ScreenNaming.screenLabel(
                "com.shop.CheckoutActivity",
                win("com.shop", 2, rootClass = "android.widget.PopupWindow\$PopupDecorView"),
                isBase = false,
            ),
        )
    }

    @Test
    fun `modal dialog alone on screen is an overlay when the activity window is known`() {
        // A modal dialog hides the activity window from getWindows(), so the dialog is index 0.
        val dialog = TargetWindow("com.shop", true, 0, "Confirm payment", null, "root", id = 11)
        assertFalse(ScreenNaming.isBase(dialog, index = 0, activityWindowId = 10))
    }

    @Test
    fun `activity window is base when its id is known`() {
        val activity = TargetWindow("com.shop", true, 0, "Shop", null, "root", id = 10)
        assertTrue(ScreenNaming.isBase(activity, index = 0, activityWindowId = 10))
    }

    @Test
    fun `lowest window is base when the activity window id is unknown`() {
        val w = TargetWindow("com.shop", true, 0, null, null, "root", id = 11)
        assertTrue(ScreenNaming.isBase(w, index = 0, activityWindowId = null))
        assertFalse(ScreenNaming.isBase(w, index = 1, activityWindowId = null))
    }

    @Test
    fun `tracker reports the activity and its window for the same target only`() {
        val t = BaseScreenTracker()
        t.onActivity("com.shop", "com.shop.CheckoutActivity", windowId = 10)
        assertEquals("com.shop.CheckoutActivity", t.activityFor("com.shop"))
        assertEquals(10, t.windowIdFor("com.shop"))
        assertEquals(null, t.activityFor("com.other"))
        assertEquals(null, t.windowIdFor("com.other"))
    }

    @Test
    fun `tracker forgets a stale activity window after a reset`() {
        val t = BaseScreenTracker()
        t.onActivity("com.shop", "com.shop.CheckoutActivity", windowId = 10)
        t.reset()
        assertEquals(null, t.activityFor("com.shop"))
        assertEquals(null, t.windowIdFor("com.shop"))
    }
}
