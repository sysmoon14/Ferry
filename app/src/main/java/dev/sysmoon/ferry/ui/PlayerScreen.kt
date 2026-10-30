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

    // Show the info banner on every channel change and on OK, hide after 5s.
    LaunchedEffect(state.channel?.id, overlayToken) {
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
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionUp, Key.ChannelUp -> { scope.launch { graph.player.step(-1) }; true }
                    Key.DirectionDown, Key.ChannelDown -> { scope.launch { graph.player.step(1) }; true }
                    Key.DirectionCenter, Key.Enter, Key.Info -> { overlayToken++; true }
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

        if (state.state == PlaybackState.ERROR) {
            Text(
                "Couldn't play this channel (${state.error ?: "unknown error"}). Up/Down to try another.",
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ch.name, style = MaterialTheme.typography.headlineMedium, color = FerryColors.Gold)
                    Spacer(Modifier.width(16.dp))
                    if (state.state == PlaybackState.BUFFERING) Text("Tuning…", color = FerryColors.Muted)
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
}
