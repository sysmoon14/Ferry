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
import dev.sysmoon.ferry.data.BbcIplayer
import dev.sysmoon.ferry.data.XtreamClient
import dev.sysmoon.ferry.data.ChannelEntity
import dev.sysmoon.ferry.data.epgKey
import dev.sysmoon.ferry.data.ChannelRepository
import dev.sysmoon.ferry.data.FerryDatabase
import dev.sysmoon.ferry.data.ProgrammeEntity
import dev.sysmoon.ferry.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

/** Which list channel up/down moves through: the one the user started from in the guide. */
sealed interface ZapScope {
    data object Favourites : ZapScope
    data object All : ZapScope
    data class Group(val name: String) : ZapScope
}

data class NowPlaying(
    val channel: ChannelEntity? = null,
    val now: ProgrammeEntity? = null,
    val next: ProgrammeEntity? = null,
    val state: PlaybackState = PlaybackState.IDLE,
    val error: String? = null,
    /** True when playing a past programme from the provider's archive; [now] is that programme. */
    val catchup: Boolean = false,
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
    private val bbc: BbcIplayer,
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

    private var zapScope: ZapScope = ZapScope.All
    private var playJob: Job? = null

    /**
     * Fire-and-forget start for UI callers. Runs in the app scope so it survives the guide
     * leaving the screen (BBC channels fetch a stream URL first and were being cancelled),
     * and cancels any start still in flight so rapid zapping can't land out of order.
     */
    fun start(channel: ChannelEntity, scope: ZapScope? = null) {
        playJob?.cancel()
        playJob = this.scope.launch { play(channel, scope) }
    }

    fun zap(delta: Int) {
        playJob?.cancel()
        playJob = scope.launch { step(delta) }
    }

    suspend fun play(target: PlayTarget): Boolean {
        val channel = resolve(target) ?: return false
        play(channel)
        return true
    }

    /** [scope] defaults to favourites (if starred) or the channel's group, e.g. for HA/deep links. */
    suspend fun play(channel: ChannelEntity, scope: ZapScope? = null) {
        zapScope = scope ?: when {
            channel.favourite -> ZapScope.Favourites
            channel.groupTitle != null -> ZapScope.Group(channel.groupTitle)
            else -> ZapScope.All
        }
        // Look up now/next first so the banner never flashes "No guide data".
        val (now, next) = programmesFor(channel)
        // BBC iPlayer channels store candidate service ids; fetch a current stream URL each play.
        val uri = if (BbcIplayer.isBbc(channel.streamUrl)) {
            _state.update { NowPlaying(channel, now, next, PlaybackState.BUFFERING) }
            when (val r = withContext(Dispatchers.IO) { bbc.resolve(channel.streamUrl) }) {
                is BbcIplayer.Resolved.Ok -> r.url
                is BbcIplayer.Resolved.Failed -> {
                    withContext(Dispatchers.Main) { player.stop(); player.clearMediaItems() }
                    _state.update { NowPlaying(channel, now, next, PlaybackState.ERROR, r.reason) }
                    return
                }
            }
        } else channel.streamUrl
        // Set the new channel *before* starting the player, then let the player's own events
        // drive the state. Setting BUFFERING afterwards could overwrite a PLAYING event that
        // already arrived, leaving the status stuck on "buffering" (seen with BBC iPlayer).
        _state.update { NowPlaying(channel, now, next, PlaybackState.BUFFERING) }
        withContext(Dispatchers.Main) {
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(uri)
                    .setMediaId(channel.id)
                    .build()
            )
            player.prepare()
            player.playWhenReady = true
            sync()
        }
        settings.setLastChannelId(channel.id)
    }

    /** Catch-up is possible for programmes that have ended (or started) within the archive window. */
    fun canCatchUp(channel: ChannelEntity, programme: ProgrammeEntity, now: Long = System.currentTimeMillis()): Boolean =
        channel.catchupDays > 0 &&
            programme.start < now &&
            programme.start > now - channel.catchupDays * 86_400_000L &&
            XtreamClient.timeshiftUrl(channel.streamUrl, programme.start, programme.stop, null) != null

    /** Plays [programme] from the provider's archive (Xtream timeshift). */
    fun startCatchUp(channel: ChannelEntity, programme: ProgrammeEntity, scope: ZapScope? = null) {
        playJob?.cancel()
        playJob = this.scope.launch {
            zapScope = scope ?: zapScope
            val tz = db.sources().get(channel.sourceId)?.serverTimezone
            val url = XtreamClient.timeshiftUrl(channel.streamUrl, programme.start, programme.stop, tz)
            if (url == null) {
                _state.update { NowPlaying(channel, programme, null, PlaybackState.ERROR, "Catch-up not available", true) }
                return@launch
            }
            _state.update { NowPlaying(channel, programme, null, PlaybackState.BUFFERING, catchup = true) }
            withContext(Dispatchers.Main) {
                player.setMediaItem(MediaItem.Builder().setUri(url).setMediaId("${channel.id}@${programme.start}").build())
                player.prepare()
                player.playWhenReady = true
                sync()
            }
        }
    }

    /** Channel up/down through the list playback started from, wrapping at the ends. */
    suspend fun step(delta: Int) {
        val currentId = _state.value.channel?.id
        val dao = db.channels()
        val ids: List<String> = when (val z = zapScope) {
            ZapScope.Favourites -> dao.favourites().map { it.id }
            is ZapScope.Group -> dao.inGroup(z.name).map { it.id }
            ZapScope.All -> dao.visibleIds()
        }
        if (ids.isEmpty()) return
        val index = ids.indexOf(currentId)
        val nextId = ids[if (index < 0) 0 else Math.floorMod(index + delta, ids.size)]
        dao.get(nextId)?.let { play(it, zapScope) }
    }

    suspend fun stop() {
        withContext(Dispatchers.Main) { player.stop(); player.clearMediaItems() }
        _state.value = NowPlaying()
    }

    suspend fun togglePause() = withContext(Dispatchers.Main) {
        if (player.isPlaying) player.pause() else player.play()
    }

    private suspend fun programmesFor(channel: ChannelEntity): Pair<ProgrammeEntity?, ProgrammeEntity?> {
        val now = System.currentTimeMillis()
        val upcoming = runCatching { db.programmes().upcoming(channel.epgKey, now, 2) }.getOrDefault(emptyList())
        val current = upcoming.firstOrNull()?.takeIf { it.start <= now }
        val next = if (current != null) upcoming.getOrNull(1) else upcoming.firstOrNull()
        return current to next
    }

    private suspend fun refreshProgrammes() {
        if (_state.value.catchup) return // [now] is the archived programme being watched
        val channel = _state.value.channel ?: return
        val (current, next) = programmesFor(channel)
        _state.update { if (it.channel?.id == channel.id) it.copy(now = current, next = next) else it }
    }
}
