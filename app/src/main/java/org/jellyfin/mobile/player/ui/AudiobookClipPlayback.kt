package org.jellyfin.mobile.player.ui

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.source.MediaSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The player controls the clip list and editor need. */
interface AudiobookClipControls {
    /** Moves the book itself to [position]. */
    fun seek(position: Duration)

    /** Plays [start] to [end] in the preview player, pausing the book where it is. */
    fun playClip(start: Duration, end: Duration)

    /** Moves the preview to [position] without playing it. */
    fun previewSeek(position: Duration)
    fun pausePreview()

    /** Stops previewing, and plays the book again if it was playing when the preview started. */
    fun stopPreview()
    fun previewPosition(): Duration
    fun isPreviewPlaying(): Boolean
    fun bookDuration(): Duration
}

/**
 * Plays clips of the audiobook in a second, hidden player, so hearing a clip never moves the book's own
 * position. The preview reuses the book's media item and the app's media source factory, so it works for
 * downloads and streams alike.
 */
class AudiobookClipPlayback(
    private val context: Context,
    private val mediaSourceFactory: MediaSource.Factory,
    private val player: () -> ExoPlayer?,
) : AudiobookClipControls {
    private var preview: ExoPlayer? = null
    private var clipStop: PlayerMessage? = null
    private var bookWasPlaying = false

    override fun seek(position: Duration) {
        stopPreview()
        player()?.seekTo(position.inWholeMilliseconds)
    }

    override fun playClip(start: Duration, end: Duration) {
        val preview = preparePreview() ?: return
        cancelClipStop()
        preview.seekTo(start.inWholeMilliseconds)
        preview.play()
        clipStop = preview.createMessage { _, _ -> preview.pause() }
            .setLooper(Looper.getMainLooper())
            .setPosition(end.inWholeMilliseconds)
            .setDeleteAfterDelivery(true)
            .send()
    }

    override fun previewSeek(position: Duration) {
        cancelClipStop()
        preparePreview()?.seekTo(position.inWholeMilliseconds)
    }

    override fun pausePreview() {
        preview?.pause()
    }

    override fun stopPreview() {
        cancelClipStop()
        preview?.pause()
        if (bookWasPlaying) player()?.play()
        bookWasPlaying = false
    }

    override fun previewPosition(): Duration = preview?.currentPosition?.milliseconds ?: Duration.ZERO

    override fun isPreviewPlaying(): Boolean = preview?.isPlaying == true

    override fun bookDuration(): Duration = player()?.duration?.takeIf { it > 0 }?.milliseconds ?: Duration.INFINITE

    /** Frees the preview player; it is created again if needed. */
    fun release() {
        stopPreview()
        preview?.release()
        preview = null
    }

    /** Pauses the book and loads it into the preview player, at the book's speed. */
    private fun preparePreview(): ExoPlayer? {
        val book = player() ?: return null
        val item = book.currentMediaItem ?: return null
        if (book.isPlaying) {
            bookWasPlaying = true
            book.pause()
        }
        val preview = preview ?: ExoPlayer.Builder(context, mediaSourceFactory).build().apply {
            val attributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build()
            setAudioAttributes(attributes, false)
        }.also { preview = it }
        if (preview.currentMediaItem?.mediaId != item.mediaId || preview.mediaItemCount == 0) {
            preview.setMediaItem(item)
            preview.prepare()
        }
        preview.playbackParameters = book.playbackParameters
        return preview
    }

    /** Forgets the pending pause at a clip's end, so it can't stop the preview after the listener moves on. */
    private fun cancelClipStop() {
        clipStop?.cancel()
        clipStop = null
    }
}
