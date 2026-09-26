package org.jellyfin.mobile.player.ui

/**
 * Remembers the playback speed from before a hold-to-speed-up was locked, so that holding again
 * and releasing without locking returns to that speed instead of the locked one.
 */
class HoldSpeedLock {
    private var speedBeforeHold = 1f
    private var speedBeforeLock: Float? = null

    /** A hold started while playing at [currentSpeed]. */
    fun onHoldStarted(currentSpeed: Float) {
        speedBeforeHold = currentSpeed
    }

    /**
     * The hold ended. Returns the speed to return to, or null when the speed stays locked.
     */
    fun onHoldReleased(locked: Boolean): Float? {
        if (locked) {
            // Keep the speed from before the first lock when re-locking a locked speed
            if (speedBeforeLock == null) speedBeforeLock = speedBeforeHold
            return null
        }
        val restore = speedBeforeLock ?: speedBeforeHold
        speedBeforeLock = null
        return restore
    }

    /** A speed was picked some other way (e.g. the speed menu), so forget any locked speed. */
    fun clear() {
        speedBeforeLock = null
    }
}
