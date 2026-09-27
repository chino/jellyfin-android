package org.jellyfin.mobile.player.ui

import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.Menu
import android.widget.EditText
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.audiobook.AudiobookDao
import org.jellyfin.mobile.data.audiobook.BookmarkEntity
import org.jellyfin.mobile.data.audiobook.ListeningSessionEntity
import org.jellyfin.mobile.databinding.ExoPlayerControlViewBinding
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import java.util.Date
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Bookmarks and listening history for the audiobook being played, reached from one button.
 */
class AudiobookBookmarksHelper(
    private val binding: ExoPlayerControlViewBinding,
    private val audiobookDao: AudiobookDao,
    private val scope: CoroutineScope,
    private val currentPosition: () -> Duration?,
    private val onSeek: (Duration) -> Unit,
) {
    private val context get() = binding.root.context
    private var itemId: String? = null

    init {
        binding.bookmarksButton.setOnClickListener { showMenu() }
    }

    fun onMediaSourceChanged(mediaSource: JellyfinMediaSource) {
        itemId = mediaSource.itemId.toString().takeIf { mediaSource.isAudiobook }
        binding.bookmarksButton.isVisible = mediaSource.isAudiobook
    }

    private fun showMenu() {
        val id = itemId ?: return
        scope.launch {
            val bookmarks = audiobookDao.getBookmarks(id)
            PopupMenu(context, binding.bookmarksButton).apply {
                val position = currentPosition() ?: Duration.ZERO
                menu.add(Menu.NONE, ADD_BOOKMARK, Menu.NONE, context.getString(R.string.bookmark_add, format(position)))
                bookmarks.forEachIndexed { index, bookmark ->
                    menu.add(Menu.NONE, FIRST_BOOKMARK + index, Menu.NONE, bookmarkLabel(bookmark))
                }
                if (bookmarks.isNotEmpty()) {
                    menu.add(Menu.NONE, DELETE_BOOKMARK, Menu.NONE, context.getString(R.string.bookmark_delete))
                }
                menu.add(Menu.NONE, HISTORY, Menu.NONE, context.getString(R.string.listening_history))
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        ADD_BOOKMARK -> askForNote(id, position)
                        DELETE_BOOKMARK -> chooseBookmarkToDelete(bookmarks)
                        HISTORY -> showHistory(id)
                        else -> onSeek(bookmarks[item.itemId - FIRST_BOOKMARK].positionMs.milliseconds)
                    }
                    true
                }
            }.show()
        }
    }

    private fun askForNote(itemId: String, position: Duration) {
        val input = EditText(context).apply { hint = context.getString(R.string.bookmark_note_hint) }
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.bookmark_add, format(position)))
            .setView(input)
            .setPositiveButton(R.string.bookmark_save) { _, _ ->
                val note = input.text.toString().trim().ifEmpty { null }
                scope.launch {
                    audiobookDao.insertBookmark(
                        BookmarkEntity(itemId = itemId, positionMs = position.inWholeMilliseconds, note = note),
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseBookmarkToDelete(bookmarks: List<BookmarkEntity>) {
        AlertDialog.Builder(context)
            .setTitle(R.string.bookmark_delete)
            .setItems(bookmarks.map(::bookmarkLabel).toTypedArray()) { _, which ->
                scope.launch { audiobookDao.deleteBookmark(bookmarks[which].id) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showHistory(itemId: String) {
        scope.launch {
            val sessions = audiobookDao.getListeningSessions(itemId, HISTORY_LIMIT)
            val builder = AlertDialog.Builder(context).setTitle(R.string.listening_history)
            if (sessions.isEmpty()) {
                builder.setMessage(R.string.listening_history_empty)
            } else {
                builder.setItems(sessions.map(::sessionLabel).toTypedArray()) { _, which ->
                    onSeek(sessions[which].endPositionMs.milliseconds)
                }
            }
            builder.setNegativeButton(R.string.listening_history_close, null).show()
        }
    }

    private fun bookmarkLabel(bookmark: BookmarkEntity): String {
        val position = format(bookmark.positionMs.milliseconds)
        return bookmark.note?.let { "$position  $it" } ?: position
    }

    private fun sessionLabel(session: ListeningSessionEntity): String {
        val date = Date(session.startedAt)
        val whenText = "${DateFormat.getMediumDateFormat(context).format(date)} " +
            DateFormat.getTimeFormat(context).format(date)
        return context.getString(
            R.string.listening_session,
            whenText,
            format(session.startPositionMs.milliseconds),
            format(session.endPositionMs.milliseconds),
        )
    }

    private fun format(duration: Duration): String = DateUtils.formatElapsedTime(duration.inWholeSeconds)

    companion object {
        private const val ADD_BOOKMARK = 1
        private const val DELETE_BOOKMARK = 2
        private const val HISTORY = 3
        private const val FIRST_BOOKMARK = 100
        private const val HISTORY_LIMIT = 50
    }
}
