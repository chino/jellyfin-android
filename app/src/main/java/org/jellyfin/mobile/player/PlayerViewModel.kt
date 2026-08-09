package org.jellyfin.mobile.player

import android.annotation.SuppressLint
import android.app.Application
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.app.PLAYER_EVENT_CHANNEL
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.player.interaction.PlayerEvent
import org.jellyfin.mobile.player.interaction.PlayerLifecycleObserver
import org.jellyfin.mobile.player.interaction.PlayerMediaSessionCallback
import org.jellyfin.mobile.player.interaction.PlayerNotificationHelper
import org.jellyfin.mobile.player.mediasegments.MediaSegmentRepository
import org.jellyfin.mobile.player.queue.QueueManager
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.player.source.LocalJellyfinMediaSource
import org.jellyfin.mobile.player.source.RemoteJellyfinMediaSource
import org.jellyfin.mobile.player.ui.DecoderType
import org.jellyfin.mobile.player.ui.DisplayPreferences
import org.jellyfin.mobile.player.ui.PlayState
import org.jellyfin.mobile.player.ui.playermenuhelper.PlayerMenuHelper
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.Constants.SUPPORTED_VIDEO_PLAYER_PLAYBACK_ACTIONS
import org.jellyfin.mobile.utils.applyDefaultAudioAttributes
import org.jellyfin.mobile.utils.applyDefaultLocalAudioAttributes
import org.jellyfin.mobile.utils.extensions.width
import org.jellyfin.mobile.utils.getVolumeLevelPercent
import org.jellyfin.mobile.utils.getVolumeRange
import org.jellyfin.mobile.utils.logTracks
import org.jellyfin.mobile.utils.seekToOffset
import org.jellyfin.mobile.utils.setPlaybackState
import org.jellyfin.mobile.utils.toMediaMetadata
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.jellyfin.sdk.api.client.extensions.hlsSegmentApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.operations.DisplayPreferencesApi
import org.jellyfin.sdk.api.operations.HlsSegmentApi
import org.jellyfin.sdk.api.operations.PlayStateApi
import org.jellyfin.sdk.api.operations.UserApi
import org.jellyfin.sdk.model.api.ChapterInfo
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.extensions.inWholeTicks
import org.jellyfin.sdk.model.extensions.ticks
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class PlayerViewModel(application: Application) : AndroidViewModel(application),
    Player.Listener,
    KoinComponent {
    private val apiClient: ApiClient = get()
    private val displayPreferencesApi: DisplayPreferencesApi = apiClient.displayPreferencesApi
    private val playStateApi: PlayStateApi = apiClient.playStateApi
    private val hlsSegmentApi: HlsSegmentApi = apiClient.hlsSegmentApi
    private val userApi: UserApi = apiClient.userApi
    private val downloadDao: DownloadDao by inject()

    private val appPreferences: AppPreferences by inject()
    private val lifecycleObserver = PlayerLifecycleObserver(this)
    private val audioManager: AudioManager by lazy { getApplication<Application>().getSystemService()!! }
    val notificationHelper: PlayerNotificationHelper by lazy { PlayerNotificationHelper(this) }

    // Media source handling
    private val trackSelector = DefaultTrackSelector(getApplication())
    val trackSelectionHelper = TrackSelectionHelper(this, trackSelector)
    val queueManager = QueueManager(this)
    val mediaSourceOrNull: JellyfinMediaSource?
        get() = queueManager.getCurrentMediaSourceOrNull()
    private val mediaSegmentRepository: MediaSegmentRepository by inject()

    // ExoPlayer
    private val _player = MutableLiveData<ExoPlayer?>()
    private val _playerState = MutableLiveData<Int>()
    private val _decoderType = MutableLiveData<DecoderType>()
    private val _playbackSpeed = MutableLiveData<Float>()
    val player: LiveData<ExoPlayer?> get() = _player
    val playerState: LiveData<Int> get() = _playerState
    val decoderType: LiveData<DecoderType> get() = _decoderType
    val playbackSpeed: LiveData<Float> get() = _playbackSpeed

    // Player Menus
    private var playerMenuHelper: PlayerMenuHelper? = null

    // Media Segments Ask to Skip
    private var askToSkipMediaSegments: List<MediaSegmentDto> = emptyList()

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val eventLogger = EventLogger()
    private var analyticsCollector = buildAnalyticsCollector()
    private val initialTracksSelected = AtomicBoolean(false)
    private var fallbackPreferExtensionRenderers = false
    private var playSpeed = 1f

    private var progressUpdateJob: Job? = null
    private var chapterMarkingUpdateJob: Job? = null
    private var skipMediaSegmentUpdateJob: Job? = null
    private var fallbackRetryJob: Job? = null

    /**
     * Returns the current ExoPlayer instance or null
     */
    val playerOrNull: ExoPlayer? get() = _player.value

    private val playerEventChannel: Channel<PlayerEvent> by inject(named(PLAYER_EVENT_CHANNEL))

    val mediaSession: MediaSession by lazy {
        MediaSession(
            getApplication<Application>().applicationContext,
            javaClass.simpleName.removePrefix(BuildConfig.APPLICATION_ID),
        ).apply {
            @Suppress("DEPRECATION")
            setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS or MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
            setCallback(mediaSessionCallback)
            applyDefaultLocalAudioAttributes(AudioAttributes.CONTENT_TYPE_MOVIE)
        }
    }
    private val mediaSessionCallback = PlayerMediaSessionCallback(this)

    private var displayPreferences = DisplayPreferences()
    private var autoPlayNextEpisodeEnabled: Boolean = false

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)

        // Load display preferences
        viewModelScope.launch {
            var customPrefs: Map<String, String?>? = null
            try {
                val displayPreferencesDto = withContext(Dispatchers.IO) {
                    displayPreferencesApi.getDisplayPreferences(
                        displayPreferencesId = Constants.DISPLAY_PREFERENCES_ID_USER_SETTINGS,
                        client = Constants.DISPLAY_PREFERENCES_CLIENT_EMBY,
                    ).content
                }

                customPrefs = displayPreferencesDto.customPrefs
            } catch (e: ApiClientException) {
                Timber.e(e, "Failed to load display preferences from API")
            }

            displayPreferences = DisplayPreferences(
                skipBackLength = customPrefs?.get(Constants.DISPLAY_PREFERENCES_SKIP_BACK_LENGTH)?.toLongOrNull()
                    ?: Constants.DEFAULT_SEEK_TIME_MS,
                skipForwardLength = customPrefs?.get(Constants.DISPLAY_PREFERENCES_SKIP_FORWARD_LENGTH)?.toLongOrNull()
                    ?: Constants.DEFAULT_SEEK_TIME_MS,
            )
        }

        viewModelScope.launch {
            try {
                val userConfig = withContext(Dispatchers.IO) {
                    userApi.getCurrentUser().content.configuration
                }

                autoPlayNextEpisodeEnabled = userConfig?.enableNextEpisodeAutoPlay ?: false
            } catch (e: ApiClientException) {
                Timber.e(e, "Failed to load user configuration from API")
            }
        }

        // Process events from bridge
        viewModelScope.launch {
            for (event in playerEventChannel) {
                when (event) {
                    PlayerEvent.Pause -> mediaSessionCallback.onPause()
                    PlayerEvent.Resume -> mediaSessionCallback.onPlay()
                    PlayerEvent.Stop -> stop()
                    PlayerEvent.Destroy -> releasePlayer()
                    is PlayerEvent.Seek -> playerOrNull?.seekTo(event.duration.inWholeMilliseconds)
                    is PlayerEvent.SetVolume -> setVolume(event.volume)
                }
            }
        }
    }

    private fun buildAnalyticsCollector() = DefaultAnalyticsCollector(Clock.DEFAULT).apply {
        addListener(eventLogger)
    }

    /**
     * Setup a new [ExoPlayer] for video playback, register callbacks and set attributes
     */
    fun setupPlayer() {
        @Suppress("MagicNumber")
        val loadControl = when (appPreferences.exoPlayerNetworkBuffer) {
            Constants.NETWORK_BUFFER_LARGE -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(50_000, 120_000, 2_500, 5_000)
                .build()
            Constants.NETWORK_BUFFER_EXTRA_LARGE -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(80_000, 240_000, 5_000, 10_000)
                .build()
            else -> DefaultLoadControl()
        }
        val renderersFactory = DefaultRenderersFactory(getApplication()).apply {
            setEnableDecoderFallback(true) // Fallback only works if initialization fails, not decoding at playback time
            val rendererMode = when {
                fallbackPreferExtensionRenderers -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            }
            setExtensionRendererMode(rendererMode)
            setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val decoderInfoList = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder,
                )
                // Allow decoder selection only for video track
                if (!MimeTypes.isVideo(mimeType)) {
                    return@setMediaCodecSelector decoderInfoList
                }
                val filteredDecoderList = when (decoderType.value) {
                    DecoderType.HARDWARE -> decoderInfoList.filter(MediaCodecInfo::hardwareAccelerated)
                    DecoderType.SOFTWARE -> decoderInfoList.filterNot(MediaCodecInfo::hardwareAccelerated)
                    else -> decoderInfoList
                }
                // Update the decoderType based on the first decoder selected
                filteredDecoderList.firstOrNull()?.let { decoder ->
                    val decoderType = when {
                        decoder.hardwareAccelerated -> DecoderType.HARDWARE
                        else -> DecoderType.SOFTWARE
                    }
                    _decoderType.postValue(decoderType)
                }

                filteredDecoderList
            }
        }
        _player.value = ExoPlayer.Builder(getApplication(), renderersFactory, get()).apply {
            setUsePlatformDiagnostics(false)
            setTrackSelector(trackSelector)
            setAnalyticsCollector(analyticsCollector)
            setLoadControl(loadControl)
        }.build().apply {
            addListener(this@PlayerViewModel)
            applyDefaultAudioAttributes(C.AUDIO_CONTENT_TYPE_MOVIE)
            playbackParameters = androidx.media3.common.PlaybackParameters(playSpeed)
            _playbackSpeed.postValue(playSpeed)
        }
    }

    /**
     * Release the current ExoPlayer and stop/release the current MediaSession
     */
    private fun releasePlayer() {
        notificationHelper.dismissNotification()
        mediaSession.isActive = false
        mediaSession.release()
        playerOrNull?.run {
            removeListener(this@PlayerViewModel)
            release()
        }
        _player.value = null
    }

    fun load(jellyfinMediaSource: JellyfinMediaSource, exoMediaSource: MediaSource, playWhenReady: Boolean) {
        val player = playerOrNull ?: return

        player.setMediaSource(exoMediaSource)
        player.prepare()

        initialTracksSelected.set(false)

        val startTime = jellyfinMediaSource.startTime
        if (startTime > Duration.ZERO) player.seekTo(startTime.inWholeMilliseconds)

        applyMediaSegments(jellyfinMediaSource)

        player.playWhenReady = playWhenReady

        mediaSession.setMetadata(jellyfinMediaSource.toMediaMetadata())

        if (jellyfinMediaSource is RemoteJellyfinMediaSource) {
            viewModelScope.launch {
                player.reportPlaybackStart(jellyfinMediaSource)
            }
        }
    }

    private fun startProgressUpdates() {
        if (mediaSourceOrNull != null && mediaSourceOrNull !is RemoteJellyfinMediaSource) return
        progressUpdateJob = viewModelScope.launch {
            while (true) {
                delay(Constants.PLAYER_TIME_UPDATE_RATE)
                playerOrNull?.reportPlaybackState()
            }
        }
    }

    private fun stopProgressUpdates() {
        progressUpdateJob?.cancel()
    }

    private fun startChapterMarkingUpdates() {
        chapterMarkingUpdateJob = viewModelScope.launch {
            while (true) {
                delay(Constants.CHAPTER_MARKING_UPDATE_DELAY)
                playerOrNull?.setWatchedChapterMarkings()
            }
        }
    }

    private fun stopChapterMarkingUpdates() {
        chapterMarkingUpdateJob?.cancel()
    }

    private fun startSkipMediaSegmentUpdates() {
        skipMediaSegmentUpdateJob = viewModelScope.launch {
            while (true) {
                delay(Constants.SKIP_MEDIA_SEGMENT_UPDATE_DELAY)
                playerOrNull?.updateSkipMediaSegmentButton()
            }
        }
    }

    private fun stopSkipMediaSegmentUpdates() {
        skipMediaSegmentUpdateJob?.cancel()
    }

    fun updateDecoderType(type: DecoderType) {
        val player = playerOrNull ?: return
        val currentPosition = player.currentPosition.milliseconds
        val currentMediaSource = mediaSourceOrNull ?: return
        val currentPlayWhenReady = player.playWhenReady

        _decoderType.value = type

        // Re-setup player
        player.removeListener(this)
        player.release()
        setupPlayer()

        // Reload current item
        currentMediaSource.startTime = currentPosition
        val exoMediaSource = when (currentMediaSource) {
            is LocalJellyfinMediaSource -> queueManager.prepareStreams(currentMediaSource)
            is RemoteJellyfinMediaSource -> queueManager.prepareStreams(currentMediaSource)
            else -> throw IllegalArgumentException("Unsupported MediaSource type")
        }
        load(currentMediaSource, exoMediaSource, currentPlayWhenReady)
    }

    private suspend fun Player.reportPlaybackStart(mediaSource: RemoteJellyfinMediaSource) {
        try {
            val playbackPosition = currentPosition.milliseconds
            val isPaused = !isPlaying
            val volumeLevel = audioManager.getVolumeLevelPercent()
            withContext(Dispatchers.IO) {
                playStateApi.reportPlaybackStart(
                    PlaybackStartInfo(
                        itemId = mediaSource.itemId,
                        playMethod = mediaSource.playMethod,
                        playSessionId = mediaSource.playSessionId,
                        liveStreamId = mediaSource.liveStreamId,
                        audioStreamIndex = mediaSource.selectedAudioStream?.index,
                        subtitleStreamIndex = mediaSource.selectedSubtitleStream?.index,
                        isPaused = isPaused,
                        isMuted = false,
                        canSeek = true,
                        positionTicks = playbackPosition.inWholeTicks,
                        volumeLevel = volumeLevel,
                        playbackOrder = PlaybackOrder.DEFAULT,
                        repeatMode = RepeatMode.REPEAT_NONE,
                    ),
                )
            }
        } catch (e: ApiClientException) {
            Timber.e(e, "Failed to report playback start")
        }
    }

    private fun Player.setWatchedChapterMarkings() {
        val mediaSource = mediaSourceOrNull ?: return
        val markings = playerMenuHelper?.chapterMarkings?.markings ?: return
        val playbackPosition = currentPosition.milliseconds

        for (marking in markings) {
            val bias = (marking.view.layoutParams as? ConstraintLayout.LayoutParams)?.horizontalBias ?: 0f
            val chapterPositionTicks = (mediaSource.runTime.inWholeTicks * bias).toLong()
            if (playbackPosition.inWholeTicks >= chapterPositionTicks) {
                marking.setColor(R.color.jellyfin_accent)
            }
        }
    }

    private fun Player.updateSkipMediaSegmentButton() {
        val playbackPosition = currentPosition.milliseconds

        val mediaSegment = askToSkipMediaSegments.find { mediaSegment ->
            val start = mediaSegment.startTicks.ticks
            val end = mediaSegment.endTicks.ticks
            playbackPosition in start..end
        }

        if (mediaSegment != null) {
            playerMenuHelper?.skipMediaSegmentButton?.showSkipSegmentButton(mediaSegment)
        } else {
            playerMenuHelper?.skipMediaSegmentButton?.hideSkipSegmentButton()
        }
    }

    private suspend fun Player.reportPlaybackState() {
        val mediaSource = mediaSourceOrNull ?: return
        val playbackPosition = currentPosition.milliseconds
        val positionTicks = playbackPosition.inWholeTicks

        if (mediaSource is LocalJellyfinMediaSource) {
            withContext(Dispatchers.IO) {
                downloadDao.updatePlaybackPosition(mediaSource.itemId, positionTicks, System.currentTimeMillis())
            }
        }

        if (playbackState != Player.STATE_ENDED) {
            val isPaused = !isPlaying
            val volumeLevel = audioManager.getVolumeLevelPercent()

            if (mediaSource is RemoteJellyfinMediaSource || (mediaSource is LocalJellyfinMediaSource && apiClient.baseUrl != null)) {
                try {
                    withContext(Dispatchers.IO) {
                        playStateApi.reportPlaybackProgress(
                            PlaybackProgressInfo(
                                itemId = mediaSource.itemId,
                                playMethod = mediaSource.playMethod,
                                playSessionId = mediaSource.playSessionId,
                                liveStreamId = (mediaSource as? RemoteJellyfinMediaSource)?.liveStreamId,
                                audioStreamIndex = mediaSource.selectedAudioStream?.index,
                                subtitleStreamIndex = mediaSource.selectedSubtitleStream?.index,
                                isPaused = isPaused,
                                isMuted = false,
                                canSeek = true,
                                positionTicks = positionTicks,
                                volumeLevel = volumeLevel,
                                repeatMode = RepeatMode.REPEAT_NONE,
                                playbackOrder = PlaybackOrder.DEFAULT,
                            ),
                        )
                    }
                } catch (e: ApiClientException) {
                    Timber.e(e, "Failed to report playback progress")
                }
            }
        }
    }

    private fun reportPlaybackStop() {
        val mediaSource = mediaSourceOrNull ?: return
        val player = playerOrNull ?: return
        val hasFinished = player.playbackState == Player.STATE_ENDED
        val lastPositionTicks = when {
            hasFinished -> mediaSource.runTime.inWholeTicks
            else -> player.currentPosition.milliseconds.inWholeTicks
        }

        // Use a more reliable scope that outlives the Activity/ViewModel if necessary
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            withContext(NonCancellable) {
                if (mediaSource is LocalJellyfinMediaSource) {
                    withContext(Dispatchers.IO) {
                        downloadDao.updatePlaybackPosition(mediaSource.itemId, lastPositionTicks, System.currentTimeMillis())
                    }
                }

                if (mediaSource is RemoteJellyfinMediaSource || (mediaSource is LocalJellyfinMediaSource && apiClient.baseUrl != null)) {
                    try {
                        withContext(Dispatchers.IO) {
                            playStateApi.reportPlaybackStopped(
                                PlaybackStopInfo(
                                    itemId = mediaSource.itemId,
                                    positionTicks = lastPositionTicks,
                                    playSessionId = mediaSource.playSessionId,
                                    liveStreamId = (mediaSource as? RemoteJellyfinMediaSource)?.liveStreamId,
                                    failed = false,
                                ),
                            )
                        }

                        if (hasFinished) {
                            withContext(Dispatchers.IO) {
                                playStateApi.markPlayedItem(itemId = mediaSource.itemId)
                            }
                        }
                    } catch (e: ApiClientException) {
                        Timber.e(e, "Failed to report playback stop")
                    }
                }

                if (mediaSource is RemoteJellyfinMediaSource) {
                    stopTranscoding(mediaSource)
                }
            }
        }
    }

    suspend fun stopTranscoding(mediaSource: RemoteJellyfinMediaSource) {
        if (mediaSource.playMethod == PlayMethod.TRANSCODE) {
            withContext(Dispatchers.IO) {
                hlsSegmentApi.stopEncodingProcess(
                    deviceId = apiClient.deviceInfo.id,
                    playSessionId = mediaSource.playSessionId,
                )
            }
        }
    }

    private fun applyMediaSegments(mediaSource: JellyfinMediaSource) {
        viewModelScope.launch {
            val mediaSegments = mediaSource.item?.let { item ->
                mediaSegmentRepository.getSegmentsForItem(item)
            } ?: emptyList()

            for (mediaSegment in mediaSegments) {
                addSkipAction(mediaSegment)
            }
        }
    }

    private fun addSkipAction(mediaSegment: MediaSegmentDto) {
        if (mediaSegment.type == org.jellyfin.sdk.model.api.MediaSegmentType.INTRO ||
            mediaSegment.type == org.jellyfin.sdk.model.api.MediaSegmentType.OUTRO
        ) {
            askToSkipMediaSegments = askToSkipMediaSegments + mediaSegment
            if (playerOrNull?.playbackState == Player.STATE_READY) {
                startSkipMediaSegmentUpdates()
            }
        }
    }

    fun play() {
        playerOrNull?.play()
    }

    fun pause() {
        playerOrNull?.pause()
    }

    fun rewind() {
        playerOrNull?.seekToOffset(displayPreferences.skipBackLength.unaryMinus())
    }

    fun fastForward() {
        playerOrNull?.seekToOffset(displayPreferences.skipForwardLength)
    }

    fun seekByOffset(offsetMs: Long) {
        playerOrNull?.seekToOffset(offsetMs)
    }

    private fun getCurrentChapterStartPosition(chapters: List<ChapterInfo>, playbackPosition: Duration): Duration? {
        val startPositions = chapters.map { c -> c.startPositionTicks.ticks }
        return startPositions.lastOrNull { p -> p < playbackPosition - Constants.MAX_SKIP_TO_PREV_CHAPTER_MS.milliseconds }
    }

    private fun getNextChapterStartPosition(chapters: List<ChapterInfo>, playbackPosition: Duration): Duration? {
        val startPositions = chapters.map { c -> c.startPositionTicks.ticks }
        return startPositions.find { p -> p > playbackPosition }
    }

    fun previousChapter() {
        val player = playerOrNull ?: return
        val mediaSource = mediaSourceOrNull ?: return
        val chapters = mediaSource.item?.chapters ?: return

        val currentPosition = player.currentPosition.milliseconds
        val target = getCurrentChapterStartPosition(chapters, currentPosition) ?: Duration.ZERO

        player.seekTo(target.inWholeMilliseconds)
    }

    fun nextChapter() {
        val player = playerOrNull ?: return
        val mediaSource = mediaSourceOrNull ?: return
        val chapters = mediaSource.item?.chapters ?: return

        val currentPosition = player.currentPosition.milliseconds
        val target = getNextChapterStartPosition(chapters, currentPosition)

        if (target != null) {
            player.seekTo(target.inWholeMilliseconds)
        }
    }

    fun skipToPrevious() {
        val player = playerOrNull ?: return
        if (player.currentPosition > Constants.MAX_SKIP_TO_PREV_MS) {
            player.seekTo(0)
        } else {
            viewModelScope.launch {
                queueManager.previous()
            }
        }
    }

    fun skipToNext() {
        viewModelScope.launch {
            queueManager.next()
        }
    }

    fun skipMediaSegment(mediaSegmentDto: MediaSegmentDto?) {
        val player = playerOrNull ?: return
        val target = mediaSegmentDto?.endTicks?.ticks ?: return
        player.seekTo(target.inWholeMilliseconds)
    }

    fun getStateAndPause(): PlayState? {
        val player = playerOrNull ?: return null
        val state = PlayState(player.playWhenReady, player.currentPosition.milliseconds)
        player.pause()
        return state
    }

    fun logTracks() {
        playerOrNull?.logTracks(analyticsCollector)
    }

    suspend fun changeBitrate(bitrate: Int?): Boolean {
        return queueManager.changeBitrate(bitrate)
    }

    fun setPlaybackSpeed(speed: Float): Boolean {
        val player = playerOrNull ?: return false

        val parameters = player.playbackParameters
        if (parameters.speed != speed) {
            player.playbackParameters = parameters.withSpeed(speed)
            return true
        }
        return false
    }

    fun stop() {
        pause()
        reportPlaybackStop()
        releasePlayer()
    }

    private fun setVolume(percent: Int) {
        val stream = AudioManager.STREAM_MUSIC
        val volumeRange = audioManager.getVolumeRange(stream)
        val scaled = (percent * volumeRange.width / Constants.PERCENT_MAX) + volumeRange.first
        audioManager.setStreamVolume(stream, scaled, 0)
    }

    @Deprecated("Deprecated in Java")
    @SuppressLint("SwitchIntDef")
    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
        val player = playerOrNull ?: return

        // Notify fragment of current state
        _playerState.value = playbackState

        // Initialise various components
        if (playbackState == Player.STATE_READY) {
            if (!initialTracksSelected.getAndSet(true)) {
                trackSelectionHelper.selectInitialTracks()
            }
            mediaSession.isActive = true
            notificationHelper.postNotification()
        }

        // Setup or stop regular progress updates
        if (playbackState == Player.STATE_READY && playWhenReady) {
            startProgressUpdates()
            if (!playerMenuHelper?.chapterMarkings?.markings.isNullOrEmpty()) {
                startChapterMarkingUpdates()
            }
            if (askToSkipMediaSegments.isNotEmpty()) {
                startSkipMediaSegmentUpdates()
            }
        } else {
            stopProgressUpdates()
            stopChapterMarkingUpdates()
            stopSkipMediaSegmentUpdates()
        }

        // Update media session
        var playbackActions = SUPPORTED_VIDEO_PLAYER_PLAYBACK_ACTIONS
        if (queueManager.hasPrevious()) {
            playbackActions = playbackActions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        }
        if (queueManager.hasNext()) {
            playbackActions = playbackActions or PlaybackState.ACTION_SKIP_TO_NEXT
        }
        mediaSession.setPlaybackState(player, playbackActions)

        // Force update playback state and position
        viewModelScope.launch {
            when (playbackState) {
                Player.STATE_READY, Player.STATE_BUFFERING -> {
                    player.reportPlaybackState()
                }
                Player.STATE_ENDED -> {
                    reportPlaybackStop()
                    if (!autoPlayNextEpisodeEnabled || !queueManager.next()) {
                        releasePlayer()
                    }
                }
            }
        }
    }

    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
        super.onTracksChanged(tracks)
        Timber.d("Player Trace: Tracks changed. Total groups: ${tracks.groups.size}")
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
        playerOrNull?.setWatchedChapterMarkings()
        playerOrNull?.updateSkipMediaSegmentButton()
    }

    override fun onPlayerError(error: PlaybackException) {
        if (error.cause is MediaCodecDecoderException && !fallbackPreferExtensionRenderers) {
            Timber.e(error.cause, "Decoder failed, attempting to restart playback with decoder extensions preferred")
            playerOrNull?.run {
                removeListener(this@PlayerViewModel)
                release()
            }
            fallbackPreferExtensionRenderers = true
            setupPlayer()
            queueManager.tryRestartPlayback()
        } else {
            Timber.w(error, "Playback error, attempting fallback")
            val startPosition = (playerOrNull?.currentPosition ?: 0L).milliseconds
            fallbackRetryJob?.cancel()
            fallbackRetryJob = viewModelScope.launch {
                val retried = queueManager.restartPlaybackWithFallback(startPosition)
                if (!retried) {
                    _error.postValue(error.localizedMessage.orEmpty())
                }
            }
        }
    }

    override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
        _playbackSpeed.postValue(playbackParameters.speed)
    }

    fun cancelFallbackRetry() {
        fallbackRetryJob?.cancel()
        fallbackRetryJob = null
    }

    override fun onCleared() {
        reportPlaybackStop()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        releasePlayer()
    }

    fun setPlayerMenuHelper(menuHelper: PlayerMenuHelper) {
        playerMenuHelper = menuHelper
    }
}
