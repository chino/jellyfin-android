package org.jellyfin.mobile.player.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.format.DateUtils
import android.view.Menu
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.SeekBar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.ImageViewCompat
import org.jellyfin.mobile.R
import org.jellyfin.mobile.databinding.ExoPlayerControlViewBinding
import org.jellyfin.mobile.player.SleepTimer
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.sdk.model.api.ChapterInfo
import org.jellyfin.sdk.model.extensions.ticks
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Shows the current chapter of an audiobook with a seek bar for that chapter only, the time into and left in
 * the chapter, the time left in the book at the current speed, and a menu to jump to any chapter.
 * The seek bar for the whole book is hidden while a book has chapters, like in other audiobook apps.
 */
class AudiobookChapterHelper(
    private val binding: ExoPlayerControlViewBinding,
    private val sleepTimer: SleepTimer,
    private val artwork: ImageView?,
    private val onSeek: (Duration) -> Unit,
) {
    private var isAudiobook = false
    private var chapters: List<ChapterInfo> = emptyList()
    private var runTime: Duration = Duration.ZERO
    private var chapterMenu: PopupMenu? = null
    private val sleepTimerMenu = createSleepTimerMenu()
    private val videoPlayTint = ImageViewCompat.getImageTintList(binding.exoPlayPause)

    /** Start and end of the chapter shown, used to turn the seek bar position into a playback position. */
    private var chapterStart: Duration = Duration.ZERO
    private var chapterEnd: Duration = Duration.ZERO
    private var isSeeking = false

    init {
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitArtwork() }
        binding.audiobookChapterTitle.setOnClickListener { chapterMenu?.show() }
        binding.sleepTimerButton.setOnClickListener { sleepTimerMenu.show() }
        binding.audiobookChapterSeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateChapterTimes(positionAt(progress))
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    isSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    isSeeking = false
                    onSeek(positionAt(seekBar.progress))
                }
            },
        )
    }

    fun onMediaSourceChanged(mediaSource: JellyfinMediaSource) {
        chapters = when {
            mediaSource.isAudiobook -> mediaSource.item?.chapters.orEmpty().sortedBy { it.startPositionTicks }
            else -> emptyList()
        }
        runTime = mediaSource.runTime

        val hasChapters = chapters.isNotEmpty()
        binding.audiobookChapterPanel.isVisible = mediaSource.isAudiobook
        binding.audiobookScrim.isVisible = mediaSource.isAudiobook
        binding.audiobookScrimFade.isVisible = mediaSource.isAudiobook
        isAudiobook = mediaSource.isAudiobook
        fitArtwork()
        binding.audiobookChapterTitle.isVisible = hasChapters
        binding.audiobookChapterSeekBar.isVisible = hasChapters
        binding.audiobookChapterElapsed.isVisible = hasChapters
        binding.audiobookChapterRemaining.isVisible = hasChapters
        // The chapter seek bar replaces the one for the whole book; books without chapters keep the latter
        binding.seekBarContainer.isVisible = !hasChapters
        binding.exoPosition.isVisible = !hasChapters
        binding.exoDuration.isVisible = !hasChapters
        binding.sleepTimerButton.isVisible = mediaSource.isAudiobook
        placePlaybackControls(belowSeekBar = mediaSource.isAudiobook)
        chapterMenu = if (hasChapters) createChapterMenu() else null
        if (!mediaSource.isAudiobook) sleepTimer.cancel()
    }

    /**
     * Audiobooks show the play controls in a row below the seek bar, like other audiobook apps, so the cover
     * stays visible. Videos keep them in the middle of the screen.
     */
    /** Fits an audiobook's cover into the space between the toolbar and the controls, so they don't cover it. */
    private fun fitArtwork() {
        val artwork = artwork ?: return
        val top = if (isAudiobook) binding.toolbar.bottom else 0
        val bottom = if (isAudiobook) (binding.root.height - binding.audiobookScrimFade.top).coerceAtLeast(0) else 0
        if (artwork.paddingTop != top || artwork.paddingBottom != bottom) {
            // Padding changes during a layout pass need a fresh one, so post it
            artwork.post { artwork.setPadding(0, top, 0, bottom) }
        }
    }

    private fun placePlaybackControls(belowSeekBar: Boolean) {
        val unset = ConstraintLayout.LayoutParams.UNSET
        val parent = ConstraintLayout.LayoutParams.PARENT_ID
        val margin = binding.root.resources.getDimensionPixelSize(R.dimen.audiobook_controls_margin)
        binding.playPauseContainer.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = if (belowSeekBar) unset else parent
            bottomToBottom = if (belowSeekBar) unset else parent
            bottomToTop = if (belowSeekBar) R.id.controls_barrier else unset
            bottomMargin = if (belowSeekBar) margin else 0
        }
        binding.seekBarContainer.updateLayoutParams<ConstraintLayout.LayoutParams> {
            bottomToTop = if (belowSeekBar) R.id.play_pause_container else R.id.controls_barrier
        }
        // Audiobooks get a solid round play button, like Audible
        binding.exoPlayPause.setBackgroundResource(
            if (belowSeekBar) R.drawable.audiobook_play_background else R.drawable.ripple_background_circular,
        )
        ImageViewCompat.setImageTintList(
            binding.exoPlayPause,
            if (belowSeekBar) ColorStateList.valueOf(Color.BLACK) else videoPlayTint,
        )
    }

    /**
     * Refreshes the chapter panel for the playback [position] at the given playback [speed].
     */
    fun update(position: Duration, speed: Float) {
        updateSleepTimer()
        updateBookTimeLeft(position, speed)
        if (chapters.isEmpty()) return

        val index = chapters.indexOfLast { it.startPositionTicks.ticks <= position }.coerceAtLeast(0)
        chapterStart = chapters[index].startPositionTicks.ticks
        chapterEnd = chapters.getOrNull(index + 1)?.startPositionTicks?.ticks ?: runTime

        binding.audiobookChapterTitle.text = binding.root.context.getString(
            R.string.player_chapter_title,
            index + 1,
            chapters.size,
            chapterName(index),
        )
        // While the user drags the seek bar, it shows where they are dragging rather than the playback position
        if (!isSeeking) {
            val chapterLength = chapterEnd - chapterStart
            binding.audiobookChapterSeekBar.progress = when {
                chapterLength > Duration.ZERO -> ((position - chapterStart) / chapterLength * PROGRESS_MAX).toInt()
                else -> 0
            }
            updateChapterTimes(position)
        }

        // The menu is a single-choice group, so checking one item unchecks the others
        chapterMenu?.menu?.findItem(index)?.isChecked = true
    }

    /** The playback position for the given chapter seek bar [progress]. */
    private fun positionAt(progress: Int): Duration =
        chapterStart + (chapterEnd - chapterStart) * (progress.toDouble() / PROGRESS_MAX)

    /** Time into and left in the chapter, in playback time like the seek bar, not adjusted for the speed. */
    private fun updateChapterTimes(position: Duration) {
        binding.audiobookChapterElapsed.text = formatDuration(position - chapterStart)
        binding.audiobookChapterRemaining.text = binding.root.context.getString(
            R.string.player_chapter_time_remaining,
            formatDuration(chapterEnd - position),
        )
    }

    private fun updateBookTimeLeft(position: Duration, speed: Float) {
        val context = binding.root.context
        val speedFactor = speed.takeIf { it > 0f } ?: 1f
        val bookLeft = formatHoursMinutes((runTime - position) / speedFactor.toDouble())
        binding.audiobookTimeLeft.text = when (speedFactor) {
            1f -> context.getString(R.string.player_book_time_left, bookLeft)
            else -> {
                val formattedSpeed = String.format(Locale.US, "%.2f", speedFactor).trimEnd('0').trimEnd('.')
                context.getString(R.string.player_book_time_left_at_speed, bookLeft, formattedSpeed)
            }
        }
    }

    /** A long time as hours and minutes, for example "7h 43m", rounded up so the last minute doesn't read 0m. */
    private fun formatHoursMinutes(duration: Duration): String {
        val totalMinutes = (duration.coerceAtLeast(Duration.ZERO).inWholeSeconds + SECONDS_PER_MINUTE - 1) /
            SECONDS_PER_MINUTE
        val hours = (totalMinutes / MINUTES_PER_HOUR).toInt()
        val minutes = (totalMinutes % MINUTES_PER_HOUR).toInt()
        val context = binding.root.context
        return when {
            hours > 0 -> context.getString(R.string.player_duration_hours_minutes, hours, minutes)
            else -> context.getString(R.string.player_duration_minutes, minutes)
        }
    }

    private fun updateSleepTimer() {
        val context = binding.root.context
        val text = when (val state = sleepTimer.state.value) {
            SleepTimer.State.Off -> null
            SleepTimer.State.EndOfChapter -> context.getString(R.string.sleep_timer_at_chapter_end)
            is SleepTimer.State.Countdown -> context.getString(
                R.string.sleep_timer_remaining,
                formatDuration(state.remaining),
            )
        }
        binding.audiobookSleepTimer.isVisible = text != null
        binding.audiobookSleepTimer.text = text
    }

    private fun createSleepTimerMenu() = PopupMenu(binding.root.context, binding.sleepTimerButton).apply {
        val context = binding.root.context
        menu.add(Menu.NONE, SLEEP_OFF, Menu.NONE, context.getString(R.string.sleep_timer_off))
        SLEEP_MINUTES.forEach { minutes ->
            menu.add(Menu.NONE, minutes, Menu.NONE, context.getString(R.string.sleep_timer_minutes, minutes))
        }
        menu.add(Menu.NONE, SLEEP_END_OF_CHAPTER, Menu.NONE, context.getString(R.string.sleep_timer_end_of_chapter))
        setOnMenuItemClickListener { item ->
            when (item.itemId) {
                SLEEP_OFF -> sleepTimer.cancel()
                SLEEP_END_OF_CHAPTER -> sleepTimer.startAtEndOfChapter()
                else -> sleepTimer.start(item.itemId.minutes)
            }
            updateSleepTimer()
            true
        }
    }

    private fun createChapterMenu() = PopupMenu(binding.root.context, binding.audiobookChapterTitle).apply {
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
        private const val SECONDS_PER_MINUTE = 60
        private const val MINUTES_PER_HOUR = 60
        private const val SLEEP_OFF = 0
        private const val SLEEP_END_OF_CHAPTER = -1
        private val SLEEP_MINUTES = listOf(15, 30, 45, 60)

        /** How often the chapter panel is refreshed while it is visible. */
        val UPDATE_INTERVAL = 1000.milliseconds
    }
}
