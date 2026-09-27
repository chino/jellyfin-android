package org.jellyfin.mobile.player

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import org.jellyfin.mobile.data.audiobook.AudiobookDao
import org.jellyfin.mobile.data.audiobook.ListeningSessionEntity

/**
 * Records each stretch of audiobook listening, from play to pause or stop, as a listening session.
 */
class ListeningHistoryRecorder(
    private val scope: CoroutineScope,
    private val audiobookDao: AudiobookDao,
    private val currentPositionMs: () -> Long?,
    /** The id of the audiobook being played, or null when the current item isn't an audiobook. */
    private val currentAudiobookId: () -> String?,
) : Player.Listener {
    private var itemId: String? = null
    private var startedAt = 0L
    private var startPositionMs = 0L

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) start() else finish()
    }

    private fun start() {
        itemId = currentAudiobookId() ?: return
        startedAt = System.currentTimeMillis()
        startPositionMs = currentPositionMs() ?: 0L
    }

    fun finish() {
        val id = itemId ?: return
        itemId = null
        val endedAt = System.currentTimeMillis()
        if (endedAt - startedAt < MIN_SESSION_MS) return

        val session = ListeningSessionEntity(
            itemId = id,
            startedAt = startedAt,
            endedAt = endedAt,
            startPositionMs = startPositionMs,
            endPositionMs = currentPositionMs() ?: startPositionMs,
        )
        // NonCancellable so a session ending as the player closes is still saved
        scope.launch(Dispatchers.IO + NonCancellable) {
            audiobookDao.insertListeningSession(session)
        }
    }

    companion object {
        /** Shorter listens, like a quick play/pause, aren't worth a history entry. */
        private const val MIN_SESSION_MS = 10_000L
    }
}
