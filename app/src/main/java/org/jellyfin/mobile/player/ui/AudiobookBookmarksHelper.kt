package org.jellyfin.mobile.player.ui

import android.content.Intent
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.Menu
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.audiobook.AudiobookDao
import org.jellyfin.mobile.data.audiobook.BookmarkEntity
import org.jellyfin.mobile.data.audiobook.ListeningSessionEntity
import org.jellyfin.mobile.databinding.ExoPlayerControlViewBinding
import org.jellyfin.mobile.player.AudiobookClipExporter
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.sdk.model.api.ChapterInfo
import java.util.Date
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

    private fun showMenu() {
        val id = itemId ?: return
        scope.launch {
            val bookmarkCount = audiobookDao.getBookmarks(id.toString()).size
            PopupMenu(context, binding.bookmarksButton).apply {
                val position = bookPosition
                val clipLabel = context.getString(R.string.clip_add, Constants.AUDIOBOOK_CLIP_SECONDS)
                menu.add(Menu.NONE, ADD_CLIP, Menu.NONE, clipLabel)
                menu.add(Menu.NONE, BOOKMARKS, Menu.NONE, context.getString(R.string.bookmarks_count, bookmarkCount))
                menu.add(Menu.NONE, HISTORY, Menu.NONE, context.getString(R.string.listening_history))
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        ADD_CLIP -> saveClip(id, position) { clip -> showClipSaved(clip) }
                        BOOKMARKS -> showBookmarks(id)
                        HISTORY -> showHistory(id)
                    }
                    true
                }
            }.show()
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
            val uri = clipExporter.export(id, title, start, end)
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
            val builder = AlertDialog.Builder(context).setTitle(R.string.listening_history)
            if (sessions.isEmpty()) {
                builder.setMessage(R.string.listening_history_empty)
            } else {
                builder.setItems(sessions.map(::sessionLabel).toTypedArray()) { _, which ->
                    seek(sessions[which].endPositionMs.milliseconds)
                }
            }
            builder.setNegativeButton(R.string.listening_history_close, null).show()
        }
    }

    private fun sessionLabel(session: ListeningSessionEntity): String {
        val date = Date(session.startedAt)
        val whenText = "${DateFormat.getMediumDateFormat(context).format(date)} " +
            DateFormat.getTimeFormat(context).format(date)
        val end = session.endPositionMs.milliseconds
        val book = itemId?.let(::book)
        val chapter = book?.chapterLabel(context, end)
        val chapterStart = book?.chapterStart(end)
        val times = if (chapterStart != null) {
            context.getString(
                R.string.listening_session_chapter_times,
                format(end - chapterStart),
                format(session.startPositionMs.milliseconds),
                format(end),
            )
        } else {
            context.getString(
                R.string.listening_session_times,
                format(session.startPositionMs.milliseconds),
                format(end),
            )
        }
        return listOfNotNull(whenText, chapter, times).joinToString("\n")
    }

    private fun format(duration: Duration): String = DateUtils.formatElapsedTime(duration.inWholeSeconds)

    companion object {
        private const val ADD_CLIP = 2
        private const val BOOKMARKS = 3
        private const val HISTORY = 4
        private const val HISTORY_LIMIT = 50
        private const val CLIP_SAVED_MS = 4000L
    }
}
