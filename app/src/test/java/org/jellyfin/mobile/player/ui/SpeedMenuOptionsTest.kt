package org.jellyfin.mobile.player.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SpeedMenuOptionsTest {
    @Test
    fun `default settings match the old menu`() {
        speedMenuOptions(maxSpeed = 2f, step = 0.25f) shouldBe
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    }

    @Test
    fun `half steps start at 0_5x`() {
        speedMenuOptions(maxSpeed = 3f, step = 0.5f) shouldBe listOf(0.5f, 1f, 1.5f, 2f, 2.5f, 3f)
    }

    @Test
    fun `whole steps start at 1x`() {
        speedMenuOptions(maxSpeed = 8f, step = 1f) shouldBe listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)
    }

    @Test
    fun `a maximum between steps is still offered`() {
        speedMenuOptions(maxSpeed = 2.75f, step = 1f) shouldBe listOf(1f, 2f, 2.75f)
    }

    @Test
    fun `1x is offered even when no step lands on it`() {
        speedMenuOptions(maxSpeed = 2f, step = 0.3f) shouldBe listOf(0.6f, 0.9f, 1f, 1.2f, 1.5f, 1.8f, 2f)
    }

    @Test
    fun `fastest default-step menu lists every quarter`() {
        val speeds = speedMenuOptions(maxSpeed = 8f, step = 0.25f)
        speeds.size shouldBe 31
        speeds.first() shouldBe 0.5f
        speeds.last() shouldBe 8f
    }
}
