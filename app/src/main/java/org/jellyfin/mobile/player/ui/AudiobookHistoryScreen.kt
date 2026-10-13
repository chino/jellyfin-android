package org.jellyfin.mobile.player.ui

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.isVisible
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.audiobook.ListeningSessionEntity
import org.jellyfin.mobile.databinding.DialogAudiobookHistoryBinding
import org.jellyfin.mobile.databinding.ItemAudiobookHistoryBinding
import java.util.Date
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Full-screen listening history, grouped by day: each session is a card with its chapter and where in it
 * listening stopped, the stretch of the book it covered, when it started, how long it lasted and how much of
 * the book was left. Tapping one resumes from where it stopped.
 */
class AudiobookHistoryScreen(
    private val context: Context,
    private val book: AudiobookBookmarksScreen.Book,
    private val runTime: Duration,
    private val onResume: (Duration) -> Unit,
    private val onClear: () -> Unit,
) {
    private val dialog = AppCompatDialog(context, R.style.AppTheme_FullScreenDialog)
    private val binding = DialogAudiobookHistoryBinding.inflate(LayoutInflater.from(context))

    /** Shows [sessions], newest first. */
    fun show(sessions: List<ListeningSessionEntity>) {
        binding.historyClose.setOnClickListener { dialog.dismiss() }
        binding.historyClear.isVisible = sessions.isNotEmpty()
        binding.historyClear.setOnClickListener { confirmClear() }
        binding.historyEmpty.isVisible = sessions.isEmpty()
        val inflater = LayoutInflater.from(context)
        var lastDay: String? = null
        sessions.forEach { session ->
            val day = dayLabel(session.startedAt)
            if (day != lastDay) {
                val header = inflater.inflate(R.layout.item_audiobook_history_day, binding.historyList, false)
                (header as TextView).text = day
                binding.historyList.addView(header)
                lastDay = day
            }
            ItemAudiobookHistoryBinding.inflate(inflater, binding.historyList, true).bind(session)
        }
        dialog.setContentView(binding.root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.show()
    }

    private fun ItemAudiobookHistoryBinding.bind(session: ListeningSessionEntity) {
        val start = session.startPositionMs.milliseconds
        val end = session.endPositionMs.milliseconds
        historyChapter.text = book.chapterShortLabel(context, end) ?: context.getString(R.string.listening_history)
        historyStopped.text = format(end - (book.chapterStart(end) ?: Duration.ZERO))
        val range = context.getString(R.string.listening_session_range, format(start), format(end))
        historyRange.text = book.chapterPosition(context, end)?.let { "$it · $range" } ?: range
        historyTime.text = context.getString(
            R.string.listening_session_when,
            DateFormat.getTimeFormat(context).format(Date(session.startedAt)),
            listened((session.endedAt - session.startedAt).milliseconds),
        )
        historyLeft.isVisible = runTime.isFinite() && runTime > end
        historyLeft.text = context.getString(R.string.chapter_time_left, hoursMinutes(runTime - end))
        root.setOnClickListener {
            onResume(end)
            dialog.dismiss()
        }
    }

    private fun dayLabel(time: Long): String = when {
        DateUtils.isToday(time) -> context.getString(R.string.listening_history_today)
        DateUtils.isToday(time + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.listening_history_yesterday)
        else -> DateFormat.getMediumDateFormat(context).format(Date(time))
    }

    /** "45s", "2m 51s" or "1h 05m". */
    private fun listened(duration: Duration): String = duration.toComponents { hours, minutes, seconds, _ ->
        when {
            hours > 0 -> context.getString(R.string.listened_hours, hours, minutes)
            minutes > 0 -> context.getString(R.string.listened_minutes, minutes, seconds)
            else -> context.getString(R.string.listened_seconds, seconds)
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(context)
            .setMessage(R.string.listening_history_clear_confirm)
            .setPositiveButton(R.string.listening_history_clear) { _, _ ->
                onClear()
                binding.historyList.removeAllViews()
                binding.historyClear.isVisible = false
                binding.historyEmpty.isVisible = true
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** "7h 25m" or "25m". */
    private fun hoursMinutes(duration: Duration): String = duration.toComponents { hours, minutes, _, _ ->
        if (hours > 0) {
            context.getString(R.string.time_hours_minutes, hours, minutes)
        } else {
            context.getString(R.string.time_minutes, minutes)
        }
    }

    private fun format(duration: Duration): String = DateUtils.formatElapsedTime(duration.inWholeSeconds)
}
