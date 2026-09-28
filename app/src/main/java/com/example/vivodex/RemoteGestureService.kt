package com.example.vivodex

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
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

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        removeCursor()
        removeBlackout()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun tap(displayId: Int, x: Float, y: Float): Boolean {
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
        val display = getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return
        if (cursorDisplayId != displayId) removeCursor()

        val displayContext = createDisplayContext(display)
        val size = (32 * displayContext.resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Hotspot is top-left of the cursor icon (arrow tip at M4,2)
            val hotspotOffset = (4 * displayContext.resources.displayMetrics.density).toInt()
            this.x = (x - hotspotOffset).toInt()
            this.y = (y - hotspotOffset).toInt()
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
                cursorWindowManager?.updateViewLayout(cursorView, params)
            } catch (_: WindowManager.BadTokenException) {
                removeCursor()
            }
        }
    }

    fun toggleCursor(): Boolean {
        cursorVisible = !cursorVisible
        if (!cursorVisible) removeCursor()
        return cursorVisible
    }

    fun isCursorVisible(): Boolean = cursorVisible

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
            text = "🌙 Phone Screen Dimmed\nTap anywhere to wake"
            setTextColor(android.graphics.Color.argb(120, 255, 255, 255))
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        frame.addView(hintText)

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
        cursorView?.let { cursorWindowManager?.removeView(it) }
        cursorView = null
        cursorWindowManager = null
        cursorDisplayId = Display.INVALID_DISPLAY
    }

    private fun removeBlackout() {
        blackoutView?.let { view ->
            try {
                blackoutWindowManager?.removeView(view)
            } catch (_: IllegalArgumentException) { }
        }
        blackoutView = null
        blackoutWindowManager = null
    }

    companion object {
        var instance: RemoteGestureService? = null
            private set
    }
}
