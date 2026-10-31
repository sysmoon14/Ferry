@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

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
import androidx.compose.runtime.key
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
import dev.sysmoon.ferry.data.displayName
import dev.sysmoon.ferry.data.shownNumber
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.sysmoon.ferry.graph
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.activity.compose.BackHandler
import dev.sysmoon.ferry.player.ZapScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

const val FAVOURITES = "\u0000fav"
const val ALL = "\u0000all"
const val RECENT = "\u0000recent"
/** Not a group: opens the search screen. */
const val SEARCH = "\u0000search"
/** Not a group: opens the football screen. */
const val FOOTBALL = "\u0000football"
/** Rail keys for Sky layout sections ("\u0000sky:Sports"). */
const val SKY_PREFIX = "\u0000sky:"
private const val GUIDE_LIMIT = 1_000

@Composable
fun GuideScreen(
    selected: String,
    onSelect: (String) -> Unit,
    /** The channel playing (kept in view behind the player, focused when coming back to the guide). */
    playingId: String?,
    /** False while another screen (player, search, settings) is on top. */
    active: Boolean,
    /** The full-screen player is covering the guide. */
    playerShowing: Boolean,
    /** Bumped each time the guide comes back into view. */
    returnSignal: Int,
    /** Coming back from search/settings: focus the group list rather than a channel. */
    returnToRail: Boolean,
    gridView: Boolean,
    onToggleView: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFootball: () -> Unit,
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
    // Sky layout: Sky's sections replace provider groups; picked provider groups follow as extras.
    val skyLayout by remember { graph.settings.skyLayout }.collectAsState(initial = false)
    val skySections by remember { dao.observeSkySections().catch { emit(emptyList()) } }.collectAsState(initial = emptyList())
    val skyExtras by remember { graph.settings.skyExtraGroups }.collectAsState(initial = emptySet())
    val sky = skyLayout && skySections.isNotEmpty()
    val railGroups = if (sky) groups.filter { it in skyExtras } else groups
    val nowPlaying by graph.player.state.collectAsState()
    val recents by remember { graph.settings.recents }.collectAsState(initial = emptyList())

    val hasFavourites = favourites.isNotEmpty()
    val validKeys = buildSet {
        add(ALL); if (hasFavourites) add(FAVOURITES); if (recents.isNotEmpty()) add(RECENT)
        if (sky) skySections.forEach { add(SKY_PREFIX + it) }
        addAll(railGroups)
    }
    // Fall back to All when the selection disappears (no favourites left, layout switched…).
    val effective = when {
        selected == FAVOURITES && !hasFavourites -> ALL
        selected == RECENT && recents.isEmpty() -> ALL
        groups.isEmpty() -> selected // lists still loading: keep the selection
        selected in validKeys -> selected
        else -> ALL
    }
    val shownFlow = remember(effective, sky) {
        when {
            effective == FAVOURITES -> dao.observeFavourites()
            effective == RECENT -> graph.settings.recents.flatMapLatest { ids ->
                dao.observeByIds(ids).map { rows -> ids.mapNotNull { id -> rows.firstOrNull { it.id == id && !it.hidden } } }
            }
            effective == ALL && sky -> dao.observeSkyAll()
            effective == ALL -> dao.observeFirst(GUIDE_LIMIT)
            effective.startsWith(SKY_PREFIX) -> dao.observeSkySection(effective.removePrefix(SKY_PREFIX))
            else -> dao.observeGroup(effective, GUIDE_LIMIT)
        }.catch { emit(emptyList()) }
    }
    val shown by shownFlow.collectAsState(initial = emptyList())
    val zapScope = when {
        effective == FAVOURITES -> ZapScope.Favourites
        effective == RECENT -> ZapScope.Recent
        effective == ALL && sky -> ZapScope.Sky(null)
        effective == ALL -> ZapScope.All
        effective.startsWith(SKY_PREFIX) -> ZapScope.Sky(effective.removePrefix(SKY_PREFIX))
        else -> ZapScope.Group(effective)
    }
    // Long-press menu (favourite + stream picker) for this channel, if open.
    var menuFor by remember { mutableStateOf<ChannelEntity?>(null) }
    menuFor?.let { ChannelMenu(it, zapScope, onDismiss = { menuFor = null }, onPlayed = onOpenPlayer) }

    // Each group gets its own scroll position, so opening a group always starts at the top.
    val listState = remember(effective) { androidx.compose.foundation.lazy.LazyListState() }
    val returnFocus = remember { FocusRequester() }

    val rail = buildList {
        add(SEARCH to "🔍  Search")
        add(FOOTBALL to "⚽  Football")
        if (hasFavourites) add(FAVOURITES to "★ Favourites")
        if (recents.isNotEmpty()) add(RECENT to "🕘  Recently watched")
        add(ALL to if (sky) "All Sky channels" else "All channels")
        if (sky) skySections.forEach { add(SKY_PREFIX + it to it) }
        railGroups.forEach { add(it to it) }
    }
    // Back from the channels (list or guide) jumps to the selected group in the rail, so you
    // never have to scroll back through days of catch-up to change group.
    val railState = rememberLazyListState()
    val railFocus = remember { FocusRequester() }
    var channelsFocused by remember { mutableStateOf(false) }
    // The group list hides while you're in the channels/guide (more room for programmes);
    // Back brings it back and focuses the current group.
    // Starts hidden when the guide first appears behind the player, so it's already in its
    // final state when the player shrinks away.
    var railVisible by remember { mutableStateOf(!playerShowing) }
    LaunchedEffect(channelsFocused) { if (channelsFocused) railVisible = false }
    LaunchedEffect(playerShowing) { if (playerShowing) railVisible = false }
    val focusRail: () -> Unit = {
        scope.launch {
            if (!railVisible) {
                railVisible = true
                withFrameNanos { } // let the rail compose before focusing into it
            }
            val index = rail.indexOfFirst { it.first == effective }.coerceAtLeast(0)
            if (railState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                railState.scrollToItem(index)
                withFrameNanos { }
            }
            runCatching { railFocus.requestFocus() }
        }
    }
    BackHandler(enabled = active && channelsFocused && !gridView) { focusRail() }

    // List view: while the player covers the guide, keep the playing channel in view; when the
    // guide comes back, focus it (or the group list, after search/settings).
    LaunchedEffect(playingId, shown, playerShowing) {
        if (gridView || !playerShowing) return@LaunchedEffect
        val index = shown.indexOfFirst { it.id == playingId }
        if (index >= 0) listState.scrollToItem((index - 2).coerceAtLeast(0))
    }
    LaunchedEffect(returnSignal) {
        if (returnSignal == 0) return@LaunchedEffect
        if (returnToRail) { focusRail(); return@LaunchedEffect }
        if (gridView) return@LaunchedEffect // the grid handles its own
        val index = shown.indexOfFirst { it.id == playingId }
        if (index < 0) { focusRail(); return@LaunchedEffect }
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.scrollToItem((index - 2).coerceAtLeast(0))
        }
        withFrameNanos { } // let the row compose before focusing it
        runCatching { returnFocus.requestFocus() }
    }

    Row(Modifier.fillMaxSize().background(FerryColors.Deep).padding(horizontal = 32.dp, vertical = 12.dp)) {
        // Group rail
        androidx.compose.animation.AnimatedVisibility(
            visible = railVisible,
            enter = androidx.compose.animation.expandHorizontally() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkHorizontally() + androidx.compose.animation.fadeOut(),
        ) {
        Row {
        Column(Modifier.width(240.dp).fillMaxHeight()) {
            Text("Ferry", style = MaterialTheme.typography.titleLarge, color = FerryColors.Gold)
            Spacer(Modifier.height(16.dp))
            LazyColumn(
                Modifier.weight(1f),
                state = railState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                // Room for the focused item's scale-up and gold outline at the list edges.
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp),
            ) {
                items(rail, key = { it.first }) { (key, label) ->
                    ListItem(
                        modifier = if (key == effective) Modifier.focusRequester(railFocus) else Modifier,
                        selected = key == effective,
                        onClick = { when (key) { SEARCH -> onOpenSearch(); FOOTBALL -> onOpenFootball(); else -> onSelect(key) } },
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
        }
        }

        // Channel list
        Column(Modifier.weight(1f).fillMaxHeight().onFocusChanged { channelsFocused = it.hasFocus }) {
            // Leave room on the right for the corner mini player when a stream is playing.
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp, end = if (nowPlaying.channel != null) MINI_CLEARANCE else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        effective == FAVOURITES -> "Favourites"
                        effective == RECENT -> "Recently watched"
                        effective == ALL -> if (sky) "All Sky channels" else "All channels"
                        else -> effective.removePrefix(SKY_PREFIX)
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                if (effective == ALL && !sky && total > GUIDE_LIMIT) {
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
            if (shown.isEmpty()) {
                EmptyState(Modifier.weight(1f))
            } else if (gridView) key(effective) {
                GuideGrid(
                    channels = shown,
                    zap = zapScope,
                    playingId = playingId,
                    active = active,
                    playerShowing = playerShowing,
                    returnSignal = if (returnToRail) 0 else returnSignal,
                    onOpenPlayer = onOpenPlayer,
                    onMenu = { menuFor = it },
                    onBackToGroups = focusRail,
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
                            modifier = if (channel.id == playingId) Modifier.focusRequester(returnFocus) else Modifier,
                            onLongClick = { menuFor = channel },
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
    Text(format.format(Date(now)), style = MaterialTheme.typography.titleLarge, color = FerryColors.Muted)
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
                    "${channel.shownNumber}",
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
                buildAnnotatedString {
                    if (channel.favourite) append("★ ")
                    append(channel.displayName)
                    if (channel.streamCount > 1) {
                        withStyle(SpanStyle(color = FerryColors.Muted)) { append("   ${channel.streamCount} streams") }
                    }
                },
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
