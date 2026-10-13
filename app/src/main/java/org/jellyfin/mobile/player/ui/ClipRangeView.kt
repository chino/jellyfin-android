package org.jellyfin.mobile.player.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import org.jellyfin.mobile.R
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A stretch of an audiobook drawn as a ruler with one tick per second, with the clip's range between two
 * handles that can be dragged, and a playhead. All positions are in milliseconds in the book.
 */
class ClipRangeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    /** Called while a handle or the playhead is dragged, and with [finished] set once it is let go. */
    interface Listener {
        fun onRangeChanged(start: Long, end: Long, finished: Boolean)
        fun onSeek(position: Long, finished: Boolean)
    }

    var listener: Listener? = null

    var windowStart = 0L
        private set
    var windowEnd = 1L
        private set
    var start = 0L
        private set
    var end = 1L
        private set

    /** Where playback is, or a negative value to hide the playhead. Ignored while the playhead is dragged. */
    var playhead = -1L
        set(value) {
            if (field != value && dragging != Drag.PLAYHEAD) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.jellyfin_accent)
    private val handleWidth = HANDLE_WIDTH_DP * density
    private val corner = CORNER_DP * density
    private val tickWidth = TICK_WIDTH_DP * density

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, android.R.color.darker_gray)
        strokeWidth = tickWidth
        strokeCap = Paint.Cap.ROUND
    }
    private val selectedTickPaint = Paint(tickPaint).apply { color = accent }
    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        alpha = RANGE_ALPHA
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.theme_background)
        style = Paint.Style.STROKE
        strokeWidth = CHEVRON_STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, android.R.color.white)
        strokeWidth = PLAYHEAD_WIDTH_DP * density
    }
    private val rect = RectF()
    private val path = Path()

    private var dragging = Drag.NONE
    private var dragOffset = 0L

    fun setRange(windowStart: Long, windowEnd: Long, start: Long, end: Long) {
        this.windowStart = windowStart
        this.windowEnd = windowEnd.coerceAtLeast(windowStart + MIN_LENGTH_MS)
        this.start = start
        this.end = end
        invalidate()
    }

    private val trackLeft get() = paddingLeft + handleWidth
    private val trackRight get() = width - paddingRight - handleWidth

    private fun xOf(position: Long): Float =
        trackLeft + (trackRight - trackLeft) * (position - windowStart) / (windowEnd - windowStart).toFloat()

    private fun positionOf(x: Float): Long =
        windowStart + ((x - trackLeft) / (trackRight - trackLeft) * (windowEnd - windowStart)).roundToLong()

    override fun onDraw(canvas: Canvas) {
        val top = paddingTop + PLAYHEAD_KNOB_DP * density * 2
        val bottom = height - paddingBottom.toFloat()
        val middle = (top + bottom) / 2
        val startX = xOf(start)
        val endX = xOf(end)

        rect.set(startX - handleWidth, top, endX + handleWidth, bottom)
        canvas.drawRoundRect(rect, corner, corner, rangePaint)

        drawTicks(canvas, top, bottom, middle)

        rect.set(startX - handleWidth, top, startX, bottom)
        drawHandle(canvas, left = true)
        rect.set(endX, top, endX + handleWidth, bottom)
        drawHandle(canvas, left = false)

        if (playhead in windowStart..windowEnd) {
            val x = xOf(playhead)
            canvas.drawLine(x, paddingTop.toFloat() + PLAYHEAD_KNOB_DP * density, x, bottom, playheadPaint)
            canvas.drawCircle(x, paddingTop + PLAYHEAD_KNOB_DP * density, PLAYHEAD_KNOB_DP * density, playheadPaint)
        }
    }

    private fun drawTicks(canvas: Canvas, top: Float, bottom: Float, middle: Float) {
        val full = (bottom - top) * TICK_MAX_FRACTION
        var second = (windowStart + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
        while (second * MILLIS_PER_SECOND <= windowEnd) {
            val position = second * MILLIS_PER_SECOND
            val height = when {
                second % LONG_TICK_SECONDS == 0L -> full
                second % MEDIUM_TICK_SECONDS == 0L -> full * MEDIUM_TICK_FRACTION
                else -> full * SHORT_TICK_FRACTION
            }
            val x = xOf(position)
            val paint = if (position in start..end) selectedTickPaint else tickPaint
            canvas.drawLine(x, middle - height / 2, x, middle + height / 2, paint)
            second++
        }
    }

    /** Draws the handle in [rect], with a chevron pointing away from the clip. */
    private fun drawHandle(canvas: Canvas, left: Boolean) {
        path.reset()
        val radii = if (left) {
            floatArrayOf(corner, corner, 0f, 0f, 0f, 0f, corner, corner)
        } else {
            floatArrayOf(0f, 0f, corner, corner, corner, corner, 0f, 0f)
        }
        path.addRoundRect(rect, radii, Path.Direction.CW)
        canvas.drawPath(path, handlePaint)
        val size = handleWidth * CHEVRON_FRACTION
        val cx = rect.centerX()
        val cy = rect.centerY()
        val tip = if (left) cx - size / 2 else cx + size / 2
        val back = if (left) cx + size / 2 else cx - size / 2
        path.reset()
        path.moveTo(back, cy - size)
        path.lineTo(tip, cy)
        path.lineTo(back, cy + size)
        canvas.drawPath(path, chevronPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val startDistance = abs(event.x - (xOf(start) - handleWidth / 2))
                val endDistance = abs(event.x - (xOf(end) + handleWidth / 2))
                val reach = handleWidth * TOUCH_REACH
                dragging = when {
                    startDistance <= endDistance && startDistance < reach -> Drag.START
                    endDistance < reach -> Drag.END
                    else -> Drag.PLAYHEAD
                }
                dragOffset = (if (dragging == Drag.START) start else end) - positionOf(event.x)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> moveHandle(event.x, finished = false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                moveHandle(event.x, finished = true)
                dragging = Drag.NONE
            }
        }
        return true
    }

    private fun moveHandle(x: Float, finished: Boolean) {
        if (dragging == Drag.PLAYHEAD) {
            val position = positionOf(x).coerceIn(windowStart, windowEnd)
            // Set the field directly: the setter ignores outside updates during this drag
            dragging = Drag.NONE
            playhead = position
            dragging = Drag.PLAYHEAD
            listener?.onSeek(position, finished)
            return
        }
        val position = positionOf(x) + dragOffset
        // Snap to whole seconds, which is as precise as the times shown
        val snapped = (position.toDouble() / MILLIS_PER_SECOND).roundToLong() * MILLIS_PER_SECOND
        when (dragging) {
            Drag.START -> start = snapped.coerceIn(windowStart, end - MIN_LENGTH_MS)
            Drag.END -> end = snapped.coerceIn(start + MIN_LENGTH_MS, windowEnd)
            Drag.NONE, Drag.PLAYHEAD -> return
        }
        invalidate()
        listener?.onRangeChanged(start, end, finished)
    }

    private enum class Drag { NONE, START, END, PLAYHEAD }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
        const val MIN_LENGTH_MS = 1000L
        const val HANDLE_WIDTH_DP = 24
        const val CORNER_DP = 12
        const val TICK_WIDTH_DP = 2
        const val CHEVRON_STROKE_DP = 2
        const val PLAYHEAD_WIDTH_DP = 2
        const val PLAYHEAD_KNOB_DP = 8
        const val RANGE_ALPHA = 60
        const val TICK_MAX_FRACTION = 0.6f
        const val MEDIUM_TICK_FRACTION = 0.6f
        const val SHORT_TICK_FRACTION = 0.3f
        const val LONG_TICK_SECONDS = 30
        const val MEDIUM_TICK_SECONDS = 5
        const val CHEVRON_FRACTION = 0.25f
        const val TOUCH_REACH = 1.5f
    }
}
