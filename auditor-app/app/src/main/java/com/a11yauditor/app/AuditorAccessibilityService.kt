package com.a11yauditor.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheckResult
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchyAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Runs on every window/content change in the currently-targeted app, executes
 * ATF's accessibility checks against the visible node tree, and POSTs any
 * findings to the local dashboard server.
 *
 * The ATF integration (AccessibilityHierarchyAndroid.newBuilder/build,
 * AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset,
 * AccessibilityHierarchyCheckResult field access) is verified against
 * accessibility-test-framework 4.1.1's actual compiled classes via javap —
 * this file compiles clean with that dependency.
 */
class AuditorAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingAudit: Runnable? = null
    private val reportSender = ReportSender()
    private val controlSync = ControlSync()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private lateinit var prefs: SharedPreferences

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        serviceRunning.value = true
        pollJob = serviceScope.launch {
            while (isActive) {
                pollRemoteControl()
                delay(CONTROL_POLL_MS)
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        serviceRunning.value = false
        pollJob?.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Picks up target/auditing changes made from the dashboard. The device
     * can't be pushed to (adb reverse is one-directional), so this is a poll.
     * A no-op if the server isn't reachable — local control from the app's
     * own UI keeps working standalone either way.
     */
    private fun pollRemoteControl() {
        // A local toggle in MainActivity writes prefs immediately (so it works
        // even with no server), then pushes to the server in the background.
        // If a poll lands in that gap, the server hasn't seen the change yet —
        // applying its still-stale response here would silently revert the
        // user's tap. Skip one cycle after a local change to let the push land;
        // the grace window is shorter than the poll interval, so it costs at
        // most a single skipped poll, not a lasting delay.
        val lastLocalIntent = prefs.getLong(KEY_LOCAL_INTENT_AT, 0)
        if (System.currentTimeMillis() - lastLocalIntent < LOCAL_INTENT_GRACE_MS) return

        val remote = controlSync.fetchControl() ?: return
        val wasAuditing = prefs.getBoolean(KEY_IS_AUDITING, false)
        val currentTarget = prefs.getString(KEY_TARGET_PACKAGE, null)
        if (remote.auditing == wasAuditing && remote.targetPackage == currentTarget) return

        Log.i(TAG, "remote control changed: auditing=${remote.auditing} target=${remote.targetPackage}")
        if (remote.auditing && !wasAuditing) sessionIssueCount.value = 0
        prefs.edit()
            .putString(KEY_TARGET_PACKAGE, remote.targetPackage)
            .putBoolean(KEY_IS_AUDITING, remote.auditing)
            .apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val targetPackage = prefs.getString(KEY_TARGET_PACKAGE, null) ?: return
        val auditing = prefs.getBoolean(KEY_IS_AUDITING, false)
        if (!auditing) return
        if (event.packageName?.toString() != targetPackage) return

        // Content-changed events fire rapidly while a screen settles (layout
        // passes, animations, etc). Debounce so we audit once the screen is
        // actually still, not on every intermediate frame.
        pendingAudit?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { runAudit(targetPackage, event.className?.toString()) }
        pendingAudit = runnable
        mainHandler.postDelayed(runnable, DEBOUNCE_MS)
    }

    override fun onInterrupt() {}

    private fun runAudit(targetPackage: String, screenName: String?) {
        val root = rootInActiveWindow
        if (root == null) {
            Log.d(TAG, "runAudit: rootInActiveWindow is null, skipping")
            return
        }
        // The debounce delay means the foreground app can change between the
        // event that scheduled this and now (user switched away, a system
        // dialog took focus, etc). Re-check so we never audit or screenshot
        // a different app than the one the user selected as the target.
        if (root.packageName?.toString() != targetPackage) {
            Log.d(TAG, "runAudit: foreground changed away from $targetPackage, skipping")
            return
        }

        val issues = try {
            checkHierarchy(root)
        } catch (e: Exception) {
            Log.e(TAG, "ATF check run failed", e)
            emptyList()
        }
        Log.d(TAG, "runAudit: ${issues.size} issue(s) on $screenName")
        if (issues.isEmpty()) return

        captureScreenshot { png ->
            sessionIssueCount.value += issues.size
            reportSender.send(targetPackage, screenName, issues, png)
        }
    }

    // ponytail: runs synchronously on the main thread (called from the
    // debounce Runnable, which is main-Handler-posted) — a screen with a very
    // large visible tree could cause jank while checks run. Not moved off
    // main without device-verifying AccessibilityNodeInfo's threading
    // behavior first; an untested threading change risks trading rare jank
    // for a real crash. Upgrade path: benchmark on a worst-case screen, and
    // if it's actually slow, move hierarchy building + check execution to a
    // background dispatcher.
    private fun checkHierarchy(root: AccessibilityNodeInfo): List<AuditIssue> {
        val hierarchy = AccessibilityHierarchyAndroid.newBuilder(root, applicationContext).build()
        val checks = AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset(
            AccessibilityCheckPreset.LATEST
        )

        val results = mutableListOf<AccessibilityHierarchyCheckResult>()
        checks.forEach { check -> results.addAll(check.runCheckOnHierarchy(hierarchy)) }

        return results
            .filter { it.type == AccessibilityCheckResultType.ERROR || it.type == AccessibilityCheckResultType.WARNING }
            .map { result ->
                // result.sourceCheckClass / result.element are ATF's linkage back to
                // which check fired and which node it fired on.
                val checkClassName = result.sourceCheckClass?.simpleName ?: "Unknown"
                val criterion = WcagMapping.forCheckClass(checkClassName)
                val element = result.element
                val bounds = element?.boundsInScreen?.takeUnless { it.isEmpty }
                AuditIssue(
                    severity = if (result.type == AccessibilityCheckResultType.ERROR) "serious" else "moderate",
                    wcagSc = criterion.sc,
                    wcagLevel = criterion.level,
                    elementDescription = describeElement(element?.className, element?.resourceName),
                    description = result.getMessage(Locale.getDefault())?.toString() ?: result.toString(),
                    suggestedFix = null,
                    bounds = bounds,
                )
            }
    }

    private fun describeElement(className: CharSequence?, resourceId: CharSequence?) =
        listOfNotNull(className?.toString()?.substringAfterLast('.'), resourceId?.toString())
            .joinToString(" ")
            .ifBlank { "Unnamed element" }

    private fun captureScreenshot(onResult: (ByteArray?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null)
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        result.hardwareBuffer.close()
                        if (bitmap == null) {
                            onResult(null)
                            return
                        }
                        val out = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        onResult(out.toByteArray())
                    }

                    override fun onFailure(errorCode: Int) {
                        // Common cause: FLAG_SECURE window on the target screen.
                        // Still report the issue, just without a screenshot.
                        Log.w(TAG, "takeScreenshot failed: $errorCode")
                        onResult(null)
                    }
                }
            )
        } catch (e: SecurityException) {
            // Thrown synchronously (not via onFailure) when the service lacks the
            // screenshot capability — e.g. an OEM that ignores canTakeScreenshot in
            // the service config, or a restricted profile. Degrade gracefully.
            Log.w(TAG, "takeScreenshot not permitted: ${e.message}")
            onResult(null)
        }
    }

    companion object {
        private const val TAG = "A11yAuditor.Service"
        const val PREFS_NAME = "a11y_auditor_prefs"
        const val KEY_TARGET_PACKAGE = "target_package"
        const val KEY_IS_AUDITING = "is_auditing"
        const val KEY_LOCAL_INTENT_AT = "local_intent_at"
        private const val DEBOUNCE_MS = 600L
        private const val CONTROL_POLL_MS = 3000L
        private const val LOCAL_INTENT_GRACE_MS = 2000L

        val serviceRunning = MutableStateFlow(false)
        val sessionIssueCount = MutableStateFlow(0)
    }
}
