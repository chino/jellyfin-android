package org.jellyfin.mobile.player.interaction

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.jellyfin.mobile.player.PlayerViewModel

class PlayerLifecycleObserver(private val viewModel: PlayerViewModel) : DefaultLifecycleObserver {

    override fun onCreate(owner: LifecycleOwner) {
        viewModel.setupPlayer()
    }

    override fun onStop(owner: LifecycleOwner) {
        // Audiobooks always keep playing in the background
        val isAudiobook = viewModel.mediaSourceOrNull?.isAudiobook == true
        if (!viewModel.notificationHelper.allowBackgroundAudio && !isAudiobook) {
            viewModel.pause()
        }
    }
}
