package com.a11yauditor.app

/**
 * Plain snapshot of an AccessibilityWindowInfo plus its root, so window
 * selection and screen labelling are JVM-testable. `R` is
 * AccessibilityNodeInfo in the service and a stand-in in tests.
 */
data class TargetWindow<R>(
    val packageName: String?,
    val isApplication: Boolean,
    val layer: Int,
    val title: String?,
    val rootClassName: String?,
    val root: R,
    val id: Int = -1,
)

/**
 * The target Activity and its window id from the last TYPE_WINDOW_STATE_CHANGED.
 * Reset whenever auditing stops or the target changes, so a stale window id
 * can't mislabel the next session's base screen as an overlay.
 */
class BaseScreenTracker {
    private var packageName: String? = null
    private var activity: String? = null
    private var windowId: Int? = null

    fun onActivity(packageName: String, className: String, windowId: Int) {
        this.packageName = packageName
        activity = className
        this.windowId = windowId
    }

    fun reset() {
        packageName = null
        activity = null
        windowId = null
    }

    fun activityFor(target: String): String? = activity?.takeIf { packageName == target }

    fun windowIdFor(target: String): Int? = windowId?.takeIf { packageName == target }
}

object ScreenNaming {

    /** The target app's own windows (activity, dialogs, sheets, popups), lowest layer (the activity) first. */
    fun <R> targetWindows(windows: List<TargetWindow<R>>, target: String): List<TargetWindow<R>> =
        windows.filter { it.isApplication && it.packageName == target }.sortedBy { it.layer }

    /**
     * TYPE_WINDOWS_CHANGED events often carry a null package, so always accept
     * them: runAudit re-selects the target's windows after the debounce and
     * skips if there are none, which keeps window lookups off the event path.
     */
    fun shouldScan(eventPackage: String?, isWindowsChanged: Boolean, target: String): Boolean =
        eventPackage == target || isWindowsChanged

    /**
     * A modal dialog hides the activity window from getWindows(), so the lowest
     * window isn't always the activity. Use the activity's window id when known.
     */
    fun isBase(window: TargetWindow<*>, index: Int, activityWindowId: Int?): Boolean =
        if (activityWindowId != null) window.id == activityWindowId else index == 0

    fun shortName(className: String?): String? =
        className?.substringAfterLast('.')?.substringBefore('$')?.takeIf { it.isNotBlank() }

    /** "CheckoutActivity" for the base window, "CheckoutActivity › Confirm payment" for a dialog/popup above it. */
    fun screenLabel(activity: String?, window: TargetWindow<*>, isBase: Boolean): String {
        val base = shortName(activity) ?: window.title?.takeIf { it.isNotBlank() } ?: "Unknown screen"
        if (isBase) return base
        val overlay = window.title?.takeIf { it.isNotBlank() } ?: shortName(window.rootClassName) ?: "Popup"
        return "$base › $overlay"
    }
}
