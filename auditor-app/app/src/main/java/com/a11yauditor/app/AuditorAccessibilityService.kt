package com.a11yauditor.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheckResult
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchyAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Runs on every window/content change in the currently-targeted app, executes
 * ATF's accessibility checks against the visible node tree, and sends any
 * findings to the embedded dashboard server (AuditServer, on localhost:8080),
 * which stores them and streams them to any open dashboard.
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
    // Target Activity and its window id; a modal dialog hides that window from getWindows(), so index 0 isn't always the activity.
    private val baseScreen = BaseScreenTracker()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: SharedPreferences
    private var deps: ServerDeps? = null
    private var overlay: AuditOverlay? = null
    // A target event arrived while the panel paused scanning; closing it must catch up, and only then.
    private var missedChange = false
    private var server: AuditServer? = null
    private var startJob: Job? = null
    // Main thread only. Distinct findings this session, and the last pass recorded.
    private val sessionFindings = mutableSetOf<FindingKey>()
    private var lastRecorded: Set<FindingKey>? = null

    // Held in a field: SharedPreferences keeps only a weak reference to its listeners.
    private val controlListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != KEY_IS_AUDITING && key != KEY_TARGET_PACKAGE) return@OnSharedPreferenceChangeListener
        // Wrapped in try/catch like onAccessibilityEvent: this can run on any thread that edits prefs.
        try {
            if (key == KEY_TARGET_PACKAGE) mainHandler.post { baseScreen.reset() }
            if (key == KEY_IS_AUDITING) {
                if (prefs.getBoolean(KEY_IS_AUDITING, false)) {
                    sessionIssueCount.value = 0
                    mainHandler.post {
                        sessionFindings.clear()
                        lastRecorded = null
                    }
                }
                else mainHandler.post { stopLive() }
            }
            deps?.publishControl()
        } catch (e: Exception) {
            Log.e(TAG, "control change handling failed, ignoring it", e)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "onServiceConnected")
        stopServer() // in case this runs twice
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        serviceBound.value = true
        overlay = AuditOverlay(this, prefs) {
            if (missedChange) {
                missedChange = false
                prefs.getString(KEY_TARGET_PACKAGE, null)?.let { scheduleAudit(it) }
            }
        }
        val newDeps = ServerDeps(
            (application as AuditorApplication).store,
            ControlStore(prefs),
            DashboardToken.getOrCreate(prefs),
            apps = { launchableApps(packageManager, packageName).map { AppEntry(it.loadLabel(packageManager).toString(), it.packageName) } },
            asset = { p -> try { assets.open("dashboard/$p") } catch (e: IOException) { null } },
        )
        val s = AuditServer(newDeps)
        deps = newDeps
        serverDeps = newDeps
        server = s
        prefs.registerOnSharedPreferenceChangeListener(controlListener)
        // start() blocks while binding, so it stays off the main thread.
        // Captured here, on main: a later stopServer() replaces `stopping` with a thread that joins this job.
        val previousStop = stopping
        startJob = serviceScope.launch {
            // The previous server frees port 8080 on its stop thread; starting first would lose the bind.
            previousStop?.join()
            // A throw here must not escape: serviceScope has no handler, so it would kill the process.
            val ok = try { s.start() } catch (e: Throwable) {
                Log.e(TAG, "dashboard server start threw", e)
                false
            }
            Log.i(TAG, "dashboard server start ok=$ok")
            // On main, where `server` is written, so a late result cannot overwrite Stopped.
            mainHandler.post { if (server === s) serverStatus.value = if (ok) ServerStatus.Running else ServerStatus.Failed }
        }
    }

    // Both onUnbind and onDestroy run on main and stop() blocks up to 1.5 s, so teardown happens on its own thread.
    private fun stopServer() {
        serviceBound.value = false
        serverDeps = null
        stopLive()
        val s = server ?: return
        Log.i(TAG, "stopping dashboard server")
        server = null
        prefs.unregisterOnSharedPreferenceChangeListener(controlListener)
        serverStatus.value = ServerStatus.Stopped
        val job = startJob
        // Outlives serviceScope, which onDestroy cancels. Joining first means stop() sees the started engine.
        stopping = thread(name = "audit-server-stop") {
            try {
                runBlocking { job?.join() }
                s.stop()
            } catch (e: Throwable) {
                Log.e(TAG, "dashboard server stop failed", e)
            }
        }
    }

    /** Also cancels a queued pass, so nothing runs or lands after Stop. */
    private fun stopLive() {
        pendingAudit?.let { mainHandler.removeCallbacks(it) }
        pendingAudit = null
        missedChange = false
        overlay?.remove()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            overlay?.onConfigurationChanged()
        } catch (e: Exception) {
            Log.e(TAG, "overlay rotation handling failed", e)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopServer()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopServer()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // This is called by the system on the main thread for every window/
        // content-change event for the life of the audit session — an
        // uncaught exception here (a SharedPreferences value of the wrong
        // type throwing ClassCastException, an OEM quirk on event field
        // access, etc.) propagates straight out of the system's dispatch
        // call and kills the whole process. One bad frame would silently end
        // the entire session instead of just being skipped, so catch broadly
        // and move on to the next event.
        try {
            val targetPackage = prefs.getString(KEY_TARGET_PACKAGE, null) ?: return
            // Tracked while stopped too: Start with the target already open sends no new Activity event.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                event.packageName?.toString() == targetPackage
            ) {
                event.className?.toString()
                    ?.takeIf { isActivity(targetPackage, it) }
                    ?.let { baseScreen.onActivity(targetPackage, it, event.windowId) }
            }
            if (!prefs.getBoolean(KEY_IS_AUDITING, false)) return
            val isWindowsChanged = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            if (isWindowsChanged && overlay?.isOwnWindowChange == true) return
            if (!ScreenNaming.shouldScan(event.packageName?.toString(), isWindowsChanged, targetPackage)) return

            // The panel shows a snapshot, so scans wait; a new screen or leaving the target ends it.
            val open = overlay?.takeIf { it.panelOpen }
            if (open != null) {
                missedChange = true
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) open.closePanel()
                else if (isWindowsChanged && currentTargetWindows(targetPackage).isEmpty()) open.closePanel()
                return
            }
            scheduleAudit(targetPackage)
        } catch (e: Exception) {
            Log.e(TAG, "onAccessibilityEvent failed, skipping this event", e)
        }
    }

    private fun scheduleAudit(targetPackage: String) {
        // Content-changed events fire rapidly while a screen settles (layout
        // passes, animations, etc). Debounce so we audit once the screen is
        // actually still, not on every intermediate frame.
        pendingAudit?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { runAudit(targetPackage) }
        pendingAudit = runnable
        mainHandler.postDelayed(runnable, DEBOUNCE_MS)
    }

    override fun onInterrupt() {}

    private fun runAudit(targetPackage: String) {
        // Runs from a main-Handler Runnable: an OEM getWindows() quirk or a window
        // torn down mid-read must skip this pass, not kill the service.
        try {
            if (!isAuditing() || overlay?.panelOpen == true) return
            auditWindows(targetPackage)
        } catch (e: Exception) {
            Log.e(TAG, "runAudit failed, skipping this pass", e)
        }
    }

    private fun auditWindows(targetPackage: String) {
        // Re-selected here, not at event time: the debounce means the foreground
        // can change in between, and only target-app windows are ever audited
        // or screenshotted.
        val targetWindows = currentTargetWindows(targetPackage)
        if (targetWindows.isEmpty()) {
            Log.d(TAG, "runAudit: no $targetPackage windows on screen, skipping")
            overlay?.render(null)
            return
        }
        val activity = baseScreen.activityFor(targetPackage)
        val baseId = baseScreen.windowIdFor(targetPackage)

        // One ATF run per window so a failure in one (e.g. a popup mid-dismiss)
        // doesn't drop the others, and each dialog/popup gets its own screen label.
        val results = targetWindows.mapIndexed { index, window ->
            val issues = try {
                checkHierarchy(window.root)
            } catch (e: Exception) {
                Log.e(TAG, "ATF check run failed on window '${window.title}'", e)
                emptyList()
            }
            ScreenNaming.screenLabel(activity, window, isBase = ScreenNaming.isBase(window, index, baseId)) to issues
        }
        // Before the screenshot, so the count does not wait on capture; a clean pass must reach the bubble too.
        overlay?.render(LiveScan.from(results))
        val perWindow = results.filter { it.second.isNotEmpty() }
        Log.d(TAG, "runAudit: ${perWindow.sumOf { it.second.size }} issue(s) across ${targetWindows.size} window(s) ${results.map { it.first }}")
        // An animating screen re-sends the same findings every pass; record only when they change.
        val keys = findingKeys(perWindow)
        if (keys == lastRecorded) return
        lastRecorded = keys
        if (sessionFindings.addAll(keys)) sessionIssueCount.value = sessionFindings.size
        if (perWindow.isEmpty()) return

        captureWithoutOverlay { shot ->
            if (!isAuditing()) return@captureWithoutOverlay
            perWindow.forEach { (label, issues) ->
                val d = deps ?: return@forEach
                serviceScope.launch {
                    try {
                        d.recordReport(targetPackage, label, System.currentTimeMillis(), shot, issues)
                    } catch (e: Exception) {
                        Log.e(TAG, "recording report for '$label' failed", e)
                    }
                }
            }
        }
    }

    /** Target-app windows, activity first; falls back to rootInActiveWindow if the window list is unavailable. */
    private fun currentTargetWindows(targetPackage: String): List<TargetWindow<AccessibilityNodeInfo>> {
        val all = windows.mapNotNull { w ->
            val root = w.root ?: return@mapNotNull null
            TargetWindow(
                packageName = root.packageName?.toString(),
                isApplication = w.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                layer = w.layer,
                title = w.title?.toString(),
                rootClassName = root.className?.toString(),
                root = root,
                id = w.id,
            )
        }
        val selected = ScreenNaming.targetWindows(all, targetPackage)
        if (selected.isNotEmpty()) return selected
        val active = rootInActiveWindow ?: return emptyList()
        if (active.packageName?.toString() != targetPackage) return emptyList()
        return listOf(TargetWindow(targetPackage, true, 0, null, active.className?.toString(), active))
    }

    private fun isActivity(pkg: String, className: String): Boolean = try {
        packageManager.getActivityInfo(ComponentName(pkg, className), 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
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
                    fix = criterion.fix,
                    framework = frameworkFor(generateSequence(element) { it.parentView }.map { it.className }),
                    bounds = bounds,
                )
            }
    }

    private fun describeElement(className: CharSequence?, resourceId: CharSequence?) =
        listOfNotNull(className?.toString()?.substringAfterLast('.'), resourceId?.toString())
            .joinToString(" ")
            .ifBlank { "Unnamed element" }

    private fun isAuditing() = prefs.getBoolean(KEY_IS_AUDITING, false)

    /** The bubble must not appear in stored screenshots, and hiding it needs a frame to reach the screen first. */
    private fun captureWithoutOverlay(onResult: (ByteArray?) -> Unit) {
        val o = overlay
        if (o == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            captureScreenshot(onResult)
            return
        }
        o.setBubbleHidden(true)
        // shortcut: fixed delay of about 3 frames at 60 Hz; upgrade to a frame callback if the bubble shows up in shots.
        mainHandler.postDelayed({
            try {
                if (o.panelOpen || !isAuditing()) {
                    o.setBubbleHidden(false)
                    onResult(null)
                } else {
                    captureScreenshot { shot ->
                        o.setBubbleHidden(false)
                        onResult(shot)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "overlay-free capture failed, skipping this report", e)
                o.setBubbleHidden(false)
            }
        }, OVERLAY_HIDE_MS)
    }

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
                        // Runs on mainExecutor (see takeScreenshot call below), so an
                        // uncaught exception here crashes on the main thread, not a
                        // background one. wrapHardwareBuffer can throw
                        // IllegalArgumentException for a ColorSpace it doesn't support
                        // (seen on some OEM wide-gamut displays), and compress() can
                        // throw IllegalStateException if the bitmap it got back is
                        // already recycled. Either way, degrade to no-screenshot rather
                        // than kill the whole session over a missing image. Scoped to
                        // just the conversion (not onResult itself) so a failure further
                        // downstream in the caller's callback — e.g. recording the report —
                        // isn't misreported as a screenshot failure and double-delivered.
                        val shot = try {
                            val bitmap = try {
                                Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            } finally {
                                // Close even if wrapHardwareBuffer throws — it wraps
                                // native memory, and the outer catch below means we'd
                                // otherwise leak it on the exception path.
                                result.hardwareBuffer.close()
                            }
                            bitmap?.let {
                                val out = ByteArrayOutputStream()
                                it.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
                                out.toByteArray()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "screenshot bitmap conversion failed", e)
                            null
                        }
                        onResult(shot)
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
        const val DASHBOARD_URL = "http://localhost:8080/"
        private const val DEBOUNCE_MS = 600L
        private const val OVERLAY_HIDE_MS = 50L

        val sessionIssueCount = MutableStateFlow(0)

        enum class ServerStatus { Stopped, Running, Failed }

        val serverStatus = MutableStateFlow(ServerStatus.Stopped)

        /** Whether the system has this service bound right now; enabled in Settings does not mean running. */
        val serviceBound = MutableStateFlow(false)

        /** Set while the service runs, so the app's Clear also reaches open dashboards. */
        @Volatile var serverDeps: ServerDeps? = null

        // Last server teardown; the next start waits for it so it does not race for the port.
        @Volatile private var stopping: Thread? = null
    }
}
