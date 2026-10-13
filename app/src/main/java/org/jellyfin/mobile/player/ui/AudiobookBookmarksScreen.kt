package org.jellyfin.mobile.player.ui

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.ViewGroup
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
    interface Actions : AudiobookClipControls {
        fun addClip(onAdded: () -> Unit)
        fun shareClip(start: Duration, end: Duration)
    }

    /** The audiobook the clips belong to, with its [chapters] sorted by start position. */
    class Book(val itemId: String, val title: String?, val chapters: List<ChapterInfo>) {
        private fun chapterIndex(position: Duration) = chapters.indexOfLast { it.startPositionTicks.ticks <= position }

        fun chapterStart(position: Duration): Duration? =
            chapters.getOrNull(chapterIndex(position))?.startPositionTicks?.ticks

        /** "Chapter 4 of 77 · Name", without the name when it is empty or only a number. */
        fun chapterLabel(context: Context, position: Duration): String? {
            val index = chapterIndex(position)
            val name = chapters.getOrNull(index)?.name?.trim() ?: return null
            return if (name.isEmpty() || name.all(Char::isDigit)) {
                context.getString(R.string.bookmark_chapter_unnamed, index + 1, chapters.size)
            } else {
                context.getString(R.string.bookmark_chapter, index + 1, chapters.size, name)
            }
        }
    }

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
        val chapterStart = book.chapterStart(position)
        bookmarkChapter.text = book.chapterLabel(context, position)
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
        bookmarkName.text = bookmark.name
        bookmarkName.isVisible = bookmark.name != null
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
                menu.add(Menu.NONE, EDIT, Menu.NONE, R.string.clip_edit)
                menu.add(Menu.NONE, DELETE, Menu.NONE, R.string.bookmark_delete_one)
                setOnMenuItemClickListener { item ->
                    if (item.itemId == EDIT) edit(bookmark) else confirmDelete(bookmark)
                    true
                }
            }.show()
        }
        root.setOnClickListener { edit(bookmark) }
    }

    private fun edit(bookmark: BookmarkEntity) {
        AudiobookClipEditor(context, audiobookDao, scope, book, actions, bookmark).show(onDone = ::reload)
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
        const val EDIT = 1
        const val DELETE = 2
        const val SORT_GROUP = 1

        /** Kept while the app runs, so reopening the list keeps the chosen order. */
        var sortOrder = SortOrder.NEWEST
    }
}
