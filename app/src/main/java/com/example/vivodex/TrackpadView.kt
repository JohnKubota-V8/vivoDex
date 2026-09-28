package com.example.vivodex

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

class TrackpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    interface TrackpadListener {
        fun onPointerMove(dx: Float, dy: Float)
        fun onSingleTap()
        fun onTwoFingerTap()
        fun onDoubleTap()
        fun onScroll(deltaX: Float, deltaY: Float)
        fun onDragStart()
        fun onDragMove(dx: Float, dy: Float)
        fun onDragEnd()
    }

    var listener: TrackpadListener? = null
    var sensitivity: Float = 1.5f

    // Touch tracking
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastTapTime = 0L

    // Multitouch / Gestures
    private var isScrolling = false
    private var isDragging = false
    private var hasMultiFingerBeenUsed = false
    private var twoFingerDownTime = 0L
    private var twoFingerStartY = 0f
    private var lastTwoFingerY = 0f
    private var accumulatedScrollY = 0f

    // Visual feedback points
    private val activeTouchPoints = mutableListOf<PointF>()
    private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#402196F3") // Soft blue
        style = Paint.Style.FILL
    }
    private val touchBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#802196F3")
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#15888888")
        style = Paint.Style.FILL
    }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw elegant dot grid pattern
        val step = 36f * resources.displayMetrics.density
        val dotRadius = 1.5f * resources.displayMetrics.density
        var x = step
        while (x < width) {
            var y = step
            while (y < height) {
                canvas.drawCircle(x, y, dotRadius, gridPaint)
                y += step
            }
            x += step
        }

        // Draw active touch points
        for (point in activeTouchPoints) {
            val radius = 32f * resources.displayMetrics.density
            canvas.drawCircle(point.x, point.y, radius, touchPaint)
            canvas.drawCircle(point.x, point.y, radius, touchBorderPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        parent?.requestDisallowInterceptTouchEvent(true)

        updateTouchPoints(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                downTime = System.currentTimeMillis()
                isScrolling = false
                hasMultiFingerBeenUsed = false

                // Double tap and drag detection
                val timeSinceLastTap = downTime - lastTapTime
                if (timeSinceLastTap < 280) {
                    isDragging = true
                    HapticHelper.heavyClick(context)
                    listener?.onDragStart()
                } else {
                    isDragging = false
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    hasMultiFingerBeenUsed = true
                    isScrolling = true
                    isDragging = false
                    twoFingerDownTime = System.currentTimeMillis()
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    twoFingerStartY = midY
                    lastTwoFingerY = midY
                    accumulatedScrollY = 0f
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isScrolling && event.pointerCount >= 2) {
                    val currentMidY = (event.getY(0) + event.getY(1)) / 2f
                    val deltaY = currentMidY - lastTwoFingerY
                    lastTwoFingerY = currentMidY
                    accumulatedScrollY += deltaY

                    // Real-time incremental smooth scroll
                    val scrollThreshold = 18f * resources.displayMetrics.density
                    if (abs(accumulatedScrollY) >= scrollThreshold) {
                        listener?.onScroll(0f, accumulatedScrollY * 2.2f)
                        HapticHelper.tick(context)
                        accumulatedScrollY = 0f
                    }
                } else if (!hasMultiFingerBeenUsed) {
                    val dx = (event.x - lastTouchX) * sensitivity
                    val dy = (event.y - lastTouchY) * sensitivity
                    lastTouchX = event.x
                    lastTouchY = event.y

                    if (isDragging) {
                        listener?.onDragMove(dx, dy)
                    } else {
                        listener?.onPointerMove(dx, dy)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2 && isScrolling) {
                    val duration = System.currentTimeMillis() - twoFingerDownTime
                    val moveDist = abs(lastTwoFingerY - twoFingerStartY)
                    // If 2-finger tap without significant movement -> Right Click / context menu
                    if (duration < 250 && moveDist < 16f * resources.displayMetrics.density) {
                        HapticHelper.click(context)
                        listener?.onTwoFingerTap()
                    } else if (abs(accumulatedScrollY) > 4f) {
                        listener?.onScroll(0f, accumulatedScrollY * 2.2f)
                    }
                    isScrolling = false
                }
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val duration = System.currentTimeMillis() - downTime
                val dist = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()).toFloat()

                if (isDragging) {
                    isDragging = false
                    listener?.onDragEnd()
                    HapticHelper.tick(context)
                } else if (!hasMultiFingerBeenUsed && !isScrolling) {
                    val touchSlop = 16f * resources.displayMetrics.density
                    if (duration < 220 && dist < touchSlop) {
                        val timeSinceLastTap = System.currentTimeMillis() - lastTapTime
                        if (timeSinceLastTap < 280) {
                            HapticHelper.doubleClick(context)
                            listener?.onDoubleTap()
                            lastTapTime = 0L
                        } else {
                            HapticHelper.click(context)
                            listener?.onSingleTap()
                            lastTapTime = System.currentTimeMillis()
                        }
                    }
                }
                activeTouchPoints.clear()
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isScrolling = false
                isDragging = false
                activeTouchPoints.clear()
                invalidate()
            }
        }

        return true
    }

    private fun updateTouchPoints(event: MotionEvent) {
        activeTouchPoints.clear()
        for (i in 0 until event.pointerCount) {
            activeTouchPoints.add(PointF(event.getX(i), event.getY(i)))
        }
        invalidate()
    }
}
