package org.jellyfin.mobile.player.ui

import android.text.format.DateUtils
import android.view.Menu
import android.widget.PopupMenu
import androidx.core.view.isVisible
import org.jellyfin.mobile.R
import org.jellyfin.mobile.databinding.ExoPlayerControlViewBinding
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.sdk.model.api.ChapterInfo
import org.jellyfin.sdk.model.extensions.ticks
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shows the current chapter of an audiobook with its own progress, the time left in the chapter and
 * the book at the current speed, and a menu to jump to any chapter.
 */
class AudiobookChapterHelper(
    private val binding: ExoPlayerControlViewBinding,
    private val onSeek: (Duration) -> Unit,
) {
    private var chapters: List<ChapterInfo> = emptyList()
    private var runTime: Duration = Duration.ZERO
    private var chapterMenu: PopupMenu? = null

    init {
        binding.chaptersButton.setOnClickListener { chapterMenu?.show() }
    }

    fun onMediaSourceChanged(mediaSource: JellyfinMediaSource) {
        chapters = when {
            mediaSource.isAudiobook -> mediaSource.item?.chapters.orEmpty().sortedBy { it.startPositionTicks }
            else -> emptyList()
        }
        runTime = mediaSource.runTime

        val hasChapters = chapters.isNotEmpty()
        binding.audiobookChapterPanel.isVisible = hasChapters
        binding.chaptersButton.isVisible = hasChapters
        chapterMenu = if (hasChapters) createChapterMenu() else null
    }

    /**
     * Refreshes the chapter panel for the playback [position] at the given playback [speed].
     */
    fun update(position: Duration, speed: Float) {
        if (chapters.isEmpty()) return
        val context = binding.root.context

        val index = chapters.indexOfLast { it.startPositionTicks.ticks <= position }.coerceAtLeast(0)
        val chapterStart = chapters[index].startPositionTicks.ticks
        val chapterEnd = chapters.getOrNull(index + 1)?.startPositionTicks?.ticks ?: runTime
        val chapterLength = chapterEnd - chapterStart

        binding.audiobookChapterTitle.text = context.getString(
            R.string.player_chapter_title,
            index + 1,
            chapters.size,
            chapterName(index),
        )
        binding.audiobookChapterProgress.progress = when {
            chapterLength > Duration.ZERO -> ((position - chapterStart) / chapterLength * PROGRESS_MAX).toInt()
            else -> 0
        }

        val speedFactor = speed.takeIf { it > 0f } ?: 1f
        val chapterLeft = formatDuration((chapterEnd - position) / speedFactor.toDouble())
        val bookLeft = formatDuration((runTime - position) / speedFactor.toDouble())
        binding.audiobookTimeLeft.text = when (speedFactor) {
            1f -> context.getString(R.string.player_time_left, chapterLeft, bookLeft)
            else -> {
                val formattedSpeed = String.format(Locale.US, "%.2f", speedFactor).trimEnd('0').trimEnd('.')
                context.getString(R.string.player_time_left_at_speed, chapterLeft, bookLeft, formattedSpeed)
            }
        }

        // The menu is a single-choice group, so checking one item unchecks the others
        chapterMenu?.menu?.findItem(index)?.isChecked = true
    }

    private fun createChapterMenu() = PopupMenu(binding.root.context, binding.chaptersButton).apply {
        chapters.forEachIndexed { index, chapter ->
            val start = formatDuration(chapter.startPositionTicks.ticks)
            menu.add(Menu.NONE, index, index, "$start  ${chapterName(index)}")
        }
        menu.setGroupCheckable(Menu.NONE, true, true)
        setOnMenuItemClickListener { item ->
            onSeek(chapters[item.itemId].startPositionTicks.ticks)
            true
        }
    }

    /**
     * The chapter's own title, or "Chapter N" when the title is missing or just a number.
     */
    private fun chapterName(index: Int): String {
        val name = chapters[index].name?.trim()
        return when {
            name.isNullOrEmpty() || name.all(Char::isDigit) -> binding.root.context.getString(
                R.string.player_chapter_number,
                index + 1,
            )
            else -> name
        }
    }

    private fun formatDuration(duration: Duration): String =
        DateUtils.formatElapsedTime(duration.coerceAtLeast(Duration.ZERO).inWholeSeconds)

    companion object {
        private const val PROGRESS_MAX = 1000

        /** How often the chapter panel is refreshed while it is visible. */
        val UPDATE_INTERVAL = 1000.milliseconds
    }
}
