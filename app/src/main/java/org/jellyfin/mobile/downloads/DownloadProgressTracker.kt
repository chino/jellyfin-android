package org.jellyfin.mobile.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object DownloadProgressTracker {
    data class Progress(val downloaded: Long, val total: Long)

    private val _progressMap = MutableStateFlow<Map<Long, Progress>>(emptyMap())
    val progressMap: StateFlow<Map<Long, Progress>> = _progressMap.asStateFlow()

    fun updateProgress(downloadId: Long, downloaded: Long, total: Long) {
        if (total > 0 && downloaded <= total) {
            _progressMap.update { current ->
                current + (downloadId to Progress(downloaded, total))
            }
        }
    }

    fun removeProgress(downloadId: Long) {
        _progressMap.update { current ->
            current - downloadId
        }
    }
}
