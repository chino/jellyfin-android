package org.jellyfin.mobile.downloads

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * Adds chapters to downloads that were saved before chapters were included with them,
 * so they don't have to be downloaded again.
 */
class DownloadChaptersWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {
    companion object {
        private val tag = DownloadChaptersWorker::class.qualifiedName!!

        /**
         * How many items are requested from the server at once.
         */
        private const val ITEMS_BATCH = 25

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<DownloadChaptersWorker>()
                .addTag(tag)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(tag, ExistingWorkPolicy.KEEP, request)
        }
    }

    private val downloadDao by inject<DownloadDao>()
    private val apiClientController by inject<ApiClientController>()

    override suspend fun doWork(): Result {
        // Chapters are null only when they were never requested; an item without chapters gets an empty list
        val missing = downloadDao.getDownloads().filter { download -> download.item.chapters == null }
        if (missing.isEmpty()) return Result.success()

        var allUpdated = true
        for ((serverUser, downloads) in missing.groupBy { download -> download.serverId to download.userId }) {
            val api = apiClientController.getApiClient(serverUser.first, serverUser.second)
            for (chunk in downloads.chunked(ITEMS_BATCH)) {
                try {
                    addChapters(api, chunk)
                } catch (e: ApiClientException) {
                    Timber.i(e, "Could not load chapters for downloads yet")
                    allUpdated = false
                }
            }
        }
        return if (allUpdated) Result.success() else Result.retry()
    }

    private suspend fun addChapters(api: ApiClient, downloads: List<DownloadEntity>) {
        val response by api.itemsApi.getItems(
            ids = downloads.map { download -> download.itemId },
            fields = DownloadManager.DOWNLOAD_ITEM_FIELDS,
        )
        val chaptersById = response.items.associate { item -> item.id to item.chapters.orEmpty() }
        for (download in downloads) {
            // Only the chapters are taken over: the rest of the saved item, such as its media
            // sources, has to keep describing the files that were actually downloaded
            val chapters = chaptersById[download.itemId] ?: continue
            downloadDao.updateItem(download.id, download.item.copy(chapters = chapters))
        }
    }
}
