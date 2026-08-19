package com.a11yauditor.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Local JVM unit tests for DeviceProtocol -- pure JSON encode/decode, no
 * OkHttp or Android framework types involved, so this runs under plain
 * JUnit like WcagMappingTest.
 */
class DeviceProtocolTest {

    @Test
    fun `buildReportMessage encodes type, envelope fields and every issue`() {
        val issues = listOf(
            AuditIssue(
                severity = "serious",
                wcagSc = "1.4.3",
                wcagLevel = "AA",
                elementDescription = "TextView subtitle",
                description = "Contrast 2.1:1",
                suggestedFix = null,
            ),
            AuditIssue(
                severity = "moderate",
                wcagSc = "1.1.1",
                wcagLevel = "A",
                elementDescription = "ImageView icon",
                description = "No accessible name",
                suggestedFix = "Add a contentDescription",
            ),
        )

        val json = JSONObject(
            DeviceProtocol.buildReportMessage(
                packageName = "com.example.app",
                screen = "MainScreen",
                timestamp = 1000L,
                issues = issues,
                screenshotBase64 = null,
            )
        )

        assertEquals("report", json.getString("type"))
        assertEquals("com.example.app", json.getString("packageName"))
        assertEquals("MainScreen", json.getString("screen"))
        assertEquals(1000L, json.getLong("timestamp"))
        assertEquals(false, json.has("screenshot"))

        val issuesJson = json.getJSONArray("issues")
        assertEquals(2, issuesJson.length())
        assertEquals("1.4.3", issuesJson.getJSONObject(0).getString("wcagSC"))
        assertEquals(false, issuesJson.getJSONObject(0).has("suggestedFix"))
        assertEquals("Add a contentDescription", issuesJson.getJSONObject(1).getString("suggestedFix"))
    }

    @Test
    fun `buildReportMessage includes the screenshot field only when present`() {
        val issue = AuditIssue("serious", "1.4.3", "AA", "Text", "Low contrast", null)

        val withScreenshot = JSONObject(
            DeviceProtocol.buildReportMessage("com.example.app", null, 1L, listOf(issue), "base64data")
        )
        assertEquals("base64data", withScreenshot.getString("screenshot"))

        val withoutScreenshot = JSONObject(
            DeviceProtocol.buildReportMessage("com.example.app", null, 1L, listOf(issue), null)
        )
        assertEquals(false, withoutScreenshot.has("screenshot"))
    }

    @Test
    fun `parseControlMessage reads a well-formed control message`() {
        val text = """{"type":"control","control":{"targetPackage":"com.example.app","auditing":true}}"""

        val result = DeviceProtocol.parseControlMessage(text)

        assertEquals(DeviceProtocol.RemoteControl("com.example.app", true), result)
    }

    @Test
    fun `parseControlMessage maps a JSON null targetPackage to Kotlin null`() {
        // org.json's optString() would otherwise return the literal string
        // "null" here, not Kotlin null -- same pitfall ControlSync.fetchControl
        // already had to guard against.
        val text = """{"type":"control","control":{"targetPackage":null,"auditing":false}}"""

        val result = DeviceProtocol.parseControlMessage(text)

        assertEquals(DeviceProtocol.RemoteControl(null, false), result)
    }

    @Test
    fun `parseControlMessage returns null for a non-control message type`() {
        val result = DeviceProtocol.parseControlMessage("""{"type":"issues","issues":[]}""")

        assertNull(result)
    }

    @Test
    fun `parseControlMessage returns null for malformed JSON instead of throwing`() {
        val result = DeviceProtocol.parseControlMessage("not json{{{")

        assertNull(result)
    }
}
