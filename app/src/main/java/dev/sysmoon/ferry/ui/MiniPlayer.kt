package dev.sysmoon.ferry.ui

import android.view.LayoutInflater
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.sysmoon.ferry.R
import dev.sysmoon.ferry.data.displayName
import dev.sysmoon.ferry.data.shownNumber
import dev.sysmoon.ferry.graph

val MINI_WIDTH: Dp = 240.dp
val MINI_HEIGHT: Dp = 135.dp
private val MINI_END = 24.dp
private val MINI_TOP = 20.dp

/**
 * The one video surface. Full screen while watching; Back shrinks it into the top-right corner
 * (the mini player) over the guide, and picking the channel grows it back, so the picture never
 * cuts out or jumps. It never takes focus.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoLayer(full: Boolean, show: Boolean) {
    val graph = LocalContext.current.graph
    val np by graph.player.state.collectAsState()
    val progress by animateFloatAsState(
        targetValue = if (full) 1f else 0f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "video",
    )
    if (!show && progress == 0f) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = lerp(MINI_WIDTH, maxWidth, progress)
        val h = lerp(MINI_HEIGHT, maxHeight, progress)
        val x = lerp(maxWidth - MINI_WIDTH - MINI_END, 0.dp, progress)
        val y = lerp(MINI_TOP, 0.dp, progress)
        val shape = RoundedCornerShape(lerp(12.dp, 0.dp, progress))
        Box(
            Modifier.offset(x, y).size(w, h).clip(shape).background(Color.Black)
                .border(BorderStroke(2.dp, FerryColors.Gold.copy(alpha = 0.85f * (1f - progress))), shape)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.mini_player_view, null) as PlayerView).apply {
                        isFocusable = false
                        player = graph.player.player
                    }
                },
                onRelease = { it.player = null },
            )
            val channel = np.channel
            if (progress < 0.02f && channel != null) {
                np.error?.let {
                    Text("Can't play this channel", color = FerryColors.Muted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.Center).padding(12.dp))
                }
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                        .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)
                ) {
                    Text("${channel.shownNumber}  ${channel.displayName}", color = FerryColors.Gold,
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    np.now?.let {
                        Text((if (np.catchup) "↺ " else "") + it.title, color = FerryColors.Text,
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
