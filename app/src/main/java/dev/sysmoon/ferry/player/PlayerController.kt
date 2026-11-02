package dev.sysmoon.ferry.player

import dev.sysmoon.ferry.Logs
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    /** Recently watched, newest first. */
    data object Recent : ZapScope
    /** A fixed list, e.g. the streams that might show a football match. */
    data class Ids(val label: String, val ids: List<String>) : ZapScope
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
    /** The stream on screen is HDR (PQ/HLG); false again after falling back to an SDR backup. */
    val hdr: Boolean = false,
) {
    val progress: Float?
        get() = now?.let {
            val span = (it.stop - it.start).coerceAtLeast(1)
            ((System.currentTimeMillis() - it.start).toFloat() / span).coerceIn(0f, 1f)
        }
}

/** How the picture fills a 16:9 screen (PlayerView resize modes). */
enum class Zoom(val label: String) { FIT("Fit"), FILL("Stretch"), ZOOM("Zoom") }

/** What the player is actually doing, for the stats overlay, `/api/status` and Home Assistant. */
data class PlayerStats(
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    /** Declared video bitrate (bit/s), or 0 when the stream doesn't say. */
    val bitrate: Int = 0,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int = 0,
    val decoder: String? = null,
    val droppedFrames: Int = 0,
    val renderedFrames: Int = 0,
    val bufferedMs: Long = 0,
    val tunnelled: Boolean = false,
    val hdr: Boolean = false,
) {
    val resolution: String? get() = if (width > 0) "${width}×$height" else null
}

/** An audio or subtitle track the viewer can pick. */
data class TrackOption(val group: Int, val track: Int, val label: String, val selected: Boolean)

