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
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.UserEntity
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.net.HttpURLConnection

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

            // Replacing drops the backoff of an earlier attempt that failed, so each app start tries again right away
            WorkManager.getInstance(context).enqueueUniqueWork(tag, ExistingWorkPolicy.REPLACE, request)
        }
    }

    private val downloadDao by inject<DownloadDao>()
    private val userDao by inject<UserDao>()
    private val apiClientController by inject<ApiClientController>()

    override suspend fun doWork(): Result {
        // Chapters are null only when they were never requested; an item without chapters gets an empty list
        val missing = downloadDao.getDownloads().filter { download -> download.item.chapters == null }
        if (missing.isEmpty()) return Result.success()

        var retry = false
        for ((userId, downloads) in missing.groupBy { download -> download.userId }) {
            val user = userDao.getUser(userId) ?: continue
            if (!addChapters(user, downloads)) retry = true
        }
        return if (retry) Result.retry() else Result.success()
    }

    /**
     * Adds chapters to [downloads] saved by [user].
     *
     * The login a download was saved with may have expired, for example after logging in again through
     * another address of the same server, so the user's other logins are tried as well.
     *
     * @return false if a server could not be reached and it should be tried again later
     */
    private suspend fun addChapters(user: UserEntity, downloads: List<DownloadEntity>): Boolean {
        val logins = listOf(user) + userDao.getLoggedInByUserId(user.userId).filter { login -> login.id != user.id }

        var reachedAll = true
        for (login in logins) {
            val api = apiClientController.getApiClient(login.serverId, login.id)
            try {
                for (chunk in downloads.chunked(ITEMS_BATCH)) addChapters(api, chunk)
                return true
            } catch (e: InvalidStatusException) {
                Timber.i(e, "Could not load chapters for downloads with login %d", login.id)
                // Unlike an unreachable server, a login that is no longer accepted won't work on a retry either
                if (e.status != HttpURLConnection.HTTP_UNAUTHORIZED) reachedAll = false
            } catch (e: ApiClientException) {
                Timber.i(e, "Could not load chapters for downloads with login %d", login.id)
                reachedAll = false
            }
        }

        // When no login is accepted, there is nothing to retry until the user logs in again,
        // which starts this worker anew on the next app start
        return reachedAll
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
