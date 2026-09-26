package org.jellyfin.mobile.player

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Pauses playback after a set time or at the end of the current chapter.
 * It lives with the player, so it keeps running while the app is in the background.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val currentPosition: () -> Duration?,
    private val chapterStarts: () -> List<Duration>,
    private val onExpired: () -> Unit,
) {
    sealed interface State {
        data object Off : State

        /** Pauses when [SystemClock.elapsedRealtime] reaches [endsAtMs]. */
        data class Countdown(val endsAtMs: Long) : State {
            val remaining: Duration
                get() = (endsAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0).milliseconds
        }

        data object EndOfChapter : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = State.Off
    }

    fun start(duration: Duration) {
        cancel()
        _state.value = State.Countdown(SystemClock.elapsedRealtime() + duration.inWholeMilliseconds)
        job = scope.launch {
            delay(duration)
            expire()
        }
    }

    /**
     * Pauses when playback reaches the start of the next chapter, or the end of the book in the last one.
     */
    fun startAtEndOfChapter() {
        cancel()
        _state.value = State.EndOfChapter
        job = scope.launch {
            val position = currentPosition() ?: Duration.ZERO
            // In the last chapter there is no next chapter to wait for, so playback just runs to the end
            val chapterEnd = chapterStarts().firstOrNull { it > position + CHAPTER_START_TOLERANCE }
                ?: Duration.INFINITE
            // A missing position means the player is gone, which also ends the wait
            while (isActive && (currentPosition() ?: chapterEnd) < chapterEnd) {
                delay(POLL_INTERVAL)
            }
            expire()
        }
    }

    private fun expire() {
        job = null
        _state.value = State.Off
        onExpired()
    }

    companion object {
        private val POLL_INTERVAL = 250.milliseconds

        /** Right at a chapter start, the timer targets the end of that chapter rather than the one before. */
        private val CHAPTER_START_TOLERANCE = 1.seconds
    }
}
