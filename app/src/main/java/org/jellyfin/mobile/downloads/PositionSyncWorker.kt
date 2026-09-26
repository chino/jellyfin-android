package org.jellyfin.mobile.downloads

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Sends playback positions of downloads that couldn't be reported while offline to the server
 * once a network connection is available, even if the app was closed in the meantime.
 */
class PositionSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {
    companion object {
        private val tag = PositionSyncWorker::class.qualifiedName!!

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PositionSyncWorker>()
                .addTag(tag)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(tag, ExistingWorkPolicy.KEEP, request)
        }
    }

    private val downloadDao by inject<DownloadDao>()
    private val apiClientController by inject<ApiClientController>()

    override suspend fun doWork(): Result {
        var allSynced = true
        for (download in downloadDao.getUnsyncedPositions()) {
            try {
                sync(download)
            } catch (e: ApiClientException) {
                Timber.i(e, "Could not sync the playback position of %s yet", download.itemId)
                allSynced = false
            }
        }
        return if (allSynced) Result.success() else Result.retry()
    }

    private suspend fun sync(download: DownloadEntity) {
        val lastPlayedAt = download.lastPlayedAt ?: return
        val positionTicks = download.playbackPositionTicks ?: return
        val api = withContext(Dispatchers.IO) {
            apiClientController.getApiClient(download.serverId, download.userId)
        }

        val item by api.userLibraryApi.getItem(itemId = download.itemId)
        val serverPlayedAt = item.userData?.lastPlayedDate?.toInstant(ZoneOffset.UTC)?.toEpochMilli() ?: 0L

        // Another device may have moved on since, so only a newer position is sent
        if (lastPlayedAt > serverPlayedAt) {
            api.itemsApi.updateItemUserData(
                itemId = download.itemId,
                data = UpdateUserItemDataDto(
                    playbackPositionTicks = positionTicks,
                    lastPlayedDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastPlayedAt), ZoneOffset.UTC),
                ),
            )
        }

        downloadDao.markPositionSynced(download.itemId, lastPlayedAt)
    }
}
