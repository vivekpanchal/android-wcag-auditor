package com.a11yauditor.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.a11yauditor.app.databinding.OverlayPanelBinding
import kotlin.math.hypot

/**
 * Bubble, issue panel and highlight box drawn over the target app. Runs on the main thread.
 * Every window is FLAG_NOT_FOCUSABLE so the target stays the active window.
 */
class AuditOverlay(
    private val service: AccessibilityService,
    private val prefs: SharedPreferences,
    private val onPanelClosed: () -> Unit,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val inflater = LayoutInflater.from(ContextThemeWrapper(service, R.style.Theme_A11yAuditor))
    private val bubbleSize = (56 * service.resources.displayMetrics.density).toInt()
    private var bubble: TextView? = null
    private var panel: OverlayPanelBinding? = null
    private var highlight: HighlightView? = null
    private var scan: LiveScan? = null
    private var quietUntil = 0L

    val panelOpen get() = panel != null

    /** True just after this class changed a window; those TYPE_WINDOWS_CHANGED events must not trigger a scan. */
    // shortcut: time window, not event identity; a real change inside it is caught by the target's own events.
    val isOwnWindowChange get() = SystemClock.uptimeMillis() < quietUntil

    /** Null means no target window is on screen: everything goes away until a later pass finds one. */
    fun render(next: LiveScan?) {
        scan = next
        if (next == null) {
            remove()
            return
        }
        val view = bubble ?: addBubble()
        val label = if (next.issues.size > 99) "99+" else next.issues.size.toString()
        if (view.text.toString() != label) view.text = label
        view.contentDescription = service.resources.getQuantityString(
            R.plurals.overlay_bubble_label, next.issues.size, next.issues.size,
        )
        (view.background as GradientDrawable).setColor(
            ContextCompat.getColor(
                service,
                when {
                    next.issues.isEmpty() -> R.color.bubble_clean
                    next.hasSerious -> R.color.bubble_serious
                    else -> R.color.bubble_moderate
                },
            ),
        )
    }

    fun remove() {
        closePanel()
        bubble?.let { removeWindow(it) }
        bubble = null
    }

    /** Hides without touching the window, so no window event fires. */
    fun setBubbleHidden(hidden: Boolean) {
        bubble?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    fun closePanel() {
        val open = panel ?: return
        panel = null
        removeWindow(open.root)
        highlight?.let { removeWindow(it) }
        highlight = null
        onPanelClosed()
    }

    fun onConfigurationChanged() {
        closePanel()
        bubble?.let { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            placeBubble(params)
            updateWindow(view, params)
        }
    }

    private fun overlayParams(width: Int, height: Int, flags: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or flags,
        PixelFormat.TRANSLUCENT,
    )

    private fun displaySize() = Point().also {
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealSize(it)
    }

    private fun placeBubble(params: WindowManager.LayoutParams) {
        val size = displaySize()
        params.x = (prefs.getFloat(KEY_BUBBLE_X, 1f) * (size.x - bubbleSize)).toInt()
        params.y = (prefs.getFloat(KEY_BUBBLE_Y, 0.6f) * (size.y - bubbleSize)).toInt()
    }

    @SuppressLint("ClickableViewAccessibility") // the listener calls performClick() on a tap
    private fun addBubble(): TextView {
        val view = TextView(service).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            setOnClickListener { togglePanel() }
        }
        val params = overlayParams(
            bubbleSize, bubbleSize,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        placeBubble(params)

        val slop = ViewConfiguration.get(service).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dragged || hypot(dx, dy) > slop) {
                        dragged = true
                        val size = displaySize()
                        params.x = (startX + dx.toInt()).coerceIn(0, size.x - bubbleSize)
                        params.y = (startY + dy.toInt()).coerceIn(0, size.y - bubbleSize)
                        updateWindow(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (dragged) {
                    val size = displaySize()
                    // Snap to the nearer side edge, so the bubble covers the screen edge, not content.
                    params.x = if (params.x + bubbleSize / 2 < size.x / 2) 0 else size.x - bubbleSize
                    updateWindow(v, params)
                    prefs.edit()
                        .putFloat(KEY_BUBBLE_X, params.x / (size.x - bubbleSize).toFloat())
                        .putFloat(KEY_BUBBLE_Y, params.y / (size.y - bubbleSize).toFloat())
                        .apply()
                } else {
                    v.performClick()
                }
            }
            true
        }
        addWindow(view, params)
        bubble = view
        return view
    }

    private fun togglePanel() {
        // A click listener runs outside the service's try/catch, and a throw here would kill the process.
        try {
            if (panelOpen) closePanel() else openPanel()
        } catch (e: Exception) {
            Log.e(TAG, "toggling the panel failed", e)
        }
    }

    private fun openPanel() {
        val snapshot = scan ?: return
        val binding = OverlayPanelBinding.inflate(inflater)
        val count = snapshot.issues.size
        binding.panelTitle.text = service.resources.getQuantityString(R.plurals.overlay_panel_title, count, count)
        ViewCompat.setAccessibilityPaneTitle(binding.root, binding.panelTitle.text)
        binding.panelScreen.text = snapshot.screen
        binding.panelEmpty.isVisible = count == 0
        binding.panelClose.setOnClickListener { closePanel() }
        binding.panelList.layoutManager = LinearLayoutManager(binding.root.context)
        binding.panelList.adapter = IssueListAdapter(onExpand = ::select).apply { submit(snapshot.issues.map { it.toRow() }) }
        val height = (displaySize().y * PANEL_HEIGHT).toInt()
        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT, height, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        ).apply { gravity = Gravity.BOTTOM }
        addWindow(binding.root, params)
        panel = binding
    }

    private fun select(row: IssueRow) {
        val box = row.bounds
        val view = highlight ?: HighlightView(service).also { addHighlight(it) }
        view.box = box
        view.invalidate()
        val open = panel ?: return
        if (box == null) return
        // The panel moves to the far side so it never covers the box it points at.
        val params = open.root.layoutParams as WindowManager.LayoutParams
        val wanted = if (box.y + box.height / 2 > displaySize().y / 2) Gravity.TOP else Gravity.BOTTOM
        if (params.gravity != wanted) {
            params.gravity = wanted
            updateWindow(open.root, params)
        }
    }

    private fun addHighlight(view: HighlightView) {
        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        addWindow(view, params)
        highlight = view
    }

    private fun addWindow(view: View, params: WindowManager.LayoutParams) {
        markChange()
        windowManager.addView(view, params)
    }

    private fun updateWindow(view: View, params: WindowManager.LayoutParams) {
        markChange()
        windowManager.updateViewLayout(view, params)
    }

    private fun removeWindow(view: View) {
        markChange()
        // Also runs during teardown, when the system may already have dropped the window.
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "removing an overlay window failed", e)
        }
    }

    private fun markChange() {
        quietUntil = SystemClock.uptimeMillis() + QUIET_MS
    }

    /** Draws in its own coordinates, so a window offset (insets, cutout) cannot shift the box. */
    private class HighlightView(context: Context) : View(context) {
        var box: Bounds? = null
        private val origin = IntArray(2)
        private val stroke = 3 * context.resources.displayMetrics.density
        private val paint = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = true }

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            val b = box ?: return
            getLocationOnScreen(origin)
            val left = (b.x - origin[0]).toFloat()
            val top = (b.y - origin[1]).toFloat()
            // White under red keeps the box visible on any background.
            paint.color = Color.WHITE
            paint.strokeWidth = stroke * 2
            canvas.drawRect(left, top, left + b.width, top + b.height, paint)
            paint.color = Color.RED
            paint.strokeWidth = stroke
            canvas.drawRect(left, top, left + b.width, top + b.height, paint)
        }
    }

    private companion object {
        const val TAG = "A11yAuditor.Overlay"
        const val KEY_BUBBLE_X = "bubble_x"
        const val KEY_BUBBLE_Y = "bubble_y"
        const val PANEL_HEIGHT = 0.45f
        const val QUIET_MS = 500L
    }
}
