package org.jellyfin.mobile.player.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import kotlin.math.abs

/**
 * Lets a view be swiped sideways to dismiss it, by a short drag or a quick flick; [onDismiss] runs once it has
 * slid away.
 */
class SwipeDismissTouchListener(private val onDismiss: (View) -> Unit) : View.OnTouchListener {
    private var downX = 0f
    private var velocity: VelocityTracker? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val dx = event.rawX - downX
                view.translationX = dx
                view.alpha = 1f - (abs(dx) / view.width).coerceIn(0f, 1f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = event.rawX - downX
                velocity?.addMovement(event)
                velocity?.computeCurrentVelocity(MILLIS_PER_SECOND)
                val speed = velocity?.xVelocity ?: 0f
                velocity?.recycle()
                velocity = null
                val flungDp = speed / view.resources.displayMetrics.density
                val flung = abs(flungDp) > FLING_DP_PER_SECOND && flungDp * dx > 0
                if (event.actionMasked == MotionEvent.ACTION_UP && (abs(dx) > view.width * DISMISS_FRACTION || flung)) {
                    view.animate().translationX(if (dx > 0) view.width.toFloat() else -view.width.toFloat())
                        .alpha(0f)
                        .withEndAction {
                            reset(view)
                            onDismiss(view)
                        }
                        .start()
                } else {
                    view.animate().translationX(0f).alpha(1f).start()
                }
            }
            else -> return false
        }
        return true
    }

    /** Puts the view back in place so it shows normally next time. */
    private fun reset(view: View) {
        view.translationX = 0f
        view.alpha = 1f
    }

    private companion object {
        const val DISMISS_FRACTION = 0.15f
        const val FLING_DP_PER_SECOND = 600
        const val MILLIS_PER_SECOND = 1000
    }
}
