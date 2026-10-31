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
import dev.sysmoon.ferry.data.archiveDays
import dev.sysmoon.ferry.data.ChannelRepository
import dev.sysmoon.ferry.data.FerryDatabase
import dev.sysmoon.ferry.data.ProgrammeEntity
import dev.sysmoon.ferry.data.Settings
import dev.sysmoon.ferry.data.StreamQuality
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
    /** Sky layout: one Sky section in Sky number order, or every Sky channel when [section] is null. */
    data class Sky(val section: String?) : ZapScope
}

data class NowPlaying(
    val channel: ChannelEntity? = null,
    val now: ProgrammeEntity? = null,
    val next: ProgrammeEntity? = null,
    val state: PlaybackState = PlaybackState.IDLE,
    val error: String? = null,
    /** True when playing a past programme from the provider's archive; [now] is that programme. */
    val catchup: Boolean = false,
    /** The stream actually playing: [channel] itself, or one of its folded duplicates. */
    val stream: ChannelEntity? = null,
    /** How many streams [channel] has (1 unless duplicates were merged into it). */
    val streamCount: Int = 1,
    /** Short message for the banner, e.g. after falling back to a backup stream. */
    val notice: String? = null,
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
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) { streamStarted = true; watchdog?.cancel() }
                sync()
            }
            override fun onPlayerError(error: PlaybackException) {
                // A merged channel has backups: move on to the next stream instead of stopping.
                if (!_state.value.catchup && streamIndex + 1 < streams.size) {
                    failover("failed")
                } else {
                    _state.update { it.copy(state = PlaybackState.ERROR, error = describe(error, it.catchup)) }
                }
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
        // In the Sky layout, numbers are Sky's (101 = BBC One).
        is PlayTarget.Number -> (if (settings.skyLayout()) db.channels().bySkyNumber(target.number) else null)
            ?: db.channels().byNumber(target.number)
            ?: db.channels().atPosition((target.number - 1).coerceAtLeast(0))
        is PlayTarget.Name -> db.channels().byName(target.name)
        PlayTarget.Last -> settings.lastChannelId()?.let { db.channels().get(it) }
            ?: db.channels().atPosition(0)
    }

    private var zapScope: ZapScope = ZapScope.All
    private var playJob: Job? = null

    // Streams of the channel being played, best first, and which one is on.
    @Volatile private var streams: List<ChannelEntity> = emptyList()
    @Volatile private var streamIndex = 0
    @Volatile private var streamStarted = false
    private var watchdog: Job? = null

    /**
     * Fire-and-forget start for UI callers. Runs in the app scope so it survives the guide
     * leaving the screen (BBC channels fetch a stream URL first and were being cancelled),
     * and cancels any start still in flight so rapid zapping can't land out of order.
     */
    fun start(channel: ChannelEntity, scope: ZapScope? = null) {
        playJob?.cancel()
        playJob = this.scope.launch { play(channel, scope) }
    }

    /** Plays a specific stream of a merged channel (chosen from the long-press menu). */
    fun startStream(stream: ChannelEntity, scope: ZapScope? = null) {
        playJob?.cancel()
        playJob = this.scope.launch { play(stream, scope ?: zapScope.takeIf { _state.value.channel != null }) }
    }

    /** A channel's streams, best first. Works for a primary or any of its duplicates. */
    suspend fun streamsFor(channel: ChannelEntity): List<ChannelEntity> {
        val primaryId = channel.primaryId ?: channel.id
        return db.channels().streamsOf(primaryId).ifEmpty { listOf(channel) }
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

    /**
     * [scope] defaults to favourites (if starred) or the channel's group, e.g. for HA/deep links.
     * [channel] may be a duplicate stream: the row it belongs to plays, starting from that stream.
     */
    suspend fun play(channel: ChannelEntity, scope: ZapScope? = null) {
        val primary = channel.primaryId?.let { db.channels().get(it) } ?: channel
        zapScope = scope ?: when {
            primary.favourite -> ZapScope.Favourites
            primary.skyNumber != null -> ZapScope.Sky(null)
            primary.groupTitle != null -> ZapScope.Group(primary.groupTitle)
            else -> ZapScope.All
        }
        val list = if (primary.streamCount > 1) streamsFor(primary) else listOf(primary)
        streams = list
        streamIndex = list.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)
        // Look up now/next first so the banner never flashes "No guide data".
        val (now, next) = programmesFor(primary)
        playCurrentStream(primary, now, next, notice = null)
        settings.setLastChannelId(primary.id)
    }

    private suspend fun playCurrentStream(
        channel: ChannelEntity,
        now: ProgrammeEntity?,
        next: ProgrammeEntity?,
        notice: String?,
    ) {
        val stream = streams.getOrNull(streamIndex) ?: channel
        fun state(s: PlaybackState, error: String? = null) =
            NowPlaying(channel, now, next, s, error, stream = stream, streamCount = streams.size, notice = notice)
        watchdog?.cancel()
        streamStarted = false
        // BBC iPlayer channels store candidate service ids; fetch a current stream URL each play.
        val uri = if (BbcIplayer.isBbc(stream.streamUrl)) {
            _state.value = state(PlaybackState.BUFFERING)
            when (val r = withContext(Dispatchers.IO) { bbc.resolve(stream.streamUrl) }) {
                is BbcIplayer.Resolved.Ok -> r.url
                is BbcIplayer.Resolved.Failed -> {
                    withContext(Dispatchers.Main) { player.stop(); player.clearMediaItems() }
                    _state.value = state(PlaybackState.ERROR, r.reason)
                    return
                }
            }
        } else stream.streamUrl
        // Set the new channel *before* starting the player, then let the player's own events
        // drive the state. Setting BUFFERING afterwards could overwrite a PLAYING event that
        // already arrived, leaving the status stuck on "buffering" (seen with BBC iPlayer).
        _state.value = state(PlaybackState.BUFFERING)
        withContext(Dispatchers.Main) {
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(uri)
                    .setMediaId(stream.id)
                    .build()
            )
            player.prepare()
            player.playWhenReady = true
            sync()
        }
        // Some dead streams never error, they just buffer forever. Give up on them too.
        if (streamIndex + 1 < streams.size) {
            watchdog = scope.launch {
                delay(STALL_TIMEOUT_MS)
                if (!streamStarted && _state.value.stream?.id == stream.id) failover("didn't start")
            }
        }
    }

    /** Moves to the next stream of the current channel, keeping the banner on the same channel. */
    private fun failover(reason: String) {
        val current = _state.value
        val channel = current.channel ?: return
        if (streamIndex + 1 >= streams.size) return
        playJob?.cancel()
        playJob = scope.launch {
            streamIndex += 1
            val label = StreamQuality.parse(streams[streamIndex].name).label
            val notice = "Stream ${streamIndex} $reason — trying backup ${streamIndex + 1} of ${streams.size}" +
                (label?.let { " ($it)" } ?: "")
            playCurrentStream(channel, current.now, current.next, notice)
        }
    }

    /** Catch-up is possible for programmes that have ended (or started) within the archive window. */
    fun canCatchUp(channel: ChannelEntity, programme: ProgrammeEntity, now: Long = System.currentTimeMillis()): Boolean =
        channel.archiveDays > 0 &&
            programme.start < now &&
            programme.start > now - channel.archiveDays * 86_400_000L &&
            XtreamClient.timeshiftUrl(channel.streamUrl, programme.start, programme.stop, null) != null

    /** Plays [programme] from the provider's archive (Xtream timeshift). */
    /**
     * Plays [programme] from the provider's archive (Xtream timeshift), starting [offsetMs] into it.
     * Timeshift URLs start on whole minutes, so the offset is rounded down to a minute.
     */
    fun startCatchUp(channel: ChannelEntity, programme: ProgrammeEntity, scope: ZapScope? = null, offsetMs: Long = 0) {
        playJob?.cancel()
        watchdog?.cancel()
        playJob = this.scope.launch {
            // A merged row's archive may live on one of its other streams.
            val stream = if (channel.streamCount > 1) streamsFor(channel).firstOrNull { it.catchupDays > 0 } ?: channel else channel
            streams = listOf(stream)
            streamIndex = 0
            zapScope = scope ?: zapScope
            val length = programme.stop - programme.start
            val base = (offsetMs.coerceIn(0, (length - 60_000).coerceAtLeast(0)) / 60_000) * 60_000
            catchupBase = base
            val tz = db.sources().get(stream.sourceId)?.serverTimezone
            val url = XtreamClient.timeshiftUrl(stream.streamUrl, programme.start + base, programme.stop, tz)
            if (url == null) {
                _state.update { NowPlaying(channel, programme, null, PlaybackState.ERROR, "Catch-up not available", true) }
                return@launch
            }
            _state.update { NowPlaying(channel, programme, null, PlaybackState.BUFFERING, catchup = true) }
            withContext(Dispatchers.Main) {
                player.setMediaItem(MediaItem.Builder().setUri(url).setMediaId("${channel.id}@${programme.start}+$base").build())
                player.prepare()
                player.playWhenReady = true
                sync()
            }
        }
    }

    /** Where the current catch-up recording started, relative to the programme start. */
    @Volatile private var catchupBase = 0L

    /** Playback position within the programme when watching catch-up (ms from its start), else null. */
    suspend fun catchupPosition(): Long? {
        val s = _state.value
        if (!s.catchup || s.now == null) return null
        val pos = withContext(Dispatchers.Main) { player.currentPosition }
        return (catchupBase + pos).coerceIn(0, s.now.stop - s.now.start)
    }

    /** Can the programme on now be restarted / rewound (channel has an archive)? */
    fun canRewindLive(): Boolean {
        val s = _state.value
        val ch = s.channel ?: return false
        val now = s.now ?: return false
        return !s.catchup && canCatchUp(ch, now)
    }

    /**
     * Skips [deltaMs] (negative = back). In catch-up this seeks within the recording (or re-requests
     * it from the new point if the server doesn't allow seeking); on live TV going back switches to
     * catch-up of the current programme, if the channel has an archive.
     */
    fun seekBy(deltaMs: Long) {
        val s = _state.value
        val ch = s.channel ?: return
        val programme = s.now ?: return
        scope.launch {
            if (s.catchup) {
                val length = programme.stop - programme.start
                val target = ((catchupPosition() ?: 0L) + deltaMs).coerceIn(0, length - 1_000)
                val seekable = withContext(Dispatchers.Main) { player.isCurrentMediaItemSeekable && player.duration > 0 }
                if (seekable && target >= catchupBase) {
                    withContext(Dispatchers.Main) { player.seekTo(target - catchupBase) }
                } else {
                    startCatchUp(ch, programme, offsetMs = target)
                }
            } else if (deltaMs < 0 && canCatchUp(ch, programme)) {
                startCatchUp(ch, programme, offsetMs = System.currentTimeMillis() - programme.start + deltaMs)
            }
        }
    }

    /** Restart what's on: catch-up from the programme start. */
    fun restartProgramme() {
        val s = _state.value
        val ch = s.channel ?: return
        val programme = s.now ?: return
        if (s.catchup) seekBy(-(catchupBase + 24 * 3_600_000L)) // to the start
        else if (canCatchUp(ch, programme)) startCatchUp(ch, programme, offsetMs = 0)
    }

    /** Back to the live channel from catch-up. */
    fun goLive() {
        val ch = _state.value.channel ?: return
        start(ch, zapScope)
    }

    /** The channel [offset] places away in the current zap list (for browsing without tuning). */
    suspend fun channelAt(offset: Int): ChannelEntity? {
        val current = _state.value.channel ?: return null
        if (offset == 0) return current
        val dao = db.channels()
        val ids: List<String> = when (val z = zapScope) {
            ZapScope.Favourites -> dao.favourites().map { it.id }
            is ZapScope.Group -> dao.inGroup(z.name).map { it.id }
            ZapScope.All -> dao.visibleIds()
            is ZapScope.Sky -> dao.skyIds(z.section)
        }
        if (ids.isEmpty()) return null
        val index = ids.indexOf(current.id).coerceAtLeast(0)
        return dao.get(ids[Math.floorMod(index + offset, ids.size)])
    }

    /** A channel's programmes around now, for the info card's previous/next browsing. */
    suspend fun programmesAround(channel: ChannelEntity): List<ProgrammeEntity> {
        val now = System.currentTimeMillis()
        val back = if (channel.archiveDays > 0) 24 * 3_600_000L else 6 * 3_600_000L
        return runCatching { db.programmes().window(listOf(channel.epgKey), now - back, now + 24 * 3_600_000L) }
            .getOrDefault(emptyList()).sortedBy { it.start }
    }

    /** Current zap scope, so the UI can tune a browsed channel within the same list. */
    val currentScope: ZapScope get() = zapScope

    /** Channel up/down through the list playback started from, wrapping at the ends. */
    suspend fun step(delta: Int) {
        val currentId = _state.value.channel?.id
        val dao = db.channels()
        val ids: List<String> = when (val z = zapScope) {
            ZapScope.Favourites -> dao.favourites().map { it.id }
            is ZapScope.Group -> dao.inGroup(z.name).map { it.id }
            ZapScope.All -> dao.visibleIds()
            is ZapScope.Sky -> dao.skyIds(z.section)
        }
        if (ids.isEmpty()) return
        val index = ids.indexOf(currentId)
        val nextId = ids[if (index < 0) 0 else Math.floorMod(index + delta, ids.size)]
        dao.get(nextId)?.let { play(it, zapScope) }
    }

    suspend fun stop() {
        watchdog?.cancel()
        streams = emptyList()
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

    companion object {
        private const val STALL_TIMEOUT_MS = 12_000L

        /** Plain-language reason for a playback failure; falls back to ExoPlayer's code name. */
        fun describe(error: PlaybackException, catchup: Boolean): String {
            var cause: Throwable? = error
            while (cause != null) {
                val code = (cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                    ?: Regex("""\((\d{3})\)""").find(cause.message.orEmpty())?.groupValues?.get(1)?.toInt()
                        ?.takeIf { cause is java.net.ProtocolException }
                if (code != null) return when (code) {
                    401, 403 -> "provider refused the stream (HTTP $code) — check the account is active"
                    404 -> if (catchup) "provider has no recording of this programme (HTTP 404)" else "stream not found (HTTP 404)"
                    407, 429, 458 -> "provider refused the stream (HTTP $code) — usually the account's connection limit is in use"
                    in 500..599 -> "provider server error (HTTP $code)"
                    else -> "HTTP $code from provider"
                }
                cause = cause.cause
            }
            return error.errorCodeName
        }
    }

    private suspend fun refreshProgrammes() {
        if (_state.value.catchup) return // [now] is the archived programme being watched
        val channel = _state.value.channel ?: return
        val (current, next) = programmesFor(channel)
        _state.update { if (it.channel?.id == channel.id) it.copy(now = current, next = next) else it }
    }
}
