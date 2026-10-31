package dev.sysmoon.ferry.ui

import android.view.LayoutInflater
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.sysmoon.ferry.R
import dev.sysmoon.ferry.data.displayName
import dev.sysmoon.ferry.data.shownNumber
import dev.sysmoon.ferry.graph

/**
 * Picture-in-picture tile shown over the guide while a stream keeps playing. It never takes
 * focus, so the guide's remote navigation is unchanged; picking the playing channel (or Back
 * from the group list) returns to full screen.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MiniPlayer(modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val np by graph.player.state.collectAsState()
    val channel = np.channel ?: return
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .size(width = 240.dp, height = 135.dp)
            .clip(shape)
            .background(Color.Black)
            .border(BorderStroke(2.dp, FerryColors.Gold.copy(alpha = 0.85f)), shape)
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
