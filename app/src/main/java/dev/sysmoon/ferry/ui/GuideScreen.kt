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
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import dev.sysmoon.ferry.player.ZapScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

const val FAVOURITES = "\u0000fav"
const val ALL = "\u0000all"
private const val GUIDE_LIMIT = 1_000

@Composable
fun GuideScreen(
    selected: String,
    onSelect: (String) -> Unit,
    /** Channel to scroll to and focus when coming back from the player. */
    returnTo: String?,
    gridView: Boolean,
    onToggleView: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val dao = graph.db.channels()
    // Only the selected group is ever loaded (capped), so 20k+ channel lineups stay light.
    // .catch: a failed read (e.g. mid-refresh) shows an empty list instead of crashing.
    val favourites by remember { dao.observeFavourites().catch { emit(emptyList()) } }
        .collectAsState(initial = emptyList())
    val groups by remember { dao.observeGroups().catch { emit(emptyList()) } }
        .collectAsState(initial = emptyList())
    val total by remember { dao.observeVisibleCount().catch { emit(0) } }.collectAsState(initial = 0)
    val nowPlaying by graph.player.state.collectAsState()

    val hasFavourites = favourites.isNotEmpty()
    val effective = if (selected == FAVOURITES && !hasFavourites) ALL else selected
    val shownFlow = remember(effective) {
        when (effective) {
            FAVOURITES -> dao.observeFavourites()
            ALL -> dao.observeFirst(GUIDE_LIMIT)
            else -> dao.observeGroup(effective, GUIDE_LIMIT)
        }.catch { emit(emptyList()) }
    }
    val shown by shownFlow.collectAsState(initial = emptyList())

    // Back from the player: put the list and focus back on the channel that was playing.
    val listState = rememberLazyListState()
    val returnFocus = remember { FocusRequester() }
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(shown, returnTo) {
        if (restored || returnTo == null || shown.isEmpty()) return@LaunchedEffect
        val index = shown.indexOfFirst { it.id == returnTo }
        restored = true
        if (index < 0) return@LaunchedEffect
        listState.scrollToItem((index - 2).coerceAtLeast(0))
        withFrameNanos { } // let the row compose before focusing it
        runCatching { returnFocus.requestFocus() }
    }

    Row(Modifier.fillMaxSize().background(FerryColors.Deep).padding(horizontal = 32.dp, vertical = 24.dp)) {
        // Group rail
        Column(Modifier.width(240.dp).fillMaxHeight()) {
            Text("Ferry", style = MaterialTheme.typography.headlineMedium, color = FerryColors.Gold)
            Spacer(Modifier.height(16.dp))
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                // Room for the focused item's scale-up and gold outline at the list edges.
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp),
            ) {
                val rail = buildList {
                    if (hasFavourites) add(FAVOURITES to "★ Favourites")
                    add(ALL to "All channels")
                    groups.forEach { add(it to it) }
                }
                items(rail, key = { it.first }) { (key, label) ->
                    ListItem(
                        selected = key == effective,
                        onClick = { onSelect(key) },
                        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = ferryListItemColors(),
                        border = ferryListItemBorder(),
                    )
                }
            }
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.padding(top = 12.dp),
                colors = ferryButtonColors(),
            ) { Text("Settings & pairing") }
        }

        Spacer(Modifier.width(24.dp))

        // Channel list
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (effective) { FAVOURITES -> "Favourites"; ALL -> "All channels"; else -> effective },
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (effective == ALL && total > GUIDE_LIMIT) {
                    Text(
                        "  first $GUIDE_LIMIT of $total — pick a group",
                        color = FerryColors.Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onToggleView,
                    colors = ferryOutlinedButtonColorsAsButton(),
                    modifier = Modifier.padding(end = 20.dp),
                ) { Text(if (gridView) "List view" else "Guide view") }
                Clock()
            }
            val zapScope = when (effective) {
                FAVOURITES -> ZapScope.Favourites
                ALL -> ZapScope.All
                else -> ZapScope.Group(effective)
            }
            if (shown.isEmpty()) {
                EmptyState(Modifier.weight(1f))
            } else if (gridView) {
                GuideGrid(
                    channels = shown,
                    zap = zapScope,
                    returnTo = returnTo,
                    onOpenPlayer = onOpenPlayer,
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 10.dp, horizontal = 12.dp),
                ) {
                    items(shown, key = { it.id }) { channel ->
                        ChannelRow(
                            channel = channel,
                            index = channel.position + 1,
                            playing = nowPlaying.channel?.id == channel.id,
                            modifier = if (channel.id == returnTo) Modifier.focusRequester(returnFocus) else Modifier,
                            onLongClick = { scope.launch { dao.setFavourite(channel.id, !channel.favourite) } },
                            onClick = {
                                // Zapping follows the list you started from, not the provider's group.
                                graph.player.start(channel, zapScope)
                                onOpenPlayer()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Clock() {
    val format = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000 - value % 60_000) // tick on the minute
        }
    }
    Text(format.format(Date(now)), style = MaterialTheme.typography.headlineSmall, color = FerryColors.Muted)
}

@Composable
private fun ChannelRow(
    channel: ChannelEntity,
    index: Int,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
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
        onLongClick = onLongClick,
        modifier = modifier,
        colors = ferryListItemColors(),
        border = ferryListItemBorder(),
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
