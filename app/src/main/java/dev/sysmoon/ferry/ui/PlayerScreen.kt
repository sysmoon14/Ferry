package dev.sysmoon.ferry.ui

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.sysmoon.ferry.graph
import dev.sysmoon.ferry.data.StreamQuality
import dev.sysmoon.ferry.data.displayName
import dev.sysmoon.ferry.player.PlaybackState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val state by graph.player.state.collectAsState()
    val focus = remember { FocusRequester() }
    var overlayToken by remember { mutableIntStateOf(0) }
    var overlayVisible by remember { mutableStateOf(true) }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    var menuOpen by remember { mutableStateOf(false) }
    // Typed channel number ("4", "40", "401"); tunes after a short pause, like a set-top box.
    var digits by remember { mutableStateOf("") }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(if (digits.length >= 3) 900 else 1_600)
        val n = digits.toInt()
        digits = ""
        graph.player.play(dev.sysmoon.ferry.player.PlayTarget.Number(n))
    }
    // OK is acted on when released, so a long press can open the menu instead.
    var okLongPressed by remember { mutableStateOf(false) }

    // Show the info banner on every channel change and on OK, hide after 5s.
    LaunchedEffect(state.channel?.id, state.stream?.id, overlayToken) {
        overlayVisible = true
        delay(5_000)
        overlayVisible = false
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter
                if (ok && e.type == KeyEventType.KeyUp) {
                    if (!okLongPressed) overlayToken++
                    okLongPressed = false
                    return@onKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (ok) {
                    if (e.nativeKeyEvent.repeatCount == 1 && state.channel != null) {
                        okLongPressed = true
                        menuOpen = true
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
                    // Up = next channel (higher in the list order), like a normal TV remote.
                    Key.DirectionUp, Key.ChannelUp -> { graph.player.zap(1); true }
                    Key.DirectionDown, Key.ChannelDown -> { graph.player.zap(-1); true }
                    Key.Info -> { overlayToken++; true }
                    Key.Menu -> { if (state.channel != null) menuOpen = true; true }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { scope.launch { graph.player.togglePause() }; true }
                    Key.Back, Key.Escape, Key.Guide, Key.DirectionLeft -> { onBack(); true }
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
                    player = graph.player.player
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
                    .background(FerryColors.Deep.copy(alpha = 0.85f)).padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        if (state.state == PlaybackState.ERROR) {
            Text(
                if (state.catchup) "Couldn't play this recording (${state.error ?: "unknown error"}). Back to return to the guide."
                else if (state.streamCount > 1) "None of this channel's ${state.streamCount} streams would play (${state.error ?: "unknown error"}). Up/Down to try another channel."
                else "Couldn't play this channel (${state.error ?: "unknown error"}). Up/Down to try another.",
                modifier = Modifier.align(Alignment.Center).background(FerryColors.Navy).padding(24.dp),
            )
        }

        AnimatedVisibility(
            visible = overlayVisible && state.channel != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val ch = state.channel ?: return@AnimatedVisibility
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, FerryColors.Deep.copy(alpha = 0.95f))))
                    .padding(horizontal = 48.dp, vertical = 32.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ch.skyNumber?.let {
                        Text("$it  ", style = MaterialTheme.typography.headlineMedium, color = FerryColors.Muted)
                    }
                    Text(ch.displayName, style = MaterialTheme.typography.headlineMedium, color = FerryColors.Gold)
                    Spacer(Modifier.width(16.dp))
                    if (state.catchup) Text("CATCH-UP  ", color = FerryColors.Sea, style = MaterialTheme.typography.labelLarge)
                    if (state.state == PlaybackState.BUFFERING) Text("Tuning…", color = FerryColors.Muted)
                    Spacer(Modifier.weight(1f))
                    if (state.streamCount > 1) {
                        val stream = state.stream
                        val label = stream?.let { StreamQuality.parse(it.name).label }
                        Text(
                            listOfNotNull(label, "${state.streamCount} streams · hold OK to switch").joinToString("  ·  "),
                            color = FerryColors.Muted,
                        )
                    }
                }
                state.notice?.let {
                    Text(it, color = FerryColors.Sea, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                state.now?.let { now ->
                    Text(
                        "${time.format(Date(now.start))}–${time.format(Date(now.stop))}  ${now.title}",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    ProgressBar(now.start, now.stop, Modifier.padding(vertical = 8.dp).width(480.dp))
                    now.description?.let {
                        Text(it, maxLines = 2, color = FerryColors.Muted, modifier = Modifier.width(900.dp))
                    }
                } ?: Text("No guide data", color = FerryColors.Muted)
                state.next?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Next ${time.format(Date(it.start))}  ${it.title}", color = FerryColors.Muted)
                }
            }
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
