package org.jellyfin.mobile.player.ui

import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/**
 * A circular arrow with the skip length in seconds inside it, like the skip buttons in audiobook apps.
 * Points counterclockwise for skip back and clockwise for skip forward.
 */
class SkipSecondsDrawable(
    resources: Resources,
    private val seconds: Int,
    private val forward: Boolean,
) : Drawable() {
    private val size = (SIZE_DP * resources.displayMetrics.density).roundToInt()
    private var tint: ColorStateList? = null
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val arcBounds = RectF()
    private val arrow = Path()

    override fun draw(canvas: Canvas) {
        val box = bounds.width().coerceAtMost(bounds.height()).toFloat()
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val radius = box * RADIUS
        val arrowSize = box * ARROW

        strokePaint.strokeWidth = box * STROKE
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Drawn as skip back (arrow at the top pointing left), mirrored for skip forward
        canvas.save()
        if (forward) canvas.scale(-1f, 1f, cx, cy)
        canvas.drawArc(arcBounds, TOP_ANGLE, ARC_SWEEP, false, strokePaint)
        val top = cy - radius
        arrow.rewind()
        arrow.moveTo(cx - arrowSize, top)
        arrow.lineTo(cx + arrowSize * ARROW_BACK, top - arrowSize)
        arrow.lineTo(cx + arrowSize * ARROW_BACK, top + arrowSize)
        arrow.close()
        canvas.drawPath(arrow, fillPaint)
        canvas.restore()

        textPaint.textSize = box * TEXT
        val baseline = cy - (textPaint.ascent() + textPaint.descent()) / 2
        canvas.drawText(seconds.toString(), cx, baseline, textPaint)
    }

    override fun getIntrinsicWidth() = size

    override fun getIntrinsicHeight() = size

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        updateColor()
    }

    override fun isStateful() = tint?.isStateful == true

    override fun onStateChange(state: IntArray): Boolean = updateColor()

    private fun updateColor(): Boolean {
        val color = tint?.getColorForState(state, tint?.defaultColor ?: DEFAULT_COLOR) ?: DEFAULT_COLOR
        if (color == textPaint.color) return false
        strokePaint.color = color
        fillPaint.color = color
        textPaint.color = color
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {
        strokePaint.alpha = alpha
        fillPaint.alpha = alpha
        textPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        strokePaint.colorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
        textPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val SIZE_DP = 32f
        const val RADIUS = 0.40f
        const val STROKE = 0.075f
        const val ARROW = 0.13f
        const val ARROW_BACK = 0.3f
        const val TEXT = 0.34f
        const val TOP_ANGLE = -90f
        const val ARC_SWEEP = 290f // clockwise from the top, leaving a gap before the arrow
        const val DEFAULT_COLOR = 0xFFFFFFFF.toInt()
    }
}
