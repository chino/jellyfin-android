package org.jellyfin.mobile.player.ui

import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.doOnLayout
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import org.jellyfin.mobile.R
import org.jellyfin.mobile.databinding.DialogAudiobookChaptersBinding
import org.jellyfin.mobile.databinding.ItemAudiobookChapterBinding
import org.jellyfin.sdk.model.api.ChapterInfo
import org.jellyfin.sdk.model.extensions.ticks
import kotlin.time.Duration

/**
 * Full-screen chapter list. Every chapter shows its length and where it starts; chapters already passed are
 * dimmed, and the current one is marked, shows how far through it the listener is, and is scrolled into view.
 */
class AudiobookChaptersScreen(
    private val context: Context,
    private val title: String?,
    private val chapters: List<ChapterInfo>,
    private val runTime: Duration,
    private val onSeek: (Duration) -> Unit,
) {
    private val dialog = AppCompatDialog(context, R.style.AppTheme_FullScreenDialog)
    private val binding = DialogAudiobookChaptersBinding.inflate(LayoutInflater.from(context))

    fun show(position: Duration) {
        binding.chaptersTitle.text = title ?: context.getString(R.string.player_chapters)
        binding.chaptersClose.setOnClickListener { dialog.dismiss() }
        val inflater = LayoutInflater.from(context)
        val current = chapters.indexOfLast { it.startPositionTicks.ticks <= position }
        var currentRow: ViewGroup? = null
        chapters.forEachIndexed { index, chapter ->
            val start = chapter.startPositionTicks.ticks
            val end = chapters.getOrNull(index + 1)?.startPositionTicks?.ticks ?: runTime
            val row = ItemAudiobookChapterBinding.inflate(inflater, binding.chaptersList, true)
            row.chapterName.text = chapter.name?.trim()?.takeIf { it.isNotEmpty() }
                ?: context.getString(R.string.clip_file_chapter_number, (index + 1).toString())
            row.chapterLength.text = format(end - start)
            val isCurrent = index == current
            row.chapterCurrentBar.isInvisible = !isCurrent
            row.chapterProgress.isVisible = isCurrent
            row.chapterDetail.text = if (isCurrent) {
                row.chapterProgress.progress = ((position - start) / (end - start) * PROGRESS_MAX).toInt()
                context.getString(R.string.chapter_time_left, format(end - position))
            } else {
                context.getString(R.string.chapter_starts_at, format(start))
            }
            row.root.alpha = if (index < current) PASSED_ALPHA else 1f
            row.root.setOnClickListener {
                onSeek(start)
                dialog.dismiss()
            }
            if (isCurrent) currentRow = row.root
        }
        dialog.setContentView(binding.root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.show()
        currentRow?.let { row ->
            // Leave a couple of rows above the current chapter so its context shows too
            row.doOnLayout { binding.chaptersScroll.scrollTo(0, (row.top - row.height * 2).coerceAtLeast(0)) }
        }
    }

    private fun format(duration: Duration): String = DateUtils.formatElapsedTime(duration.inWholeSeconds)

    private companion object {
        const val PROGRESS_MAX = 1000
        const val PASSED_ALPHA = 0.5f
    }
}
