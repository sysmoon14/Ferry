package dev.sysmoon.ferry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.sysmoon.ferry.data.ChannelEntity
import dev.sysmoon.ferry.data.StreamQuality
import dev.sysmoon.ferry.data.displayName
import dev.sysmoon.ferry.graph
import dev.sysmoon.ferry.player.ZapScope
import kotlinx.coroutines.launch

/**
 * Long-press menu for a channel: favourite toggle plus every stream the row has (best first),
 * so a backup or a different quality can be picked by hand.
 */
@Composable
fun ChannelMenu(
    channel: ChannelEntity,
    zap: ZapScope?,
    onDismiss: () -> Unit,
    /** Called after a stream was started, e.g. to switch to the player screen. */
    onPlayed: () -> Unit,
) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val primaryId = channel.primaryId ?: channel.id
    // Fresh copy: the row passed in may be stale after a favourite toggle.
    val primary by produceState(channel, primaryId) {
        value = graph.db.channels().get(primaryId) ?: channel
    }
    val streams by produceState(emptyList<ChannelEntity>(), primaryId) {
        value = graph.player.streamsFor(channel)
    }
    val nowPlaying by graph.player.state.collectAsState()
    val playingId = nowPlaying.stream?.id.takeIf { nowPlaying.channel?.id == primaryId }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    // The menu usually opens while OK is still held (long press). Swallow the rest of that press,
    // or its key-up would "click" whatever the menu focused first.
    var armed by remember { mutableStateOf(false) }
    var heldOk by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(400)
        if (!heldOk) armed = true
    }

    FerryDialog(
        onDismiss = onDismiss,
        width = 640.dp,
        modifier = Modifier.onPreviewKeyEvent { e ->
            if (armed) return@onPreviewKeyEvent false
            val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
            if (!ok) return@onPreviewKeyEvent false
            if (e.type == KeyEventType.KeyUp) armed = true else heldOk = true
            true
        },
    ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoChip(primary.logo, primary.name, width = 64.dp, height = 40.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(primary.displayName, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(primary.groupTitle, if (streams.size > 1) "${streams.size} streams" else null).joinToString("  ·  "),
                        color = FerryColors.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    scope.launch {
                        graph.db.channels().setFavourite(primaryId, !primary.favourite)
                        onDismiss()
                    }
                },
                colors = ferryButtonColors(),
                modifier = Modifier.focusRequester(first),
            ) {
                androidx.tv.material3.Icon(if (primary.favourite) FerryIcons.Star else FerryIcons.StarOutline, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (primary.favourite) "Remove from favourites" else "Add to favourites")
            }

            Spacer(Modifier.height(20.dp))
            Text(
                if (streams.size > 1) "STREAMS  ·  best first, Ferry falls back down this list" else "STREAM",
                color = FerryColors.Faint,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                Modifier.heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
            ) {
                itemsIndexed(streams, key = { _, s -> s.id }) { i, s ->
                    val info = StreamQuality.parse(s.name)
                    val playing = s.id == playingId
                    ListItem(
                        selected = playing,
                        onClick = {
                            graph.player.startStream(s, zap)
                            onDismiss()
                            onPlayed()
                        },
                        colors = ferryListItemColors(),
                        border = ferryListItemBorder(),
                        shape = ferryListItemShape(),
                        scale = ferryListItemScale(),
                        headlineContent = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    info.label ?: "quality unknown",
                                    "best".takeIf { i == 0 && streams.size > 1 },
                                    "catch-up".takeIf { s.catchupDays > 0 },
                                ).joinToString("  ·  "),
                                color = FerryColors.Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        trailingContent = if (playing) ({ Tag("Playing", FerryColors.Gold) }) else null,
                    )
                }
            }
    }
}
