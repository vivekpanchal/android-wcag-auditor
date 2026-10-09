package com.a11yauditor.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameworkTest {

    @Test
    fun `element under an AndroidComposeView is compose`() {
        val chain = sequenceOf("android.view.View", "androidx.compose.ui.platform.AndroidComposeView", "android.widget.FrameLayout")
        assertEquals("compose", frameworkFor(chain))
    }

    @Test
    fun `element under a ComposeView host is compose`() {
        assertEquals("compose", frameworkFor(sequenceOf("android.view.View", "androidx.compose.ui.platform.ComposeView")))
    }

    @Test
    fun `plain view hierarchy is views, null class names ignored`() {
        assertEquals("views", frameworkFor(sequenceOf("android.widget.Button", null, "android.widget.LinearLayout")))
    }

    @Test
    fun `empty ancestry is views`() {
        assertEquals("views", frameworkFor(emptySequence()))
    }
}
