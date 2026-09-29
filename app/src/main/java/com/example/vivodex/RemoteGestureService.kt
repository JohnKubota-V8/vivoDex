package com.example.vivodex

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

class RemoteGestureService : AccessibilityService() {
    private var cursorView: ImageView? = null
    private var cursorWindowManager: WindowManager? = null
    private var cursorDisplayId = Display.INVALID_DISPLAY
    private var cursorVisible = true
    private var blackoutView: View? = null
    private var blackoutWindowManager: WindowManager? = null
    private val blackoutAnimators = mutableListOf<Animator>()
    private var editableNode: AccessibilityNodeInfo? = null
    private var editablePackageName: String? = null
    private var editableWindowId = -1
    private var editableViewId: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoHideCursorRunnable = Runnable {
        hideCursorForInactivity()
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() == packageName) return
        val source = event.source ?: return
        val editable = if (source.isEditable) source else source.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (editable?.isEditable == true) {
            editableNode = AccessibilityNodeInfo.obtain(editable)
            editablePackageName = event.packageName?.toString()
            editableWindowId = editable.windowId
            editableViewId = editable.viewIdResourceName
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoHideCursorRunnable)
        removeCursor()
        removeBlackout()
        editableNode = null
        editablePackageName = null
        editableWindowId = -1
        editableViewId = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun tap(displayId: Int, x: Float, y: Float): Boolean {
        resetCursorInactivityTimer()
        val display = getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return false
        val clampedX = x.coerceIn(0f, display.mode.physicalWidth.toFloat())
        val clampedY = y.coerceIn(0f, display.mode.physicalHeight.toFloat())
        val path = Path().apply { moveTo(clampedX, clampedY) }
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun longPress(displayId: Int, x: Float, y: Float): Boolean {
        resetCursorInactivityTimer()
        val display = getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return false
        val clampedX = x.coerceIn(0f, display.mode.physicalWidth.toFloat())
        val clampedY = y.coerceIn(0f, display.mode.physicalHeight.toFloat())
        val path = Path().apply { moveTo(clampedX, clampedY) }
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, 600))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun drag(displayId: Int, startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 250): Boolean {
        resetCursorInactivityTimer()
        val display = getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return false
        val clampedStartX = startX.coerceIn(0f, display.mode.physicalWidth.toFloat())
        val clampedStartY = startY.coerceIn(0f, display.mode.physicalHeight.toFloat())
        val clampedEndX = endX.coerceIn(0f, display.mode.physicalWidth.toFloat())
        val clampedEndY = endY.coerceIn(0f, display.mode.physicalHeight.toFloat())
        val path = Path().apply {
            moveTo(clampedStartX, clampedStartY)
            lineTo(clampedEndX, clampedEndY)
        }
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun scroll(displayId: Int, x: Float, y: Float, deltaX: Float, deltaY: Float): Boolean {
        resetCursorInactivityTimer()
        val display = getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return false
        val endX = (x + deltaX).coerceIn(0f, display.mode.physicalWidth.toFloat())
        val endY = (y + deltaY).coerceIn(0f, display.mode.physicalHeight.toFloat())
        if (kotlin.math.abs(endX - x) < 2f && kotlin.math.abs(endY - y) < 2f) return false

        val path = Path().apply {
            moveTo(x, y)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun moveCursor(displayId: Int, x: Float, y: Float) {
        if (!cursorVisible) return
        resetCursorInactivityTimer()
        val display = getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return
        if (cursorDisplayId != displayId) removeCursor()

        val displayContext = createDisplayContext(display)
        val size = (28 * displayContext.resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.toInt()
            this.y = y.toInt()
        }

        if (cursorView == null) {
            val newCursor = ImageView(displayContext).apply {
                setImageResource(R.drawable.ic_mouse_pointer)
            }
            val windowManager = displayContext.getSystemService(WindowManager::class.java)
            try {
                windowManager.addView(newCursor, params)
                cursorView = newCursor
                cursorWindowManager = windowManager
                cursorDisplayId = displayId
            } catch (_: WindowManager.BadTokenException) { }
        } else {
            try {
                cursorView?.visibility = View.VISIBLE
                cursorWindowManager?.updateViewLayout(cursorView, params)
            } catch (_: WindowManager.BadTokenException) {
                removeCursor()
            }
        }
    }

    private fun resetCursorInactivityTimer() {
        mainHandler.removeCallbacks(autoHideCursorRunnable)
        if (cursorView?.visibility != View.VISIBLE && cursorVisible) {
            cursorView?.visibility = View.VISIBLE
        }
        if (cursorVisible) {
            mainHandler.postDelayed(autoHideCursorRunnable, CURSOR_AUTO_HIDE_DELAY_MS)
        }
    }

    private fun hideCursorForInactivity() {
        if (!cursorVisible) return
        cursorView?.visibility = View.GONE
    }

    fun toggleCursor(): Boolean {
        cursorVisible = !cursorVisible
        if (!cursorVisible) {
            mainHandler.removeCallbacks(autoHideCursorRunnable)
            removeCursor()
        } else {
            resetCursorInactivityTimer()
        }
        return cursorVisible
    }

    fun isCursorVisible(): Boolean = cursorVisible

    fun hasEditableTarget(): Boolean = editableNode?.isEditable == true

    fun editableTargetPackage(): String = editablePackageName ?: "external app"

    fun setEditableText(text: CharSequence): Boolean {
        val node = liveEditableNode() ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun liveEditableNode(): AccessibilityNodeInfo? {
        val root = windows.firstOrNull { it.id == editableWindowId }?.root ?: return editableNode
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused?.isEditable == true) return focused
        val viewId = editableViewId ?: return editableNode
        return findEditableNode(root, viewId) ?: editableNode
    }

    private fun findEditableNode(node: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        if (node.isEditable && node.viewIdResourceName == viewId) return node
        repeat(node.childCount) { index ->
            node.getChild(index)?.let { child ->
                findEditableNode(child, viewId)?.let { return it }
            }
        }
        return null
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun blackoutPhoneScreen(): Boolean {
        if (blackoutView != null) return true
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        val displayContext = createDisplayContext(display)

        val frame = FrameLayout(displayContext).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isClickable = true
            isFocusable = true
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    removeBlackout()
                }
                true
            }
        }

        val hintText = TextView(displayContext).apply {
            text = "🌙 Phone Screen Dimmed\n(Anti-Burn Active)\n\nTap anywhere to wake"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        frame.addView(hintText)

        // OLED Anti-Burn-in Drift Animators (Lissajous curves with prime period intervals)
        val density = displayContext.resources.displayMetrics.density
        val driftX = 70f * density
        val driftY = 120f * density

        val animX = ObjectAnimator.ofFloat(hintText, View.TRANSLATION_X, -driftX, driftX).apply {
            duration = 11000L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val animY = ObjectAnimator.ofFloat(hintText, View.TRANSLATION_Y, -driftY, driftY).apply {
            duration = 17000L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val animAlpha = ObjectAnimator.ofFloat(hintText, View.ALPHA, 0.35f, 0.70f).apply {
            duration = 7000L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }

        blackoutAnimators.clear()
        blackoutAnimators.add(animX)
        blackoutAnimators.add(animY)
        blackoutAnimators.add(animAlpha)

        animX.start()
        animY.start()
        animAlpha.start()

        val windowManager = displayContext.getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return try {
            windowManager.addView(frame, params)
            blackoutView = frame
            blackoutWindowManager = windowManager
            true
        } catch (_: WindowManager.BadTokenException) {
            false
        }
    }

    private fun removeCursor() {
        mainHandler.removeCallbacks(autoHideCursorRunnable)
        cursorView?.let { cursorWindowManager?.removeView(it) }
        cursorView = null
        cursorWindowManager = null
        cursorDisplayId = Display.INVALID_DISPLAY
    }

    private fun removeBlackout() {
        blackoutAnimators.forEach { it.cancel() }
        blackoutAnimators.clear()
        blackoutView?.let { view ->
            try {
                blackoutWindowManager?.removeView(view)
            } catch (_: IllegalArgumentException) { }
        }
        blackoutView = null
        blackoutWindowManager = null
    }

    companion object {
        const val CURSOR_AUTO_HIDE_DELAY_MS = 10_000L

        var instance: RemoteGestureService? = null
            private set
    }
}
