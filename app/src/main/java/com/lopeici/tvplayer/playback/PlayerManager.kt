package com.lopeici.tvplayer.playback

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.cast.CastPlayer
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.gms.cast.framework.CastContext
import com.lopeici.tvplayer.data.Channel
import com.lopeici.tvplayer.data.hlsVariant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns a single ExoPlayer (local) wrapped by a CastPlayer that transparently routes playback to a
 * connected Chromecast and back to the local player on disconnect. Because the Chromecast streams
 * directly, casting keeps playing when the app is minimized.
 */
class PlayerManager(context: Context) {

    private val loadControl = DefaultLoadControl.Builder()
        // Tuned for live IPTV: small buffers so channel switching feels fast.
        .setBufferDurationsMs(2_000, 20_000, 1_000, 2_000)
        .build()

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .setHandleAudioBecomingNoisy(true)
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

    private val _currentMediaId = MutableStateFlow<String?>(null)
    val currentMediaId = _currentMediaId.asStateFlow()

    private val _isCasting = MutableStateFlow(false)
    val isCasting = _isCasting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { _isPlaying.value = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) { _playbackState.value = playbackState }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _currentMediaId.value = mediaItem?.mediaId
            }
            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                _isCasting.value = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
            }
            override fun onPlayerError(error: PlaybackException) {
                _error.value = friendlyError(error)
            }
            override fun onPlayerErrorChanged(error: PlaybackException?) {
                if (error == null) _error.value = null
            }
        })
    }

    fun play(channels: List<Channel>, startIndex: Int, castHls: Boolean = false) {
        if (channels.isEmpty()) return
        val idx = startIndex.coerceIn(0, channels.lastIndex)
        player.setMediaItems(channels.map { it.toMediaItem(castHls, _isCasting.value) }, idx, 0L)
        player.playWhenReady = true
        player.prepare()
    }

    fun playIndex(index: Int) {
        if (index in 0 until player.mediaItemCount) {
            player.seekTo(index, 0L)
            player.playWhenReady = true
        }
    }

    fun next() = player.run { if (hasNextMediaItem()) seekToNextMediaItem() }
    fun previous() = player.run { if (hasPreviousMediaItem()) seekToPreviousMediaItem() }
    fun togglePlayPause() = player.run { if (isPlaying) pause() else play() }

    /** Fully stop the stream and clear the queue — nothing is "now playing" afterwards. */
    fun stop() {
        _error.value = null
        player.stop()
        player.clearMediaItems()
    }

    fun retry() {
        _error.value = null
        player.prepare()
        player.play()
    }

    fun clearError() { _error.value = null }

    fun release() {
        if (player !== exoPlayer) player.release()
        exoPlayer.release()
    }

    private fun friendlyError(e: PlaybackException): String =
        "Can't play this channel (${e.errorCodeName}). It may be offline."

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
