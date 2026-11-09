package com.a11yauditor.app

import com.google.android.apps.common.testing.accessibility.framework.replacements.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FindingKeysTest {
    private fun issue(element: String, bounds: Rect? = null) =
        AuditIssue("serious", "2.5.8", "AA", element, "Touch target too small", null, "views", bounds)

    @Test
    fun movedElementIsTheSameFinding() {
        val a = findingKeys(listOf("Main" to listOf(issue("Track", Rect(0, 0, 10, 10)))))
        val b = findingKeys(listOf("Main" to listOf(issue("Track", Rect(0, 50, 10, 60)))))
        assertEquals(a, b)
    }

    @Test
    fun repeatsCollapseAndScreensStayApart() {
        val keys = findingKeys(listOf("Main" to listOf(issue("Track"), issue("Track")), "Main › Popup" to listOf(issue("Track"))))
        assertEquals(2, keys.size)
        assertNotEquals(findingKeys(listOf("Main" to listOf(issue("Track")))), findingKeys(emptyList()))
    }
}
