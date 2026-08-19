package com.a11yauditor.app

import android.app.Activity
import android.app.Instrumentation
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the scanner works on both a real XML view tree and a real Compose
 * semantics tree, using ONE shared assertion path — runAtfChecks, the exact
 * function AuditorAccessibilityService.checkHierarchy delegates to in
 * production — against two fixture Activities that seed the same two
 * violations by different means (XML attributes vs. Compose Modifiers).
 *
 * Root AccessibilityNodeInfo comes from UiAutomation (the same interface an
 * AccessibilityService/rootInActiveWindow uses under the hood), so this is a
 * real accessibility-tree scan, not a mock.
 */
@RunWith(AndroidJUnit4::class)
class AtfScanParityTest {

    @Test
    fun xmlFixture_flagsSeededViolations() {
        assertSeededViolationsDetected(XmlFixtureActivity::class.java)
    }

    @Test
    fun composeFixture_flagsSeededViolations() {
        assertSeededViolationsDetected(ComposeFixtureActivity::class.java)
    }

    private fun <T : Activity> assertSeededViolationsDetected(activityClass: Class<T>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(activityClass).use {
            instrumentation.waitForIdleSync()

            val root = waitForRoot(instrumentation, instrumentation.targetContext.packageName)
            val issues = runAtfChecks(root, instrumentation.targetContext)

            assertTrue(
                "expected a missing-accessible-name issue (WCAG 1.1.1) from $activityClass, got: $issues",
                issues.any { it.wcagSc == "1.1.1" },
            )
            assertTrue(
                "expected a touch-target-too-small issue (WCAG 2.5.5) from $activityClass, got: $issues",
                issues.any { it.wcagSc == "2.5.5" },
            )
        }
    }

    // ponytail: fixed poll/timeout rather than a proper IdlingResource — the
    // accessibility tree can lag a frame or two behind waitForIdleSync (most
    // visible on the Compose fixture, whose semantics tree is built off a
    // separate pass). Upgrade path: a UiAutomation-backed IdlingResource if
    // this ever turns out flaky in CI.
    private fun waitForRoot(
        instrumentation: Instrumentation,
        expectedPackage: String,
        timeoutMs: Long = 5000,
    ): AccessibilityNodeInfo {
        val deadline = System.currentTimeMillis() + timeoutMs
        var root: AccessibilityNodeInfo? = null
        while (System.currentTimeMillis() < deadline) {
            root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null && root.packageName?.toString() == expectedPackage) return root
            Thread.sleep(100)
        }
        return root
            ?: error("rootInActiveWindow never became available for package $expectedPackage")
    }
}
