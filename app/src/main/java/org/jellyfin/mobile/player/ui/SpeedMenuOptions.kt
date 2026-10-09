package org.jellyfin.mobile.player.ui

import kotlin.math.roundToInt

private const val HUNDREDTHS = 100
private const val MIN_SPEED = 50 // 0.5x
private const val NORMAL_SPEED = 100 // 1x

/**
 * The speeds offered by the speed menu: every multiple of [step] from 0.5x up to [maxSpeed].
 * 1x and [maxSpeed] are always included, even when they don't fall on a step.
 */
fun speedMenuOptions(maxSpeed: Float, step: Float): List<Float> {
    // Work in hundredths so repeated float additions can't drift (0.1 + 0.2 != 0.3)
    val max = (maxSpeed * HUNDREDTHS).roundToInt().coerceAtLeast(NORMAL_SPEED)
    val stepSize = (step * HUNDREDTHS).roundToInt().coerceAtLeast(1)
    val first = (MIN_SPEED + stepSize - 1) / stepSize * stepSize
    val speeds = sortedSetOf(NORMAL_SPEED, max)
    speeds += (first..max step stepSize)
    return speeds.map { speed -> speed.toFloat() / HUNDREDTHS }
}
