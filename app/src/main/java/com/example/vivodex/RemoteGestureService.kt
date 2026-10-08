package com.example.vivodex

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.graphics.Rect
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
    private var lastBlackoutTapTime = 0L
    private var keepScreenAwake = false
    private var gestureInFlight = false
    private var pendingScroll: ScrollRequest? = null
    private val displayBoundsCache = mutableMapOf<Int, Rect>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoHideCursorRunnable = Runnable {
        hideCursorForInactivity()
    }
    private val autoDisableRunnable = Runnable {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(AUTO_DISABLE_DEADLINE).apply()
        disableSelf()
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            displayBoundsCache.remove(displayId)
        }

        override fun onDisplayRemoved(displayId: Int) {
            displayBoundsCache.remove(displayId)
            if (cursorDisplayId == displayId) removeCursor()
            if (pendingScroll?.displayId == displayId) pendingScroll = null
        }
    }

    override fun onServiceConnected() {
        instance = this
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, mainHandler)
        onConnectionChanged?.invoke(true)
        reconcileAutoDisable()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoHideCursorRunnable)
        mainHandler.removeCallbacks(autoDisableRunnable)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        removeCursor()
        removeBlackout()
        if (instance === this) {
            instance = null
            onConnectionChanged?.invoke(false)
        }
        super.onDestroy()
    }

    fun tap(displayId: Int, x: Float, y: Float): Boolean {
        resetCursorInactivityTimer(displayId)
        val bounds = displayBounds(displayId) ?: return false
        val clampedX = x.coerceIn(0f, bounds.width().toFloat() - 1f)
        val clampedY = y.coerceIn(0f, bounds.height().toFloat() - 1f)
        val path = Path().apply { moveTo(clampedX, clampedY) }
        return dispatch(GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build())
    }

    fun longPress(displayId: Int, x: Float, y: Float): Boolean {
        resetCursorInactivityTimer(displayId)
        val bounds = displayBounds(displayId) ?: return false
        val clampedX = x.coerceIn(0f, bounds.width().toFloat() - 1f)
        val clampedY = y.coerceIn(0f, bounds.height().toFloat() - 1f)
        val path = Path().apply { moveTo(clampedX, clampedY) }
        return dispatch(GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, 600))
            .build())
    }

    fun drag(displayId: Int, startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 250): Boolean {
        resetCursorInactivityTimer(displayId)
        val bounds = displayBounds(displayId) ?: return false
        val clampedStartX = startX.coerceIn(0f, bounds.width().toFloat() - 1f)
        val clampedStartY = startY.coerceIn(0f, bounds.height().toFloat() - 1f)
        val clampedEndX = endX.coerceIn(0f, bounds.width().toFloat() - 1f)
        val clampedEndY = endY.coerceIn(0f, bounds.height().toFloat() - 1f)
        val path = Path().apply {
            moveTo(clampedStartX, clampedStartY)
            lineTo(clampedEndX, clampedEndY)
        }
        return dispatch(GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build())
    }

    fun scroll(displayId: Int, x: Float, y: Float, deltaX: Float, deltaY: Float): Boolean {
        resetCursorInactivityTimer(displayId)
        val bounds = displayBounds(displayId) ?: return false
        val startX = x.coerceIn(0f, bounds.width().toFloat() - 1f)
        val startY = y.coerceIn(0f, bounds.height().toFloat() - 1f)
        val endX = (startX + deltaX).coerceIn(0f, bounds.width().toFloat() - 1f)
        val endY = (startY + deltaY).coerceIn(0f, bounds.height().toFloat() - 1f)
        if (kotlin.math.abs(endX - startX) < 2f && kotlin.math.abs(endY - startY) < 2f) return false

        if (gestureInFlight) {
            pendingScroll = pendingScroll?.takeIf { it.displayId == displayId }?.let { pending ->
                pending.copy(
                deltaX = pending.deltaX + deltaX,
                deltaY = pending.deltaY + deltaY,
                )
            } ?: ScrollRequest(displayId, startX, startY, deltaX, deltaY)
            return true
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        return dispatch(GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build())
    }

    fun pinch(displayId: Int, x: Float, y: Float, scale: Float): Boolean {
        resetCursorInactivityTimer(displayId)
        val bounds = displayBounds(displayId) ?: return false
        if (scale <= 0f) return false

        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        val halfSpan = minOf(width, height) * 0.12f
        val endHalfSpan = halfSpan * scale
        val centerX = x.coerceIn(0f, width)
        val centerY = y.coerceIn(0f, height)
        fun pointX(span: Float, direction: Float) = (centerX + span * direction).coerceIn(0f, width)

        val first = Path().apply {
            moveTo(pointX(halfSpan, -1f), centerY)
            lineTo(pointX(endHalfSpan, -1f), centerY)
        }
        val second = Path().apply {
            moveTo(pointX(halfSpan, 1f), centerY)
            lineTo(pointX(endHalfSpan, 1f), centerY)
        }
        return dispatch(GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(first, 0, 180))
            .addStroke(GestureDescription.StrokeDescription(second, 0, 180))
            .build())
    }

    fun moveCursor(displayId: Int, x: Float, y: Float) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            if (cursorView != null) removeCursor()
            return
        }
        if (!cursorVisible) return
        resetCursorInactivityTimer(displayId)
        val display = getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return
        val bounds = displayBounds(displayId) ?: return
        if (cursorDisplayId != displayId) removeCursor()

        val windowContext = createWindowContext(display, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val size = (28 * windowContext.resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = x.coerceIn(0f, bounds.width().toFloat() - 1f).toInt()
            this.y = y.coerceIn(0f, bounds.height().toFloat() - 1f).toInt()
        }

        if (cursorView == null) {
            val newCursor = ImageView(windowContext).apply {
                setImageResource(R.drawable.ic_mouse_pointer)
            }
            val windowManager = windowContext.getSystemService(WindowManager::class.java)
            try {
                windowManager.addView(newCursor, params)
                cursorView = newCursor
                cursorWindowManager = windowManager
                cursorDisplayId = displayId
            } catch (error: WindowManager.BadTokenException) {
                Log.w(TAG, "Could not add cursor to display $displayId", error)
            } catch (error: WindowManager.InvalidDisplayException) {
                Log.w(TAG, "Cursor display $displayId disappeared", error)
            }
        } else {
            try {
                cursorView?.visibility = View.VISIBLE
                cursorWindowManager?.updateViewLayout(cursorView, params)
            } catch (error: WindowManager.BadTokenException) {
                Log.w(TAG, "Could not update cursor on display $displayId", error)
                removeCursor()
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Cursor was detached before update", error)
                removeCursor()
            }
        }
    }

    private fun resetCursorInactivityTimer(displayId: Int = cursorDisplayId) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            if (cursorView != null) removeCursor()
            return
        }
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

    fun setKeepScreenAwake(enabled: Boolean) {
        keepScreenAwake = enabled
        val view = blackoutView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = if (enabled) {
            params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        try {
            blackoutWindowManager?.updateViewLayout(view, params)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Could not update blackout flags", error)
        }
    }

    fun scheduleAutoDisable() {
        mainHandler.removeCallbacks(autoDisableRunnable)
        mainHandler.postDelayed(autoDisableRunnable, AUTO_DISABLE_DELAY_MS)
    }

    fun cancelAutoDisable() {
        mainHandler.removeCallbacks(autoDisableRunnable)
    }

    fun disableNow() {
        mainHandler.removeCallbacks(autoDisableRunnable)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(AUTO_DISABLE_DEADLINE).apply()
        disableSelf()
    }

    fun hasEditableTarget(displayId: Int): Boolean = editableTarget(displayId) != null

    fun editableTargetPackage(displayId: Int): String = editableTarget(displayId)?.packageName?.toString() ?: "external app"

    fun setEditableText(displayId: Int, text: CharSequence): Boolean {
        val node = editableTarget(displayId) ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun editableTarget(displayId: Int): AccessibilityNodeInfo? =
        windowsOnAllDisplays[displayId]?.asSequence()
            ?.mapNotNull(AccessibilityWindowInfo::getRoot)
            ?.mapNotNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            ?.firstOrNull { it.isEditable }

    fun blackoutPhoneScreen(): Boolean = showBlackoutOverlay()

    private fun showBlackoutOverlay(): Boolean {
        if (blackoutView != null) return true
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        val windowContext = createWindowContext(display, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)

        val frame = FrameLayout(windowContext).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isClickable = true
            isFocusable = true
            contentDescription = "Wake phone screen"
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (lastBlackoutTapTime != 0L && event.eventTime - lastBlackoutTapTime <= DOUBLE_TAP_TIMEOUT_MS) {
                        lastBlackoutTapTime = 0L
                        performClick()
                    } else {
                        lastBlackoutTapTime = event.eventTime
                    }
                }
                true
            }
            setOnClickListener { removeBlackout() }
        }

        val hintText = TextView(windowContext).apply {
            text = "Phone screen dimmed\nAnti-burn protection is active\n\nDouble tap anywhere to wake"
            setTextColor(getColor(R.color.text_glass_primary))
            textSize = 13f
            setBackgroundResource(R.drawable.bg_glass_alert)
            setPadding((20 * windowContext.resources.displayMetrics.density).toInt(), (16 * windowContext.resources.displayMetrics.density).toInt(), (20 * windowContext.resources.displayMetrics.density).toInt(), (16 * windowContext.resources.displayMetrics.density).toInt())
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        frame.addView(hintText)

        val windowManager = windowContext.getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                if (keepScreenAwake) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0,
            PixelFormat.OPAQUE,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return try {
            windowManager.addView(frame, params)
            blackoutView = frame
            blackoutWindowManager = windowManager
            hintText.postDelayed({ hintText.visibility = View.GONE }, BLACKOUT_HINT_DURATION_MS)
            true
        } catch (error: WindowManager.BadTokenException) {
            Log.w(TAG, "Could not add blackout overlay", error)
            false
        } catch (error: WindowManager.InvalidDisplayException) {
            Log.w(TAG, "Phone display disappeared while adding blackout", error)
            false
        }
    }

    private fun removeCursor() {
        mainHandler.removeCallbacks(autoHideCursorRunnable)
        cursorView?.let {
            try {
                cursorWindowManager?.removeView(it)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Cursor was already removed", error)
            }
        }
        cursorView = null
        cursorWindowManager = null
        cursorDisplayId = Display.INVALID_DISPLAY
    }

    private fun removeBlackout() {
        blackoutView?.let { view ->
            try {
                blackoutWindowManager?.removeView(view)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Blackout was already removed", error)
            }
        }
        blackoutView = null
        blackoutWindowManager = null
        lastBlackoutTapTime = 0L
    }

    companion object {
        private const val TAG = "VivoDex"
        private const val PREFS_NAME = "vivodex_prefs"
        private const val AUTO_DISABLE_DEADLINE = "auto_disable_deadline"
        private const val BLACKOUT_HINT_DURATION_MS = 5_000L
        private const val DOUBLE_TAP_TIMEOUT_MS = 350L
        const val CURSOR_AUTO_HIDE_DELAY_MS = 10_000L
        const val AUTO_DISABLE_DELAY_MS = 10 * 60 * 1000L

        var instance: RemoteGestureService? = null
            private set
        var onConnectionChanged: ((Boolean) -> Unit)? = null
    }

    private data class ScrollRequest(
        val displayId: Int,
        val x: Float,
        val y: Float,
        val deltaX: Float,
        val deltaY: Float,
    )

    fun displayBounds(displayId: Int): Rect? {
        displayBoundsCache[displayId]?.let { return Rect(it) }
        val display = getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return null
        val bounds = createWindowContext(display, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
            .currentWindowMetrics
            .bounds
        displayBoundsCache[displayId] = Rect(bounds)
        return bounds
    }

    private fun dispatch(gesture: GestureDescription): Boolean {
        if (gestureInFlight) return false
        gestureInFlight = true
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) = completeGesture()
            override fun onCancelled(gestureDescription: GestureDescription) = completeGesture()
        }, mainHandler)
        if (!accepted) gestureInFlight = false
        return accepted
    }

    private fun completeGesture() {
        gestureInFlight = false
        pendingScroll?.let { request ->
            pendingScroll = null
            scroll(request.displayId, request.x, request.y, request.deltaX, request.deltaY)
        }
    }

    private fun reconcileAutoDisable() {
        val deadline = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getLong(AUTO_DISABLE_DEADLINE, 0L)
        if (deadline == 0L) return
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0L) disableNow() else {
            mainHandler.removeCallbacks(autoDisableRunnable)
            mainHandler.postDelayed(autoDisableRunnable, remaining)
        }
    }
}
