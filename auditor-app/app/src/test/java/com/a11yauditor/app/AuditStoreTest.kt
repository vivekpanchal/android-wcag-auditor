package com.a11yauditor.app

import com.google.android.apps.common.testing.accessibility.framework.replacements.Rect
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuditStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var shotsDir: File
    private lateinit var store: AuditStore

    @Before fun setUp() {
        shotsDir = tmp.newFolder("shots")
        store = AuditStore(RuntimeEnvironment.getApplication(), null, shotsDir, capBytes = 250)
    }

    private fun issue(sc: String) = AuditIssue(
        "serious", sc, "AA", "Button b", "d",
        WcagMapping.Fix("S", "v", "c", "https://d"), "views", Rect(1, 2, 11, 22),
    )

    @Test fun `addReport stores every issue with its fields`() {
        val out = store.addReport("com.ex", "Main", 5, null, listOf(issue("1.4.3"), issue("2.5.5")))
        assertEquals(2, out.size)
        assertEquals(out, store.issues())
        assertEquals(Bounds(1, 2, 10, 20), out[0].bounds)
        assertEquals("views", JSONObject(out[0].suggestedFix!!).getString("framework"))
        assertNull(out[0].screenshotFile)
    }

    @Test fun `one screenshot file is shared by the report's issues`() {
        val out = store.addReport("p", "S", 1, ByteArray(10), listOf(issue("a"), issue("b")))
        assertEquals(out[0].screenshotFile, out[1].screenshotFile)
        assertEquals(10L, store.screenshot(out[0].screenshotFile!!)!!.length())
    }

    @Test fun `clear removes rows and files and ids keep increasing`() {
        val first = store.addReport("p", "S", 1, ByteArray(10), listOf(issue("a")))
        store.clear()
        assertTrue(store.issues().isEmpty()); assertEquals(0, shotsDir.listFiles()!!.size)
        assertTrue(store.addReport("p", "S", 2, null, listOf(issue("a")))[0].id > first[0].id)
    }

    @Test fun `cap evicts the oldest screenshot and nulls its rows`() {
        val a = store.addReport("p", "S", 1, ByteArray(100), listOf(issue("a")))
        val b = store.addReport("p", "S", 2, ByteArray(100), listOf(issue("b")))
        val c = store.addReport("p", "S", 3, ByteArray(100), listOf(issue("c")))  // 300 > 250
        val rows = store.issues().associateBy { it.id }
        assertNull(rows[a[0].id]!!.screenshotFile)
        assertEquals("a", rows[a[0].id]!!.wcagSc)
        assertNotNull(rows[b[0].id]!!.screenshotFile); assertNotNull(rows[c[0].id]!!.screenshotFile)
        assertNull(store.screenshot(a[0].screenshotFile!!))
    }

    @Test fun `screenshot rejects names outside the shots dir`() {
        assertNull(store.screenshot("../audit.db")); assertNull(store.screenshot("x.png"))
    }
}
