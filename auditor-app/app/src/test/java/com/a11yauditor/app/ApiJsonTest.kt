package com.a11yauditor.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiJsonTest {

    @Test fun `issue encodes every field and a relative screenshot url`() {
        val i = StoredIssue(7, "com.ex", "Main › Dialog", 42, "serious", "1.4.3", "AA", "TextView t", "Contrast 2.1:1",
            """{"summary":"S"}""", Bounds(1, 2, 3, 4), "abc.webp")
        val j = ApiJson.issue(i)
        assertEquals(7L, j.getLong("id"))
        assertEquals("1.4.3", j.getString("wcagSC"))
        assertEquals("""{"summary":"S"}""", j.getString("suggestedFix"))
        assertEquals(3, j.getJSONObject("bounds").getInt("width"))
        assertEquals("/api/v1/screenshots/abc.webp", j.getString("screenshotUrl"))
    }

    @Test fun `issue writes JSON null for missing optional fields`() {
        val j = ApiJson.issue(StoredIssue(1, "p", null, 0, "moderate", "4.1.2", "A", "e", "d", null, null, null))
        listOf("screen", "suggestedFix", "bounds", "screenshotUrl").forEach { assertTrue(it, j.isNull(it)) }
    }

    @Test fun `suggestedFix carries snippets and detected framework`() {
        val s = ApiJson.suggestedFix(WcagMapping.Fix("Add a label", "v", "c", "https://d"), "compose")!!
        val j = JSONObject(s)
        assertEquals("Add a label", j.getString("summary")); assertEquals("compose", j.getString("framework"))
        assertNull(ApiJson.suggestedFix(null, "views"))
    }

    @Test fun `parseControl accepts valid bodies and rejects bad ones`() {
        assertEquals(Control("com.ex", true), ApiJson.parseControl("""{"targetPackage":"com.ex","auditing":true}"""))
        assertEquals(Control(null, false), ApiJson.parseControl("""{"targetPackage":"","auditing":false}"""))
        assertNull(ApiJson.parseControl("not json"))
        assertNull(ApiJson.parseControl("""{"auditing":"yes"}"""))
        assertNull(ApiJson.parseControl("""{"targetPackage":"x"}"""))
    }

    @Test fun `control encodes null target as JSON null`() {
        assertTrue(JSONObject(ApiJson.control(Control(null, false))).isNull("targetPackage"))
    }

    @Test fun `sse frame is one event`() {
        assertEquals("event: clear\ndata: {}\n\n", ApiJson.sse("clear", "{}"))
    }
}
