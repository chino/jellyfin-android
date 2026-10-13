package org.jellyfin.mobile.player.ui

import android.content.Intent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.isVisible
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.audiobook.AudiobookDao
import org.jellyfin.mobile.data.audiobook.BookmarkEntity
import org.jellyfin.mobile.databinding.DialogAudiobookClipMenuBinding
import org.jellyfin.mobile.databinding.ExoPlayerControlViewBinding
import org.jellyfin.mobile.player.AudiobookClipExporter
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.sdk.model.api.ChapterInfo
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Clips (with notes) and listening history for the audiobook being played, reached from one button.
 */
class AudiobookBookmarksHelper(
    private val binding: ExoPlayerControlViewBinding,
    private val audiobookDao: AudiobookDao,
    private val clipExporter: AudiobookClipExporter,
    private val clipPlayback: AudiobookClipPlayback,
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
) : AudiobookBookmarksScreen.Actions, AudiobookClipControls by clipPlayback {
    private val hideClipSaved = Runnable { binding.clipSavedBar.isVisible = false }
    private val bookPosition: Duration
        get() = player()?.currentPosition?.milliseconds ?: Duration.ZERO

    private val context get() = binding.root.context
    private var itemId: UUID? = null
    private var title: String? = null
    private var chapters: List<ChapterInfo> = emptyList()

    init {
        binding.bookmarksButton.setOnClickListener { showMenu() }
        binding.clipSavedBar.setOnTouchListener(
            SwipeDismissTouchListener { bar ->
                bar.removeCallbacks(hideClipSaved)
                bar.isVisible = false
            },
        )
    }

    /** Frees the clip preview player. */
    fun release() {
        clipPlayback.release()
    }

    fun onMediaSourceChanged(mediaSource: JellyfinMediaSource) {
        itemId = mediaSource.itemId.takeIf { mediaSource.isAudiobook }
        title = mediaSource.item?.name
        chapters = mediaSource.item?.chapters.orEmpty().sortedBy { it.startPositionTicks }
        binding.bookmarksButton.isVisible = mediaSource.isAudiobook
    }

    /** A sheet of big, easy-to-press choices that slides up from the bottom. */
    private fun showMenu() {
        val id = itemId ?: return
        val position = bookPosition
        scope.launch {
            val clipCount = audiobookDao.getBookmarks(id.toString()).size
            val dialog = AppCompatDialog(context, R.style.AppTheme_BottomMenu)
            val menu = DialogAudiobookClipMenuBinding.inflate(LayoutInflater.from(context))
            menu.menuAddClip.text = context.getString(R.string.clip_add, Constants.AUDIOBOOK_CLIP_SECONDS)
            menu.menuClips.text = context.getString(R.string.bookmarks_count, clipCount)
            menu.menuAddClip.setOnClickListener {
                dialog.dismiss()
                saveClip(id, position) { clip -> showClipSaved(clip) }
            }
            menu.menuClips.setOnClickListener {
                dialog.dismiss()
                showBookmarks(id)
            }
            menu.menuHistory.setOnClickListener {
                dialog.dismiss()
                showHistory(id)
            }
            dialog.setContentView(menu.root)
            dialog.window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM)
            }
            dialog.show()
        }
    }

    private fun showBookmarks(itemId: UUID) {
        AudiobookBookmarksScreen(
            context = context,
            audiobookDao = audiobookDao,
            scope = scope,
            book = book(itemId),
            actions = this,
        ).show()
    }

    private fun book(itemId: UUID) = AudiobookBookmarksScreen.Book(itemId.toString(), title, chapters)

    override fun addClip(onAdded: () -> Unit) {
        val id = itemId ?: return
        saveClip(id, bookPosition) { onAdded() }
    }

    override fun shareClip(start: Duration, end: Duration) {
        val id = itemId ?: return
        scope.launch {
            val uri = clipExporter.export(id, clipFileName(id, start), start, end)
            if (uri == null) {
                Toast.makeText(context, R.string.clip_share_unavailable, Toast.LENGTH_LONG).show()
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, context.getString(R.string.clip_share)))
        }
    }

    /** Saves a clip of the [Constants.AUDIOBOOK_CLIP_SECONDS] leading up to [position] right away, like a bookmark. */
    private fun saveClip(itemId: UUID, position: Duration, onSaved: (BookmarkEntity) -> Unit) {
        val start = (position - Constants.AUDIOBOOK_CLIP_SECONDS.seconds).coerceAtLeast(Duration.ZERO)
        val clip = BookmarkEntity(
            itemId = itemId.toString(),
            positionMs = start.inWholeMilliseconds,
            note = null,
            endPositionMs = position.inWholeMilliseconds,
        )
        scope.launch {
            val id = audiobookDao.insertBookmark(clip)
            onSaved(clip.copy(id = id))
        }
    }

    /**
     * "Title - Chapter 4 We've Lost - 12m30s": where the clip starts, as time into its chapter, or into the
     * book when there are no chapters.
     */
    private fun clipFileName(itemId: UUID, start: Duration): String {
        val book = book(itemId)
        val chapter = book.chapterShortLabel(context, start)
        val offset = start - (book.chapterStart(start) ?: Duration.ZERO)
        val hms = offset.toComponents { hours, minutes, seconds, _ ->
            when {
                hours > 0 -> String.format(Locale.ROOT, "%dh%02dm%02ds", hours, minutes, seconds)
                minutes > 0 -> String.format(Locale.ROOT, "%dm%02ds", minutes, seconds)
                else -> "${seconds}s"
            }
        }
        return listOfNotNull(title ?: context.getString(R.string.clip_play), chapter, hms).joinToString(" - ")
    }

    /** Shows "Clip saved" with an Edit button for a few seconds. */
    private fun showClipSaved(clip: BookmarkEntity) {
        val bar = binding.clipSavedBar
        bar.removeCallbacks(hideClipSaved)
        bar.isVisible = true
        binding.clipSavedEdit.setOnClickListener {
            bar.isVisible = false
            val id = itemId ?: return@setOnClickListener
            AudiobookClipEditor(context, audiobookDao, scope, book(id), this, clip).show {}
        }
        bar.postDelayed(hideClipSaved, CLIP_SAVED_MS)
    }

    private fun showHistory(itemId: UUID) {
        scope.launch {
            val sessions = audiobookDao.getListeningSessions(itemId.toString(), HISTORY_LIMIT)
            AudiobookHistoryScreen(context, book(itemId), bookDuration(), ::seek) {
                scope.launch { audiobookDao.deleteListeningSessions(itemId.toString()) }
            }.show(sessions)
        }
    }

    companion object {
        private const val HISTORY_LIMIT = 50
        private const val CLIP_SAVED_MS = 4000L
    }
}
