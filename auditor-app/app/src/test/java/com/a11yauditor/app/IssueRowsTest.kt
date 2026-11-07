package com.a11yauditor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueRowsTest {
    private var nextId = 1L

    private fun stored(
        screen: String? = "Home",
        severity: String = "serious",
        element: String = "Button",
        shot: String? = null,
    ) = StoredIssue(
        nextId++, "com.ex", screen, 0, severity, "1.4.3", "AA", element, "Low contrast", null, null, shot,
    )

    private fun live(severity: String) = AuditIssue(severity, "1.4.3", "AA", "Button", "Low contrast", null, "views")

    @Test fun `identical rescans collapse into one row with a count`() {
        val rows = groupStored(listOf(stored(), stored(), stored(element = "Text")))
        assertEquals(listOf(2, 1), rows.map { it.count })
    }

    @Test fun `only the first row of a screen carries the header, serious first`() {
        val rows = groupStored(listOf(stored(severity = "moderate", element = "A"), stored(element = "B"), stored(screen = "Cart")))
        assertEquals(listOf("serious", "moderate", "serious"), rows.map { it.severity })
        assertEquals(listOf("Home · com.ex", null, "Cart · com.ex"), rows.map { it.header })
    }

    @Test fun `a collapsed row keeps a screenshot from any of its repeats`() {
        val rows = groupStored(listOf(stored(shot = "a.webp"), stored()))
        assertEquals("a.webp", rows.single().shotFile)
    }

    @Test fun `fix shows the detected framework first`() {
        val json = ApiJson.suggestedFix(WcagMapping.Fix("Sum", "viewCode", "composeCode", "url"), "compose")
        val text = parseFix(json)!!
        assertTrue(text, text.startsWith("Sum"))
        assertTrue(text, text.indexOf("composeCode") < text.indexOf("viewCode"))
    }

    @Test fun `missing or broken fix JSON gives no text`() {
        assertNull(parseFix(null))
        assertNull(parseFix("not json"))
    }

    @Test fun `a clean pass still gives a scan, named by the top window`() {
        val scan = LiveScan.from(listOf("Main" to emptyList(), "Main › Dialog" to emptyList()))
        assertEquals("Main › Dialog", scan.screen)
        assertTrue(scan.issues.isEmpty())
    }

    @Test fun `a scan flattens windows and reports serious`() {
        val scan = LiveScan.from(listOf("A" to listOf(live("moderate")), "B" to listOf(live("serious"))))
        assertEquals(2, scan.issues.size)
        assertTrue(scan.hasSerious)
    }
}
