package dev.sysmoon.ferry.ui

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.sysmoon.ferry.data.ChannelEntity
import dev.sysmoon.ferry.data.ProgrammeEntity
import dev.sysmoon.ferry.data.StreamQuality
import dev.sysmoon.ferry.data.displayName
import dev.sysmoon.ferry.data.shownNumber
import dev.sysmoon.ferry.graph
import dev.sysmoon.ferry.player.PlayTarget
import dev.sysmoon.ferry.player.PlaybackState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Buttons in the player controls row, left to right. */
private enum class Control(val label: String) {
    RESTART("⟲  Restart"), BACK30("«  30s"), PLAY_PAUSE(""), FWD30("30s  »"), LIVE("●  Live"), STREAMS("≡  Streams")
}

private const val SKIP_MS = 30_000L

/**
 * Full-screen playback with a Sky-style info card:
 *  - OK shows the card for what's on. ▲▼ browse other channels on the card without tuning
 *    (the stream keeps playing); ◀▶ step through that channel's previous/next programmes.
 *  - OK on a browsed channel watches it; OK on a past programme plays it from catch-up.
 *  - OK on the card for what you're watching opens the controls row: restart, skip back/forward,
 *    play/pause, back to live, streams.
 *  - Channel +/- zap straight away; Back closes the card, then returns to the guide.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val player = graph.player
    val scope = rememberCoroutineScope()
    val state by player.state.collectAsState()
    val focus = remember { FocusRequester() }
    var menuOpen by remember { mutableStateOf(false) }

    // ---- Card state
    var cardVisible by remember { mutableStateOf(false) }
    var browseOffset by remember { mutableIntStateOf(0) }       // channels away from the one playing
    var browseChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var programmes by remember { mutableStateOf<List<ProgrammeEntity>>(emptyList()) }
    var progIndex by remember { mutableIntStateOf(-1) }        // selected programme on the card
    var controlsVisible by remember { mutableStateOf(false) }
    var control by remember { mutableStateOf(Control.PLAY_PAUSE) }
    var interaction by remember { mutableIntStateOf(0) }       // bumps reset the auto-hide timer
    var message by remember { mutableStateOf<String?>(null) }  // short hint, e.g. "Not on yet"
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) { delay(1_000); value = System.currentTimeMillis() }
    }

    fun openCard(offset: Int = 0) {
        browseOffset = offset; cardVisible = true; controlsVisible = false; message = null; interaction++
    }
    fun closeCard() { cardVisible = false; controlsVisible = false; browseOffset = 0; message = null }

    // Load the browsed channel and its programmes; select what's on (or the catch-up being watched).
    LaunchedEffect(cardVisible, browseOffset, state.channel?.id, state.now?.start, state.catchup) {
        if (!cardVisible) return@LaunchedEffect
        val ch = player.channelAt(browseOffset) ?: return@LaunchedEffect
        val list = player.programmesAround(ch)
        browseChannel = ch
        programmes = list
        val t = System.currentTimeMillis()
        progIndex = if (browseOffset == 0 && state.catchup && state.now != null) list.indexOfFirst { it.start == state.now!!.start }
            else list.indexOfFirst { it.start <= t && it.stop > t }
    }
    // Show the card on every channel change; hide it after a while without key presses.
    LaunchedEffect(state.channel?.id) { if (state.channel != null) openCard() }
    LaunchedEffect(cardVisible, interaction, controlsVisible) {
        if (!cardVisible) return@LaunchedEffect
        delay(if (controlsVisible) 12_000 else 7_000)
        closeCard()
    }
    // Catch-up: track the position inside the programme for the progress bar.
    val catchupPos by produceState<Long?>(null, state.catchup, state.now?.start) {
        while (state.catchup) { value = player.catchupPosition(); delay(500) }
        value = null
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // Typed channel number ("4", "40", "401"); tunes after a short pause, like a set-top box.
    var digits by remember { mutableStateOf("") }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(if (digits.length >= 3) 900 else 1_600)
        val n = digits.toInt()
        // Tune in the screen's scope: clearing [digits] restarts (cancels) this effect.
        scope.launch { player.play(PlayTarget.Number(n)) }
        digits = ""
    }
    // OK acts on release, so a long press can open the channel menu instead.
    var okLongPressed by remember { mutableStateOf(false) }

    val isCurrent = browseOffset == 0
    val selected = programmes.getOrNull(progIndex)
    val watchingSelected = isCurrent && selected != null && state.now?.start == selected.start &&
        (state.catchup || (selected.start <= nowMs && selected.stop > nowMs))
    val canRewind = state.catchup || player.canRewindLive()
    fun enabled(c: Control) = when (c) {
        Control.RESTART, Control.BACK30 -> canRewind
        Control.FWD30, Control.LIVE -> state.catchup
        Control.STREAMS -> state.streamCount > 1
        Control.PLAY_PAUSE -> true
    }

    fun activate(c: Control) {
        interaction++
        when (c) {
            Control.RESTART -> player.restartProgramme()
            Control.BACK30 -> player.seekBy(-SKIP_MS)
            Control.FWD30 -> player.seekBy(SKIP_MS)
            Control.PLAY_PAUSE -> scope.launch { player.togglePause() }
            Control.LIVE -> { player.goLive(); closeCard() }
            Control.STREAMS -> { menuOpen = true; closeCard() }
        }
    }

    fun confirm() {
        val ch = browseChannel ?: return
        val p = selected
        val t = System.currentTimeMillis()
        when {
            watchingSelected || (isCurrent && p == null) -> {
                controlsVisible = true
                control = Control.PLAY_PAUSE
                interaction++
            }
            p == null || (p.start <= t && p.stop > t) -> { player.start(ch, player.currentScope); closeCard() }
            p.stop <= t -> if (player.canCatchUp(ch, p, t)) { player.startCatchUp(ch, p, player.currentScope); closeCard() }
                else { message = "No catch-up for this programme"; interaction++ }
            else -> {
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(p.start))
                message = "Not on yet — starts at $time"; interaction++
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (ok && e.type == KeyEventType.KeyUp) {
                    if (!okLongPressed) when {
                        controlsVisible -> if (enabled(control)) activate(control)
                        cardVisible -> confirm()
                        else -> openCard()
                    }
                    okLongPressed = false
                    return@onKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (ok) {
                    if (e.nativeKeyEvent.repeatCount == 1 && state.channel != null) {
                        okLongPressed = true
                        menuOpen = true
                        closeCard()
                    }
                    return@onKeyEvent true
                }
                val digit = when (e.key) {
                    Key.Zero, Key.NumPad0 -> 0; Key.One, Key.NumPad1 -> 1; Key.Two, Key.NumPad2 -> 2
                    Key.Three, Key.NumPad3 -> 3; Key.Four, Key.NumPad4 -> 4; Key.Five, Key.NumPad5 -> 5
                    Key.Six, Key.NumPad6 -> 6; Key.Seven, Key.NumPad7 -> 7; Key.Eight, Key.NumPad8 -> 8
                    Key.Nine, Key.NumPad9 -> 9; else -> null
                }
                if (digit != null) {
                    if (digits.length < 4) digits += digit
                    return@onKeyEvent true
                }
                when (e.key) {
                    Key.DirectionUp, Key.DirectionDown -> {
                        val delta = if (e.key == Key.DirectionUp) 1 else -1 // up = next channel, like a remote
                        when {
                            controlsVisible -> { controlsVisible = false; interaction++ }
                            cardVisible -> { browseOffset += delta; message = null; interaction++ }
                            else -> openCard(delta)
                        }
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val delta = if (e.key == Key.DirectionRight) 1 else -1
                        when {
                            controlsVisible -> {
                                val all = Control.entries
                                var i = all.indexOf(control)
                                do { i += delta } while (i in all.indices && !enabled(all[i]))
                                if (i in all.indices) control = all[i]
                                interaction++
                            }
                            cardVisible -> {
                                if (programmes.isNotEmpty()) {
                                    progIndex = (if (progIndex < 0) 0 else progIndex + delta).coerceIn(0, programmes.lastIndex)
                                }
                                message = null; interaction++
                            }
                            else -> openCard()
                        }
                        true
                    }
                    Key.ChannelUp -> { player.zap(1); true }
                    Key.ChannelDown -> { player.zap(-1); true }
                    Key.Info -> { if (cardVisible) closeCard() else openCard(); true }
                    Key.Menu -> { if (state.channel != null) { menuOpen = true; closeCard() }; true }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { scope.launch { player.togglePause() }; true }
                    Key.MediaFastForward -> { player.seekBy(SKIP_MS); true }
                    Key.MediaRewind -> { player.seekBy(-SKIP_MS); true }
                    Key.Back, Key.Escape -> {
                        when {
                            controlsVisible -> { controlsVisible = false; interaction++ }
                            cardVisible -> closeCard()
                            else -> onBack()
                        }
                        true
                    }
                    Key.Guide -> { onBack(); true }
                    else -> false
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    isFocusable = false
                    this.player = graph.player.player
                }
            },
            onRelease = { it.player = null },
        )

        if (digits.isNotEmpty()) {
            Text(
                digits,
                style = MaterialTheme.typography.displayMedium,
                color = FerryColors.Gold,
                modifier = Modifier.align(Alignment.TopEnd).padding(48.dp)
                    .clip(RoundedCornerShape(12.dp)).background(FerryColors.Deep.copy(alpha = 0.85f))
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        if (state.state == PlaybackState.ERROR && !cardVisible) {
            Text(
                if (state.catchup) "Couldn't play this recording (${state.error ?: "unknown error"}). OK for options, Back for the guide."
                else if (state.streamCount > 1) "None of this channel's ${state.streamCount} streams would play (${state.error ?: "unknown error"}). ▲▼ to pick another channel."
                else "Couldn't play this channel (${state.error ?: "unknown error"}). ▲▼ to pick another channel.",
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp))
                    .background(FerryColors.Navy).padding(24.dp),
            )
        }

        AnimatedVisibility(
            visible = cardVisible && browseChannel != null,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val ch = browseChannel ?: return@AnimatedVisibility
            InfoCard(
                channel = ch,
                programmes = programmes,
                index = progIndex,
                nowMs = nowMs,
                isCurrent = isCurrent,
                watchingSelected = watchingSelected,
                catchup = state.catchup,
                catchupPos = catchupPos,
                buffering = isCurrent && state.state == PlaybackState.BUFFERING,
                paused = isCurrent && state.state == PlaybackState.PAUSED,
                streamLabel = if (isCurrent && state.streamCount > 1) state.stream?.let { StreamQuality.parse(it.name).label } else null,
                notice = if (isCurrent) state.notice else null,
                message = message,
                canCatchUp = { p -> player.canCatchUp(ch, p, nowMs) },
                controls = if (controlsVisible) Control.entries.map { it to enabled(it) } else null,
                focusedControl = control,
            )
        }
    }

    if (menuOpen) {
        state.channel?.let { ch ->
            ChannelMenu(
                ch,
                zap = null,
                onDismiss = {
                    menuOpen = false
                    okLongPressed = false
                    runCatching { focus.requestFocus() }
                },
                onPlayed = {},
            )
        }
    }
}

@Composable
private fun InfoCard(
    channel: ChannelEntity,
    programmes: List<ProgrammeEntity>,
    index: Int,
    nowMs: Long,
    isCurrent: Boolean,
    watchingSelected: Boolean,
    catchup: Boolean,
    catchupPos: Long?,
    buffering: Boolean,
    paused: Boolean,
    streamLabel: String?,
    notice: String?,
    message: String?,
    canCatchUp: (ProgrammeEntity) -> Boolean,
    controls: List<Pair<Control, Boolean>>?,
    focusedControl: Control,
) {
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val day = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val p = programmes.getOrNull(index)
    val prev = programmes.getOrNull(index - 1)
    val next = programmes.getOrNull(index + 1)

    Column(
        Modifier
            .padding(horizontal = 40.dp, vertical = 24.dp)
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(FerryColors.Deep.copy(alpha = 0.93f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        // Channel row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${channel.shownNumber}", style = MaterialTheme.typography.titleMedium, color = FerryColors.Muted)
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(width = 52.dp, height = 32.dp).clip(RoundedCornerShape(6.dp)).background(FerryColors.Navy),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logo != null) AsyncImage(channel.logo, null, Modifier.padding(3.dp))
                else Text(channel.displayName.take(3).uppercase(), color = FerryColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(10.dp))
            Text(channel.displayName, style = MaterialTheme.typography.titleMedium, color = FerryColors.Gold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(14.dp))
            when {
                !isCurrent -> Pill("PREVIEW — OK TO WATCH", FerryColors.Sea)
                catchup -> Pill("CATCH-UP", FerryColors.Sea)
                else -> Pill("WATCHING", FerryColors.Gold)
            }
            if (paused) Pill("PAUSED", FerryColors.Muted)
            if (buffering) Text("   Tuning…", color = FerryColors.Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            streamLabel?.let { Text(it, color = FerryColors.Muted, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.width(18.dp))
            Text(time.format(Date(nowMs)), style = MaterialTheme.typography.titleSmall, color = FerryColors.Muted)
        }
        notice?.let { Text(it, color = FerryColors.Sea, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(Modifier.height(8.dp))

        if (p == null) {
            Text("No guide data for this channel", style = MaterialTheme.typography.titleSmall, color = FerryColors.Muted)
        } else {
            // Neighbouring programmes: ◀ previous · selected · next ▶
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(prev?.let { "◀  ${time.format(Date(it.start))}  ${it.title}" } ?: "",
                    color = FerryColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                Text(next?.let { "${time.format(Date(it.start))}  ${it.title}  ▶" } ?: "",
                    color = FerryColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Spacer(Modifier.height(6.dp))
            Text(p.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                val dayText = if (sameDay(p.start, nowMs)) "" else day.format(Date(p.start)) + "  "
                Text("$dayText${time.format(Date(p.start))} – ${time.format(Date(p.stop))}  ·  ${(p.stop - p.start) / 60_000} min",
                    color = FerryColors.Muted, style = MaterialTheme.typography.bodySmall)
                when {
                    p.start <= nowMs && p.stop > nowMs -> Pill("LIVE", FerryColors.Gold)
                    p.stop <= nowMs && canCatchUp(p) -> Pill("↺ CATCH-UP", FerryColors.Sea)
                    p.stop <= nowMs -> Pill("ENDED", FerryColors.Muted)
                    else -> Pill("LATER", FerryColors.Muted)
                }
            }
            // Progress: real position when watching catch-up, wall clock for live.
            val length = (p.stop - p.start).coerceAtLeast(1)
            val position: Long? = when {
                watchingSelected && catchup -> catchupPos
                p.start <= nowMs && p.stop > nowMs -> nowMs - p.start
                else -> null
            }
            if (position != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(FerryColors.Navy)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((position.toFloat() / length).coerceIn(0f, 1f))
                            .background(if (watchingSelected && catchup) FerryColors.Sea else FerryColors.Gold))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (watchingSelected && catchup) "${time.format(Date(p.start + position))} · ${(length - position) / 60_000} min left"
                        else "${(length - position) / 60_000} min left",
                        color = FerryColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    )
                }
            }
            p.description?.let {
                Text(it, color = FerryColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }

        if (controls != null) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                controls.forEach { (c, on) ->
                    val focused = c == focusedControl
                    val label = if (c == Control.PLAY_PAUSE) (if (paused) "▶  Play" else "❚❚  Pause") else c.label
                    Text(
                        label,
                        color = when { focused -> FerryColors.Deep; on -> FerryColors.Text; else -> FerryColors.Muted.copy(alpha = 0.45f) },
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(18.dp))
                            .background(if (focused) FerryColors.Gold else FerryColors.Navy)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            message ?: when {
                controls != null -> "◀▶ choose  ·  OK select  ·  ▲ or Back to close"
                !isCurrent && p != null && p.stop <= nowMs && canCatchUp(p) -> "OK  watch from the start  ·  ▲▼ channels  ·  ◀▶ programmes"
                !isCurrent -> "OK  watch this channel  ·  ▲▼ channels  ·  ◀▶ programmes  ·  Back to close"
                watchingSelected -> "OK  controls  ·  ▲▼ channels  ·  ◀▶ programmes"
                p != null && p.stop <= nowMs && canCatchUp(p) -> "OK  watch from the start  ·  ◀▶ programmes"
                else -> "▲▼ channels  ·  ◀▶ programmes  ·  Back to close"
            },
            color = if (message != null) FerryColors.Sea else FerryColors.Muted.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text,
        color = FerryColors.Deep,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(4.dp)).background(color)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

private fun sameDay(a: Long, b: Long): Boolean {
    val z = java.time.ZoneId.systemDefault()
    return java.time.Instant.ofEpochMilli(a).atZone(z).toLocalDate() == java.time.Instant.ofEpochMilli(b).atZone(z).toLocalDate()
}
