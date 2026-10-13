package org.jellyfin.mobile.player.ui

import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import org.jellyfin.mobile.R
import org.jellyfin.mobile.databinding.ExoPlayerControlViewBinding

/**
 * The row of buttons under the player. For audiobooks it drops what only matters for video (playback info,
 * decoder, fullscreen, and the audio track picker when there is only one track), then makes the remaining
 * buttons bigger and spreads them across the width, like Audible's player.
 */
class AudiobookBottomBar(private val binding: ExoPlayerControlViewBinding) {
    private val resources = binding.root.resources
    private var isAudiobook = false

    /** In layout order, left to right. */
    private val buttons: List<View> = with(binding) {
        listOf(
            lockScreenButton,
            audioStreamsButton,
            subtitlesButton,
            speedButton,
            qualityButton,
            decoderButton,
            infoButton,
            sleepTimerButton,
            bookmarksButton,
        )
    }

    init {
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> spreadButtons() }
    }

    fun update(isAudiobook: Boolean, audioTrackCount: Int) {
        this.isAudiobook = isAudiobook
        binding.infoButton.isVisible = !isAudiobook
        binding.decoderButton.isVisible = !isAudiobook
        binding.fullscreenSwitcher.isVisible = !isAudiobook
        binding.audioStreamsButton.isVisible = !isAudiobook || audioTrackCount > 1

        val size = resources.getDimensionPixelSize(
            if (isAudiobook) R.dimen.audiobook_bottom_controls_size else R.dimen.exo_bottom_controls_size,
        )
        val padding = resources.getDimensionPixelSize(
            if (isAudiobook) R.dimen.audiobook_bottom_controls_padding else R.dimen.exo_bottom_controls_padding,
        )
        buttons.forEach { button ->
            button.updateLayoutParams {
                width = size
                height = size
            }
            if (button is ImageView) {
                button.scaleType = ImageView.ScaleType.FIT_CENTER
                button.updatePadding(padding, padding, padding, padding)
            }
        }
        binding.speedButton.textSize = if (isAudiobook) AUDIOBOOK_SPEED_TEXT_SP else SPEED_TEXT_SP
        spreadButtons()
    }

    /** Gives the visible buttons equal gaps for audiobooks, or puts back the default spacing. */
    private fun spreadButtons() {
        val width = binding.root.width
        if (width == 0) return
        val visible = buttons.filter { it.isVisible }
        val defaultMargin = resources.getDimensionPixelSize(R.dimen.exo_bottom_controls_margin)
        val gap = if (isAudiobook && visible.isNotEmpty()) {
            ((width - visible.sumOf { it.layoutParams.width }) / (visible.size + 1)).coerceAtLeast(0)
        } else {
            0
        }
        buttons.forEach { button ->
            val margin = when {
                isAudiobook -> if (button.isVisible) gap else 0
                button === binding.lockScreenButton -> defaultMargin
                else -> 0
            }
            val params = button.layoutParams as ConstraintLayout.LayoutParams
            if (params.marginStart != margin) {
                // Changing margins mid-layout needs a fresh pass, so post it
                button.post { button.updateLayoutParams<ConstraintLayout.LayoutParams> { marginStart = margin } }
            }
        }
    }

    private companion object {
        const val SPEED_TEXT_SP = 14f
        const val AUDIOBOOK_SPEED_TEXT_SP = 17f
    }
}
