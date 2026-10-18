package com.k9drone.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Simple virtual joystick.
 * Returns normalized values -1.0 .. +1.0 for both axes.
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onMove(x: Float, y: Float) // -1..1
        fun onRelease()
    }

    var listener: Listener? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44FFFFFF
        style = Paint.Style.FILL
    }
    private val stickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var stickRadius = 0f

    private val stickPos = PointF()
    private var touching = false

    // Current normalized output
    var normX = 0f
        private set
    var normY = 0f
        private set

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        baseRadius = min(w, h) / 2f * 0.9f
        stickRadius = baseRadius * 0.35f
        stickPos.set(centerX, centerY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Base circle
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint)
        canvas.drawCircle(centerX, centerY, baseRadius, borderPaint)
        // Stick
        canvas.drawCircle(stickPos.x, stickPos.y, stickRadius, stickPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touching = true
                val dx = event.x - centerX
                val dy = event.y - centerY
                val dist = hypot(dx, dy)

                if (dist <= baseRadius) {
                    stickPos.set(event.x, event.y)
                } else {
                    val angle = atan2(dy, dx)
                    stickPos.x = centerX + cos(angle) * baseRadius
                    stickPos.y = centerY + sin(angle) * baseRadius
                }

                // Normalize: X left-right, Y up-down (inverted so up = positive)
                normX = ((stickPos.x - centerX) / baseRadius).coerceIn(-1f, 1f)
                normY = -((stickPos.y - centerY) / baseRadius).coerceIn(-1f, 1f)

                listener?.onMove(normX, normY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                stickPos.set(centerX, centerY)
                normX = 0f
                normY = 0f
                listener?.onRelease()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
