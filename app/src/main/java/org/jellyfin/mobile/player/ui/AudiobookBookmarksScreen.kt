package org.jellyfin.mobile.player.ui

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.audiobook.AudiobookDao
import org.jellyfin.mobile.data.audiobook.BookmarkEntity
import org.jellyfin.mobile.databinding.DialogAudiobookBookmarksBinding
import org.jellyfin.mobile.databinding.ItemAudiobookBookmarkBinding
import org.jellyfin.sdk.model.api.ChapterInfo
import org.jellyfin.sdk.model.extensions.ticks
import java.util.Date
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Full-screen, scrollable list of an audiobook's clips and notes, like Audible's. Each clip shows when it was
 * made, its chapter, its range in the chapter and in the book, and its note, with buttons to play it, share it,
 * play on from it, edit it or delete it.
 */
class AudiobookBookmarksScreen(
    private val context: Context,
    private val audiobookDao: AudiobookDao,
    private val scope: CoroutineScope,
    private val book: Book,
    private val actions: Actions,
) {
    /** What the list's buttons do, provided by the player. */
    interface Actions {
        fun addClip(onAdded: () -> Unit)
        fun seek(position: Duration)
        fun playClip(start: Duration, end: Duration)
        fun shareClip(start: Duration, end: Duration)
    }

    /** The audiobook the clips belong to, with its [chapters] sorted by start position. */
    class Book(val itemId: String, val title: String?, val chapters: List<ChapterInfo>)

    private val dialog = AppCompatDialog(context, R.style.AppTheme_FullScreenDialog)
    private val binding = DialogAudiobookBookmarksBinding.inflate(LayoutInflater.from(context))
    private var bookmarks: List<BookmarkEntity> = emptyList()

    fun show() {
        binding.bookmarksTitle.text = book.title ?: context.getString(R.string.bookmarks)
        binding.bookmarksClose.setOnClickListener { dialog.dismiss() }
        binding.bookmarksSort.setOnClickListener { showSortMenu() }
        binding.bookmarksAdd.setOnClickListener { actions.addClip(onAdded = ::reload) }
        dialog.setContentView(binding.root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.show()
        reload()
    }

    private fun reload() {
        scope.launch {
            bookmarks = audiobookDao.getBookmarks(book.itemId)
            render()
        }
    }

    private fun render() {
        binding.bookmarksEmpty.isVisible = bookmarks.isEmpty()
        binding.bookmarksList.removeAllViews()
        val sorted = when (sortOrder) {
            SortOrder.NEWEST -> bookmarks.sortedByDescending { it.createdAt }
            SortOrder.OLDEST -> bookmarks.sortedBy { it.createdAt }
            SortOrder.BOOK -> bookmarks.sortedBy { it.positionMs }
        }
        val inflater = LayoutInflater.from(context)
        sorted.forEach { bookmark ->
            ItemAudiobookBookmarkBinding.inflate(inflater, binding.bookmarksList, true).bind(bookmark)
        }
    }

    private fun ItemAudiobookBookmarkBinding.bind(bookmark: BookmarkEntity) {
        val position = bookmark.positionMs.milliseconds
        val created = Date(bookmark.createdAt)
        bookmarkCreated.text = context.getString(
            R.string.bookmark_created,
            DateFormat.getMediumDateFormat(context).format(created),
            DateFormat.getTimeFormat(context).format(created),
        )
        val end = bookmark.endPositionMs?.milliseconds ?: position
        val index = book.chapters.indexOfLast { it.startPositionTicks.ticks <= position }
        val chapterStart = book.chapters.getOrNull(index)?.startPositionTicks?.ticks
        bookmarkChapter.text = if (chapterStart != null) chapterLabel(index) else null
        bookmarkChapter.isVisible = chapterStart != null
        bookmarkTimes.text = if (chapterStart != null) {
            context.getString(
                R.string.clip_times,
                format(position - chapterStart),
                format(end - chapterStart),
                format(position),
            )
        } else {
            context.getString(R.string.clip_times_book, format(position), format(end))
        }
        bookmarkNote.text = bookmark.note
        bookmarkNote.isVisible = bookmark.note != null
        bookmarkPlay.setOnClickListener {
            actions.seek(position)
            dialog.dismiss()
        }
        bookmarkClipPlay.setOnClickListener { actions.playClip(position, end) }
        bookmarkShare.setOnClickListener { actions.shareClip(position, end) }
        bookmarkMore.setOnClickListener {
            PopupMenu(context, bookmarkMore).apply {
                menu.add(Menu.NONE, EDIT_NOTE, Menu.NONE, R.string.bookmark_edit_note)
                menu.add(Menu.NONE, CLIP_LENGTH, Menu.NONE, R.string.clip_length)
                menu.add(Menu.NONE, DELETE, Menu.NONE, R.string.bookmark_delete_one)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        EDIT_NOTE -> editNote(bookmark)
                        CLIP_LENGTH -> chooseClipLength(bookmark, end)
                        else -> confirmDelete(bookmark)
                    }
                    true
                }
            }.show()
        }
    }

    /** Changes how much a clip covers, keeping where it ends. */
    private fun chooseClipLength(bookmark: BookmarkEntity, end: Duration) {
        val current = (end - bookmark.positionMs.milliseconds).inWholeSeconds.toInt()
        val labels = CLIP_LENGTHS.map { context.getString(R.string.clip_length_seconds, it) }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.clip_length)
            .setSingleChoiceItems(labels, CLIP_LENGTHS.indexOf(current)) { dialog, which ->
                val start = (end - CLIP_LENGTHS[which].seconds).coerceAtLeast(Duration.ZERO)
                scope.launch {
                    audiobookDao.updateClipStart(bookmark.id, start.inWholeMilliseconds)
                    reload()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chapterLabel(index: Int): String {
        val name = book.chapters[index].name?.trim()
        return if (name.isNullOrEmpty() || name.all(Char::isDigit)) {
            context.getString(R.string.bookmark_chapter_unnamed, index + 1, book.chapters.size)
        } else {
            context.getString(R.string.bookmark_chapter, index + 1, book.chapters.size, name)
        }
    }

    private fun showSortMenu() {
        PopupMenu(context, binding.bookmarksSort).apply {
            SortOrder.entries.forEach { order ->
                menu.add(SORT_GROUP, order.ordinal, Menu.NONE, order.label).isChecked = order == sortOrder
            }
            menu.setGroupCheckable(SORT_GROUP, true, true)
            setOnMenuItemClickListener { item ->
                sortOrder = SortOrder.entries[item.itemId]
                render()
                true
            }
        }.show()
    }

    private fun editNote(bookmark: BookmarkEntity) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.bookmark_note_hint)
            setText(bookmark.note)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.bookmark_edit_note)
            .setView(input)
            .setPositiveButton(R.string.bookmark_save) { _, _ ->
                val note = input.text.toString().trim().ifEmpty { null }
                scope.launch {
                    audiobookDao.updateBookmarkNote(bookmark.id, note)
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(bookmark: BookmarkEntity) {
        AlertDialog.Builder(context)
            .setMessage(R.string.bookmark_delete_confirm)
            .setPositiveButton(R.string.bookmark_delete_one) { _, _ ->
                scope.launch {
                    audiobookDao.deleteBookmark(bookmark.id)
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun format(duration: Duration): String = DateUtils.formatElapsedTime(duration.inWholeSeconds)

    private enum class SortOrder(val label: Int) {
        NEWEST(R.string.bookmarks_sort_newest),
        OLDEST(R.string.bookmarks_sort_oldest),
        BOOK(R.string.bookmarks_sort_book_order),
    }

    private companion object {
        const val EDIT_NOTE = 1
        const val DELETE = 2
        const val CLIP_LENGTH = 3
        val CLIP_LENGTHS = listOf(15, 30, 45, 60, 90, 120)
        const val SORT_GROUP = 1

        /** Kept while the app runs, so reopening the list keeps the chosen order. */
        var sortOrder = SortOrder.NEWEST
    }
}
