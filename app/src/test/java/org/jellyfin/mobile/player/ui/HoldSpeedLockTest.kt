package org.jellyfin.mobile.player.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HoldSpeedLockTest {
    private val lock = HoldSpeedLock()

    @Test
    fun `releasing a hold returns to the speed before it`() {
        lock.onHoldStarted(1.5f)
        lock.onHoldReleased(locked = false) shouldBe 1.5f
    }

    @Test
    fun `releasing a locked hold keeps the locked speed`() {
        lock.onHoldStarted(1f)
        lock.onHoldReleased(locked = true) shouldBe null
    }

    @Test
    fun `holding again after a lock returns to the speed before the lock`() {
        lock.onHoldStarted(1f)
        lock.onHoldReleased(locked = true)

        lock.onHoldStarted(2f)
        lock.onHoldReleased(locked = false) shouldBe 1f
    }

    @Test
    fun `re-locking keeps the speed from before the first lock`() {
        lock.onHoldStarted(1.25f)
        lock.onHoldReleased(locked = true)
        lock.onHoldStarted(2f)
        lock.onHoldReleased(locked = true)

        lock.onHoldStarted(2.5f)
        lock.onHoldReleased(locked = false) shouldBe 1.25f
    }

    @Test
    fun `a speed picked from the menu replaces the speed before the lock`() {
        lock.onHoldStarted(1f)
        lock.onHoldReleased(locked = true)
        lock.clear()

        lock.onHoldStarted(1.75f)
        lock.onHoldReleased(locked = false) shouldBe 1.75f
    }
}
