package org.jellyfin.mobile.player.ui

import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The player controls the clip list and editor need. */
interface AudiobookClipControls {
    fun seek(position: Duration)
    fun playClip(start: Duration, end: Duration)
    fun pause()

    /** Goes back to [position] after previewing a clip, playing again if it was [playing] before. */
    fun resume(position: Duration, playing: Boolean)
    fun position(): Duration
    fun isPlaying(): Boolean
    fun bookDuration(): Duration
}

/** Plays clips of the audiobook in [player], stopping at the end of each. */
class AudiobookClipPlayback(private val player: () -> ExoPlayer?) : AudiobookClipControls {
    private var clipStop: PlayerMessage? = null

    override fun seek(position: Duration) {
        cancelClipStop()
        player()?.seekTo(position.inWholeMilliseconds)
    }

    override fun playClip(start: Duration, end: Duration) {
        val player = player() ?: return
        cancelClipStop()
        player.seekTo(start.inWholeMilliseconds)
        player.play()
        clipStop = player.createMessage { _, _ -> player.pause() }
            .setLooper(Looper.getMainLooper())
            .setPosition(end.inWholeMilliseconds)
            .setDeleteAfterDelivery(true)
            .send()
    }

    override fun pause() {
        player()?.pause()
    }

    override fun resume(position: Duration, playing: Boolean) {
        seek(position)
        val player = player() ?: return
        if (playing) player.play() else player.pause()
    }

    override fun position(): Duration = player()?.currentPosition?.milliseconds ?: Duration.ZERO

    override fun isPlaying(): Boolean = player()?.isPlaying == true

    override fun bookDuration(): Duration = player()?.duration?.takeIf { it > 0 }?.milliseconds ?: Duration.INFINITE

    /** Forgets the pending pause at a clip's end, so it can't stop playback after the listener moves on. */
    private fun cancelClipStop() {
        clipStop?.cancel()
        clipStop = null
    }
}
