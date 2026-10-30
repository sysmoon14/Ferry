package dev.sysmoon.ferry.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.sysmoon.ferry.data.ChannelEntity
import dev.sysmoon.ferry.data.epgKey
import dev.sysmoon.ferry.graph
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private const val FAVOURITES = "\u0000fav"
private const val ALL = "\u0000all"

@Composable
fun GuideScreen(onOpenPlayer: () -> Unit, onOpenSettings: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val channels by graph.db.channels().observeVisible().collectAsState(initial = emptyList())
    val groups by graph.db.channels().observeGroups().collectAsState(initial = emptyList())
    val nowPlaying by graph.player.state.collectAsState()
    var selected by remember { mutableStateOf(FAVOURITES) }

    val hasFavourites = channels.any { it.favourite }
    val effective = if (selected == FAVOURITES && !hasFavourites) ALL else selected
    val indexById = remember(channels) { channels.withIndex().associate { it.value.id to it.index + 1 } }
    val shown = when (effective) {
        FAVOURITES -> channels.filter { it.favourite }
        ALL -> channels
        else -> channels.filter { it.groupTitle == effective }
    }

    Row(Modifier.fillMaxSize().background(FerryColors.Deep).padding(horizontal = 32.dp, vertical = 24.dp)) {
        // Group rail
        Column(Modifier.width(240.dp).fillMaxHeight()) {
            Text("Ferry", style = MaterialTheme.typography.headlineMedium, color = FerryColors.Gold)
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val rail = buildList {
                    if (hasFavourites) add(FAVOURITES to "★ Favourites")
                    add(ALL to "All channels")
                    groups.forEach { add(it to it) }
                }
                items(rail, key = { it.first }) { (key, label) ->
                    ListItem(
                        selected = key == effective,
                        onClick = { selected = key },
                        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 12.dp)) { Text("Settings & pairing") }
        }

        Spacer(Modifier.width(24.dp))

        // Channel list
        if (shown.isEmpty()) {
            EmptyState(Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.id }) { channel ->
                    ChannelRow(
                        channel = channel,
                        index = indexById[channel.id] ?: 0,
                        playing = nowPlaying.channel?.id == channel.id,
                        onClick = {
                            scope.launch { graph.player.play(channel) }
                            onOpenPlayer()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(channel: ChannelEntity, index: Int, playing: Boolean, onClick: () -> Unit) {
    val graph = LocalContext.current.graph
    // Per-row lookup is indexed and cheap; swap for a windowed query when the grid view lands.
    val programmes by produceState(initialValue = emptyList(), channel.epgKey) {
        value = graph.db.programmes().upcoming(channel.epgKey, System.currentTimeMillis(), 2)
    }
    val now = programmes.firstOrNull()?.takeIf { it.start <= System.currentTimeMillis() }
    val next = if (now != null) programmes.getOrNull(1) else programmes.firstOrNull()
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    ListItem(
        selected = playing,
        onClick = onClick,
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${channel.number ?: index}",
                    style = MaterialTheme.typography.titleMedium,
                    color = FerryColors.Muted,
                    modifier = Modifier.width(48.dp),
                )
                Box(
                    Modifier.size(width = 72.dp, height = 44.dp).clip(RoundedCornerShape(6.dp))
                        .background(FerryColors.Navy),
                    contentAlignment = Alignment.Center,
                ) {
                    if (channel.logo != null) {
                        AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.padding(4.dp))
                    } else {
                        Text(channel.name.take(3).uppercase(), color = FerryColors.Muted)
                    }
                }
            }
        },
        headlineContent = {
            Text(
                (if (channel.favourite) "★ " else "") + channel.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text(
                    now?.title ?: "No guide data",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (now != null) MaterialTheme.colorScheme.onSurface else FerryColors.Muted,
                )
                if (now != null) {
                    ProgressBar(now.start, now.stop, Modifier.padding(vertical = 4.dp).width(220.dp))
                }
                next?.let {
                    Text(
                        "Next ${time.format(Date(it.start))}  ${it.title}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = FerryColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}

@Composable
fun ProgressBar(start: Long, stop: Long, modifier: Modifier = Modifier) {
    val fraction = ((System.currentTimeMillis() - start).toFloat() / (stop - start).coerceAtLeast(1))
        .coerceIn(0f, 1f)
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(FerryColors.Navy)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(FerryColors.Gold))
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center) {
        Text("No channels yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Open Settings & pairing, scan the QR code with your phone and add a playlist from there.",
            color = FerryColors.Muted,
        )
    }
}