/** Things worth telling Home Assistant about as they happen. */
sealed interface PlayerEvent {
    data class Failover(val reason: String, val fromStream: Int, val toStream: Int, val streamCount: Int, val channel: String) : PlayerEvent
    data class Backstop(val reason: String, val action: String, val channel: String) : PlayerEvent
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
    appScope: CoroutineScope,
    private val bbc: BbcIplayer,
) {
    /**
     * Everything the controller launches runs on the main thread: ExoPlayer may only be touched
     * there, and a timer that read it from a background thread crashed Ferry. Database and
     * settings calls are suspend functions that move off the main thread themselves.
     */
    private val scope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()
    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()
    private val _zoom = MutableStateFlow(Zoom.FIT)
    val zoom: StateFlow<Zoom> = _zoom.asStateFlow()

    val player: ExoPlayer
    private val trackSelector: androidx.media3.exoplayer.trackselection.DefaultTrackSelector
    /** Tunnelled playback is on (see [Settings.tunnelling]). */
    @Volatile var tunnelling: Boolean = false
        private set
    @Volatile private var decoderName_: String? = null

    init {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ChannelRepository.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
            .setConstantBitrateSeekingEnabled(true)
        // Live IPTV: start fast (1.5 s), then build a deep buffer so a wobbly Wi-Fi moment doesn't
        // show as "buffering" — and after one does, wait for 5 s of video before resuming so it
        // doesn't happen again straight away. Capped in bytes too, so 4K streams don't eat memory
        // (48 MB ≈ 15 s at 25 Mbit/s, which is plenty; SD streams get the full 50 s).
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_500, 5_000)
            .setTargetBufferBytes(48 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        // Decoder fallback: if the TV's preferred HEVC/AVC decoder fails to start, try the next
        // one rather than erroring. Tunnelling: video goes straight from decoder to display with
        // A/V sync done in hardware — on TV chipsets (MediaTek, Tegra) it's the difference between
        // smooth 4K HDR and dropped frames. Only used where the decoder supports it.
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context)
        // Off until the saved setting is read: a box that couldn't do it last time never tries again.
        scope.launch {
            tunnelling = settings.tunnelling()
            trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(tunnelling))
            _zoom.value = runCatching { Zoom.valueOf(settings.zoom()) }.getOrDefault(Zoom.FIT)
        }
        player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http, extractors))
            .setLoadControl(load)
            .setTrackSelector(trackSelector)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val live = !_state.value.catchup && _state.value.channel != null
                // A live channel never really "ends": the provider dropped the connection.
                if (playbackState == Player.STATE_ENDED && live) {
                    reconnect("dropped")
                    return
                }
                // Mid-stream stall: it was playing, then buffers and never comes back.
                stallJob?.cancel()
                if (playbackState == Player.STATE_BUFFERING && live && streamStarted) {
                    val id = _state.value.stream?.id
                    // On the main thread: ExoPlayer may only be touched there (reading its state from a
                    // background thread crashed Ferry when a stream hung).
                    stallJob = scope.launch(Dispatchers.Main) {
                        delay(MID_STALL_TIMEOUT_MS)
                        if (player.playbackState == Player.STATE_BUFFERING && _state.value.stream?.id == id) reconnect("stalled")
                    }
                }
                sync()
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val transfer = player.videoFormat?.colorInfo?.colorTransfer
                val hdr = transfer == androidx.media3.common.C.COLOR_TRANSFER_ST2084 ||
                    transfer == androidx.media3.common.C.COLOR_TRANSFER_HLG
                if (_state.value.hdr != hdr) _state.update { it.copy(hdr = hdr) }
                player.videoFormat?.let { f ->
                    Logs.i(TAG, "Tracks: ${f.width}×${f.height}@${f.frameRate.toInt()} ${f.sampleMimeType} ${f.bitrate / 1000} kbit/s" +
                        (player.audioFormat?.let { a -> ", audio ${a.sampleMimeType} ${a.channelCount}ch" } ?: "") +
                        (if (hdr) ", HDR" else "") + (if (tunnelling) ", tunnelled" else ""))
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                stableJob?.cancel()
                if (isPlaying) {
                    streamStarted = true
                    watchdog?.cancel()
                    // Playing steadily again: a later failure starts a fresh round of backups.
                    stableJob = scope.launch(Dispatchers.Main) {
                        delay(STABLE_MS)
                        recovery.stable()
                    }
                }
                sync()
            }
            override fun onPlayerError(error: PlaybackException) {
                Logs.w(TAG, "Player error ${error.errorCodeName} on ${_state.value.stream?.name}: ${error.cause?.message ?: error.message}")
                // Some TVs (the Philips' MediaTek audio HAL, for one) can't open a tunnelled audio
                // track. Turn tunnelling off for good on this box and carry on with the same stream.
                if (tunnelling && (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)) {
                    Logs.w(TAG, "Tunnelled playback failed (${error.errorCodeName}): switching it off on this TV")
                    tunnelling = false
                    trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(false))
                    scope.launch { settings.setTunnelling(false) }
                    player.prepare()
                    player.playWhenReady = true
                    return
                }
                val live = !_state.value.catchup && _state.value.channel != null
                // HLS fell behind the live window (common after a hiccup): jump back to live
                // on the same stream rather than treating it as dead.
                if (live && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                if (live) {
                    // Before or during playback: move on to the channel's other streams,
                    // then keep retrying for a bit before giving up.
                    reconnect("failed", error)
                } else {
                    _state.update { it.copy(state = PlaybackState.ERROR, error = describe(error, it.catchup)) }
                }
            }
        })
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long,
            ) {
                decoderName_ = decoderName
                Logs.i(TAG, "Video decoder $decoderName (${initializationDurationMs} ms)")
            }
        })
        // Keep now/next fresh while something is playing.
        scope.launch {
            while (isActive) {
                delay(30_000)
                refreshProgrammes()
            }
        }
        // Backstop for anything the event-driven recovery misses.
        scope.launch {
            while (isActive) {
                delay(BACKSTOP_TICK_MS)
                backstop(System.currentTimeMillis())
            }
        }
    }

    // ---------- Stats, tracks, zoom

    /** A snapshot of what the player is doing (main thread). */
    suspend fun stats(): PlayerStats = withContext(Dispatchers.Main.immediate) {
        val v = player.videoFormat
        val a = player.audioFormat
        val c = player.videoDecoderCounters?.also { it.ensureUpdated() }
        PlayerStats(
            width = v?.width?.takeIf { it > 0 } ?: 0,
            height = v?.height?.takeIf { it > 0 } ?: 0,
            frameRate = v?.frameRate?.takeIf { it > 0 } ?: 0f,
            bitrate = v?.bitrate?.takeIf { it > 0 } ?: 0,
            videoCodec = v?.sampleMimeType?.substringAfterLast('/')?.let(::codecName),
            audioCodec = a?.sampleMimeType?.substringAfterLast('/')?.let(::codecName),
            audioChannels = a?.channelCount?.takeIf { it > 0 } ?: 0,
            decoder = decoderName_,
            droppedFrames = c?.droppedBufferCount ?: 0,
            renderedFrames = c?.renderedOutputBufferCount ?: 0,
            bufferedMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0),
            tunnelled = tunnelling,
            hdr = _state.value.hdr,
        )
    }

    private fun codecName(mime: String) = when (mime.lowercase()) {
        "avc" -> "H.264"; "hevc" -> "HEVC"; "mp4a-latm" -> "AAC"; "ac3" -> "AC-3"; "eac3" -> "E-AC-3"
        "mpeg" -> "MP3"; "mpeg-l2" -> "MP2"; "av01" -> "AV1"; "x-vnd.on2.vp9" -> "VP9"
        else -> mime.uppercase()
    }

    /** The audio tracks of what's playing, in the order the stream lists them. */
    suspend fun audioTracks(): List<TrackOption> = tracks(androidx.media3.common.C.TRACK_TYPE_AUDIO)
    /** Subtitle tracks of what's playing (none for most live streams). */
    suspend fun textTracks(): List<TrackOption> = tracks(androidx.media3.common.C.TRACK_TYPE_TEXT)

    private suspend fun tracks(type: Int): List<TrackOption> = withContext(Dispatchers.Main.immediate) {
        val out = mutableListOf<TrackOption>()
        player.currentTracks.groups.forEachIndexed { gi, g ->
            if (g.type != type) return@forEachIndexed
            for (ti in 0 until g.length) {
                if (!g.isTrackSupported(ti)) continue
                val f = g.getTrackFormat(ti)
                val lang = f.language?.takeIf { it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
                val parts = listOfNotNull(
                    f.label ?: lang,
                    if (type == androidx.media3.common.C.TRACK_TYPE_AUDIO) f.sampleMimeType?.substringAfterLast('/')?.let(::codecName) else null,
                    if (type == androidx.media3.common.C.TRACK_TYPE_AUDIO && f.channelCount > 0) "${f.channelCount}ch" else null,
                    if ((f.roleFlags and androidx.media3.common.C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) "audio description" else null,
                    if ((f.roleFlags and androidx.media3.common.C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0) "hard of hearing" else null,
                )
                out += TrackOption(gi, ti, parts.ifEmpty { listOf("Track ${out.size + 1}") }.joinToString(" · "), g.isTrackSelected(ti))
            }
        }
        out
    }

    /** Picks one audio track (by its [TrackOption]). */
    fun selectAudio(option: TrackOption) = selectTrack(androidx.media3.common.C.TRACK_TYPE_AUDIO, option)

    /** Picks a subtitle track, or none when [option] is null. */
    fun selectText(option: TrackOption?) = selectTrack(androidx.media3.common.C.TRACK_TYPE_TEXT, option)

    private fun selectTrack(type: Int, option: TrackOption?) {
        scope.launch {
            val groups = player.currentTracks.groups
            val b = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
            if (option == null) {
                b.setTrackTypeDisabled(type, true)
            } else {
                val g = groups.getOrNull(option.group) ?: return@launch
                b.setTrackTypeDisabled(type, false)
                b.setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, option.track))
            }
            player.trackSelectionParameters = b.build()
            Logs.i(TAG, "Selected ${if (type == androidx.media3.common.C.TRACK_TYPE_AUDIO) "audio" else "subtitles"}: ${option?.label ?: "off"}")
        }
    }

    /** Fit → Stretch → Zoom → Fit. Remembered across launches. */
    fun cycleZoom(): Zoom {
        val next = Zoom.entries[(_zoom.value.ordinal + 1) % Zoom.entries.size]
        _zoom.value = next
        scope.launch { settings.setZoom(next.name) }
        return next
    }

    // ---------- Backstop
    private var stuckSince = 0L
    private var lastFrames = -1L
    private var lastPosition = -1L

    /**
     * Every few seconds: is a live channel meant to be on but nothing is moving? Picture frozen
     * (no new frames), position not advancing, buffering, or stuck on an error. After a minute of
     * that, retune from scratch whatever state the player is in: first the same stream, then the
     * channel's next stream. At most 4 times in 10 minutes, so a dead channel isn't hammered.
     */
    private fun backstop(now: Long) {
        val s = _state.value
        val wanted = s.channel != null && !s.catchup && streams.isNotEmpty() &&
            (player.playWhenReady || s.state == PlaybackState.ERROR)
        if (!wanted) { stuckSince = 0; return }
        // In tunnelled mode the decoder renders straight to the display and the frame counters
        // don't always tick, so only the position is trusted there. Audio-only streams have no
        // video counters either: the position alone says it's moving.
        val frames = if (tunnelling) null else player.videoDecoderCounters?.let {
            it.ensureUpdated()
            (it.renderedOutputBufferCount + it.skippedOutputBufferCount + it.droppedBufferCount).toLong()
        }
        val position = player.currentPosition
        val moving = player.isPlaying && position != lastPosition && (frames == null || frames != lastFrames)
        lastFrames = frames ?: -1
        lastPosition = position
        if (moving) { stuckSince = 0; return }
        if (stuckSince == 0L) { stuckSince = now; return }
        if (now - stuckSince < BACKSTOP_STUCK_MS) return
        stuckSince = 0
        val action = recovery.onBackstop() ?: return
        val why = when {
            s.state == PlaybackState.ERROR -> "was stuck on an error"
            player.playbackState == Player.STATE_BUFFERING -> "kept buffering"
            else -> "froze"
        }
        Logs.w(TAG, "Backstop: stream $why for a minute, $action (state=${s.state}, exo=${player.playbackState})")
        val channel = s.channel ?: return
        _events.tryEmit(PlayerEvent.Backstop(why, if (action is Recovery.Action.Switch) "switch to stream ${action.index + 1}" else "retune", channel.name))
        player.stop()
        playJob?.cancel()
        playJob = scope.launch {
            val notice = if (action is Recovery.Action.Switch) "Stream $why — switching to stream ${action.index + 1} of ${streams.size}"
                else "Stream $why — retuning"
            playCurrentStream(channel, s.now, s.next, notice)
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
    /** Which stream of [streams] is on, and what to do when it fails. */
    private val recovery = Recovery()
    private val streamIndex: Int get() = recovery.index
    @Volatile private var streamStarted = false
    private var watchdog: Job? = null
    private var stallJob: Job? = null
    private var stableJob: Job? = null

    /**
     * Fire-and-forget start for UI callers. Runs in the app scope so it survives the guide
     * leaving the screen (BBC channels fetch a stream URL first and were being cancelled),
     * and cancels any start still in flight so rapid zapping can't land out of order.
     */
    fun start(channel: ChannelEntity, scope: ZapScope? = null) {
        // Already playing this channel live (e.g. picked it in the guide while it's in the mini
        // player): just carry on, don't retune.
        val cur = _state.value
        if (cur.channel?.id == (channel.primaryId ?: channel.id) && !cur.catchup && cur.error == null && streams.isNotEmpty()) {
            if (scope != null) zapScope = scope
            return
        }
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
        Logs.i(TAG, "Tune ${primary.name} (${list.size} stream${if (list.size == 1) "" else "s"})")
        streams = list
        recovery.tuned(list.size, list.indexOfFirst { it.id == channel.id }.coerceAtLeast(0))
        // Look up now/next first so the banner never flashes "No guide data".
        val (now, next) = programmesFor(primary)
        playCurrentStream(primary, now, next, notice = null)
        settings.setLastChannelId(primary.id)
        settings.pushRecent(primary.id)
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
        stallJob?.cancel()
        stableJob?.cancel()
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
        if (streams.size > 1) {
            watchdog = scope.launch(Dispatchers.Main) {
                delay(STALL_TIMEOUT_MS)
                if (!streamStarted && _state.value.stream?.id == stream.id) reconnect("didn't start")
            }
        }
    }

    /**
     * The live stream failed, dropped or stalled (before or during playback). [Recovery] decides:
     * restart the same stream, switch to the channel's next untried stream, retry a round from the
     * best one after a pause, or give up.
     */
    private fun reconnect(reason: String, error: PlaybackException? = null) {
        val current = _state.value
        val channel = current.channel ?: return
        val from = streamIndex
        val action = recovery.onFailure(hadStarted = streamStarted)
        Logs.w(TAG, "Stream ${from + 1}/${streams.size} of ${channel.name} $reason (started=$streamStarted): $action")
        if (action is Recovery.Action.Switch) _events.tryEmit(PlayerEvent.Failover(reason, from + 1, action.index + 1, streams.size, channel.name))
        when (action) {
            Recovery.Action.RestartSame -> {
                playJob?.cancel()
                playJob = scope.launch {
                    playCurrentStream(channel, current.now, current.next, notice = "Stream $reason — restarting it")
                }
            }
            is Recovery.Action.Switch -> {
                playJob?.cancel()
                playJob = scope.launch {
                    val label = StreamQuality.parse(streams[action.index].name).label
                    val notice = "Stream ${from + 1} $reason — switching to stream ${action.index + 1} of ${streams.size}" +
                        (label?.let { " ($it)" } ?: "")
                    playCurrentStream(channel, current.now, current.next, notice)
                }
            }
            Recovery.Action.RetryRound -> {
                playJob?.cancel()
                playJob = scope.launch {
                    val what = if (streams.size > 1) "All ${streams.size} streams $reason" else "Stream $reason"
                    _state.update { it.copy(state = PlaybackState.BUFFERING, notice = "$what — reconnecting…") }
                    delay(1_500)
                    playCurrentStream(channel, current.now, current.next, notice = "Reconnected after the stream $reason")
                }
            }
            Recovery.Action.GiveUp -> {
                val msg = when {
                    error != null -> describe(error, false)
                    else -> "the provider keeps dropping the stream — another device may be using the account"
                }
                _state.update { it.copy(state = PlaybackState.ERROR, error = msg) }
            }
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
            recovery.tuned(1, 0)
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
        val ids = scopeIds(zapScope)
        if (ids.isEmpty()) return null
        val index = ids.indexOf(current.id).coerceAtLeast(0)
        return dao.get(ids[Math.floorMod(index + offset, ids.size)])
    }

    private suspend fun scopeIds(z: ZapScope): List<String> {
        val dao = db.channels()
        return when (z) {
            ZapScope.Favourites -> dao.favourites().map { it.id }
            is ZapScope.Group -> dao.inGroup(z.name).map { it.id }
            ZapScope.All -> dao.visibleIds()
            is ZapScope.Sky -> dao.skyIds(z.section)
            ZapScope.Recent -> settings.recents()
            is ZapScope.Ids -> z.ids
        }
    }

    /** Flips back to the channel watched before this one. */
    fun previousChannel() {
        playJob?.cancel()
        playJob = scope.launch {
            val current = _state.value.channel?.id
            val id = settings.recents().firstOrNull { it != current } ?: return@launch
            db.channels().get(id)?.let { play(it, zapScope) }
        }
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
        val ids = scopeIds(zapScope)
        if (ids.isEmpty()) return
        val index = ids.indexOf(currentId)
        val nextId = ids[if (index < 0) 0 else Math.floorMod(index + delta, ids.size)]
        dao.get(nextId)?.let { play(it, zapScope) }
    }

    /**
     * Everything on the main thread in one go, with any tune still in flight cancelled first:
     * otherwise a stop racing a start could reset the state while the player went on to play
     * (seen as "playing" with no channel in Home Assistant).
     */
    suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        playJob?.cancel()
        watchdog?.cancel()
        stallJob?.cancel()
        stableJob?.cancel()
        streams = emptyList()
        player.stop(); player.clearMediaItems()
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
        private const val TAG = "FerryPlayer"
        private const val STALL_TIMEOUT_MS = 12_000L
        /** Buffering this long after the stream was playing counts as a failure. */
        private const val MID_STALL_TIMEOUT_MS = 15_000L
        /** Playing this long counts as steady: the next failure gets a fresh round of backups. */
        private const val STABLE_MS = 30_000L
        private const val BACKSTOP_TICK_MS = 5_000L
        /** Nothing moving this long (in any state) and the backstop retunes. */
        private const val BACKSTOP_STUCK_MS = 60_000L

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
