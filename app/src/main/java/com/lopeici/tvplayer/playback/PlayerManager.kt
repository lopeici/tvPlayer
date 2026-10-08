package com.lopeici.tvplayer.playback

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.cast.CastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lopeici.tvplayer.R
import com.lopeici.tvplayer.data.Channel
import com.lopeici.tvplayer.data.USER_AGENT
import com.lopeici.tvplayer.data.hlsVariant
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Owns a single ExoPlayer (local) wrapped by a CastPlayer that transparently routes playback to a
 * connected Chromecast and back to the local player on disconnect. Because the Chromecast streams
 * directly, casting keeps playing when the app is minimized.
 */
class PlayerManager(
    context: Context,
    httpClient: OkHttpClient,
    /** When true, cast playback uses each channel's HLS (.m3u8) variant (see [hlsVariant]). */
    private val castAsHls: StateFlow<Boolean>,
) {

    // App-scoped like this class itself; only cancelled in release().
    private val scope = MainScope()

    private val loadControl = DefaultLoadControl.Builder()
        // Tuned for live IPTV: small buffers so channel switching feels fast.
        .setBufferDurationsMs(2_000, 20_000, 1_000, 2_000)
        .build()

    private val resources = context.resources

    /**
     * Headers the current channel asks for (`#EXTVLCOPT` user agent / referrer). Applied to every
     * request while it plays — HLS playlists and segments alike. Read on the loader thread.
     */
    @Volatile private var streamHeaders: Map<String, String> = emptyMap()

    // OkHttp follows http<->https redirects, which IPTV servers use a lot; the default
    // HttpURLConnection-based source refuses cross-protocol redirects. The app's User-Agent is a
    // default request property (not setUserAgent, which would be *added* next to a per-channel one)
    // so a channel's own User-Agent replaces it.
    private val dataSourceFactory = ResolvingDataSource.Factory(
        OkHttpDataSource.Factory(httpClient).setDefaultRequestProperties(mapOf("User-Agent" to USER_AGENT)),
    ) { spec -> streamHeaders.let { if (it.isEmpty()) spec else spec.withAdditionalHeaders(it) } }

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .setHandleAudioBecomingNoisy(true)
        // Take audio focus as movie/media playback: pauses when another app (or the voice
        // assistant) takes focus, and stops playing over other apps' audio.
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        .build()

    /** The player the UI binds to. CastPlayer auto-switches between local and remote. */
    val player: Player = runCatching {
        CastContext.getSharedInstance(context.applicationContext)
        CastPlayer.Builder(context.applicationContext).setLocalPlayer(exoPlayer).build() as Player
    }.getOrDefault(exoPlayer)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState = _playbackState.asStateFlow()

    /** The channel loaded into the player (null after [stop]); set directly by [load]. */
    private val _current = MutableStateFlow<Channel?>(null)
    val current = _current.asStateFlow()

    private val _isCasting = MutableStateFlow(false)
    val isCasting = _isCasting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /**
     * The channel list being zapped through. Kept here rather than in the (Activity-scoped)
     * ViewModel so a reopened UI still knows what an ongoing local or cast session is playing.
     *
     * Only the current channel is loaded into the player: a "play all channels" queue can be tens
     * of thousands of items, which is slow to build and far too big to send to a Cast receiver.
     * Next/previous are resolved here against [queue] instead. Side benefit: a live stream that
     * ends just ends (STATE_ENDED) rather than the player silently moving on to the next channel.
     */
    private val _queue = MutableStateFlow<List<Channel>>(emptyList())
    val queue = _queue.asStateFlow()

    /** Position in [queue] of the channel loaded into the player. */
    private var index = 0

    /** Errors recovered without showing the overlay since the stream last played (see onPlayerError). */
    private var silentRetries = 0

    /** Track groups of the current stream (audio languages, subtitles, …). */
    private val _tracks = MutableStateFlow(Tracks.EMPTY)
    val tracks = _tracks.asStateFlow()

    /** Size of the current local video (drives the PiP window's aspect ratio). */
    private val _videoSize = MutableStateFlow(VideoSize.UNKNOWN)
    val videoSize = _videoSize.asStateFlow()

    /**
     * [player] as seen by the media session (notification, lock screen, headset buttons, Android TV
     * now-playing card): its next/previous zap through [queue] — the player itself only ever holds
     * one item — and its stop clears the queue like the in-app stop button.
     */
    val sessionPlayer: Player by lazy {
        object : ForwardingSimpleBasePlayer(player) {
            override fun getState(): State {
                val state = super.getState()
                return state.buildUpon()
                    .setAvailableCommands(state.availableCommands.buildUpon().addAll(*ZAP_COMMANDS).build())
                    .build()
            }

            override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> =
                when (seekCommand) {
                    COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> { next(); Futures.immediateVoidFuture() }
                    COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> { previous(); Futures.immediateVoidFuture() }
                    else -> super.handleSeek(mediaItemIndex, positionMs, seekCommand)
                }

            override fun handleStop(): ListenableFuture<*> {
                this@PlayerManager.stop()
                return Futures.immediateVoidFuture()
            }
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { _isPlaying.value = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) {
                _playbackState.value = playbackState
                if (playbackState == Player.STATE_READY) silentRetries = 0
            }
            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                _isCasting.value = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
            }
            override fun onTracksChanged(tracks: Tracks) { _tracks.value = tracks }
            override fun onVideoSizeChanged(videoSize: VideoSize) { _videoSize.value = videoSize }
            override fun onPlayerError(error: PlaybackException) {
                if (silentRetries < MAX_SILENT_RETRIES && recover(error)) {
                    silentRetries++
                } else {
                    _error.value = friendlyError(error)
                }
            }
            override fun onPlayerErrorChanged(error: PlaybackException?) {
                if (error == null) _error.value = null
            }
        })

        // Reload the current channel when casting starts/stops (the media items' URL and MIME
        // depend on it) or when the HLS toggle changes *while casting*. A toggle change while
        // playing locally maps to the same value and is ignored; drop(1) skips the initial state.
        scope.launch {
            combine(_isCasting, castAsHls) { casting, hls -> casting to (casting && hls) }
                .distinctUntilChanged()
                .drop(1)
                .collect { reloadCurrent() }
        }
    }

    fun play(channels: List<Channel>, startIndex: Int) {
        if (channels.isEmpty()) return
        _queue.value = channels
        load(startIndex.coerceIn(0, channels.lastIndex))
    }

    private fun reloadCurrent() {
        if (_queue.value.isNotEmpty()) load(index)
    }

    /** Loads queue[[i]] into the player as its only item and starts it. */
    private fun load(i: Int) {
        val channel = _queue.value.getOrNull(i) ?: return
        index = i
        _current.value = channel
        silentRetries = 0
        _error.value = null
        streamHeaders = channel.httpHeaders
        val casting = _isCasting.value
        val castHls = casting && castAsHls.value
        player.setMediaItem(channel.toMediaItem(castHls, casting))
        player.playWhenReady = true
        player.prepare()
    }

    fun playIndex(index: Int) {
        if (index in _queue.value.indices) load(index)
    }

    /** Zapping wraps around: next from the last channel goes to the first, and vice versa. */
    fun next() {
        val size = _queue.value.size
        if (size > 1) load((index + 1) % size)
    }

    fun previous() {
        val size = _queue.value.size
        if (size > 1) load((index - 1 + size) % size)
    }
    fun togglePlayPause() = player.run { if (isPlaying) pause() else play() }

    /** Fully stop the stream and clear the queue — nothing is "now playing" afterwards. */
    fun stop() {
        _error.value = null
        _queue.value = emptyList()   // also keeps the cast watcher from restarting it
        _current.value = null
        player.stop()
        player.clearMediaItems()
    }

    /** Reload the current channel from scratch (also works once a stream has ENDED). */
    fun retry() = reloadCurrent()

    /**
     * Tries to recover from [error] without bothering the user; returns false if it can't.
     * - Behind the live window (fell too far behind after a stall): jump back to the live edge.
     * - Transient network failure: re-prepare after a short pause.
     */
    private fun recover(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
            player.seekToDefaultPosition()
            player.prepare()
            true
        }
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
            val failedIndex = index
            scope.launch {
                delay(1_000)
                // Skip if the user zapped, stopped, or retried in the meantime.
                if (index == failedIndex && _queue.value.isNotEmpty() && player.playerError != null) {
                    player.prepare()
                }
            }
            true
        }
        else -> false
    }

    fun clearError() { _error.value = null }

    /** Force a specific audio/subtitle track of the current stream. */
    fun selectTrack(group: Tracks.Group, trackIndex: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(group.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    /** Back to automatic audio selection. */
    fun clearAudioOverride() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
    }

    /** Turn subtitles off. */
    fun disableTextTracks() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun release() {
        scope.cancel()
        if (player !== exoPlayer) player.release()
        exoPlayer.release()
    }

    private companion object {
        /** Silent recoveries allowed before the error overlay is shown; reset once playback is READY. */
        const val MAX_SILENT_RETRIES = 2

        val ZAP_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
    }

    private fun friendlyError(e: PlaybackException): String =
        resources.getString(R.string.player_cant_play, e.errorCodeName)

    private fun Channel.toMediaItem(castHls: Boolean, casting: Boolean): MediaItem {
        // For casting, optionally use the HLS variant (a stock Chromecast can't play raw mpegts).
        val streamUrl = if (castHls) hlsVariant(url) else url
        // A MIME type is required for Chromecast: the Cast receiver needs a contentType, and
        // Media3's converter would otherwise pass null to MediaInfo (which crashes casting).
        val path = streamUrl.substringBefore('?').lowercase()
        val mime = when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
            path.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
            path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            // Extensionless (Xtream-style) URLs usually serve raw mpegts. Locally the MIME must
            // stay null so ExoPlayer sniffs the container; forcing HLS here breaks playback with
            // ERROR_CODE_PARSING_MANIFEST_MALFORMED. When casting, a contentType is mandatory and
            // HLS is the only shape a stock receiver can play anyway.
            else -> if (casting) MimeTypes.APPLICATION_M3U8 else null
        }
        return MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(key)
            .setMimeType(mime)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setStation(group)
                    .apply { logo?.let { setArtworkUri(it.toUri()) } }
                    .build(),
            )
            .build()
    }
}
