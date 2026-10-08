package com.example.vivodex

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Shader
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
    private var isDragCandidate = false
    private var hasMultiFingerBeenUsed = false
    private var twoFingerDownTime = 0L
    private var twoFingerStartY = 0f
    private var lastTwoFingerY = 0f
    private var accumulatedScrollY = 0f

    // Touch ripples
    private class LiquidRipple(val x: Float, val y: Float, var radius: Float, var alpha: Int)
    private val activeRipples = mutableListOf<LiquidRipple>()

    // Visual feedback points
    private val activeTouchPoints = mutableListOf<PointF>()
    private val startDragRunnable = Runnable {
        if (isDragCandidate && !hasMultiFingerBeenUsed) {
            isDragging = true
            listener?.onDragStart()
        }
    }

    // Gold touch feedback over the navy control surface.
    private val liquidCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.deck_accent)
        style = Paint.Style.FILL
    }
    private val liquidRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.liquid_cyan_dim)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }
    private val liquidBridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.liquid_cyan_glow)
        style = Paint.Style.STROKE
        strokeWidth = 6f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val crystalDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3506996")
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isFocusable = true
    }

    private fun spawnRipple(x: Float, y: Float) {
        activeRipples.add(LiquidRipple(x, y, 10f * resources.displayMetrics.density, 255))
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val density = resources.displayMetrics.density

        // A quiet dot field gives the surface scale without adding visual noise.
        val step = 28f * density
        val dotRadius = 1.4f * density
        var gx = step
        while (gx < width) {
            var gy = step
            while (gy < height) {
                canvas.drawCircle(gx, gy, dotRadius, crystalDotPaint)
                gy += step
            }
            gx += step
        }

        // Two-finger bridge while scrolling.
        if (activeTouchPoints.size >= 2) {
            val p1 = activeTouchPoints[0]
            val p2 = activeTouchPoints[1]
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, liquidBridgePaint)
        }

        // Expanding input ripples.
        val rippleIterator = activeRipples.iterator()
        while (rippleIterator.hasNext()) {
            val ripple = rippleIterator.next()
            ripplePaint.color = Color.argb(ripple.alpha, 209, 173, 87)
            canvas.drawCircle(ripple.x, ripple.y, ripple.radius, ripplePaint)
            ripple.radius += 5f * density
            ripple.alpha = (ripple.alpha - 15).coerceAtLeast(0)
            if (ripple.alpha <= 0) {
                rippleIterator.remove()
            }
        }
        if (activeRipples.isNotEmpty()) {
            postInvalidateOnAnimation()
        }

        // Touch reticles.
        for (point in activeTouchPoints) {
            val glowRadius = 48f * density
            val glowGradient = RadialGradient(
                point.x, point.y, glowRadius,
                intArrayOf(
                    Color.parseColor("#66D1AD57"),
                    Color.parseColor("#22D1AD57"),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            glowPaint.shader = glowGradient
            canvas.drawCircle(point.x, point.y, glowRadius, glowPaint)

            // Inner droplet ring & core
            canvas.drawCircle(point.x, point.y, 22f * density, liquidRingPaint)
            canvas.drawCircle(point.x, point.y, 6f * density, liquidCorePaint)
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
                downTime = event.eventTime
                isScrolling = false
                hasMultiFingerBeenUsed = false

                spawnRipple(event.x, event.y)

                // The second touch becomes a drag only when held or moved.
                val timeSinceLastTap = downTime - lastTapTime
                if (timeSinceLastTap < 280) {
                    isDragCandidate = true
                    postDelayed(startDragRunnable, DRAG_HOLD_TIMEOUT_MS)
                } else {
                    isDragging = false
                    isDragCandidate = false
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    hasMultiFingerBeenUsed = true
                    isScrolling = true
                    isDragging = false
                    isDragCandidate = false
                    removeCallbacks(startDragRunnable)
                    twoFingerDownTime = event.eventTime
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    twoFingerStartY = midY
                    lastTwoFingerY = midY
                    accumulatedScrollY = 0f
                    spawnRipple((event.getX(0) + event.getX(1)) / 2f, midY)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isScrolling && event.pointerCount >= 2) {
                    val currentMidY = (event.getY(0) + event.getY(1)) / 2f
                    val deltaY = currentMidY - lastTwoFingerY
                    lastTwoFingerY = currentMidY
                    accumulatedScrollY += deltaY

                    // Real-time incremental smooth scroll
                    val scrollThreshold = 16f * resources.displayMetrics.density
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

                    if (isDragCandidate && !isDragging && hypot(dx.toDouble(), dy.toDouble()) >= 16f * resources.displayMetrics.density) {
                        isDragging = true
                        removeCallbacks(startDragRunnable)
                        HapticHelper.heavyClick(context)
                        listener?.onDragStart()
                    }
                    if (isDragging) {
                        listener?.onDragMove(dx, dy)
                    } else {
                        listener?.onPointerMove(dx, dy)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2 && isScrolling) {
                    val duration = event.eventTime - twoFingerDownTime
                    val moveDist = abs(lastTwoFingerY - twoFingerStartY)
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
                removeCallbacks(startDragRunnable)
                val duration = event.eventTime - downTime
                val dist = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()).toFloat()

                if (isDragging) {
                    isDragging = false
                    listener?.onDragEnd()
                    HapticHelper.tick(context)
                } else if (!hasMultiFingerBeenUsed && !isScrolling) {
                    val touchSlop = 16f * resources.displayMetrics.density
                    if (duration < 220 && dist < touchSlop) {
                        spawnRipple(event.x, event.y)
                        HapticHelper.click(context)
                        performClick()
                        lastTapTime = event.eventTime
                    }
                }
                activeTouchPoints.clear()
                isDragCandidate = false
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isScrolling = false
                isDragging = false
                isDragCandidate = false
                removeCallbacks(startDragRunnable)
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

    override fun performClick(): Boolean {
        super.performClick()
        listener?.onSingleTap()
        return true
    }

    companion object {
        private const val DRAG_HOLD_TIMEOUT_MS = 180L
    }
}
