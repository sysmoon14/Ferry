package dev.sysmoon.ferry.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.sysmoon.ferry.data.ChannelEntity
import dev.sysmoon.ferry.data.StreamQuality
import dev.sysmoon.ferry.data.displayName
import dev.sysmoon.ferry.graph
import dev.sysmoon.ferry.player.TrackOption
import dev.sysmoon.ferry.player.ZapScope
import kotlinx.coroutines.launch
import androidx.tv.material3.Button
import dev.sysmoon.ferry.player.Zoom

private enum class Picker { AUDIO, SUBTITLES, PICTURE, HIDE }

/**
 * Long-press / Options menu for a channel: favourite and hide, then — while it's the channel
 * playing — audio track, subtitles and picture zoom, and every stream the row has (best first)
 * so a backup or a different quality can be picked by hand.
 */
@Composable
fun ChannelMenu(
    channel: ChannelEntity,
    zap: ZapScope?,
    onDismiss: () -> Unit,
    /** Called after a stream was started, e.g. to switch to the player screen. */
    onPlayed: () -> Unit,
    /** Called after the channel was hidden, e.g. so the guide moves on. */
    onHidden: () -> Unit = {},
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
    val isPlaying = nowPlaying.channel?.id == primaryId && nowPlaying.channel != null
    val playingId = nowPlaying.stream?.id.takeIf { isPlaying }
    val zoom by graph.player.zoom.collectAsState()
    var tracksVersion by remember { mutableStateOf(0) }
    val audio by produceState(emptyList<TrackOption>(), isPlaying, nowPlaying.state, tracksVersion) {
        value = if (isPlaying) graph.player.audioTracks() else emptyList()
    }
    val subs by produceState(emptyList<TrackOption>(), isPlaying, nowPlaying.state, tracksVersion) {
        value = if (isPlaying) graph.player.textTracks() else emptyList()
    }
    var picker by remember { mutableStateOf<Picker?>(null) }

    val first = remember { FocusRequester() }
    LaunchedEffect(picker) {
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
        onDismiss = { if (picker != null) picker = null else onDismiss() },
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
        Spacer(Modifier.height(16.dp))

        when (val p = picker) {
            null -> LazyColumn(
                Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
            ) {
                item("fav") {
                    MenuRow(
                        icon = if (primary.favourite) FerryIcons.Star else FerryIcons.StarOutline,
                        title = if (primary.favourite) "Remove from favourites" else "Add to favourites",
                        modifier = Modifier.focusRequester(first),
                    ) {
                        scope.launch { graph.db.channels().setFavourite(primaryId, !primary.favourite); onDismiss() }
                    }
                }
                if (isPlaying) {
                    if (audio.size > 1) item("audio") {
                        MenuRow(FerryIcons.Audio, "Audio", audio.firstOrNull { it.selected }?.label ?: "${audio.size} tracks") { picker = Picker.AUDIO }
                    }
                    if (subs.isNotEmpty()) item("subs") {
                        MenuRow(FerryIcons.Subtitles, "Subtitles", subs.firstOrNull { it.selected }?.label ?: "Off") { picker = Picker.SUBTITLES }
                    }
                    item("zoom") {
                        MenuRow(FerryIcons.AspectRatio, "Picture", zoom.label) { picker = Picker.PICTURE }
                    }
                }
                item("hide") {
                    MenuRow(FerryIcons.Hide, "Hide channel", "Bring it back from Settings › Hidden channels") { picker = Picker.HIDE }
                }
                item("streams-head") {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (streams.size > 1) "STREAMS  ·  best first, Ferry falls back down this list" else "STREAM",
                        color = FerryColors.Muted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
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
                        trailingContent = if (playing) ({ Tag("Playing", FerryColors.Text) }) else null,
                    )
                }
            }
            Picker.PICTURE -> {
                Text("PICTURE", color = FerryColors.Muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Zoom.entries.forEach { z ->
                        ListItem(
                            selected = z == zoom,
                            onClick = { graph.player.setZoom(z); picker = null },
                            colors = ferryListItemColors(), border = ferryListItemBorder(), shape = ferryListItemShape(), scale = ferryListItemScale(),
                            headlineContent = { Text(z.label, style = MaterialTheme.typography.titleSmall) },
                            supportingContent = {
                                Text(
                                    when (z) {
                                        Zoom.FIT -> "The whole picture, with bars if it isn't widescreen"
                                        Zoom.FILL -> "Stretched to fill the screen"
                                        Zoom.ZOOM -> "Enlarged to fill the screen; the edges are cropped"
                                    },
                                    color = FerryColors.Muted, style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            trailingContent = if (z == zoom) ({ Icon(FerryIcons.Check, null, tint = FerryColors.Gold, modifier = Modifier.size(20.dp)) }) else null,
                            modifier = if (z == zoom) Modifier.focusRequester(first) else Modifier,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Hint("Back  menu")
            }
            Picker.HIDE -> {
                // Asked first: it used to hide on one press, with the web page the only way back.
                Text("Hide ${primary.displayName}?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("It leaves every list on this TV. You can bring it back from Settings › Hidden channels.",
                    color = FerryColors.Muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { picker = null }, colors = ferryButtonColors(), modifier = Modifier.focusRequester(first)) { Text("Keep it") }
                    Button(
                        onClick = {
                            scope.launch {
                                graph.db.channels().setHidden(primaryId, true)
                                if (isPlaying) graph.player.stop()
                                onDismiss()
                                onHidden()
                            }
                        },
                        colors = ferryOutlinedButtonColorsAsButton(), border = ferrySecondaryButtonBorder(),
                    ) { Text("Hide channel") }
                }
            }
            else -> {
                val options = if (p == Picker.AUDIO) audio else subs
                Text(if (p == Picker.AUDIO) "AUDIO TRACK" else "SUBTITLES", color = FerryColors.Muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    Modifier.heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
                ) {
                    if (p == Picker.SUBTITLES) item("off") {
                        val off = options.none { it.selected }
                        ListItem(
                            selected = off,
                            onClick = { graph.player.selectText(null); tracksVersion++; picker = null },
                            colors = ferryListItemColors(), border = ferryListItemBorder(), shape = ferryListItemShape(), scale = ferryListItemScale(),
                            headlineContent = { Text("Off", style = MaterialTheme.typography.titleSmall) },
                            trailingContent = if (off) ({ Icon(FerryIcons.Check, null, tint = FerryColors.Gold, modifier = Modifier.size(20.dp)) }) else null,
                            modifier = if (off) Modifier.focusRequester(first) else Modifier,
                        )
                    }
                    items(options, key = { "${it.group}/${it.track}" }) { o ->
                        ListItem(
                            selected = o.selected,
                            onClick = {
                                if (p == Picker.AUDIO) graph.player.selectAudio(o) else graph.player.selectText(o)
                                tracksVersion++
                                picker = null
                            },
                            colors = ferryListItemColors(), border = ferryListItemBorder(), shape = ferryListItemShape(), scale = ferryListItemScale(),
                            headlineContent = { Text(o.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall) },
                            trailingContent = if (o.selected) ({ Icon(FerryIcons.Check, null, tint = FerryColors.Gold, modifier = Modifier.size(20.dp)) }) else null,
                            modifier = if (o.selected) Modifier.focusRequester(first) else Modifier,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Hint("Back  menu")
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, subtitle: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        colors = ferryListItemColors(),
        border = ferryListItemBorder(),
        shape = ferryListItemShape(),
        scale = ferryListItemScale(),
        leadingContent = { Icon(icon, null, modifier = Modifier.size(22.dp)) },
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle?.let { { Text(it, color = FerryColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        modifier = modifier,
    )
}
