package org.jellyfin.mobile.player.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.audiobook.AudiobookDao
import org.jellyfin.mobile.data.audiobook.BookmarkEntity
import org.jellyfin.mobile.databinding.DialogAudiobookClipEditBinding
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Full-screen editor for one clip, like Audible's: drag the ends of the clip or nudge them a second at a time,
 * play it to hear the result, and give it a name and a note. Previews play in a separate player, so the
 * book keeps its place.
 */
class AudiobookClipEditor(
    private val context: Context,
    private val audiobookDao: AudiobookDao,
    private val scope: CoroutineScope,
    private val book: AudiobookBookmarksScreen.Book,
    private val actions: AudiobookBookmarksScreen.Actions,
    private val clip: BookmarkEntity,
) {
    private var onDone: () -> Unit = {}
    private val dialog = AppCompatDialog(context, R.style.AppTheme_FullScreenDialog)
    private val binding = DialogAudiobookClipEditBinding.inflate(LayoutInflater.from(context))
    private val handler = Handler(Looper.getMainLooper())
    private val bookLength = actions.bookDuration().inWholeMilliseconds

    private var start = clip.positionMs
    private var end = clip.endPositionMs ?: clip.positionMs

    private val ticker = object : Runnable {
        override fun run() {
            binding.clipRange.playhead = actions.previewPosition().inWholeMilliseconds
            val playing = actions.isPreviewPlaying()
            val icon = if (playing) R.drawable.ic_pause_black_42dp else R.drawable.ic_play_black_42dp
            binding.clipPlay.setImageResource(icon)
            val label = if (playing) R.string.clip_pause else R.string.clip_play
            binding.clipPlay.contentDescription = context.getString(label)
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Shows the editor; [onDone] runs once the clip has been saved or deleted. */
    fun show(onDone: () -> Unit) = with(binding) {
        this@AudiobookClipEditor.onDone = onDone
        val isNew = clip.id == 0L
        clipTitle.setText(if (isNew) R.string.clip_new else R.string.clip_edit)
        clipBook.text = book.title
        clipBook.isVisible = book.title != null
        clipName.setText(clip.name)
        clipNote.setText(clip.note)
        updateNameCount()
        clipName.doAfterTextChanged { updateNameCount() }
        clipDelete.isVisible = !isNew

        clipRange.listener = object : ClipRangeView.Listener {
            override fun onRangeChanged(start: Long, end: Long, finished: Boolean) {
                this@AudiobookClipEditor.start = start
                this@AudiobookClipEditor.end = end
                updateTimes()
                if (finished) {
                    recenterIfNearEdge()
                    keepPlayheadInClip()
                }
            }

            override fun onSeek(position: Long, finished: Boolean) {
                if (finished) seekTo(position)
            }
        }
        clipStartEarlier.setOnClickListener { setRange(start - NUDGE_MS, end) }
        clipStartLater.setOnClickListener { setRange(start + NUDGE_MS, end) }
        clipEndEarlier.setOnClickListener { setRange(start, end - NUDGE_MS) }
        clipEndLater.setOnClickListener { setRange(start, end + NUDGE_MS) }
        clipPlay.setOnClickListener { togglePreview() }
        clipClose.setOnClickListener { dialog.dismiss() }
        clipSave.setOnClickListener { save() }
        clipDelete.setOnClickListener { confirmDelete() }

        recenter()
        updateTimes()
        actions.previewSeek(start.milliseconds)
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setOnDismissListener {
            handler.removeCallbacks(ticker)
            actions.stopPreview()
        }
        dialog.show()
        handler.post(ticker)
    }

    private fun setRange(newStart: Long, newEnd: Long) {
        start = newStart.coerceIn(0L, end - MIN_LENGTH_MS)
        end = newEnd.coerceIn(start + MIN_LENGTH_MS, bookLength.coerceAtLeast(start + MIN_LENGTH_MS))
        val range = binding.clipRange
        if (start < range.windowStart || end > range.windowEnd) {
            recenter()
        } else {
            range.setRange(range.windowStart, range.windowEnd, start, end)
            recenterIfNearEdge()
        }
        updateTimes()
        keepPlayheadInClip()
    }

    /**
     * Snaps the preview into the clip after an edit: to the new start if it was before it, or to the new end if
     * it was past it. A playing preview carries on and still stops at the (new) end.
     */
    private fun keepPlayheadInClip() {
        val position = actions.previewPosition().inWholeMilliseconds
        val snapped = position.coerceIn(start, end)
        if (actions.isPreviewPlaying()) {
            actions.playClip(snapped.milliseconds, end.milliseconds)
        } else if (snapped != position) {
            actions.previewSeek(snapped.milliseconds)
        }
    }

    /** Moves playback to [position], carrying on to the clip's end if it is playing inside the clip. */
    private fun seekTo(position: Long) {
        if (actions.isPreviewPlaying() && position in start until end) {
            actions.playClip(position.milliseconds, end.milliseconds)
        } else {
            actions.previewSeek(position.milliseconds)
        }
    }

    /** Shows [CONTEXT_MS] either side of the clip, so a handle can be dragged that far. */
    private fun recenter() {
        val windowStart = (start - CONTEXT_MS).coerceAtLeast(0L)
        val windowEnd = (end + CONTEXT_MS).coerceAtMost(bookLength.coerceAtLeast(end))
        binding.clipRange.setRange(windowStart, windowEnd, start, end)
    }

    private fun recenterIfNearEdge() {
        val range = binding.clipRange
        val nearStart = start - range.windowStart < EDGE_MS && range.windowStart > 0L
        val nearEnd = range.windowEnd - end < EDGE_MS && range.windowEnd < bookLength
        if (nearStart || nearEnd) recenter()
    }

    private fun updateTimes() {
        val position = start.milliseconds
        val chapterStart = book.chapterStart(position)
        binding.clipChapter.text = book.chapterLabel(context, position)
        binding.clipChapter.isVisible = chapterStart != null
        binding.clipTimes.text = if (chapterStart != null) {
            context.getString(
                R.string.clip_times,
                format(position - chapterStart),
                format(end.milliseconds - chapterStart),
                format(position),
            )
        } else {
            context.getString(R.string.clip_times_book, format(position), format(end.milliseconds))
        }
    }

    private fun updateNameCount() {
        binding.clipNameCount.text = context.getString(R.string.clip_name_count, binding.clipName.length(), NAME_LENGTH)
    }

    /** Plays from the playhead when it sits inside the clip, otherwise from the clip's start. */
    private fun togglePreview() {
        if (actions.isPreviewPlaying()) {
            actions.pausePreview()
            return
        }
        val position = actions.previewPosition().inWholeMilliseconds
        val from = if (position in start until end - MIN_LENGTH_MS) position else start
        actions.playClip(from.milliseconds, end.milliseconds)
    }

    private fun save() {
        val name = binding.clipName.text.toString().trim().ifEmpty { null }
        val note = binding.clipNote.text.toString().trim().ifEmpty { null }
        val edited = clip.copy(positionMs = start, endPositionMs = end, name = name, note = note)
        scope.launch {
            if (clip.id == 0L) audiobookDao.insertBookmark(edited) else audiobookDao.updateBookmark(edited)
            onDone()
        }
        dialog.dismiss()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(context)
            .setMessage(R.string.bookmark_delete_confirm)
            .setPositiveButton(R.string.bookmark_delete_one) { _, _ ->
                scope.launch {
                    audiobookDao.deleteBookmark(clip.id)
                    onDone()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun format(duration: Duration): String = DateUtils.formatElapsedTime(duration.inWholeSeconds)

    private companion object {
        const val TICK_MS = 100L
        const val NUDGE_MS = 1000L
        const val MIN_LENGTH_MS = 1000L
        const val CONTEXT_MS = 60_000L
        const val EDGE_MS = 10_000L
        const val NAME_LENGTH = 32
    }
}
