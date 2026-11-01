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
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.tween
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
import dev.sysmoon.ferry.data.epgKey
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
    RESTART("⟲ Restart"), BACK30("« 30s"), PLAY_PAUSE(""), FWD30("30s »"), LIVE("● Live"),
    PREVIOUS("↩ Previous"), SLEEP(""), STREAMS("≡ Streams")
}

private const val SKIP_MS = 30_000L

/** Channel the info card last popped up for; survives the player screen leaving composition. */
private var lastCardedChannel: String? = null

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
    val recents by remember { graph.settings.recents }.collectAsState(initial = emptyList())
    val sleepEnds by graph.sleep.endsAt.collectAsState()
    val reminderList by graph.reminders.reminders.collectAsState()
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
    // The card pops up when the channel changes, not when coming back to full screen from the
    // mini player on the same channel.
    LaunchedEffect(state.channel?.id) {
        val id = state.channel?.id ?: return@LaunchedEffect
        if (id != lastCardedChannel) { lastCardedChannel = id; openCard() }
    }
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
        Control.PREVIOUS -> recents.any { it != state.channel?.id }
        Control.PLAY_PAUSE, Control.SLEEP -> true
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
            Control.PREVIOUS -> player.previousChannel()
            Control.SLEEP -> graph.sleep.cycle()
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
            else -> scope.launch {
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(p.start))
                message = if (graph.reminders.toggle(ch, p)) "🔔 Reminder set — Ferry will let you know at $time" else "Reminder removed"
                interaction++
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
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
                    Key.LastChannel -> { player.previousChannel(); true }
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
        // The picture itself is MainActivity's VideoLayer (shared with the mini player), drawn
        // underneath this screen's card and controls.

        if (digits.isNotEmpty()) {
            Text(
                digits,
                style = MaterialTheme.typography.displayMedium,
                color = FerryColors.Text,
                modifier = Modifier.align(Alignment.TopEnd).padding(48.dp)
                    .clip(RoundedCornerShape(16.dp)).background(FerryColors.Deep.copy(alpha = 0.88f))
                    .border(BorderStroke(1.dp, FerryColors.Line), RoundedCornerShape(16.dp))
                    .padding(horizontal = 28.dp, vertical = 6.dp),
            )
        }

        if (state.state == PlaybackState.ERROR && !cardVisible) {
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 640.dp).clip(RoundedCornerShape(20.dp))
                    .background(FerryColors.Navy).border(BorderStroke(1.dp, FerryColors.Line), RoundedCornerShape(20.dp))
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.tv.material3.Icon(FerryIcons.Warning, null, tint = FerryColors.Gold, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        state.catchup -> "Couldn't play this recording"
                        state.streamCount > 1 -> "None of this channel's ${state.streamCount} streams would play"
                        else -> "Couldn't play this channel"
                    },
                    style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(state.error ?: "unknown error", color = FerryColors.Muted, style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Hint(if (state.catchup) "OK  options   ·   Back  guide" else "▲ ▼  another channel   ·   Back  guide")
            }
        }

        AnimatedVisibility(
            visible = cardVisible && browseChannel != null,
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 4 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 4 },
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
                streamLabels = listOfNotNull(
                    if (isCurrent && state.streamCount > 1) state.stream?.let { StreamQuality.parse(it.name).label } else null,
                    if (isCurrent && state.hdr) "HDR" else null,
                ),
                notice = if (isCurrent) state.notice else null,
                message = message,
                canCatchUp = { p -> player.canCatchUp(ch, p, nowMs) },
                controls = if (controlsVisible) Control.entries.map { it to enabled(it) } else null,
                focusedControl = control,
                hasReminder = { p -> reminderList.any { it.epgKey == ch.epgKey && it.start == p.start } },
                sleepMinutes = sleepEnds?.let { ((it - nowMs + 59_999) / 60_000).toInt() },
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
    streamLabels: List<String>,
    notice: String?,
    message: String?,
    canCatchUp: (ProgrammeEntity) -> Boolean,
    controls: List<Pair<Control, Boolean>>?,
    focusedControl: Control,
    hasReminder: (ProgrammeEntity) -> Boolean,
    sleepMinutes: Int?,
) {
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val day = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val p = programmes.getOrNull(index)
    val prev = programmes.getOrNull(index - 1)
    val next = programmes.getOrNull(index + 1)
    val tabular = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR)

    // A scrim rising from the bottom of the picture, with everything set on one left margin.
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.35f to Color.Black.copy(alpha = 0.55f),
                    1f to Color.Black.copy(alpha = 0.92f),
                )
            )
            .padding(start = 56.dp, end = 56.dp, top = 96.dp, bottom = 34.dp)
    ) {
        // Channel row
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LogoChip(channel.logo, channel.displayName, width = 58.dp, height = 36.dp)
            Spacer(Modifier.width(12.dp))
            Text("${channel.shownNumber}", style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = TABULAR), color = FerryColors.Faint)
            Spacer(Modifier.width(10.dp))
            Text(channel.displayName, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 360.dp))
            Spacer(Modifier.width(14.dp))
            when {
                !isCurrent -> Tag("Preview", FerryColors.Sea)
                catchup -> Tag("Catch-up", FerryColors.Sea)
                else -> Tag("Watching", FerryColors.Gold)
            }
            if (paused) { Spacer(Modifier.width(8.dp)); Tag("Paused", FerryColors.Muted, filled = false) }
            sleepMinutes?.let { Spacer(Modifier.width(8.dp)); Tag("Sleep ${it}m", FerryColors.Muted, filled = false) }
            if (buffering) { Spacer(Modifier.width(12.dp)); Text("Tuning…", color = FerryColors.Muted, style = MaterialTheme.typography.labelMedium) }
            Spacer(Modifier.weight(1f))
            streamLabels.forEach { Spacer(Modifier.width(8.dp)); Tag(it, FerryColors.Muted, filled = false) }
            Spacer(Modifier.width(20.dp))
            Text(time.format(Date(nowMs)), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TABULAR), color = FerryColors.Muted)
        }
        notice?.let {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                androidx.tv.material3.Icon(FerryIcons.Info, null, tint = FerryColors.Sea, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(it, color = FerryColors.Sea, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(14.dp))

        if (p == null) {
            Text("No guide data for this channel", style = MaterialTheme.typography.titleLarge, color = FerryColors.Muted)
        } else {
            // Neighbouring programmes: ◀ previous · selected · next ▶
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (prev != null) {
                    androidx.tv.material3.Icon(FerryIcons.ChevronLeft, null, tint = FerryColors.Faint, modifier = Modifier.size(16.dp))
                    Text("${time.format(Date(prev.start))}  ${prev.title}", color = FerryColors.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = tabular.copy(fontSize = 13.sp), modifier = Modifier.weight(1f))
                } else Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(24.dp))
                if (next != null) {
                    Text("${time.format(Date(next.start))}  ${next.title}", color = FerryColors.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = tabular.copy(fontSize = 13.sp), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    androidx.tv.material3.Icon(FerryIcons.ChevronRight, null, tint = FerryColors.Faint, modifier = Modifier.size(16.dp))
                } else Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Text(p.title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                val dayText = if (sameDay(p.start, nowMs)) "" else day.format(Date(p.start)) + "  "
                Text("$dayText${time.format(Date(p.start))} – ${time.format(Date(p.stop))}  ·  ${(p.stop - p.start) / 60_000} min",
                    color = FerryColors.Muted, style = tabular)
                Spacer(Modifier.width(12.dp))
                when {
                    p.start <= nowMs && p.stop > nowMs -> Tag("Live", FerryColors.Live, dot = true)
                    p.stop <= nowMs && canCatchUp(p) -> Tag("Catch-up", FerryColors.Sea, filled = false)
                    p.stop <= nowMs -> Tag("Ended", FerryColors.Muted, filled = false)
                    hasReminder(p) -> Tag("Reminder set", FerryColors.Sea)
                    else -> Tag("Later", FerryColors.Muted, filled = false)
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                    ProgressLine(position.toFloat() / length, Modifier.weight(1f), color = if (watchingSelected && catchup) FerryColors.Sea else FerryColors.Gold)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        if (watchingSelected && catchup) "${time.format(Date(p.start + position))} · ${(length - position) / 60_000} min left"
                        else "${(length - position) / 60_000} min left",
                        color = FerryColors.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    )
                }
            }
            p.description?.let {
                Text(it, color = FerryColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp).widthIn(max = 1100.dp))
            }
        }

        if (controls != null) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                controls.forEach { (c, on) ->
                    val focused = c == focusedControl
                    val (icon, label): Pair<androidx.compose.ui.graphics.vector.ImageVector, String> = when (c) {
                        Control.RESTART -> FerryIcons.Replay to "Restart"
                        Control.BACK30 -> FerryIcons.Rewind to "30s back"
                        Control.PLAY_PAUSE -> if (paused) FerryIcons.Play to "Play" else FerryIcons.Pause to "Pause"
                        Control.FWD30 -> FerryIcons.Forward to "30s on"
                        Control.LIVE -> FerryIcons.LiveTv to "Live"
                        Control.PREVIOUS -> FerryIcons.SkipPrevious to "Previous"
                        Control.SLEEP -> FerryIcons.Moon to (if (sleepMinutes == null) "Sleep" else "Sleep ${sleepMinutes}m")
                        Control.STREAMS -> FerryIcons.Layers to "Streams"
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
                        Box(
                            Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                .background(when { focused -> FerryColors.Gold; on -> Color.White.copy(alpha = 0.12f); else -> Color.White.copy(alpha = 0.05f) }),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.tv.material3.Icon(
                                icon, null,
                                tint = when { focused -> FerryColors.Deep; on -> FerryColors.Text; else -> FerryColors.Muted.copy(alpha = 0.35f) },
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            label,
                            color = when { focused -> FerryColors.Gold; on -> FerryColors.Muted; else -> FerryColors.Faint.copy(alpha = 0.5f) },
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Hint(
            message ?: when {
                controls != null -> "◀ ▶  choose   ·   OK  select   ·   ▲ or Back  close"
                p != null && p.start > nowMs -> (if (hasReminder(p)) "OK  cancel reminder" else "OK  remind me") + "   ·   ▲ ▼  channels   ·   ◀ ▶  programmes"
                !isCurrent && p != null && p.stop <= nowMs && canCatchUp(p) -> "OK  watch from the start   ·   ▲ ▼  channels   ·   ◀ ▶  programmes"
                !isCurrent -> "OK  watch this channel   ·   ▲ ▼  channels   ·   ◀ ▶  programmes   ·   Back  close"
                watchingSelected -> "OK  controls   ·   ▲ ▼  channels   ·   ◀ ▶  programmes"
                p != null && p.stop <= nowMs && canCatchUp(p) -> "OK  watch from the start   ·   ◀ ▶  programmes"
                else -> "▲ ▼  channels   ·   ◀ ▶  programmes   ·   Back  close"
            },
            color = if (message != null) FerryColors.Sea else FerryColors.Faint,
        )
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val z = java.time.ZoneId.systemDefault()
    return java.time.Instant.ofEpochMilli(a).atZone(z).toLocalDate() == java.time.Instant.ofEpochMilli(b).atZone(z).toLocalDate()
}
