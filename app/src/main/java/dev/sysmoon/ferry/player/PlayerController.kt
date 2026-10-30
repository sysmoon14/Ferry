package dev.sysmoon.ferry.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import dev.sysmoon.ferry.data.ChannelEntity
import dev.sysmoon.ferry.data.epgKey
import dev.sysmoon.ferry.data.ChannelRepository
import dev.sysmoon.ferry.data.FerryDatabase
import dev.sysmoon.ferry.data.ProgrammeEntity
import dev.sysmoon.ferry.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PlayTarget {
    data class Id(val id: String) : PlayTarget
    data class Tvg(val tvgId: String) : PlayTarget
    data class Number(val number: Int) : PlayTarget
    data class Name(val name: String) : PlayTarget
    data object Last : PlayTarget
}

enum class PlaybackState { IDLE, BUFFERING, PLAYING, PAUSED, ERROR }

data class NowPlaying(
    val channel: ChannelEntity? = null,
    val now: ProgrammeEntity? = null,
    val next: ProgrammeEntity? = null,
    val state: PlaybackState = PlaybackState.IDLE,
    val error: String? = null,
) {
    val progress: Float?
        get() = now?.let {
            val span = (it.stop - it.start).coerceAtLeast(1)
            ((System.currentTimeMillis() - it.start).toFloat() / span).coerceIn(0f, 1f)
        }
}

/**
 * Single long-lived ExoPlayer shared by the UI, deep links, the web API and MQTT.
 * All player calls hop to Main; callers can be on any thread.
 */
@OptIn(UnstableApi::class)
class PlayerController(
    context: Context,
    private val db: FerryDatabase,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    val player: ExoPlayer

    init {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ChannelRepository.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
            .setConstantBitrateSeekingEnabled(true)
        // Live IPTV: start fast, buffer modestly. Tune here for zap speed vs. stability.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(8_000, 30_000, 1_000, 2_000)
            .build()
        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http, extractors))
            .setLoadControl(load)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = sync()
            override fun onIsPlayingChanged(isPlaying: Boolean) = sync()
            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(state = PlaybackState.ERROR, error = error.errorCodeName) }
            }
        })
        // Keep now/next fresh while something is playing.
        scope.launch {
            while (isActive) {
                delay(30_000)
                refreshProgrammes()
            }
        }
    }

    private fun sync() {
        val s = when {
            player.playerError != null -> PlaybackState.ERROR
            player.playbackState == Player.STATE_BUFFERING -> PlaybackState.BUFFERING
            player.isPlaying -> PlaybackState.PLAYING
            player.playbackState == Player.STATE_READY -> PlaybackState.PAUSED
            else -> PlaybackState.IDLE
        }
        _state.update { it.copy(state = s, error = if (s == PlaybackState.ERROR) it.error else null) }
    }

    suspend fun resolve(target: PlayTarget): ChannelEntity? = when (target) {
        is PlayTarget.Id -> db.channels().get(target.id)
        is PlayTarget.Tvg -> db.channels().byTvgId(target.tvgId)
        is PlayTarget.Number -> db.channels().byNumber(target.number)
            ?: db.channels().atPosition((target.number - 1).coerceAtLeast(0))
        is PlayTarget.Name -> db.channels().byName(target.name)
        PlayTarget.Last -> settings.lastChannelId()?.let { db.channels().get(it) }
            ?: db.channels().atPosition(0)
    }

    suspend fun play(target: PlayTarget): Boolean {
        val channel = resolve(target) ?: return false
        play(channel)
        return true
    }

    suspend fun play(channel: ChannelEntity) {
        withContext(Dispatchers.Main) {
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(channel.streamUrl)
                    .setMediaId(channel.id)
                    .build()
            )
            player.prepare()
            player.playWhenReady = true
        }
        _state.update { NowPlaying(channel = channel, state = PlaybackState.BUFFERING) }
        settings.setLastChannelId(channel.id)
        refreshProgrammes()
    }

    /**
     * Channel up/down, wrapping at the ends. Zaps within favourites when the current channel
     * is one, otherwise within its group (as TiviMate does), never across the whole lineup.
     */
    suspend fun step(delta: Int) {
        val current = _state.value.channel?.let { db.channels().get(it.id) }
        val list = when {
            current == null -> db.channels().favourites().ifEmpty { listOfNotNull(db.channels().atPosition(0)) }
            current.favourite -> db.channels().favourites()
            current.groupTitle != null -> db.channels().inGroup(current.groupTitle)
            else -> listOf(current)
        }
        if (list.isEmpty()) return
        val current = list.indexOfFirst { it.id == _state.value.channel?.id }
        val next = if (current < 0) 0 else Math.floorMod(current + delta, list.size)
        play(list[next])
    }

    suspend fun stop() {
        withContext(Dispatchers.Main) { player.stop(); player.clearMediaItems() }
        _state.value = NowPlaying()
    }

    suspend fun togglePause() = withContext(Dispatchers.Main) {
        if (player.isPlaying) player.pause() else player.play()
    }

    private suspend fun refreshProgrammes() {
        val channel = _state.value.channel ?: return
        val now = System.currentTimeMillis()
        val upcoming = db.programmes().upcoming(channel.epgKey, now, 2)
        val current = upcoming.firstOrNull()?.takeIf { it.start <= now }
        val next = if (current != null) upcoming.getOrNull(1) else upcoming.firstOrNull()
        _state.update { if (it.channel?.id == channel.id) it.copy(now = current, next = next) else it }
    }
}
