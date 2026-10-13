package org.jellyfin.mobile.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.downloads.DownloadFileType
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.time.Duration

/**
 * Cuts a stretch out of a downloaded audiobook into a small .m4a file that can be shared.
 * The audio is copied as is, without re-encoding, so the clip keeps the book's quality.
 */
class AudiobookClipExporter(
    private val context: Context,
    private val downloadDao: DownloadDao,
) {
    /**
     * Writes the clip from [start] to [end] and returns a content URI other apps can read, or null when
     * the book isn't downloaded or its audio can't be copied into an .m4a file.
     */
    suspend fun export(itemId: UUID, title: String?, start: Duration, end: Duration): Uri? =
        withContext(Dispatchers.IO) {
            val download = downloadDao.getDownloadByItemId(itemId) ?: return@withContext null
            val source = downloadDao.getFiles(download.id).find { it.type == DownloadFileType.ITEM }
                ?: return@withContext null

            val dir = File(context.cacheDir, CLIPS_DIR).apply { mkdirs() }
            // Only the latest clip is kept; the receiving app has its own copy once shared
            dir.listFiles()?.forEach(File::delete)
            val name = (title ?: "Clip").replace(UNSAFE_FILE_CHARS, "_").take(MAX_NAME_LENGTH)
            val output = File(dir, "$name ${start.inWholeSeconds}s.m4a")

            try {
                copyAudio(source.uri, output, start, end)
                FileProvider.getUriForFile(context, AUTHORITY, output)
            } catch (e: IOException) {
                Timber.e(e, "Failed to export audiobook clip")
                output.delete()
                null
            } catch (e: IllegalStateException) {
                // MediaMuxer throws this for audio it can't put in an MP4 container
                Timber.e(e, "Failed to export audiobook clip")
                output.delete()
                null
            }
        }

    private fun copyAudio(source: Uri, output: File, start: Duration, end: Duration) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, source, null)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track")
            extractor.selectTrack(track)
            val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                copySamples(extractor, extractor.getTrackFormat(track), muxer, start, end)
            } finally {
                muxer.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun copySamples(
        extractor: MediaExtractor,
        format: MediaFormat,
        muxer: MediaMuxer,
        start: Duration,
        end: Duration,
    ) {
        val outputTrack = muxer.addTrack(format)
        muxer.start()
        val startUs = start.inWholeMicroseconds
        val endUs = end.inWholeMicroseconds
        val bufferSize = when {
            format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) -> format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            else -> DEFAULT_BUFFER_SIZE
        }
        val buffer = ByteBuffer.allocate(bufferSize)
        val info = MediaCodec.BufferInfo()
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            val time = extractor.sampleTime
            if (size < 0 || time > endUs) break
            if (time >= startUs) {
                val flags = extractor.sampleFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME
                info.set(0, size, time - startUs, flags)
                muxer.writeSampleData(outputTrack, buffer, info)
            }
            extractor.advance()
        }
        muxer.stop()
    }

    companion object {
        const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.clips"
        private const val CLIPS_DIR = "clips"
        private const val MAX_NAME_LENGTH = 60
        private const val DEFAULT_BUFFER_SIZE = 1024 * 1024
        private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9 ._-]")
    }
}
