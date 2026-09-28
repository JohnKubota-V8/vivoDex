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

    // Liquid Ripples
    private class LiquidRipple(val x: Float, val y: Float, var radius: Float, var alpha: Int)
    private val activeRipples = mutableListOf<LiquidRipple>()

    // Visual feedback points
    private val activeTouchPoints = mutableListOf<PointF>()

    // Paints (Monochrome Dark Tone)
    private val liquidCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6FFFFFF")
        style = Paint.Style.FILL
    }
    private val liquidRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }
    private val liquidBridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 6f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val crystalDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#15FFFFFF")
        style = Paint.Style.FILL
    }

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

        // 1. Crystal Micro-Dot Grid
        val step = 38f * density
        val dotRadius = 1.2f * density
        var gx = step
        while (gx < width) {
            var gy = step
            while (gy < height) {
                canvas.drawCircle(gx, gy, dotRadius, crystalDotPaint)
                gy += step
            }
            gx += step
        }

        // 2. Liquid Two-Finger Bridge (When scrolling)
        if (activeTouchPoints.size >= 2) {
            val p1 = activeTouchPoints[0]
            val p2 = activeTouchPoints[1]
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, liquidBridgePaint)
        }

        // 3. Expanding Liquid Ripples (Monochrome White)
        val rippleIterator = activeRipples.iterator()
        while (rippleIterator.hasNext()) {
            val ripple = rippleIterator.next()
            ripplePaint.color = Color.argb(ripple.alpha, 255, 255, 255)
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

        // 4. Monochrome Luminous Orbs at Touch Points
        for (point in activeTouchPoints) {
            val glowRadius = 48f * density
            val glowGradient = RadialGradient(
                point.x, point.y, glowRadius,
                intArrayOf(
                    Color.parseColor("#45FFFFFF"),
                    Color.parseColor("#14FFFFFF"),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glowGradient }
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
                downTime = System.currentTimeMillis()
                isScrolling = false
                hasMultiFingerBeenUsed = false

                spawnRipple(event.x, event.y)

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
                        spawnRipple(event.x, event.y)
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
